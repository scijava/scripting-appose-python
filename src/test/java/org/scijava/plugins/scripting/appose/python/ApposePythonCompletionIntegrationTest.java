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
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.scijava.Context;
import org.scijava.code.api.CodeCompletionService;
import org.scijava.code.api.Completion;
import org.scijava.code.api.CompletionRequest;
import org.scijava.code.api.CompletionResult;
import org.scijava.code.api.Diagnostic;
import org.scijava.code.api.Hover;
import org.scijava.code.api.SignatureHelp;
import org.scijava.module.ModuleService;
import org.scijava.script.ScriptInfo;
import org.scijava.script.ScriptLanguage;
import org.scijava.script.ScriptModule;
import org.scijava.script.ScriptService;

/**
 * End-to-end tests of {@link ApposePythonCodeCompleter} with a real language
 * server (jedi-language-server).
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
	private static CodeCompletionService service;
	private static File scriptDir;

	@BeforeClass
	public static void setUp() throws URISyntaxException {
		context = new Context();
		language = context.service(ScriptService.class).getLanguageByName(
			"appose-python");
		service = context.service(CodeCompletionService.class);
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
		final List<Completion> completions = completeUntil(
			"import json\njson.du", null, "dumps");
		final Completion dumps = completions.stream().filter(c -> c
			.insertionText().equals("dumps")).findFirst().get();
		assertTrue(dumps.isCallable());
		assertFalse(dumps.parameters().isEmpty());
		assertTrue(dumps.description() != null);
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

		// Now the server analyzes the script against its environment: numpy is known,
		// and so is the type of the image parameter.
		completeUntil(header + "np.ze", script.getPath(), "zeros");
		completeUntil(header + "image.sha", script.getPath(), "shape");
		report(header + "np.random.r", script.getPath(), "randn", "a");
	}

	@Test
	public void testSignatureHelp() throws Exception {
		final String code = "import json\njson.dumps(obj, ";
		final long deadline = System.currentTimeMillis() + BUILD_TIMEOUT;
		SignatureHelp help = SignatureHelp.NONE;
		while (help.isEmpty() && System.currentTimeMillis() < deadline) {
			help = completer().signatureHelp(new CompletionRequest(code, language,
				null));
			if (help.update() != null) {
				final SignatureHelp better = help.update().get(1, TimeUnit.MINUTES);
				if (better != null) help = better;
			}
		}
		assertEquals("dumps", help.signatures().get(0).callable().insertionText());
		assertFalse(help.signatures().get(0).callable().parameters().isEmpty());
		assertEquals(1, help.activeParameter());
		assertEquals(code.indexOf('('), help.callStart());
	}

	@Test
	public void testHover() throws Exception {
		final String code = "import json\njson.dumps(1)";
		final CompletionRequest request = new CompletionRequest(code, code
			.indexOf("dumps") + 2, language, null, null, null);
		final long deadline = System.currentTimeMillis() + BUILD_TIMEOUT;
		Hover hover = Hover.NONE;
		while (hover.isEmpty() && System.currentTimeMillis() < deadline) {
			hover = completer().hover(request);
			if (hover.update() != null) {
				final Hover better = hover.update().get(1, TimeUnit.MINUTES);
				if (better != null) hover = better;
			}
		}
		assertTrue(hover.text(), hover.text().contains("JSON"));
		assertEquals(code.indexOf("dumps"), hover.start());
		assertEquals(code.indexOf("dumps") + "dumps".length(), hover.end());
	}

	@Test
	public void testDiagnostics() throws Exception {
		// A syntax error, on the script's third line.
		final String code = "#@ double sigma\nx = sigma\ny = (1 +\n";
		final long deadline = System.currentTimeMillis() + BUILD_TIMEOUT;
		List<Diagnostic> problems = Collections.emptyList();
		while (problems.isEmpty() && System.currentTimeMillis() < deadline) {
			problems = completer().diagnose(new CompletionRequest(code, language,
				null)).get(2, TimeUnit.MINUTES);
		}
		assertFalse(problems.isEmpty());
		assertTrue(problems.toString(), problems.get(0).start() >= code.indexOf(
			"y = "));
		// Fixed: no more problems.
		assertTrue(completer().diagnose(new CompletionRequest(code.replace("(1 +",
			"(1 + 2)"), language, null)).get(1, TimeUnit.MINUTES).isEmpty());
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
		completer().prepare(new CompletionRequest(code, code.length(), language,
			null, null, script.getPath()));
		completeUntil(code, script.getPath(), "imread");
	}

	// -- Helper methods --

	private static ApposePythonCodeCompleter completer() {
		return (ApposePythonCodeCompleter) service.getCompleterPlugin(language);
	}

	/** Completes until the expected completion appears (environments build). */
	private static List<Completion> completeUntil(final String code,
		final String path, final String expected)
	{
		final long deadline = System.currentTimeMillis() + BUILD_TIMEOUT;
		while (System.currentTimeMillis() < deadline) {
			final List<Completion> completions = complete(code, path);
			if (completions.stream().anyMatch(c -> c.insertionText().equals(
				expected))) return completions;
			try {
				Thread.sleep(1000);
			}
			catch (final InterruptedException exc) {
				throw new RuntimeException(exc);
			}
		}
		fail("No '" + expected + "' completion for: " + code);
		return null;
	}

	private static List<Completion> complete(final String code,
		final String path)
	{
		return service.complete(new CompletionRequest(code, code.length(),
			language, null, null, path)).completions();
	}

	/**
	 * Prints how long completions take, once warm: for another keystroke of a
	 * word the server has answered (cached); and for a new word, how long the editor
	 * waits (barely: it answers at once), and how long until the server's completions
	 * arrive as an update.
	 */
	private static void report(final String code, final String path,
		final String expected, final String more) throws Exception
	{
		completeUntil(code, path, expected);
		long t0 = System.nanoTime();
		final int n = complete(code + more, path).size();
		final long cached = System.nanoTime() - t0;
		final String other = code.substring(0, code.lastIndexOf('.') + 1) + "s";
		t0 = System.nanoTime();
		final CompletionResult result = service.complete(new CompletionRequest(
			other, other.length(), language, null, null, path));
		final long wait = System.nanoTime() - t0;
		final CompletionResult update = result.update() == null ? result : result
			.update().get(1, TimeUnit.MINUTES);
		final long arrived = System.nanoTime() - t0;
		System.out.println("[completion timing] same word (cached): " + cached /
			1000000 + " ms, " + n + " completions; new word: editor waits " + wait /
				1000000 + " ms, the server's " + update.completions().size() +
			" completions after " + arrived / 1000000 + " ms");
	}
}
