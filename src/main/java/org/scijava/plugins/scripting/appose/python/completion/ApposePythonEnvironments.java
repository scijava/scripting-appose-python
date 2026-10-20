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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.function.Consumer;

import org.apposed.appose.Environment;
import org.apposed.appose.util.Environments;
import org.scijava.plugins.scripting.appose.python.ScriptEnvironment;
import org.scijava.plugins.scripting.appose.python._internal.InlineMetadata;
import org.scijava.plugins.scripting.appose.python._internal.LazyEnvironment;
import org.scijava.plugins.scripting.appose.python._internal.ResidentWorker;
import org.scijava.plugins.scripting.appose.python._internal.ResidentWorkerService;

/**
 * Where Python scripts run via Appose, as their language servers need to
 * know: the Python of a script's environment, if built (in this session, or an
 * earlier one).
 * <p>
 * An environment not built yet (e.g. as the script's dependencies are
 * edited) is built in the background, once its configuration has stayed the
 * same for a while, so that completion soon knows what the script will run
 * with. A script running in the environment is waited for first: its
 * environment is not rebuilt beneath it. Building replaces the environment's
 * resident worker (of its earlier configuration), as the script's next run
 * would.
 * </p>
 *
 * @author Gabriel Selzer
 */
final class ApposePythonEnvironments {

	/**
	 * How long an environment's configuration must stay the same before it is
	 * built, in milliseconds.
	 */
	static final long SETTLE = 5000;

	/** Where settling environments are waited for. */
	private static final ScheduledExecutorService TIMER = Executors
		.newSingleThreadScheduledExecutor(daemon("appose-python-settle"));

	/** Where environments are built: one at a time. */
	private static final ExecutorService BUILDS = Executors
		.newSingleThreadExecutor(daemon("appose-python-prebuild"));

	/** The resident workers, to find built environments; or null if none. */
	private final ResidentWorkerService workers;

	/** How long a configuration must stay the same before it is built. */
	private final long settle;

	/** Builds an environment; or null, to build none. */
	private final Consumer<ScriptEnvironment> build;

	/** Scripts waiting to settle, by path ("" if unsaved): what, and when. */
	private final Map<String, Settling> settling = new HashMap<>();

	/**
	 * The configuration last built (or failing to) for each script, by path
	 * ("" if unsaved): not built again, unless the script changes to another.
	 */
	private final Map<String, String> tried = new HashMap<>();

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

	/** @param workers The resident workers, or null if none (none built). */
	ApposePythonEnvironments(final ResidentWorkerService workers) {
		this(workers, SETTLE, null);
	}

	/**
	 * @param workers The resident workers, or null if none.
	 * @param settle How long a configuration must stay the same before it is
	 *          built, in milliseconds.
	 * @param build Builds an environment (e.g. a fake one, in tests); or null,
	 *          to build them with the workers (if any).
	 */
	ApposePythonEnvironments(final ResidentWorkerService workers,
		final long settle, final Consumer<ScriptEnvironment> build)
	{
		this.workers = workers;
		this.settle = settle;
		this.build = build != null ? build : workers != null ? this::build
			: null;
	}

	/**
	 * Gets where an Appose script runs: the Python executable of its declared
	 * environment, <b>if that has already been built</b>; or none (the
	 * servers' own Python), otherwise.
	 *
	 * @param text The script's text.
	 * @param path The script's file path, or null if it has none.
	 */
	org.scijava.code.lsp.Environment environment(final String text,
		final String path)
	{
		final String python = scriptPython(text, path);
		return python == null ? org.scijava.code.lsp.Environment.NONE
			: new org.scijava.code.lsp.Environment(python, null);
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
		// FIXME: For an unsaved script, this path is null, so its environment is
		// named after its metadata (see ScriptEnvironment#inlineName). But the
		// script editor runs it with its tab's name as its path (e.g. New_.py),
		// so a run names it after that: the environment built here is not the
		// one the run uses, which builds another. Completion and runs should
		// agree on unsaved scripts' paths: e.g. the engine taking a path that is
		// no file as none, or the editor giving sessions its tab's name too.
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
		else prepare(path, env);
		// NB: Being built (e.g. above): not usable yet, though its Python may
		// already exist.
		if (LazyEnvironment.isBuilding(env.name)) return null;
		// Built in an earlier session? Its files are where Appose keeps them.
		return pythonOnDisk(new File(Environments.apposeEnvsDir(), env.name));
	}

	/**
	 * Builds an environment once its configuration has stayed the same for a
	 * while (see {@link #settle}), unless it was tried already.
	 */
	private void prepare(final String path, final ScriptEnvironment env) {
		// NB: By script, not by environment: an unsaved script's environment
		// is named after its configuration, so changes to it with each edit.
		final String script = path == null ? "" : path;
		if (build == null) return;
		final String key = env.name + "\n" + env.config();
		synchronized (settling) {
			if (key.equals(tried.get(script))) return;
			final Settling last = settling.get(script);
			if (last != null) {
				if (last.key.equals(key)) return; // NB: Already waiting.
				last.timer.cancel(false);
			}
			settling.put(script, new Settling(key, TIMER.schedule(() -> {
				synchronized (settling) {
					settling.remove(script);
					if (key.equals(tried.put(script, key))) return;
				}
				CompletableFuture.runAsync(() -> build.accept(env), BUILDS);
			}, settle, TimeUnit.MILLISECONDS)));
		}
	}

	/**
	 * Builds an environment, with the resident workers: waiting for a script
	 * running in it first, then replacing its worker, as the script's next run
	 * would.
	 */
	private void build(final ScriptEnvironment env) {
		try {
			final Optional<Lock> running = workers.current(env.name).map(
				ResidentWorker::exclusive);
			final ResidentWorker worker;
			if (running.isPresent()) running.get().lockInterruptibly();
			try {
				worker = env.worker(workers);
			}
			finally {
                running.ifPresent(Lock::unlock);
			}
			// NB: Shown as a task, as all builds are (see ResidentWorkerService).
			worker.environment().get();
		}
		catch (final InterruptedException exc) {
			Thread.currentThread().interrupt();
		}
		catch (final Exception exc) {
			// NB: E.g. the context was disposed, or the build failed (which its
			// task shows): completion goes on without it.
		}
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

	private static ThreadFactory daemon(final String name) {
		return r -> {
			final Thread t = new Thread(r, name);
			t.setDaemon(true);
			return t;
		};
	}

	/** An environment's configuration, waiting to settle. */
	private static final class Settling {

		private final String key;
		private final ScheduledFuture<?> timer;

		private Settling(final String key, final ScheduledFuture<?> timer) {
			this.key = key;
			this.timer = timer;
		}
	}
}
