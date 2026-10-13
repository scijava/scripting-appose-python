/*
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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import org.apposed.appose.Appose;
import org.apposed.appose.Environment;
import org.apposed.appose.Service;
import org.apposed.appose.util.Environments;
import org.eclipse.lsp4j.TextDocumentItem;
import org.scijava.plugins.scripting.appose.python.ScriptEnvironment;
import org.scijava.plugins.scripting.appose.python._internal.InlineMetadata;
import org.scijava.plugins.scripting.appose.python._internal.ResidentWorker;
import org.scijava.plugins.scripting.appose.python._internal.ResidentWorkerService;

/**
 * Where Python scripts run via Appose, as their language servers need to
 * know: the Python of a script's environment, if built (in this session, or an
 * earlier one; never building it, nor disturbing its worker); the overlay of
 * packages it declares but its environment lacks (see
 * {@link DependencyOverlays}); and the tooling environment jedi-language-server
 * runs in.
 *
 * @author Gabriel Selzer
 */
final class ApposePythonEnvironments {

	/** Name of the tooling environment in which the language server runs. */
	static final String TOOLING_ENV = "appose-python-completion";

	/**
	 * How long a script's dependencies must stay unchanged before packages
	 * missing from its environment are installed for completion, in ms.
	 */
	private static final long OVERLAY_SETTLE = 1500;

	/** Task scripts (see overlay.py), by resource name. */
	private static final Map<String, String> SCRIPTS = new HashMap<>();

	/** One per resident worker service (that is, per context). */
	private static final Map<ResidentWorkerService, ApposePythonEnvironments> ALL =
		new WeakHashMap<>();

	private final ResidentWorkerService workers;

	/** Python executables of script environments, by script and metadata. */
	private final Map<String, String> pythons = new LinkedHashMap<String, String>(
		16, 0.75f, true)
	{

		@Override
		protected boolean removeEldestEntry(
			final Map.Entry<String, String> eldest)
		{
			return size() > 16;
		}
	};

	private DependencyOverlays overlays;

	private ApposePythonEnvironments(final ResidentWorkerService workers) {
		this.workers = workers;
	}

	/** Gets those of a context (by its resident worker service). */
	static ApposePythonEnvironments of(final ResidentWorkerService workers) {
		synchronized (ALL) {
			return ALL.computeIfAbsent(workers, ApposePythonEnvironments::new);
		}
	}

	/**
	 * Derives an {@link org.scijava.code.lsp.Environment} for an Appose script.
	 * <p>
	 * The {@code python} executable is found using the script's text and file
	 * path. For convenience, packages declared in the script but not yet
	 * installed (i.e. between the time the dependency is added to the script
	 * and the time when it is run) are provided to the language server in the
	 * <em>overlay</em>.
	 * </p>
	 *
	 * @param script the script
	 * @return an {@link org.scijava.code.lsp.Environment} where:
	 *         <ul>
	 *         <li>the interpreter is the Python executable of the script's
	 *         declared environment, <b>if that has already been built</b>,
	 *         or {@code null} otherwise;</li>
	 *         <li>the paths hold the overlay, once installed; none if no package
	 *         is missing, or until it is installed (which starts once the
	 *         script's dependencies stop changing, or at once if the script was
	 *         just opened).</li>
	 *         </ul>
	 */
	org.scijava.code.lsp.Environment environment(final TextDocumentItem script) {
		final String text = script.getText();
		final String path = path(script.getUri());
		final String python = scriptPython(text, path);
		final boolean settled = script.getVersion() <= 1;
		// NB: An alternative design could have all the script's dependencies
		// in a constant overlay, but that would have the effect of installing them
		// twice - once for the overlay and once for the script's environment.
		final String overlay = overlay(text, path, python, settled);
		return new org.scijava.code.lsp.Environment(python, overlay == null ? null
			: Collections.singletonList(overlay), null);
	}

	/** Sets the dependency overlays, e.g. to use fake ones in tests. */
	synchronized void setOverlays(final DependencyOverlays overlays) {
		this.overlays = overlays;
	}

	/** The file path of a {@code file:} URI, or null. */
	static String path(final String uri) {
		try {
			final URI u = URI.create(uri);
			return "file".equals(u.getScheme()) ? new File(u).getPath() : null;
		}
		catch (final IllegalArgumentException exc) {
			return null;
		}
	}

	/**
	 * Gets the overlay of packages the script declares but its environment
	 * lacks (see {@link DependencyOverlays}), if ready; starts preparing it
	 * otherwise. Only for environments declared inline, for now.
	 *
	 * @param settled Whether the dependencies are known not to be changing.
	 * @return The overlay directory, or null if none is needed or ready.
	 */
	String overlay(final String text, final String path,
		final String python, final boolean settled)
	{
		final DependencyOverlays overlays = overlays();
		if (overlays == null) return null;
		final List<String> dependencies;
		final String name;
		try {
			final String metadata = InlineMetadata.extract(text);
			if (metadata == null) return null;
			dependencies = InlineMetadata.dependencies(metadata);
			// NB: Unsaved scripts' environments are named after their metadata,
			// which changes as dependencies are edited: share one overlay.
			name = path == null ? "unsaved" : ScriptEnvironment.inlineName(metadata,
				path);
		}
		catch (final RuntimeException exc) {
			return null; // NB: E.g. half-typed metadata.
		}
		return overlays.overlay(name, python, dependencies, settled);
	}

	/** Gets the dependency overlays, creating them on first use. */
	private synchronized DependencyOverlays overlays() {
		if (overlays != null) return overlays;
		if (workers == null) return null;
		overlays = new DependencyOverlays((python, dependencies,
			dir) -> installOverlay(workers, python, dependencies, dir), new File(
				Environments.apposeEnvsDir(), TOOLING_ENV + "-overlays"),
			OVERLAY_SETTLE, System::currentTimeMillis);
		return overlays;
	}

	/**
	 * Installs an overlay by running overlay.py on the tooling environment's
	 * worker. Called on the overlays' background thread.
	 */
	private static String installOverlay(final ResidentWorkerService workers,
		final String python, final List<String> dependencies, final File dir)
	{
		final Map<String, Object> inputs = new HashMap<>();
		inputs.put("python", python);
		inputs.put("dependencies", dependencies);
		inputs.put("overlay", dir.getAbsolutePath());
		try {
			final Service.Task task = toolingWorker(workers).task(resource(
				"overlay.py"), inputs);
			task.waitFor();
			final Object overlay = task.outputs.get("path");
			return overlay instanceof String ? (String) overlay : null;
		}
		catch (final Exception exc) {
			throw new IllegalStateException("Overlay installation failed", exc);
		}
	}

	/**
	 * Gets the Python executable of the script's environment, if it has been
	 * built already: in this session (e.g. by running the script), or in an
	 * earlier one. Completion never builds it. Returns null if not built.
	 */
	String scriptPython(final String text, final String path) {
		// NB: Called before each request, so remember what was found (but not
		// what was not: the environment may be built at any moment).
		final String key;
		try {
			key = path + "\n" + ScriptEnvironment.directive(text) + "\n" +
				InlineMetadata.extract(text);
		}
		catch (final RuntimeException exc) {
			return null; // NB: E.g. half-typed metadata.
		}
		synchronized (pythons) {
			final String known = pythons.get(key);
			if (known != null && new File(known).isFile()) return known;
		}
		final String python = findScriptPython(text, path);
		if (python != null) {
			synchronized (pythons) {
				pythons.put(key, python);
			}
		}
		return python;
	}

	/** As {@link #scriptPython}, without remembering. */
	private String findScriptPython(final String text, final String path) {
		final ScriptEnvironment env;
		try {
			final Map<String, String> directive = ScriptEnvironment.directive(text);
			env = ScriptEnvironment.of(text, path, directive.get("env"), directive
				.get("scheme"));
		}
		catch (final IOException | RuntimeException exc) {
			return null; // NB: E.g. half-typed metadata.
		}
		if (env == null) return null;
		// NB: A read-only lookup: worker(...) would replace the worker whenever
		// the configuration changed, i.e. as the user edits it.
		final ResidentWorker worker = workers == null ? null : workers.find(
			env.name, env.config());
		if (worker != null && worker.environment().isBuilt()) {
			try {
				final String python = pythonExecutable(worker.environment().get());
				if (python != null) return python;
			}
			catch (final Exception exc) {
				// NB: Fall back to looking on disk.
			}
		}
		// Built in an earlier session? Its files are where Appose keeps them.
		return pythonOnDisk(new File(Environments.apposeEnvsDir(), env.name));
	}

	/** Finds the Python executable of a built environment, or null. */
	static String pythonExecutable(final Environment env) {
		// NB: Look in the bin paths, and in their parents: on Windows, conda-style
		// (e.g. pixi) environments keep python.exe in the environment's root,
		// above the bin directory that Appose reports.
		final List<File> dirs = new ArrayList<>();
		for (final String dir : env.binPaths()) {
			dirs.add(new File(dir));
			final File parent = new File(dir).getParentFile();
			if (parent != null) dirs.add(parent);
		}
		if (env.base() != null) {
			final File base = new File(env.base());
			dirs.add(base);
			dirs.add(new File(base, "Scripts"));
			dirs.add(new File(base, "bin"));
		}
		return findPython(dirs);
	}

	/**
	 * Finds the Python executable of an environment in the given directory, as
	 * Appose builds them (pixi, uv or conda), without building or starting
	 * anything; or null if there is none.
	 */
	static String pythonOnDisk(final File envDir) {
		if (!envDir.isDirectory()) return null;
		final List<File> dirs = new ArrayList<>();
		// pixi: .pixi/envs/<name>, with "default" first.
		final File pixiEnvs = new File(envDir, ".pixi" + File.separator + "envs");
		final List<File> pixi = new ArrayList<>();
		pixi.add(new File(pixiEnvs, "default"));
		final File[] others = pixiEnvs.listFiles(File::isDirectory);
		if (others != null) pixi.addAll(Arrays.asList(others));
		for (final File p : pixi) {
			dirs.add(p);
			dirs.add(new File(p, "bin"));
		}
		// uv (a virtual environment) and conda/mamba.
		for (final File base : new File[] { envDir, new File(envDir, ".venv") }) {
			dirs.add(new File(base, "Scripts"));
			dirs.add(new File(base, "bin"));
			dirs.add(base);
		}
		return findPython(dirs);
	}

	private static String findPython(final List<File> dirs) {
		for (final File dir : dirs) {
			for (final String exe : new String[] { "python.exe", "python",
				"python3" })
			{
				final File f = new File(dir, exe);
				if (f.isFile()) return f.getAbsolutePath();
			}
		}
		return null;
	}

	/**
	 * Gets the Python of the tooling environment, in which jedi-language-server
	 * runs (see {@link JediServerProcessPlugin}): built first if needed (but
	 * starting no worker).
	 */
	static String toolingPython(final ResidentWorkerService workers)
		throws Exception
	{
		final String python = pythonExecutable(toolingWorker(workers).environment()
			.get());
		if (python == null) throw new IOException("No Python in " + TOOLING_ENV);
		return python;
	}

	/** Gets the tooling environment's worker, building it first if needed. */
	private static ResidentWorker toolingWorker(
		final ResidentWorkerService workers)
	{
		final String config = toolingConfig();
		return workers.worker(TOOLING_ENV, config, () -> Appose.content(config)
			.scheme("pixi.toml"), Environment::python);
	}

	/**
	 * The tooling environment: Python, Jedi, jedi-language-server, NumPy and
	 * Appose; and uv, to install packages for {@link DependencyOverlays}.
	 */
	static String toolingConfig() {
		return "[workspace]\n" + //
			"name = \"" + TOOLING_ENV + "\"\n" + //
			"channels = [\"conda-forge\"]\n" + //
			"platforms = [\"" + InlineMetadata.condaPlatform() + "\"]\n" + //
			"\n" + //
			"[dependencies]\n" + //
			"python = \"3.12.*\"\n" + //
			"jedi = \">=0.19.2\"\n" + //
			// NB: Script parameters are typed as NumPy arrays (see
			// ApposePythonDialect): known even before a script's environment is.
			"numpy = \"*\"\n" + //
			"\n" + //
			"[pypi-dependencies]\n" + //
			"appose = \"*\"\n" + //
			"jedi-language-server = \">=0.47\"\n" + //
			"uv = \"*\"\n";
	}

	/** Gets a task script (e.g. overlay.py), by resource name. */
	private static String resource(final String name) throws IOException {
		synchronized (SCRIPTS) {
			final String known = SCRIPTS.get(name);
			if (known != null) return known;
			try (final InputStream in = ApposePythonEnvironments.class
				.getResourceAsStream(name))
			{
				if (in == null) throw new IOException(name + " not found");
				final java.io.ByteArrayOutputStream out =
					new java.io.ByteArrayOutputStream();
				final byte[] buf = new byte[8192];
				int n;
				while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
				final String script = new String(out.toByteArray(),
					StandardCharsets.UTF_8);
				SCRIPTS.put(name, script);
				return script;
			}
		}
	}
}
