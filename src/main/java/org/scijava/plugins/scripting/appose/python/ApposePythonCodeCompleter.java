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

package org.scijava.plugins.scripting.appose.python;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;

import org.apposed.appose.Appose;
import org.apposed.appose.Environment;
import org.apposed.appose.Service;
import org.apposed.appose.util.Environments;
import org.scijava.Context;
import org.scijava.code.api.AbstractCodeCompleterPlugin;
import org.scijava.code.api.CodeCompleterPlugin;
import org.scijava.code.api.CodeCompletionService;
import org.scijava.code.api.Completion;
import org.scijava.code.api.CompletionRequest;
import org.scijava.code.api.CompletionResult;
import org.scijava.code.api.EnvironmentContext;
import org.scijava.code.api.ScriptDialect;
import org.scijava.code.api.ScriptDocument;
import org.scijava.code.api.SignatureHelp;
import org.scijava.code.api.SignatureHelp.Fit;
import org.scijava.code.api.Signatures;
import org.scijava.plugin.Plugin;
import org.scijava.plugins.scripting.appose.python._internal.InlineMetadata;
import org.scijava.plugins.scripting.appose.python._internal.ResidentWorker;
import org.scijava.plugins.scripting.appose.python._internal.ResidentWorkerService;
import org.scijava.plugins.scripting.appose.python.completion.ApposePythonDialect;
import org.scijava.plugins.scripting.appose.python.completion.DependencyOverlays;
import org.scijava.plugins.scripting.appose.python.completion.JediClient;
import org.scijava.plugins.scripting.appose.python.completion.PythonSyntax;
import org.scijava.plugins.scripting.appose.python.completion.PythonSyntax.Kind;
import org.scijava.script.ScriptLanguage;

/**
 * Code completion for {@link ApposePythonScriptLanguage} scripts, in two
 * layers:
 * <ol>
 * <li>{@link PythonSyntax} (tree-sitter, in process): instantly, with no
 * Python needed, names in scope, the script's parameters, builtins and
 * keywords.</li>
 * <li>Jedi, in a small tooling environment of its own, built in the
 * background on first use: members of objects and modules, signatures and
 * documentation. Once the script's own environment is built (by running the
 * script), Jedi analyzes the script against it, so its packages are known.
 * Jedi's answers are used if they come within a short time budget; typing
 * never waits for them.</li>
 * </ol>
 * <p>
 * Completion never builds a script's environment, and never disturbs its
 * worker.
 * </p>
 *
 * @author Gabriel Selzer
 */
@Plugin(type = CodeCompleterPlugin.class, name = "appose-python")
public class ApposePythonCodeCompleter extends AbstractCodeCompleterPlugin {

	/** Name of the tooling environment in which Jedi runs. */
	static final String TOOLING_ENV = "appose-python-completion";

	/**
	 * How long to wait for Jedi before answering without it (and updating the
	 * answer once Jedi's arrives), in milliseconds.
	 */
	private static final long BUDGET = Long.getLong(
		"scijava.appose.completion.budget", 25);

	/** How long to wait for a completion's documentation, in milliseconds. */
	private static final long DESCRIBE_BUDGET = 1000;

	/** How many completions get signatures, once a word is begun. */
	private static final int SIGNATURES = 30;

	/** How many signatures (overloads) each gets, at most. */
	private static final int OVERLOADS = 8;

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

	/** Task scripts (see jedi_complete.py, overlay.py), by resource name. */
	private static final Map<String, String> SCRIPTS = new HashMap<>();

	private JediClient jedi;

	private DependencyOverlays overlays;

	/**
	 * How long a script's dependencies must stay unchanged before packages
	 * missing from its environment are installed for completion, in ms.
	 */
	private static final long OVERLAY_SETTLE = 1500;

	/** How scripts see their parameters (as Python values) and {@code task}. */
	private static final ApposePythonDialect DIALECT = new ApposePythonDialect();

	@Override
	public boolean supports(final ScriptLanguage language) {
		return language instanceof ApposePythonScriptLanguage;
	}

	@Override
	public CompletionResult complete(final CompletionRequest request) {
		final String text = request.text();
		final PythonSyntax.Context ctx = PythonSyntax.analyze(text, request
			.offset());
		if (ctx.kind == Kind.NONE) return CompletionResult.EMPTY;
		if (ctx.kind == Kind.NAME && ctx.prefix.isEmpty()) {
			return CompletionResult.EMPTY;
		}

		final JediClient client = jediClient();
		final ScriptDocument doc = document(request, client != null, false);
		final List<Completion> local = localCompletions(ctx, doc.parameters());
		final CompletableFuture<List<Completion>> answer = client == null ? null
			: jediCompletions(client, request, ctx, doc);
		if (answer == null) return merge(ctx, null, local);

		// Use Jedi's answer if it is (almost) ready; otherwise answer without it
		// at once, and announce the better result to follow.
		try {
			return merge(ctx, answer.get(BUDGET, TimeUnit.MILLISECONDS), local);
		}
		catch (final TimeoutException exc) {
			return merge(ctx, null, local).withUpdate(answer.handle((jedi,
				error) -> jedi == null ? null : merge(ctx, jedi, local)));
		}
		catch (final InterruptedException exc) {
			Thread.currentThread().interrupt();
		}
		catch (final ExecutionException | CancellationException exc) {
			// NB: Jedi failed; the next request asks again.
		}
		return merge(ctx, null, local);
	}

	@Override
	public void prepare(final CompletionRequest request) {
		// Warm up: start Jedi (building its environment, if needed), and have it
		// analyze the script against its environment, in the background.
		final JediClient client = jediClient();
		if (client == null) return;
		final ScriptDocument doc = document(request, true, true);
		client.answer("prepare\n" + request.path() + "\n" + request.text(), query(
			doc, request.text().length(), request.path(), 0));
	}

	@Override
	public ScriptDialect dialect() {
		return DIALECT;
	}

	@Override
	public SignatureHelp signatureHelp(final CompletionRequest request) {
		final String text = request.text();
		final int caret = request.offset();
		if (PythonSyntax.analyze(text, caret).kind == Kind.NONE) {
			return SignatureHelp.NONE; // NB: In a comment or string.
		}
		final int open = Signatures.callStart(text, caret);
		if (open < 0) return SignatureHelp.NONE;
		final List<String> args = Signatures.arguments(text, open, caret);
		if (args == null) return SignatureHelp.NONE;
		final JediClient client = jediClient();
		if (client == null) return SignatureHelp.NONE;

		final ScriptDocument doc = document(request, true, false);
		final JediClient.Query query = query(doc, caret, request.path(), 0);
		final int active = args.size() - 1;
		// NB: Where Jedi cannot tell the argument, count the commas.
		final CompletableFuture<SignatureHelp> help = client.signatures(
			"signatures\n" + key(doc, caret, request.path()), query).handle((h, error) -> h == null ? null
					: new SignatureHelp(h.signatures(), h.activeParameter() >= 0 ? h
						.activeParameter() : active, open));
		try {
			final SignatureHelp h = help.get(BUDGET, TimeUnit.MILLISECONDS);
			return h == null ? SignatureHelp.NONE : h;
		}
		catch (final TimeoutException exc) {
			return SignatureHelp.NONE.withUpdate(help);
		}
		catch (final InterruptedException exc) {
			Thread.currentThread().interrupt();
		}
		catch (final ExecutionException | CancellationException exc) {
			// NB: Jedi failed.
		}
		return SignatureHelp.NONE;
	}

	/**
	 * Adapts the "signatures" task output to the shape of "completions" items
	 * (one item per signature), for {@link #toCompletions}.
	 */
	@SuppressWarnings("unchecked")
	private static List<Object> signatureItems(final Object output) {
		final List<Object> items = new ArrayList<>();
		if (!(output instanceof List)) return items;
		for (final Object o : (List<?>) output) {
			if (!(o instanceof Map)) continue;
			final Map<String, Object> sig = (Map<String, Object>) o;
			final Map<String, Object> item = new HashMap<>();
			item.put("name", sig.get("name"));
			item.put("type", "function");
			item.put("signatures", Collections.singletonList(sig));
			items.add(item);
		}
		return items;
	}

	/**
	 * Merges Jedi's completions (if any) with the local ones: Jedi's first,
	 * since they carry types, signatures and documentation; then whatever only
	 * the local analysis knows.
	 */
	private static CompletionResult merge(final PythonSyntax.Context ctx,
		final List<Completion> jedi, final List<Completion> local)
	{
		final List<Completion> out = new ArrayList<>();
		final Set<String> seen = new HashSet<>();
		if (jedi != null) {
			for (final Completion c : jedi) {
				if (!matches(c.insertionText(), ctx.prefix)) continue;
				if (ctx.kind == Kind.IMPORT) {
					// NB: An import names things rather than calling them: one plain
					// name per function or class, without its parameters.
					if (seen.add(c.insertionText())) out.add(name(c));
					continue;
				}
				out.add(c);
				seen.add(c.insertionText());
			}
		}
		for (final Completion c : local) {
			if (seen.add(c.insertionText())) out.add(c);
		}
		return new CompletionResult(out, ctx.replaceStart);
	}

	/** Sets the dependency overlays, e.g. to use fake ones in tests. */
	synchronized void setOverlays(final DependencyOverlays overlays) {
		this.overlays = overlays;
	}

	/** Sets the Jedi client, e.g. to use a fake one in tests. */
	synchronized void setJediClient(final JediClient jedi) {
		this.jedi = jedi;
	}

	// -- Helper methods --

	/**
	 * A completion inserting just the name of {@code c}: not callable, so the
	 * editor inserts no parameter list for it.
	 */
	static Completion name(final Completion c) {
		final Completion.Kind kind = c.kind() == Completion.Kind.METHOD
			? Completion.Kind.OTHER : c.kind();
		return Completion.builder(c.insertionText()).kind(kind).summary(c
			.summary()).lazyDescription(c::description).build();
	}

	/** Completions from the local (syntactic) analysis alone. */
	private static List<Completion> localCompletions(
		final PythonSyntax.Context ctx, final Map<String, String> params)
	{
		final List<Completion> out = new ArrayList<>();
		final String prefix = ctx.prefix;
		if (ctx.kind == Kind.ATTRIBUTE) {
			for (final String name : ctx.attributesUsed) {
				if (matches(name, prefix)) out.add(Completion.builder(name).kind(
					Completion.Kind.OTHER).summary("Used elsewhere in this script")
					.build());
			}
			return out;
		}
		if (ctx.kind != Kind.NAME) return out;
		for (final String name : ctx.names) {
			if (matches(name, prefix)) out.add(Completion.builder(name).kind(
				Completion.Kind.VARIABLE).build());
		}
		for (final Map.Entry<String, String> p : params.entrySet()) {
			if (matches(p.getKey(), prefix)) out.add(Completion.builder(p.getKey())
				.kind(Completion.Kind.VARIABLE).summary(p.getValue() == null ? "Script parameter"
					: p.getValue() + " (script parameter)").build());
		}
		if (matches("task", prefix)) out.add(Completion.builder("task").kind(
			Completion.Kind.VARIABLE).summary(ApposePythonDialect.TASK_TYPE).build());
		for (final String name : PythonSyntax.BUILTINS) {
			if (matches(name, prefix)) out.add(Completion.builder(name).kind(
				Completion.Kind.OTHER).summary("builtin").build());
		}
		for (final String keyword : PythonSyntax.KEYWORDS) {
			if (matches(keyword, prefix)) out.add(Completion.builder(keyword).kind(
				Completion.Kind.KEYWORD).build());
		}
		return out;
	}

	/**
	 * Asks Jedi for completions.
	 *
	 * @return Jedi's answer, or pending answer; or null if Jedi is unavailable.
	 */
	private static CompletableFuture<List<Completion>> jediCompletions(
		final JediClient client, final CompletionRequest request,
		final PythonSyntax.Context ctx, final ScriptDocument doc)
	{
		// Ask about the first character typed, if any: later keystrokes of the
		// same word then reuse the answer (filtered), while it stays short.
		final int caret = ctx.replaceStart + Math.min(1, ctx.prefix.length());
		// NB: Signatures are expensive, so ask for them only once a word is
		// begun, which narrows the completions down. Before that, functions are
		// offered by name.
		final JediClient.Query query = query(doc, caret, request.path(), ctx.prefix
			.isEmpty() ? 0 : SIGNATURES);
		return client.answer(key(doc, caret, request.path()), query);
	}

	/**
	 * Gets the script as plain Python, for Jedi: its parameters declared, and
	 * (if asked) where it runs: its environment's Python, and its overlay.
	 *
	 * @param environment Whether to find where the script runs.
	 * @param settled Whether the dependencies are known not to be changing.
	 */
	private ScriptDocument document(final CompletionRequest request,
		final boolean environment, final boolean settled)
	{
		final String text = request.text();
		EnvironmentContext env = null;
		if (environment) {
			final String python = scriptPython(text, request.path());
			final String overlay = overlay(text, request.path(), python, settled);
			env = new EnvironmentContext(python, overlay == null ? null
				: Collections.singletonList(overlay), null);
		}
		final CodeCompletionService service = service(
			CodeCompletionService.class);
		final Map<String, Class<?>> params = service == null ? Collections
			.emptyMap() : service.scriptParameters(text);
		return ScriptDocument.of(text, params, DIALECT, env);
	}

	/** Asks Jedi about the given offset of the script. */
	private static JediClient.Query query(final ScriptDocument doc,
		final int offset, final String path, final int flags)
	{
		final String before = doc.text().substring(0, doc.map().toTransformed(
			offset));
		final int line = count(before, '\n') + 1;
		final int column = before.length() - before.lastIndexOf('\n') - 1;
		final EnvironmentContext env = doc.environment();
		final String overlay = env.searchPaths().isEmpty() ? null : env
			.searchPaths().get(0);
		return new JediClient.Query(doc.text(), line, column, path, env
			.interpreter(), flags, overlay);
	}

	/** Identifies a question about the given offset, for reusing answers. */
	private static String key(final ScriptDocument doc, final int offset,
		final String path)
	{
		final EnvironmentContext env = doc.environment();
		final String overlay = env.searchPaths().isEmpty() ? null : env
			.searchPaths().get(0);
		return env.interpreter() + "\n" + overlay + "\n" + path + "\n" + doc
			.text().substring(0, doc.map().toTransformed(offset));
	}

	/**
	 * Gets the overlay of packages the script declares but its environment
	 * lacks (see {@link DependencyOverlays}), if ready; starts preparing it
	 * otherwise. Only for environments declared inline, for now.
	 *
	 * @param settled Whether the dependencies are known not to be changing.
	 * @return The overlay directory, or null if none is needed or ready.
	 */
	private String overlay(final String text, final String path,
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
		final ResidentWorkerService workers = service(
			ResidentWorkerService.class);
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
				"completion/overlay.py"), inputs);
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
	private String scriptPython(final String text, final String path) {
		// NB: Called on every keystroke, so remember what was found (but not
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
		final ResidentWorkerService workers = service(
			ResidentWorkerService.class);
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

	/** Gets the Jedi client, creating it on first use. */
	private synchronized JediClient jediClient() {
		if (jedi != null) return jedi;
		final ResidentWorkerService workers = service(
			ResidentWorkerService.class);
		if (workers == null) return null;
		final JediClient[] client = { null };
		client[0] = new JediClient(new JediClient.Backend() {

			@Override
			public List<Completion> complete(final String key,
				final JediClient.Query query)
			{
				final Object output = runJedi(workers, "complete", key, query, null)
					.get("completions");
				// NB: Documentation is fetched only for completions looked at.
				return toCompletions(output, name -> () -> client[0].describe(key,
					query, name, DESCRIBE_BUDGET));
			}

			@Override
			public SignatureHelp signatures(final String key,
				final JediClient.Query query)
			{
				final Map<String, Object> outputs = runJedi(workers, "signatures", key,
					query, null);
				final List<SignatureHelp.Signature> signatures = new ArrayList<>();
				for (final Completion c : toCompletions(signatureItems(outputs.get(
					"signatures"))))
				{
					// NB: Python's conversions are not rated yet: Jedi's order.
					signatures.add(new SignatureHelp.Signature(c, Fit.UNKNOWN));
				}
				final Object index = outputs.get("index");
				return new SignatureHelp(signatures, index instanceof Number
					? ((Number) index).intValue() : -1, -1);
			}

			@Override
			public String describe(final String key, final JediClient.Query query,
				final String name)
			{
				final Object doc = runJedi(workers, "describe", key, query, name).get(
					"docstring");
				return doc instanceof String && !((String) doc).isEmpty() ? "<pre>" +
					escapeHTML((String) doc) + "</pre>" : null;
			}
		});
		jedi = client[0];
		return jedi;
	}

	/**
	 * Runs a Jedi task (see jedi_complete.py) on the tooling environment's
	 * worker, building the environment first, if needed. Called on the Jedi
	 * client's background thread.
	 *
	 * @return The task's outputs.
	 */
	private static Map<String, Object> runJedi(
		final ResidentWorkerService workers, final String op, final String key,
		final JediClient.Query query, final String name)
	{
		final ResidentWorker worker = toolingWorker(workers);
		final Map<String, Object> inputs = new HashMap<>();
		inputs.put("op", op);
		inputs.put("key", key);
		inputs.put("source", query.source);
		inputs.put("line", query.line);
		inputs.put("column", query.column);
		inputs.put("path", query.path);
		inputs.put("python", query.python);
		inputs.put("max_signatures", query.signatures);
		inputs.put("max_overloads", OVERLOADS);
		inputs.put("name", name);
		inputs.put("overlay", query.overlay);
		try {
			final Service.Task task = worker.task(resource(
				"completion/jedi_complete.py"), inputs);
			task.waitFor();
			return task.outputs;
		}
		catch (final Exception exc) {
			throw new IllegalStateException("Jedi failed", exc);
		}
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
	 * The tooling environment: Python, Jedi and Appose; and uv, to install
	 * packages for {@link DependencyOverlays}.
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
			"\n" + //
			"[pypi-dependencies]\n" + //
			"appose = \"*\"\n" + //
			"uv = \"*\"\n";
	}

	/** Converts the task's output (see jedi_complete.py) to completions. */
	static List<Completion> toCompletions(final Object output) {
		return toCompletions(output, null);
	}

	/**
	 * Converts the task's output (see jedi_complete.py) to completions.
	 *
	 * @param docs Supplies documentation, by completion name, on demand; or
	 *          null if there is none.
	 */
	@SuppressWarnings("unchecked")
	static List<Completion> toCompletions(final Object output,
		final Function<String, Supplier<String>> docs)
	{
		if (!(output instanceof List)) return Collections.emptyList();
		final List<Completion> out = new ArrayList<>();
		for (final Object o : (List<?>) output) {
			if (!(o instanceof Map)) continue;
			final Map<String, Object> item = (Map<String, Object>) o;
			final String name = (String) item.get("name");
			if (name == null) continue;
			final String type = (String) item.get("type");
			final String doc = (String) item.get("docstring");
			final Supplier<String> description = doc != null ? () -> "<pre>" +
				escapeHTML(doc) + "</pre>" : docs != null ? docs.apply(name) : null;
			final List<Object> signatures = item.get(
				"signatures") instanceof List ? (List<Object>) item.get("signatures")
					: Collections.emptyList();
			if (signatures.isEmpty()) {
				// NB: Without a signature, even a function is offered by name only.
				final Completion.Kind kind = kind(type);
				out.add(Completion.builder(name).kind(kind == Completion.Kind.METHOD
					? Completion.Kind.OTHER : kind).summary((String) item.get(
						"description")).lazyDescription(description).build());
				continue;
			}
			// One completion per signature, e.g. for each typing.overload.
			for (final Object s : signatures) {
				final Map<String, Object> sig = (Map<String, Object>) s;
				final List<Completion.Parameter> params = new ArrayList<>();
				for (final Object p : (List<Object>) sig.get("params")) {
					final List<Object> param = (List<Object>) p;
					params.add(new Completion.Parameter((String) param.get(0),
						(String) param.get(1)));
				}
				out.add(Completion.builder(name).kind(Completion.Kind.METHOD)
					.parameters(params).returnType((String) sig.get("returns")).summary(
						(String) item.get("description")).lazyDescription(description)
					.build());
			}
		}
		return out;
	}

	private static Completion.Kind kind(final String jediType) {
		if (jediType == null) return Completion.Kind.OTHER;
		switch (jediType) {
			case "function":
				return Completion.Kind.METHOD;
			case "class":
				return Completion.Kind.CLASS;
			case "module":
				return Completion.Kind.PACKAGE;
			case "property":
				return Completion.Kind.FIELD;
			case "keyword":
				return Completion.Kind.KEYWORD;
			case "instance":
			case "statement":
			case "param":
				return Completion.Kind.VARIABLE;
			default:
				return Completion.Kind.OTHER;
		}
	}

	/** Gets a task script (e.g. jedi_complete.py), by resource name. */
	private static String resource(final String name) throws IOException {
		synchronized (SCRIPTS) {
			final String known = SCRIPTS.get(name);
			if (known != null) return known;
			try (final InputStream in = ApposePythonCodeCompleter.class
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

	private <S extends org.scijava.service.Service> S service(
		final Class<S> type)
	{
		final Context context = getContext();
		return context == null ? null : context.getService(type);
	}

	private static boolean matches(final String name, final String prefix) {
		if (name.startsWith("__") && !prefix.startsWith("_")) return false;
		return name.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(
			Locale.ROOT)) && !name.equals(prefix);
	}

	private static int count(final String s, final char c) {
		int n = 0;
		for (int i = 0; i < s.length(); i++) {
			if (s.charAt(i) == c) n++;
		}
		return n;
	}

	private static String escapeHTML(final String s) {
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
