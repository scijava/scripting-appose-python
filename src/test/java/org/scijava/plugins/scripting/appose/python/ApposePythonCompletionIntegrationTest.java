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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.SignatureHelp;
import org.eclipse.lsp4j.SignatureHelpParams;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.scijava.Context;
import org.scijava.code.lsp.LanguageServerService;
import org.scijava.code.lsp.Positions;
import org.scijava.code.lsp.MergedLanguageServer;
import org.scijava.code.lsp.UpdatingCompletionList;
import org.scijava.module.ModuleService;
import org.scijava.script.ScriptInfo;
import org.scijava.script.ScriptLanguage;
import org.scijava.script.ScriptModule;
import org.scijava.script.ScriptService;

/**
 * End-to-end tests of the language servers of Python scripts run via Appose
 * (see {@link JediServerPlugin}), with a real jedi-language-server, through
 * the {@link LanguageServerService}, as editors use it.
 * <p>
 * The first completion builds the completion tooling environment (Python and
 * jedi-language-server, via pixi), and a script run builds the small uv
 * environment of {@code appose-test/requirements.txt}; both require network
 * access.
 * </p>
 *
 * @author Gabriel Selzer
 */
public class ApposePythonCompletionIntegrationTest {

	private static final String HEADER = "#!appose-python\n" +
		"#@script(env=\"requirements.txt\", scheme=\"requirements.txt\")\n";

	/** How long to wait for environments to build. */
	private static final long BUILD_TIMEOUT = TimeUnit.MINUTES.toMillis(15);

	private static Context context;
	private static ScriptLanguage language;
	private static MergedLanguageServer server;
	private static File scriptDir;

	@BeforeClass
	public static void setUp() throws URISyntaxException {
		context = new Context();
		language = context.service(ScriptService.class).getLanguageByName(
			"appose-python");
		server = context.service(LanguageServerService.class).server(language);
		scriptDir = new File(ApposePythonCompletionIntegrationTest.class
			.getResource("/appose-test/requirements.txt").toURI()).getParentFile();
	}

	@AfterClass
	public static void tearDown() {
		if (context != null) context.dispose();
	}

	@Test
	public void testStandardLibrary() throws Exception {
		// Before any script environment is built, the server knows the standard
		// library, with signatures and documentation.
		final List<CompletionItem> completions = completeUntil(
			"import json\njson.du", null, "dumps");
		final CompletionItem dumps = completions.stream().filter(c -> c.getLabel()
			.equals("dumps") && c.getLabelDetails() != null).findFirst().get();
		assertTrue(dumps.getLabelDetails().getDetail(), dumps.getLabelDetails()
			.getDetail().startsWith("(Any obj, bool skipkeys"));
		assertTrue(dumps.getDocumentation() != null || server
			.resolveCompletionItem(dumps).get(1, TimeUnit.MINUTES)
			.getDocumentation() != null);
		report("import json\njson.l", null, "loads", "o");
	}

	@Test
	public void testScriptEnvironment() throws Exception {
		// Run a script, building its environment (which includes numpy).
		final File script = new File(scriptDir, "completion.py");
		final String header = HEADER + "#@ net.imglib2.img.Img image\n" +
			"import numpy as np\n";
		Files.write(script.toPath(), (header + "x = 1\n").getBytes(
			StandardCharsets.UTF_8));
		final ModuleService moduleService = context.service(ModuleService.class);
		final ScriptModule module = (ScriptModule) moduleService.createModule(
			new ScriptInfo(context, script));
		moduleService.run(module, false, Collections.singletonMap("image",
			null)).get();

		// Now the server analyzes the script against its environment: numpy is
		// known, and so is the type of the image parameter.
		completeUntil(header + "np.ze", script.getPath(), "zeros");
		completeUntil(header + "image.sha", script.getPath(), "shape");
		report(header + "np.random.r", script.getPath(), "randn", "a");
	}

	@Test
	public void testSignatureHelp() throws Exception {
		final String code = "import json\njson.dumps(obj, ";
		final String uri = sync(code, null);
		final long deadline = System.currentTimeMillis() + BUILD_TIMEOUT;
		SignatureHelp help = null;
		while (help == null && System.currentTimeMillis() < deadline) {
			help = server.signatureHelp(new SignatureHelpParams(
				new TextDocumentIdentifier(uri), Positions.position(code, code
					.length()))).get(1, TimeUnit.MINUTES);
		}
		assertTrue(help.getSignatures().get(0).getLabel().contains("dumps("));
		assertFalse(help.getSignatures().get(0).getParameters().isEmpty());
		assertEquals(Integer.valueOf(1), help.getActiveParameter());
	}

	@Test
	public void testHover() throws Exception {
		final String code = "import json\njson.dumps(1)";
		final String uri = sync(code, null);
		final long deadline = System.currentTimeMillis() + BUILD_TIMEOUT;
		Hover hover = null;
		while (hover == null && System.currentTimeMillis() < deadline) {
			hover = server.hover(new HoverParams(new TextDocumentIdentifier(uri),
				Positions.position(code, code.indexOf("dumps") + 2))).get(1,
					TimeUnit.MINUTES);
		}
		assertTrue(String.valueOf(hover), hover.getContents().getRight().getValue()
			.contains("JSON"));
		// The word's range, in the script.
		assertEquals(Positions.position(code, code.indexOf("dumps")), hover
			.getRange().getStart());
		assertEquals(Positions.position(code, code.indexOf("dumps") + 5), hover
			.getRange().getEnd());
	}

	@Test
	public void testDiagnostics() throws Exception {
		// A syntax error, on the script's third line.
		final String code = "#@ double sigma\nx = sigma\ny = (1 +\n";
		final LanguageServerService servers = context.service(
			LanguageServerService.class);
		final long deadline = System.currentTimeMillis() + BUILD_TIMEOUT;
		List<Diagnostic> problems = Collections.emptyList();
		while (problems.isEmpty() && System.currentTimeMillis() < deadline) {
			problems = servers.diagnose(language, "untitled:/diagnose.py", code,
				120_000);
		}
		assertFalse(problems.isEmpty());
		assertTrue(problems.toString(), problems.get(0).getRange().getStart()
			.getLine() >= 2);
		// Fixed: no more problems.
		assertTrue(servers.diagnose(language, "untitled:/diagnose.py", code
			.replace("(1 +", "(1 + 2)"), 120_000).isEmpty());
	}

	@Test
	public void testParameterBeforeEnvironment() throws Exception {
		// An image parameter is a NumPy array: known, even though the script's
		// environment is not built (here, it is not even declared).
		completeUntil("#@ net.imglib2.img.Img image\nx = 1\nimage.", null,
			"shape");
	}

	@Test
	public void testServerOutlivesNoParent() throws Exception {
		// The server, once its parent (this JVM) is gone, must exit: here, its
		// parent is a process that already has.
		completeUntil("import json\njson.du", null, "dumps");
		final String python = ApposePythonEnvironments.pythonOnDisk(new File(
			org.apposed.appose.util.Environments.apposeEnvsDir(),
			ApposePythonEnvironments.TOOLING_ENV));
		final Process gone = new ProcessBuilder(python, "-c", "pass").start();
		gone.waitFor();
		final Process process = new ProcessBuilder(python, "-c",
			org.scijava.plugins.scripting.appose.python.completion.JediLanguageServerSpec.LAUNCHER,
			String.valueOf(gone.pid())).redirectErrorStream(true).start();
		final boolean exited = process.waitFor(30, TimeUnit.SECONDS);
		if (!exited) process.destroyForcibly();
		assertTrue(exited);
	}

	@Test
	public void testNewDependency() throws Exception {
		// A script declaring a dependency that is installed nowhere: its
		// environment has not even been built.
		final File script = new File(scriptDir, "overlaid.py");
		final String header = "#!appose-python\n# /// script\n" +
			"# dependencies = [\"tifffile\"]\n# ///\nimport tifffile\n";
		Files.write(script.toPath(), (header + "x = 1\n").getBytes(
			StandardCharsets.UTF_8));
		// Opening it prepares completion, including the dependency's overlay.
		final String code = header + "tifffile.imr";
		sync(code, script.getPath());
		completeUntil(code, script.getPath(), "imread");
	}

	// -- Helper methods --

	/** The open documents' versions, by URI. */
	private static final Map<String, Integer> versions = new HashMap<>();

	/** Opens a script on the server, or changes it; returns its URI. */
	private static String sync(final String code, final String path) {
		final String uri = path == null ? "untitled:/integration.py" : new File(
			path).toURI().toString();
		final Integer version = versions.get(uri);
		if (version == null) {
			versions.put(uri, 1);
			server.didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(uri,
				"appose-python", 1, code)));
		}
		else {
			versions.put(uri, version + 1);
			server.didChange(new DidChangeTextDocumentParams(
				new VersionedTextDocumentIdentifier(uri, version + 1), Collections
					.singletonList(new TextDocumentContentChangeEvent(code))));
		}
		return uri;
	}

	/** Completes until the expected completion appears (environments build). */
	private static List<CompletionItem> completeUntil(final String code,
		final String path, final String expected) throws Exception
	{
		final long deadline = System.currentTimeMillis() + BUILD_TIMEOUT;
		while (System.currentTimeMillis() < deadline) {
			final List<CompletionItem> completions = complete(code, path, true);
			if (completions.stream().anyMatch(c -> c.getLabel().equals(expected))) {
				return completions;
			}
			Thread.sleep(1000);
		}
		fail("No '" + expected + "' completion for: " + code);
		return null;
	}

	/**
	 * Completes at the end of the code.
	 *
	 * @param settled Whether to wait for the slower servers' completions.
	 */
	private static List<CompletionItem> complete(final String code,
		final String path, final boolean settled) throws Exception
	{
		final String uri = sync(code, path);
		CompletionList list = server.completion(new CompletionParams(
			new TextDocumentIdentifier(uri), Positions.position(code, code.length())))
			.get(1, TimeUnit.MINUTES).getRight();
		if (settled && list instanceof UpdatingCompletionList) {
			final CompletionList better = ((UpdatingCompletionList) list).update()
				.get(1, TimeUnit.MINUTES);
			if (better != null) list = better;
		}
		return list.getItems();
	}

	/**
	 * Prints how long completions take, once warm: for another keystroke of a
	 * word the server has answered (cached); and for a new word, how long the
	 * editor waits (barely: it answers at once), and how long until the
	 * server's completions arrive as an update.
	 */
	private static void report(final String code, final String path,
		final String expected, final String more) throws Exception
	{
		completeUntil(code, path, expected);
		long t0 = System.nanoTime();
		final int n = complete(code + more, path, true).size();
		final long cached = System.nanoTime() - t0;
		final String other = code.substring(0, code.lastIndexOf('.') + 1) + "s";
		final String uri = sync(other, path);
		t0 = System.nanoTime();
		final CompletionList first = server.completion(new CompletionParams(
			new TextDocumentIdentifier(uri), Positions.position(other, other
				.length()))).get(1, TimeUnit.MINUTES).getRight();
		final long wait = System.nanoTime() - t0;
		final CompletionList update = first instanceof UpdatingCompletionList
			? ((UpdatingCompletionList) first).update().get(1, TimeUnit.MINUTES)
			: first;
		final long arrived = System.nanoTime() - t0;
		System.out.println("[completion timing] same word (cached): " + cached /
			1000000 + " ms, " + n + " completions; new word: editor waits " + wait /
				1000000 + " ms, all servers' " + update.getItems().size() +
			" completions after " + arrived / 1000000 + " ms");
	}
}
