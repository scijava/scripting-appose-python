/*-
 * #%L
 * Python scripting language plugin backed by Appose.
 * %%
 * Copyright (C) 2026 SciJava developers.
 * %%
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 * #L%
 */

package org.scijava.plugins.scripting.appose.python.completion;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.LongSupplier;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.apposed.appose.tool.Uv;
import org.scijava.code.lsp.Environment;
import org.scijava.plugins.scripting.appose.python._internal.InlineMetadata;

/**
 * Makes packages a script declares (in its PEP 723 {@code # /// script}
 * block), but its environment lacks, known to code completion before the
 * environment is (re)built: e.g. a dependency just added to the script.
 * <p>
 * The missing packages are installed, in the background, into a separate
 * directory per script (an "overlay"), which the environment a script is
 * analyzed in then searches too (see {@link ApposePythonDialect#environment}).
 * The environment itself is never touched, so this never disturbs whatever
 * runs in it (e.g. an Appose worker). Installs start only once the
 * dependencies have stopped changing for a moment, so that half-typed names
 * do not cause installs; and only if the missing packages differ from the
 * overlay's.
 * </p>
 * <p>
 * Alternatives considered, which may be worth revisiting:
 * </p>
 * <ul>
 * <li><em>Rebuilding the script's environment</em> as its dependencies
 * change. Jedi would then see exactly what will run, but builds can take
 * minutes and gigabytes, half-finished edits would trigger builds, and e.g. Appose
 * rebuilds environments in place, which can interfere with (or, by changing
 * its configuration, release) a resident worker using it.</li>
 * <li><em>Installing nothing</em>, but hinting that new dependencies are
 * known only once the script has run. Cheap and honest, but the user must
 * run the script first. It may still be worthwhile for dependencies an
 * overlay cannot provide: conda packages (e.g.
 * {@code [tool.pixi.dependencies]}), and environments declared in files via
 * {@code #@script(env=...)} in Appose-Python scripts, which overlays do not
 * handle.</li>
 * </ul>
 *
 * @author Gabriel Selzer
 */
final class DependencyOverlays {

	/** Installs the missing packages of a script's environment. */
	@FunctionalInterface
	interface Installer {

		/**
		 * Installs the packages among {@code dependencies} that the environment
		 * with the given Python lacks into the given overlay directory.
		 *
		 * @param python The environment's Python executable, or null if the
		 *          environment has not been built (all packages are missing).
		 * @param dependencies The script's dependencies, as PEP 508 strings.
		 * @param overlay The directory to install into.
		 * @return The overlay directory, or null if nothing is missing.
		 */
		String install(String python, List<String> dependencies, File overlay);
	}

	private final Installer installer;
	private final File root;
	private final long settleMillis;
	private final LongSupplier clock;
	private final ExecutorService executor = Executors.newSingleThreadExecutor(
		r -> {
			final Thread t = new Thread(r, "appose-python-overlays");
			t.setDaemon(true);
			return t;
		});

	/** Installs, by environment name, Python and dependencies. */
	private final Map<String, CompletableFuture<String>> installs =
		new HashMap<>();

	/** The dependencies last seen for each environment, and since when. */
	private final Map<String, Seen> seen = new HashMap<>();

	/** The scripts asked about so far, by overlay name. */
	private final Set<String> known = new HashSet<>();

	/**
	 * @param installer Does the installing, on a background thread.
	 * @param root Directory holding the overlays (one per script).
	 * @param settleMillis How long dependencies must stay unchanged before
	 *          their missing packages are installed.
	 * @param clock The current time, in milliseconds.
	 */
	DependencyOverlays(final Installer installer, final File root,
		final long settleMillis, final LongSupplier clock)
	{
		this.installer = installer;
		this.root = root;
		this.settleMillis = settleMillis;
		this.clock = clock;
	}

	/**
	 * Gets the environment to analyze a script in: the given one, searching
	 * the overlay of the packages the script declares but the environment lacks
	 * too, once ready (see {@link #overlay}). Never blocks.
	 *
	 * @param text The script.
	 * @param path The script's file path, or null if it has none.
	 * @param env Where the script runs.
	 */
	Environment environment(final String text, final String path,
		final Environment env)
	{
		final String name = name(path);
		// NB: The first time a script is asked about (e.g. just opened), its
		// dependencies need no time to settle.
		final boolean settled;
		synchronized (installs) {
			settled = known.add(name);
		}
		final String overlay;
		try {
			final String metadata = InlineMetadata.extract(text);
			if (metadata == null) return env;
			overlay = overlay(name, env.interpreter(), InlineMetadata.dependencies(
				metadata), settled);
		}
		catch (final RuntimeException exc) {
			return env; // NB: E.g. half-typed metadata.
		}
		if (overlay == null) return env;
		final List<String> paths = new ArrayList<>(env.paths());
		paths.add(overlay);
		return new Environment(env.interpreter(), paths, env.live());
	}

	/**
	 * Gets the overlay providing the packages the environment lacks, if it is
	 * ready; starts preparing it once the dependencies have settled. Never
	 * blocks.
	 *
	 * @param envName The overlay's name (e.g. of its script).
	 * @param python The environment's Python executable, or null if not built.
	 * @param dependencies The script's dependencies (PEP 508 strings).
	 * @param settled Whether the dependencies are known not to be changing
	 *          (e.g. the script was just opened), so need no time to settle.
	 * @return The overlay directory, or null if none is needed or ready.
	 */
	String overlay(final String envName, final String python,
		final List<String> dependencies, final boolean settled)
	{
		if (dependencies == null || dependencies.isEmpty()) return null;
		final String key = envName + "\n" + python + "\n" + dependencies;
		synchronized (installs) {
			final CompletableFuture<String> install = installs.get(key);
			if (install != null) {
				return install.isDone() && !install.isCompletedExceptionally()
					? install.join() : null;
			}
			// Wait for the dependencies to settle, e.g. a name to be typed fully.
			final long now = clock.getAsLong();
			final Seen last = seen.get(envName);
			if (last == null || !last.key.equals(key)) {
				seen.put(envName, new Seen(key, now));
				if (!settled) return null;
			}
			else if (!settled && now - last.since < settleMillis) return null;
			final File overlay = new File(root, envName);
			final CompletableFuture<String> started = CompletableFuture.supplyAsync(
				() -> installer.install(python, dependencies, overlay), executor);
			// NB: Once done, asking again gets the overlay.
			installs.put(key, started);
			return null;
		}
	}

	/**
	 * The name of a script's overlay: after its file, if any; otherwise
	 * {@code unsaved}, as untitled scripts share one.
	 *
	 * @param path The script's file path, or null if it has none.
	 */
	static String name(final String path) {
		if (path == null) return "unsaved";
		final File f = new File(path).getAbsoluteFile();
		return f.getName().replaceAll("[^A-Za-z0-9_.-]", "_") + "-" + Integer
			.toHexString(f.getPath().hashCode());
	}

	/**
	 * Installs the packages the environment with the given Python lacks into an
	 * overlay directory, with uv (as Appose has it): {@link Installer} as used
	 * outside tests.
	 */
	static String install(final String python, final List<String> dependencies,
		final File overlay)
	{
		try {
			final Set<String> installed = python == null ? new HashSet<>()
				: installed(python);
			final List<String> missing = new ArrayList<>();
			for (final String d : dependencies) {
				final String name = distribution(d);
				if (name != null && !installed.contains(name)) missing.add(d);
			}
			if (missing.isEmpty()) {
				delete(overlay.toPath());
				return null;
			}
			// NB: Up to date already, if installed for this Python and these
			// requirements.
			final Map<String, Object> wanted = new LinkedHashMap<>();
			wanted.put("python", python);
			wanted.put("requirements", new ArrayList<>(new TreeSet<>(missing)));
			final ObjectMapper json = new ObjectMapper();
			final File marker = new File(overlay, MARKER);
			if (marker.isFile() && json.readTree(marker).equals(json.valueToTree(
				wanted))) return overlay.getPath();

			// NB: Installed next to the overlay, then swapped in, so that it is
			// never seen half-installed.
			final Path staging = new File(overlay.getPath() + ".new").toPath();
			delete(staging);
			final Uv uv = new Uv();
			if (!uv.isInstalled()) uv.install();
			final List<String> args = new ArrayList<>(Arrays.asList("pip",
				"install", "--target", staging.toString(), "--no-deps"));
			if (python != null) args.addAll(Arrays.asList("--python", python));
			args.addAll(missing);
			uv.exec(args.toArray(new String[0]));
			json.writeValue(staging.resolve(MARKER).toFile(), wanted);
			delete(overlay.toPath());
			Files.move(staging, overlay.toPath(), StandardCopyOption.ATOMIC_MOVE);
			return overlay.getPath();
		}
		catch (final IOException | InterruptedException | RuntimeException exc) {
			throw new IllegalStateException("Overlay installation failed", exc);
		}
	}

	/** Marks an overlay with what was installed into it, for which Python. */
	private static final String MARKER = ".scijava-overlay.json";

	/** A requirement's distribution name, e.g. {@code scikit-image}. */
	private static final Pattern REQUIREMENT = Pattern.compile(
		"\\s*([A-Za-z0-9][A-Za-z0-9._-]*).*", Pattern.DOTALL);

	/** Lists the distributions installed in an environment, as JSON. */
	private static final String INSTALLED = "import importlib.metadata as m, " +
		"json, re\n" + "print(json.dumps(sorted({re.sub(r'[-_.]+', '-', " +
		"(d.metadata['Name'] or '').lower()) for d in m.distributions()})))";

	/**
	 * The normalized distribution name of a PEP 508 requirement, or null if
	 * none.
	 */
	static String distribution(final String requirement) {
		final Matcher m = REQUIREMENT.matcher(requirement);
		return m.matches() ? m.group(1).toLowerCase().replaceAll("[-_.]+", "-")
			: null;
	}

	/** The distributions installed in the environment of a Python. */
	private static Set<String> installed(final String python)
		throws IOException, InterruptedException
	{
		final Process p = new ProcessBuilder(python, "-c", INSTALLED)
			.redirectErrorStream(false).start();
		final ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (final InputStream in = p.getInputStream()) {
			final byte[] buf = new byte[8192];
			int n;
			while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
		}
		if (!p.waitFor(2, TimeUnit.MINUTES) || p.exitValue() != 0) {
			p.destroyForcibly();
			return new HashSet<>(); // NB: As if nothing were installed.
		}
		final Set<String> names = new HashSet<>();
		new ObjectMapper().readTree(new String(out.toByteArray(),
			StandardCharsets.UTF_8)).forEach(n -> names.add(n.asText()));
		return names;
	}

	/** Deletes a directory, if any. */
	private static void delete(final Path dir) throws IOException {
		if (!Files.exists(dir)) return;
		try (final Stream<Path> paths = Files.walk(dir)) {
			paths.sorted(Comparator.reverseOrder()).forEach(f -> f.toFile()
				.delete());
		}
	}

	private static final class Seen {

		private final String key;
		private final long since;

		private Seen(final String key, final long since) {
			this.key = key;
			this.since = since;
		}
	}
}
