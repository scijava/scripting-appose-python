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
import org.scijava.code.api.Diagnostic;
import org.scijava.code.api.Hover;
import org.scijava.code.api.EnvironmentContext;
import org.scijava.code.api.ScriptDialect;
import org.scijava.code.api.ScriptDocument;
import org.scijava.code.api.SignatureHelp;
import org.scijava.code.api.Signatures;
import org.scijava.code.lsp.LspClient;
import org.scijava.code.lsp.LspServers;
import org.scijava.code.lsp.LspService;
import org.scijava.code.lsp.PythonLspClient;
import org.scijava.plugin.Plugin;
import org.scijava.plugins.scripting.appose.python._internal.InlineMetadata;
import org.scijava.plugins.scripting.appose.python._internal.ResidentWorker;
import org.scijava.plugins.scripting.appose.python._internal.ResidentWorkerService;
import org.scijava.plugins.scripting.appose.python.completion.ApposePythonDialect;
import org.scijava.plugins.scripting.appose.python.completion.DependencyOverlays;
import org.scijava.plugins.scripting.appose.python.completion.JediLanguageServerSpec;
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
 * <li>jedi-language-server (a language server built on Jedi), in a small
 * tooling environment of its own, built in the background on first use:
 * members of objects and modules, signatures and documentation. Once the
 * script's own environment is built (by running the script), the server
 * analyzes the script against it, so its packages are known. Its answers are
 * used if they come within a short time budget; typing never waits for
 * them.</li>
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

	/** Name of the tooling environment in which the language server runs. */
	static final String TOOLING_ENV = "appose-python-completion";

	/**
	 * How long to wait for the language server before answering without it
	 * (and updating the answer once its arrives), in milliseconds.
	 */
	private static final long BUDGET = Long.getLong(
		"scijava.appose.completion.budget", 25);

	/** How long to wait for the server to find problems, in milliseconds. */
	private static final long DIAGNOSE_TIMEOUT = 30_000;

	/** How many completions get signatures, once a word is begun. */
	private static final int SIGNATURES = 30;


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

	/** Task scripts (see overlay.py), by resource name. */
	private static final Map<String, String> SCRIPTS = new HashMap<>();

	private LspClient lsp;

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

		final LspClient client = lspClient();
		final ScriptDocument doc = document(request, client != null, false);
		final List<Completion> local = localCompletions(ctx, doc.parameters());
		if (client == null) return merge(ctx, null, local);
		// Ask about the first character typed, if any: later keystrokes of the
		// same word then reuse the answer (filtered), while it stays short.
		// NB: Signatures are expensive, so ask for them only once a word is
		// begun, which narrows the completions down. Before that, functions are
		// offered by name.
		final CompletableFuture<List<Completion>> answer = client.complete(doc,
			uri(request), ctx.replaceStart + Math.min(1, ctx.prefix.length()),
			ctx.prefix.isEmpty() ? 0 : SIGNATURES);

		// Use the server's answer if it is (almost) ready; otherwise answer
		// without it at once, and announce the better result to follow.
		try {
			return merge(ctx, answer.get(BUDGET, TimeUnit.MILLISECONDS), local);
		}
		catch (final TimeoutException exc) {
			return merge(ctx, null, local).withUpdate(answer.handle((server,
				error) -> server == null ? null : merge(ctx, server, local)));
		}
		catch (final InterruptedException exc) {
			Thread.currentThread().interrupt();
		}
		catch (final ExecutionException | CancellationException exc) {
			// NB: The server failed (and said why in the log); the next request
			// asks again.
		}
		return merge(ctx, null, local);
	}

	@Override
	public void prepare(final CompletionRequest request) {
		// Warm up: start the language server (building its environment, if
		// needed), and have it analyze the script against its environment, in
		// the background.
		final LspClient client = lspClient();
		if (client == null) return;
		client.prepare(document(request, true, true), uri(request));
	}

	@Override
	public void closed(final CompletionRequest request) {
		final LspClient client;
		synchronized (this) {
			client = lsp;
		}
		if (client != null) client.close(uri(request));
	}

	@Override
	public ScriptDialect dialect() {
		return DIALECT;
	}

	@Override
	public Hover hover(final CompletionRequest request) {
		final String text = request.text();
		final int at = request.offset();
		if (PythonSyntax.analyze(text, at).kind == Kind.NONE) {
			return Hover.NONE; // NB: In a comment or string.
		}
		final LspClient client = lspClient();
		if (client == null) return Hover.NONE;
		final CompletableFuture<Hover> hover = client.hover(document(request,
			true, false), uri(request), at).handle((h, error) -> h);
		try {
			final Hover h = hover.get(BUDGET, TimeUnit.MILLISECONDS);
			return h == null ? Hover.NONE : h;
		}
		catch (final TimeoutException exc) {
			return Hover.NONE.withUpdate(hover);
		}
		catch (final InterruptedException exc) {
			Thread.currentThread().interrupt();
		}
		catch (final ExecutionException | CancellationException exc) {
			// NB: The server failed.
		}
		return Hover.NONE;
	}

	@Override
	public CompletableFuture<List<Diagnostic>> diagnose(
		final CompletionRequest request)
	{
		final LspClient client = lspClient();
		if (client == null) return super.diagnose(request);
		return client.diagnose(document(request, true, false), uri(request),
			DIAGNOSE_TIMEOUT);
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
		final LspClient client = lspClient();
		if (client == null) return SignatureHelp.NONE;

		// NB: Where the server cannot tell the argument, count the commas.
		final CompletableFuture<SignatureHelp> help = client.signatureHelp(
			document(request, true, false), uri(request), caret, open, args.size() -
				1).handle((h, error) -> h);
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
			// NB: The server failed.
		}
		return SignatureHelp.NONE;
	}

	/**
	 * Merges the server's completions (if any) with the local ones: the
	 * server's first, since they carry types, signatures and documentation;
	 * then whatever only the local analysis knows.
	 */
	private static CompletionResult merge(final PythonSyntax.Context ctx,
		final List<Completion> server, final List<Completion> local)
	{
		final List<Completion> out = new ArrayList<>();
		final Set<String> seen = new HashSet<>();
		if (server != null) {
			for (final Completion c : server) {
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

	/** Sets the language server client, e.g. to use a fake one in tests. */
	synchronized void setLspClient(final LspClient lsp) {
		this.lsp = lsp;
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
	 * Gets the script as plain Python, for the server: its parameters
	 * declared, and
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

	/** The URI of the script, for the language server. */
	private static String uri(final CompletionRequest request) {
		return LspClient.uri(request.path(), "script.py");
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

	/**
	 * Gets the language server client, creating it on first use; for the
	 * Python servers of the context (see {@link ApposePythonLspServerPlugin}).
	 */
	private synchronized LspClient lspClient() {
		if (lsp != null) return lsp;
		final LspService service = service(LspService.class);
		final LspServers servers = service == null ? null : service.servers(
			"python");
		if (servers == null) return null;
		lsp = new PythonLspClient(servers);
		return lsp;
	}

	/**
	 * How to start jedi-language-server: with the Python of the tooling
	 * environment, built first if needed (but starting no worker).
	 */
	static JediLanguageServerSpec serverSpec(
		final ResidentWorkerService workers)
	{
		final File envs = new File(Environments.apposeEnvsDir());
		return new JediLanguageServerSpec(() -> {
			final String python = pythonExecutable(toolingWorker(workers)
				.environment().get());
			if (python == null) throw new IOException("No Python in " +
				TOOLING_ENV);
			return python;
		}, new File(envs, TOOLING_ENV + "-workspace"), new File(envs, TOOLING_ENV +
			"-server.log"));
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
	 * The tooling environment: Python, Jedi, jedi-language-server and Appose;
	 * and uv, to install packages for {@link DependencyOverlays}.
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
			"jedi-language-server = \">=0.47\"\n" + //
			"uv = \"*\"\n";
	}

	/** Gets a task script (e.g. overlay.py), by resource name. */
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
}
