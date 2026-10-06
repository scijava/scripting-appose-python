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

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import net.imglib2.img.Img;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.scijava.Context;
import org.scijava.code.api.CodeCompletionService;
import org.scijava.code.api.Completion;
import org.scijava.code.api.CompletionRequest;
import org.scijava.plugins.scripting.appose.python._internal.ResidentWorkerService;
import org.scijava.plugins.scripting.appose.python.completion.ApposePythonDialect;
import org.scijava.plugins.scripting.appose.python.completion.JediClient;
import org.scijava.script.ScriptLanguage;
import org.scijava.script.ScriptService;

/**
 * Tests {@link ApposePythonCodeCompleter}, with a fake Jedi.
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
		noJedi();
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
	public void testJediResultsMergedFirst() {
		final Completion zeros = Completion.builder("zeros").kind(
			Completion.Kind.METHOD).parameters(Collections.singletonList(
				new Completion.Parameter("shape", "_ShapeLike"))).build();
		final Completion zeta = Completion.of("zeta");
		completer.setJediClient(new JediClient(q -> Arrays.asList(zeros, zeta)));
		final List<Completion> completions = completions(
			"import numpy as np\nnp.zeros(3)\nnp.ze");
		assertEquals("zeros", completions.get(0).insertionText());
		assertEquals(1, completions.get(0).parameters().size());
		assertEquals(Arrays.asList("zeros", "zeta"), completions.stream().map(
			Completion::insertionText).collect(Collectors.toList()));
	}

	@Test
	public void testImportsAreNames() {
		// Jedi reports imshow as a function, once per overload.
		final Completion imshow = Completion.builder("imshow").kind(
			Completion.Kind.METHOD).parameters(Collections.singletonList(
				new Completion.Parameter("data", "Any"))).summary("def imshow")
			.build();
		completer.setJediClient(new JediClient(q -> Arrays.asList(imshow,
			imshow)));
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
	public void testTimeBudgetAndReuse() throws InterruptedException {
		final AtomicInteger calls = new AtomicInteger();
		final CountDownLatch release = new CountDownLatch(1);
		final CountDownLatch done = new CountDownLatch(1);
		completer.setJediClient(new JediClient(q -> {
			calls.incrementAndGet();
			try {
				release.await();
			}
			catch (final InterruptedException exc) {
				Thread.currentThread().interrupt();
			}
			done.countDown();
			return Collections.singletonList(Completion.of("zeros"));
		}));
		// Jedi is slow: the local results come back, in time.
		final String code = "import numpy as np\nnp.zeros(3)\nnp.z";
		final long start = System.nanoTime();
		final List<String> first = complete(code);
		assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(2));
		assertEquals(Collections.singletonList("zeros"), first); // used before
		// Once Jedi answers, later keystrokes of the word reuse its answer.
		release.countDown();
		assertTrue(done.await(5, TimeUnit.SECONDS));
		Thread.sleep(50);
		assertTrue(complete(code + "e").contains("zeros"));
		assertTrue(complete(code + "er").contains("zeros"));
		assertEquals(1, calls.get());
	}

	@Test
	public void testUpdateWhenJediIsSlow() throws Exception {
		final CountDownLatch release = new CountDownLatch(1);
		completer.setJediClient(new JediClient(q -> {
			try {
				release.await();
			}
			catch (final InterruptedException exc) {
				Thread.currentThread().interrupt();
			}
			return Collections.singletonList(Completion.of("zeros"));
		}));
		// At once: what is known locally, and the announcement of more.
		final String code = "import numpy as np\nnp.z";
		final org.scijava.code.api.CompletionResult first = service.complete(
			new CompletionRequest(code, language, null));
		assertTrue(first.completions().isEmpty());
		assertTrue(first.update() != null);
		// Then Jedi's answer, merged.
		release.countDown();
		final org.scijava.code.api.CompletionResult better = first.update().get(5,
			TimeUnit.SECONDS);
		assertEquals("zeros", better.completions().get(0).insertionText());
		assertEquals(first.replaceStart(), better.replaceStart());
	}

	@Test
	public void testLazyDocumentation() {
		final List<String> described = new java.util.ArrayList<>();
		final String[] keys = { null, null };
		completer.setJediClient(new JediClient(new JediClient.Backend() {

			@Override
			public List<Completion> complete(final String key,
				final JediClient.Query query)
			{
				keys[0] = key;
				final Map<String, Object> zeros = new HashMap<>();
				zeros.put("name", "zeros");
				zeros.put("type", "function");
				return ApposePythonCodeCompleter.toCompletions(Collections
					.singletonList(zeros), name -> () -> {
						described.add(name);
						keys[1] = key;
						return "Return a new array of zeros.";
					});
			}

			@Override
			public String describe(final String key, final JediClient.Query query,
				final String name)
			{
				return null;
			}
		}));
		final Completion zeros = completions("import numpy\nnumpy.z").get(0);
		// Not described until asked, then by the same query.
		assertTrue(described.isEmpty());
		assertEquals("Return a new array of zeros.", zeros.description());
		assertEquals(Collections.singletonList("zeros"), described);
		assertEquals(keys[0], keys[1]);
	}

	@Test
	public void testSignaturesOnlyOnceWordBegun() {
		final List<Integer> signatures = new java.util.ArrayList<>();
		completer.setJediClient(new JediClient(q -> {
			signatures.add(q.signatures);
			return Collections.emptyList();
		}));
		complete("import numpy\nnumpy.");
		complete("import numpy\nnumpy.z");
		assertEquals(Arrays.asList(0, 30), signatures);
		// A function without signature is offered by name, not as a call.
		final Map<String, Object> zeros = new HashMap<>();
		zeros.put("name", "zeros");
		zeros.put("type", "function");
		final Completion plain = ApposePythonCodeCompleter.toCompletions(
			Collections.singletonList(zeros)).get(0);
		assertFalse(plain.isCallable());
	}

	@Test
	public void testOverlayInQuery() throws Exception {
		// A script declaring a dependency its environment lacks.
		final List<List<String>> installed = new java.util.ArrayList<>();
		completer.setOverlays(new org.scijava.plugins.scripting.appose.python.completion.DependencyOverlays(
			(python, dependencies, dir) -> {
				installed.add(dependencies);
				return "/overlays/blur";
			}, new java.io.File("overlays"), 0, System::currentTimeMillis));
		final List<JediClient.Query> queries = new java.util.ArrayList<>();
		completer.setJediClient(new JediClient(q -> {
			queries.add(q);
			return Collections.emptyList();
		}));
		final String code = "# /// script\n# dependencies = [\"tifffile\"]\n" +
			"# ///\nimport tifffile\ntifffile.im";
		// The first request notes the dependencies; once they have stayed the
		// same (here, for 0 ms), the next one starts installing the overlay.
		complete(code);
		complete(code);
		for (int i = 0; i < 100 && installed.isEmpty(); i++) Thread.sleep(20);
		Thread.sleep(50);
		complete(code + "r");
		// Once the overlay is ready, Jedi searches it too (anew: a new key).
		assertEquals(Collections.singletonList(Collections.singletonList(
			"tifffile")), installed);
		assertEquals("/overlays/blur", queries.get(queries.size() - 1).overlay);
	}

	@Test
	public void testSignatureHelp() throws Exception {
		final JediClient.Query[] asked = { null };
		final Completion dumps = Completion.builder("dumps").kind(
			Completion.Kind.METHOD).parameters(Arrays.asList(
				new Completion.Parameter("obj", null), new Completion.Parameter(
					"indent", "int"))).build();
		completer.setJediClient(new JediClient(new JediClient.Backend() {

			@Override
			public List<Completion> complete(final String key,
				final JediClient.Query query)
			{
				return Collections.emptyList();
			}

			@Override
			public String describe(final String key, final JediClient.Query query,
				final String name)
			{
				return null;
			}

			@Override
			public org.scijava.code.api.SignatureHelp signatures(final String key,
				final JediClient.Query query)
			{
				asked[0] = query;
				// NB: Jedi cannot always tell the argument (-1).
				return new org.scijava.code.api.SignatureHelp(Collections
					.singletonList(new org.scijava.code.api.SignatureHelp.Signature(
						dumps, org.scijava.code.api.SignatureHelp.Fit.UNKNOWN)), -1, -1);
			}
		}));
		final String code = "import json\njson.dumps(obj, ";
		org.scijava.code.api.SignatureHelp help = completer.signatureHelp(
			new CompletionRequest(code, language, null));
		if (help.update() != null) help = help.update().get(5, TimeUnit.SECONDS);
		assertEquals("dumps", help.signatures().get(0).callable().insertionText());
		// The argument the caret is in, counted where Jedi cannot tell; and the
		// call's opening parenthesis.
		assertEquals(1, help.activeParameter());
		assertEquals(code.indexOf('('), help.callStart());
		// Jedi was asked about the caret (shifted by the preamble).
		assertEquals(3, asked[0].line);
		assertEquals("json.dumps(obj, ".length(), asked[0].column);
		// No help outside calls, or in strings.
		assertTrue(completer.signatureHelp(new CompletionRequest("x = 1",
			language, null)).isEmpty());
		assertTrue(completer.signatureHelp(new CompletionRequest("s = 'f(1, ",
			language, null)).isEmpty());
	}

	@Test
	public void testPrepareWarmsUp() throws Exception {
		final CountDownLatch asked = new CountDownLatch(1);
		completer.setJediClient(new JediClient(q -> {
			asked.countDown();
			return Collections.emptyList();
		}));
		completer.prepare(new CompletionRequest("import numpy\n", language,
			null));
		assertTrue(asked.await(5, TimeUnit.SECONDS));
	}

	@Test
	public void testJediQuery() {
		final JediClient.Query[] query = { null };
		completer.setJediClient(new JediClient(q -> {
			query[0] = q;
			return Collections.emptyList();
		}));
		final String code = "#@ net.imglib2.img.Img image\n#@ double sigma\n" +
			"image.sha";
		complete(code);
		// The script's parameters are typed in a preamble, so Jedi knows them.
		assertTrue(query[0].source.startsWith(
			"import appose.python_worker; import numpy; image: numpy.ndarray; " +
				"sigma: float; task: appose.python_worker.Task\n"));
		// The caret is after the word's first character, shifted by the preamble.
		assertEquals(4, query[0].line);
		assertEquals("image.s".length(), query[0].column);
		// No script environment has been built: Jedi uses its own interpreter.
		assertNull(query[0].python);
	}

	@Test
	public void testScriptWorkersUntouched() {
		noJedi();
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

			final JediClient.Query[] query = { null };
			completer.setJediClient(new JediClient(q -> {
				query[0] = q;
				return Collections.emptyList();
			}));
			final String code = header + "numpy.ze";
			service.complete(new CompletionRequest(code, code.length(), language,
				null, null, script.getPath()));
			// Jedi analyzes against the environment's Python...
			assertEquals(python.toFile().getAbsolutePath(), query[0].python);
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
		assertEquals("numpy.ndarray", DIALECT.runtimeType(
			Img.class));
		assertEquals("float", DIALECT.runtimeType(double.class));
		assertEquals("int", DIALECT.runtimeType(Long.class));
		assertEquals("str", DIALECT.runtimeType(String.class));
		assertEquals("bool", DIALECT.runtimeType(boolean.class));
		assertEquals("list", DIALECT.runtimeType(int[].class));
		assertNull(DIALECT.runtimeType(Context.class));
	}

	@Test
	public void testJediOutputConversion() {
		final Map<String, Object> sig1 = new HashMap<>();
		sig1.put("params", Arrays.asList(Arrays.asList("shape", "_ShapeLike"),
			Arrays.asList("dtype", null)));
		sig1.put("returns", "NDArray[float64]");
		final Map<String, Object> sig2 = new HashMap<>();
		sig2.put("params", Collections.singletonList(Arrays.asList("shape",
			null)));
		sig2.put("returns", null);
		final Map<String, Object> zeros = new LinkedHashMap<>();
		zeros.put("name", "zeros");
		zeros.put("type", "function");
		zeros.put("description", "def zeros");
		zeros.put("docstring", "zeros(shape) -> a < b");
		zeros.put("signatures", Arrays.asList(sig1, sig2));
		final Map<String, Object> shape = new LinkedHashMap<>();
		shape.put("name", "shape");
		shape.put("type", "property");
		shape.put("signatures", Collections.emptyList());
		final List<Completion> out = ApposePythonCodeCompleter.toCompletions(
			Arrays.asList(zeros, shape));
		// One completion per overload.
		assertEquals(3, out.size());
		assertEquals(Completion.Kind.METHOD, out.get(0).kind());
		assertEquals("_ShapeLike", out.get(0).parameters().get(0).type());
		assertEquals("NDArray[float64]", out.get(0).returnType());
		assertEquals(1, out.get(1).parameters().size());
		assertTrue(out.get(0).description().contains("a &lt; b"));
		assertEquals(Completion.Kind.FIELD, out.get(2).kind());
		assertFalse(out.get(2).isCallable());
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

	/** Uses a Jedi that never answers in time. */
	private void noJedi() {
		completer.setJediClient(new JediClient(q -> null));
	}

	private List<Completion> completions(final String code) {
		return service.complete(new CompletionRequest(code, language, null))
			.completions();
	}

	private List<String> complete(final String code) {
		return completions(code).stream().map(Completion::insertionText).collect(
			Collectors.toList());
	}
}
