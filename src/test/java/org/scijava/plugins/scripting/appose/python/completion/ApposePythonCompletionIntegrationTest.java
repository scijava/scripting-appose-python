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
import org.eclipse.lsp4j.Diagnostic;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.scijava.Context;
import org.scijava.code.lsp.LanguageServerService;
import org.scijava.code.lsp.ScriptSession;
import org.scijava.module.ModuleService;
import org.scijava.script.ScriptInfo;
import org.scijava.script.ScriptLanguage;
import org.scijava.script.ScriptModule;
import org.scijava.script.ScriptService;

/**
 * End-to-end tests of completing Python scripts run via Appose (see
 * {@link ApposePythonDialect}), with a real jedi-language-server (from
 * scijava-code-lsp-jedi, whose own tests test it on plain Python), through the
 * {@link LanguageServerService}, as editors use it: what is particular to
 * these scripts.
 * <p>
 * The first completion builds jedi-language-server's environment, a script
 * run the small uv environment of {@code appose-test/requirements.txt}, and a
 * new dependency installs an overlay; all require network access.
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
	private static LanguageServerService servers;
	private static File scriptDir;

	@BeforeClass
	public static void setUp() throws URISyntaxException {
		context = new Context();
		language = context.service(ScriptService.class).getLanguageByName(
			"appose-python");
		servers = context.service(LanguageServerService.class);
		scriptDir = new File(ApposePythonCompletionIntegrationTest.class
			.getResource("/appose-test/requirements.txt").toURI()).getParentFile();
	}

	@AfterClass
	public static void tearDown() {
		sessions.values().forEach(ScriptSession::close);
		if (context != null) context.dispose();
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
	}

	@Test
	public void testDiagnostics() throws Exception {
		// A syntax error, on the script's third line: after its parameter
		// lines.
		final String code = "#@ double sigma\nx = sigma\ny = (1 +\n";
		final long deadline = System.currentTimeMillis() + BUILD_TIMEOUT;
		List<Diagnostic> problems = Collections.emptyList();
		while (problems.isEmpty() && System.currentTimeMillis() < deadline) {
			problems = problems(code);
		}
		assertFalse(problems.isEmpty());
		assertTrue(problems.toString(), problems.get(0).getRange().getStart()
			.getLine() >= 2);
		// Fixed: no more problems.
		assertTrue(problems(code.replace("(1 +", "(1 + 2)")).isEmpty());
	}

	@Test
	public void testParameterBeforeEnvironment() throws Exception {
		// An image parameter is a NumPy array: known, even though the script's
		// environment is not built (here, it is not even declared).
		completeUntil("#@ net.imglib2.img.Img image\nx = 1\nimage.", null,
			"shape");
	}

	@Test
	public void testNewDependency() throws Exception {
		// A script declaring a dependency that is installed nowhere: installed
		// for completion only (in an overlay), once the dependencies settle.
		final String header = "# /// script\n# dependencies = [\"tifffile\"]\n" +
			"# ///\nimport tifffile\n";
		completeUntil(header + "tifffile.imr", null, "imread");
	}

	// -- Helper methods --

	/** The scripts' sessions, by path ("" for an untitled one). */
	private static final Map<String, ScriptSession> sessions = new HashMap<>();

	/** The session about a script (null path: untitled). */
	private static ScriptSession session(final String path) {
		return sessions.computeIfAbsent(path == null ? "" : path,
			p -> new ScriptSession(servers, language).setFile(() -> path == null
				? null : new File(path)));
	}

	/** The problems found in a script, in a session of its own. */
	private static List<Diagnostic> problems(final String code) {
		try (ScriptSession session = new ScriptSession(servers, language)) {
			return session.update(code).diagnostics(120_000);
		}
	}

	/** Completes until the expected completion appears (environments build). */
	private static List<CompletionItem> completeUntil(final String code,
		final String path, final String expected) throws Exception
	{
		final long deadline = System.currentTimeMillis() + BUILD_TIMEOUT;
		while (System.currentTimeMillis() < deadline) {
			final List<CompletionItem> completions = complete(code, path);
			if (completions.stream().anyMatch(c -> c.getLabel().equals(expected))) {
				return completions;
			}
			Thread.sleep(1000);
		}
		fail("No '" + expected + "' completion for: " + code);
		return null;
	}

	/** Completes at the end of the code: all servers' completions. */
	private static List<CompletionItem> complete(final String code,
		final String path) throws Exception
	{
		return session(path).completion(code, code.length()).get(1,
			TimeUnit.MINUTES).getItems();
	}
}
