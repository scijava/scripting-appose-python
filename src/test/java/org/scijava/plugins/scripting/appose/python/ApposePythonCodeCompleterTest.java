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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import net.imglib2.img.Img;

import org.eclipse.lsp4j.Position;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.scijava.Context;
import org.scijava.code.api.CodeCompletionService;
import org.scijava.code.api.Completion;
import org.scijava.code.api.CompletionRequest;
import org.scijava.code.api.EnvironmentContext;
import org.scijava.code.api.ScriptDocument;
import org.scijava.code.api.SignatureHelp;
import org.scijava.code.lsp.LspClient;
import org.scijava.plugins.scripting.appose.python._internal.ResidentWorkerService;
import org.scijava.plugins.scripting.appose.python.completion.ApposePythonDialect;
import org.scijava.plugins.scripting.appose.python.completion.JediLanguageServerSpec;
import org.scijava.script.ScriptLanguage;
import org.scijava.script.ScriptService;

/**
 * Tests {@link ApposePythonCodeCompleter}, with a fake language server client.
 *
 * @author Gabriel Selzer
 */
public class ApposePythonCodeCompleterTest {

	private static final ApposePythonDialect DIALECT = new ApposePythonDialect();

	private Context context;
	private ScriptLanguage language;
	private CodeCompletionService service;
	private ApposePythonCodeCompleter completer;

	@Before
	public void setUp() {
		context = new Context(ScriptService.class, CodeCompletionService.class,
			ResidentWorkerService.class);
		language = context.service(ScriptService.class).getLanguageByName(
			"appose-python");
		service = context.service(CodeCompletionService.class);
		completer = (ApposePythonCodeCompleter) service.getCompleterPlugin(
			language);
	}

	@After
	public void tearDown() {
		context.dispose();
	}

	@Test
	public void testCompleterIsDiscovered() {
		assertTrue(completer != null);
	}

	@Test
	public void testLocalCompletions() {
		noServer();
		final String header = "#@ net.imglib2.img.Img image\n#@ double sigma\n";
		final List<String> names = complete(header +
			"def blur(img, s):\n    result = img\n    re");
		assertTrue(names.toString(), names.contains("result"));
		assertTrue(names.contains("return"));
		assertTrue(complete(header + "x = si").contains("sigma"));
		assertTrue(complete(header + "pri").contains("print"));
		assertTrue(complete(header + "ta").contains("task"));
		// Nothing on parameter lines, in strings or comments.
		assertTrue(complete("#@ String na").isEmpty());
		assertTrue(complete("s = 'pri").isEmpty());
	}

	@Test
	public void testServerResultsMergedFirst() {
		final Completion zeros = Completion.builder("zeros").kind(
			Completion.Kind.METHOD).parameters(Collections.singletonList(
				new Completion.Parameter("shape", "_ShapeLike"))).build();
		final Completion zeta = Completion.of("zeta");
		fake(q -> Arrays.asList(zeros, zeta));
		final List<Completion> completions = completions(
			"import numpy as np\nnp.zeros(3)\nnp.ze");
		assertEquals("zeros", completions.get(0).insertionText());
		assertEquals(1, completions.get(0).parameters().size());
		assertEquals(Arrays.asList("zeros", "zeta"), completions.stream().map(
			Completion::insertionText).collect(Collectors.toList()));
	}

	@Test
	public void testImportsAreNames() {
		// The server reports imshow as a function, with its signature.
		final Completion imshow = Completion.builder("imshow").kind(
			Completion.Kind.METHOD).parameters(Collections.singletonList(
				new Completion.Parameter("data", "Any"))).summary("def imshow")
			.build();
		fake(q -> Arrays.asList(imshow, imshow));
		// In an import, it is a name to import, not a call: listed once, without
		// a parameter list for the editor to insert.
		final List<Completion> imported = completions("from ndv import i");
		assertEquals(1, imported.size());
		assertEquals("imshow", imported.get(0).insertionText());
		assertFalse(imported.get(0).isCallable());
		assertEquals("def imshow", imported.get(0).summary());
		// Elsewhere, it is still a call.
		assertTrue(completions("import ndv\nndv.i").get(0).isCallable());
	}

	@Test
	public void testSameQuestionWhileTyping() {
		final FakeLsp fake = fake(q -> Collections.singletonList(Completion.of(
			"zeros")));
		// Later keystrokes of a word ask about its first character, so that the
		// client can reuse the answer.
		final String code = "import numpy as np\nnp.z";
		complete(code);
		complete(code + "e");
		complete(code + "er");
		assertEquals(3, fake.asked.size());
		for (final Asked a : fake.asked) assertEquals(code.length(), a.offset);
	}

	@Test
	public void testUpdateWhenServerIsSlow() throws Exception {
		final CountDownLatch release = new CountDownLatch(1);
		fake(q -> {
			try {
				release.await();
			}
			catch (final InterruptedException exc) {
				Thread.currentThread().interrupt();
			}
			return Collections.singletonList(Completion.of("zeros"));
		});
		// At once: what is known locally, and the announcement of more.
		final String code = "import numpy as np\nnp.z";
		final long start = System.nanoTime();
		final org.scijava.code.api.CompletionResult first = service.complete(
			new CompletionRequest(code, language, null));
		assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(2));
		assertTrue(first.completions().isEmpty());
		assertTrue(first.update() != null);
		// Then the server's answer, merged.
		release.countDown();
		final org.scijava.code.api.CompletionResult better = first.update().get(5,
			TimeUnit.SECONDS);
		assertEquals("zeros", better.completions().get(0).insertionText());
		assertEquals(first.replaceStart(), better.replaceStart());
	}

	@Test
	public void testSignaturesOnlyOnceWordBegun() {
		final FakeLsp fake = fake(q -> Collections.emptyList());
		complete("import numpy\nnumpy.");
		complete("import numpy\nnumpy.z");
		assertEquals(Arrays.asList(0, 30), fake.asked.stream().map(a -> a.resolve)
			.collect(Collectors.toList()));
	}

	@Test
	public void testOverlayInQuery() throws Exception {
		// A script declaring a dependency its environment lacks.
		final List<List<String>> installed = new ArrayList<>();
		completer.setOverlays(new org.scijava.plugins.scripting.appose.python.completion.DependencyOverlays(
			(python, dependencies, dir) -> {
				installed.add(dependencies);
				return "/overlays/blur";
			}, new java.io.File("overlays"), 0, System::currentTimeMillis));
		final FakeLsp fake = fake(q -> Collections.emptyList());
		final String code = "# /// script\n# dependencies = [\"tifffile\"]\n" +
			"# ///\nimport tifffile\ntifffile.im";
		// The first request notes the dependencies; once they have stayed the
		// same (here, for 0 ms), the next one starts installing the overlay.
		complete(code);
		complete(code);
		for (int i = 0; i < 100 && installed.isEmpty(); i++) Thread.sleep(20);
		Thread.sleep(50);
		complete(code + "r");
		// Once the overlay is ready, the server searches it too.
		assertEquals(Collections.singletonList(Collections.singletonList(
			"tifffile")), installed);
		assertEquals(Collections.singletonList("/overlays/blur"), last(fake).doc
			.environment().searchPaths());
	}

	@Test
	public void testSignatureHelp() throws Exception {
		final Completion dumps = Completion.builder("dumps").kind(
			Completion.Kind.METHOD).parameters(Arrays.asList(
				new Completion.Parameter("obj", null), new Completion.Parameter(
					"indent", "int"))).build();
		final FakeLsp fake = fake(q -> Collections.emptyList());
		fake.signatures = Collections.singletonList(new SignatureHelp.Signature(
			dumps, SignatureHelp.Fit.UNKNOWN));
		final String code = "import json\njson.dumps(obj, ";
		SignatureHelp help = completer.signatureHelp(new CompletionRequest(code,
			language, null));
		if (help.update() != null) help = help.update().get(5, TimeUnit.SECONDS);
		assertEquals("dumps", help.signatures().get(0).callable().insertionText());
		// The argument the caret is in, counted (for when the server cannot
		// tell); and the call's opening parenthesis.
		assertEquals(1, help.activeParameter());
		assertEquals(code.indexOf('('), help.callStart());
		// The server was asked about the caret (shifted by the preamble).
		final Asked asked = last(fake);
		assertEquals(code.length(), asked.offset);
		assertEquals(new Position(2, "json.dumps(obj, ".length()), position(
			asked));
		// No help outside calls, or in strings.
		assertTrue(completer.signatureHelp(new CompletionRequest("x = 1",
			language, null)).isEmpty());
		assertTrue(completer.signatureHelp(new CompletionRequest("s = 'f(1, ",
			language, null)).isEmpty());
	}

	@Test
	public void testPrepareAndClose() throws Exception {
		final FakeLsp fake = fake(q -> Collections.emptyList());
		completer.prepare(new CompletionRequest("import numpy\n", language,
			null));
		assertEquals(1, fake.asked.size());
		assertEquals("untitled:/script.py", fake.asked.get(0).uri);
		completer.closed(new CompletionRequest("import numpy\n", language, null));
		assertEquals(Collections.singletonList("untitled:/script.py"),
			fake.closed);
	}

	@Test
	public void testServerQuery() {
		final FakeLsp fake = fake(q -> Collections.emptyList());
		final String code = "#@ net.imglib2.img.Img image\n#@ double sigma\n" +
			"image.sha";
		complete(code);
		final Asked asked = last(fake);
		// The script's parameters are typed in a preamble, so the server knows
		// them.
		assertTrue(asked.doc.text().startsWith(
			"import appose.python_worker; import numpy; image: numpy.ndarray; " +
				"sigma: float; task: appose.python_worker.Task\n"));
		// The caret is after the word's first character, shifted by the preamble.
		assertEquals(new Position(3, "image.s".length()), position(asked));
		// No script environment has been built: the server uses its own Python.
		assertNull(asked.doc.environment().interpreter());
	}

	@Test
	public void testServerOptions() {
		final JediLanguageServerSpec spec = new JediLanguageServerSpec(() -> null,
			new java.io.File("workspace"), new java.io.File("server.log"));
		assertEquals("python", spec.languageId());
		assertEquals("{workspace={environmentPath=/env/bin/python, " +
			"extraPaths=[/overlay]}, diagnostics={enable=false}}", String.valueOf(
				spec.initializationOptions(new EnvironmentContext("/env/bin/python",
					Collections.singletonList("/overlay"), null))));
		// Without a script environment: the server's own.
		assertEquals("{workspace={extraPaths=[]}, diagnostics={enable=false}}",
			String.valueOf(spec.initializationOptions(EnvironmentContext.EMPTY)));
		assertTrue(spec.rootUri(EnvironmentContext.EMPTY).startsWith("file:"));
		new java.io.File("workspace").delete();
	}

	@Test
	public void testScriptWorkersUntouched() {
		noServer();
		// Completing in a script declaring an environment must not create (or
		// replace) a worker for it, even as its metadata is edited.
		final String script = "# /// script\n# dependencies = [\"appose\"]\n" +
			"# ///\nimport os\nos.pa";
		complete(script);
		complete(script.replace("appose", "appose, numpy"));
		assertTrue(context.service(ResidentWorkerService.class).workers()
			.isEmpty());
	}

	@Test
	public void testPythonExecutable() throws Exception {
		final java.nio.file.Path tmp = java.nio.file.Files.createTempDirectory(
			"appose-envs");
		try {
			// pixi on Windows: python.exe is in the environment's root, above the
			// bin directory Appose reports (which may not even exist).
			final java.nio.file.Path pixiEnv = tmp.resolve(
				"pixi/.pixi/envs/default");
			java.nio.file.Files.createDirectories(pixiEnv);
			final java.nio.file.Path pixiPython = java.nio.file.Files.createFile(
				pixiEnv.resolve("python.exe"));
			assertEquals(pixiPython.toFile().getAbsolutePath(),
				ApposePythonCodeCompleter.pythonExecutable(env(tmp.resolve("pixi"),
					pixiEnv.resolve("bin"))));
			// uv on Windows: python.exe is in the reported Scripts directory.
			final java.nio.file.Path scripts = tmp.resolve("uv/Scripts");
			java.nio.file.Files.createDirectories(scripts);
			final java.nio.file.Path uvPython = java.nio.file.Files.createFile(
				scripts.resolve("python.exe"));
			assertEquals(uvPython.toFile().getAbsolutePath(),
				ApposePythonCodeCompleter.pythonExecutable(env(tmp.resolve("uv"),
					scripts)));
			// No Python at all.
			assertNull(ApposePythonCodeCompleter.pythonExecutable(env(tmp.resolve(
				"none"), tmp.resolve("none/bin"))));
		}
		finally {
			delete(tmp);
		}
	}

	@Test
	public void testPythonOnDisk() throws Exception {
		final java.nio.file.Path tmp = java.nio.file.Files.createTempDirectory(
			"appose-envs");
		try {
			for (final String layout : new String[] {
				".pixi/envs/default/python.exe", // pixi, Windows
				".pixi/envs/default/bin/python", // pixi, Linux & macOS
				"Scripts/python.exe", // uv, Windows
				"bin/python" }) // uv or conda, Linux & macOS
			{
				final java.nio.file.Path env = tmp.resolve("env" + layout.hashCode());
				final java.nio.file.Path python = env.resolve(layout);
				java.nio.file.Files.createDirectories(python.getParent());
				java.nio.file.Files.createFile(python);
				assertEquals(layout, python.toFile().getAbsolutePath(),
					ApposePythonCodeCompleter.pythonOnDisk(env.toFile()));
			}
			assertNull(ApposePythonCodeCompleter.pythonOnDisk(tmp.resolve("missing")
				.toFile()));
		}
		finally {
			delete(tmp);
		}
	}

	@Test
	public void testEnvironmentFromEarlierSession() throws Exception {
		// A script whose environment was built in an earlier session: it is on
		// disk, but no worker for it exists in this one.
		final java.nio.file.Path tmp = java.nio.file.Files.createTempDirectory(
			"appose-envs");
		final String old = System.getProperty("appose.envs-dir");
		System.setProperty("appose.envs-dir", tmp.toString());
		try {
			final java.io.File script = tmp.resolve("blur.py").toFile();
			final String header = "# /// script\n# dependencies = [\"numpy\"]\n" +
				"# ///\nimport numpy\n";
			final String envName = ScriptEnvironment.of(header, script.getPath(),
				null, null).name;
			final java.nio.file.Path python = tmp.resolve(envName).resolve(
				".pixi/envs/default/python.exe");
			java.nio.file.Files.createDirectories(python.getParent());
			java.nio.file.Files.createFile(python);

			final FakeLsp fake = fake(q -> Collections.emptyList());
			final String code = header + "numpy.ze";
			service.complete(new CompletionRequest(code, code.length(), language,
				null, null, script.getPath()));
			// The server analyzes against the environment's Python...
			final Asked asked = last(fake);
			assertEquals(python.toFile().getAbsolutePath(), asked.doc.environment()
				.interpreter());
			// ...of the saved script, by its file...
			assertEquals(script.toURI().toString(), asked.uri);
			// ...without anything built or started.
			assertTrue(context.service(ResidentWorkerService.class).workers()
				.isEmpty());
		}
		finally {
			if (old == null) System.clearProperty("appose.envs-dir");
			else System.setProperty("appose.envs-dir", old);
			delete(tmp);
		}
	}

	@Test
	public void testPythonTypes() {
		assertEquals("numpy.ndarray", DIALECT.runtimeType(Img.class));
		assertEquals("float", DIALECT.runtimeType(double.class));
		assertEquals("int", DIALECT.runtimeType(Long.class));
		assertEquals("str", DIALECT.runtimeType(String.class));
		assertEquals("bool", DIALECT.runtimeType(boolean.class));
		assertEquals("list", DIALECT.runtimeType(int[].class));
		assertNull(DIALECT.runtimeType(Context.class));
	}

	// -- Helper methods --

	private static void delete(final java.nio.file.Path dir) throws Exception {
		try (java.util.stream.Stream<java.nio.file.Path> paths =
			java.nio.file.Files.walk(dir))
		{
			paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p
				.toFile().delete());
		}
	}

	/** A built environment with the given base and bin directory. */
	private static org.apposed.appose.Environment env(
		final java.nio.file.Path base, final java.nio.file.Path bin)
	{
		return new org.apposed.appose.Environment() {

			@Override
			public String base() {
				return base.toString();
			}

			@Override
			public List<String> binPaths() {
				return Collections.singletonList(bin.toString());
			}

			@Override
			public List<String> launchArgs() {
				return Collections.emptyList();
			}

			@Override
			public org.apposed.appose.Builder<?> builder() {
				return null;
			}
		};
	}

	/** Uses a server that never answers. */
	private void noServer() {
		completer.setLspClient(new FakeLsp(null));
	}

	/** Uses a server answering via {@code answers}, asynchronously. */
	private FakeLsp fake(final Function<Asked, List<Completion>> answers) {
		final FakeLsp fake = new FakeLsp(answers);
		completer.setLspClient(fake);
		return fake;
	}

	private static Asked last(final FakeLsp fake) {
		return fake.asked.get(fake.asked.size() - 1);
	}

	/** Where in the server's document a question was asked. */
	private static Position position(final Asked asked) {
		final String text = asked.doc.text();
		final int at = asked.doc.map().toTransformed(asked.offset);
		final int lineStart = text.lastIndexOf('\n', at - 1) + 1;
		int line = 0;
		for (int i = 0; i < lineStart; i++) {
			if (text.charAt(i) == '\n') line++;
		}
		return new Position(line, at - lineStart);
	}

	private List<Completion> completions(final String code) {
		return service.complete(new CompletionRequest(code, language, null))
			.completions();
	}

	private List<String> complete(final String code) {
		return completions(code).stream().map(Completion::insertionText).collect(
			Collectors.toList());
	}

	/** A question asked of the language server. */
	private static final class Asked {

		private final ScriptDocument doc;
		private final String uri;
		private final int offset, resolve;

		private Asked(final ScriptDocument doc, final String uri,
			final int offset, final int resolve)
		{
			this.doc = doc;
			this.uri = uri;
			this.offset = offset;
			this.resolve = resolve;
		}
	}

	/** A language server client answering from a function. */
	private static final class FakeLsp extends LspClient {

		private final Function<Asked, List<Completion>> answers;
		private final List<Asked> asked = new java.util.concurrent.CopyOnWriteArrayList<>();
		private final List<String> closed = new ArrayList<>();
		private List<SignatureHelp.Signature> signatures = Collections.emptyList();

		private FakeLsp(final Function<Asked, List<Completion>> answers) {
			super(null);
			this.answers = answers;
		}

		@Override
		public CompletableFuture<List<Completion>> complete(
			final ScriptDocument doc, final String uri, final int offset,
			final int resolve)
		{
			final Asked q = new Asked(doc, uri, offset, resolve);
			asked.add(q);
			if (answers == null) return new CompletableFuture<>();
			return CompletableFuture.supplyAsync(() -> answers.apply(q));
		}

		@Override
		public CompletableFuture<SignatureHelp> signatureHelp(
			final ScriptDocument doc, final String uri, final int offset,
			final int callStart, final int active)
		{
			asked.add(new Asked(doc, uri, offset, 0));
			return CompletableFuture.completedFuture(new SignatureHelp(signatures,
				active, callStart));
		}

		@Override
		public void prepare(final ScriptDocument doc, final String uri) {
			asked.add(new Asked(doc, uri, doc.original().length(), 0));
		}

		@Override
		public void close(final String uri) {
			closed.add(uri);
		}
	}
}
