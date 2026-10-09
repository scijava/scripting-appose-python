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

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import net.imglib2.img.Img;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.TextDocumentItem;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.scijava.Context;
import org.scijava.code.lsp.LanguageServerService;
import org.scijava.code.lsp.Environment;
import org.scijava.code.lsp.LanguageServerPlugin;
import org.scijava.code.lsp.ScriptSession;
import org.scijava.code.lsp.ScriptDocument;
import org.scijava.plugin.PluginService;
import org.scijava.plugins.scripting.appose.python._internal.ResidentWorkerService;
import org.scijava.plugins.scripting.appose.python.completion.ApposePythonDialect;
import org.scijava.plugins.scripting.appose.python.completion.DependencyOverlays;
import org.scijava.plugins.scripting.appose.python.completion.JediLanguageServerSpec;
import org.scijava.script.ScriptLanguage;
import org.scijava.script.ScriptService;

/**
 * Tests the language servers of Python scripts run via Appose, without
 * starting any server process: the syntax-only server
 * ({@link PythonSyntaxLanguageServer}), and what {@link ApposePythonServerPlugin}
 * tells jedi-language-server (the script as plain Python, and where it runs).
 *
 * @author Gabriel Selzer
 */
public class ApposePythonServersTest {

	private static final ApposePythonDialect DIALECT = new ApposePythonDialect();

	private Context context;
	private ScriptLanguage language;
	private ApposePythonServerPlugin plugin;
	private ApposePythonEnvironments environments;

	@Before
	public void setUp() {
		context = new Context(ScriptService.class, LanguageServerService.class,
			ResidentWorkerService.class, LanguageServerService.class);
		language = context.service(ScriptService.class).getLanguageByName(
			"appose-python");
		plugin = context.service(PluginService.class).createInstancesOfType(
			LanguageServerPlugin.class).stream().filter(
				p -> p instanceof ApposePythonServerPlugin).map(
				p -> (ApposePythonServerPlugin) p)
			.findFirst().get();
		environments = ApposePythonEnvironments.of(context.service(
			ResidentWorkerService.class));
	}

	@After
	public void tearDown() {
		context.dispose();
	}

	@Test
	public void testServersAreOffered() {
		assertTrue(context.service(LanguageServerService.class).supports(
			language));
		// NB: Here, appose-python offers the Python server itself.
		assertTrue(plugin.jediOffered());
	}

	@Test
	public void testLocalCompletions() {
		final String header = "#@ net.imglib2.img.Img image\n#@ double sigma\n";
		final List<String> names = complete(header +
			"def blur(img, s):\n    result = img\n    re");
		assertTrue(names.toString(), names.contains("result"));
		assertTrue(names.contains("return"));
		assertTrue(complete(header + "x = si").contains("sigma"));
		assertTrue(complete(header + "pri").contains("print"));
		assertTrue(complete(header + "ta").contains("task"));
		// Declared with the types they have when the script runs.
		assertEquals("float", detail(header + "x = si", "sigma"));
		assertEquals("numpy.ndarray", detail(header + "x = ima", "image"));
		assertEquals("appose.python_worker.Task", detail(header + "ta", "task"));
		// Modules imported only to declare those: not completed.
		assertFalse(complete(header + "nu").contains("numpy"));
		assertFalse(complete(header + "ap").contains("appose"));
		assertTrue(complete(header + "import numpy\nnu").contains("numpy"));
		// Nothing on parameter lines, in strings or comments.
		assertTrue(complete("#@ String na").isEmpty());
		assertTrue(complete("s = 'pri").isEmpty());
	}

	@Test
	public void testScriptAsPython() {
		final String code = "#@ net.imglib2.img.Img image\n#@ double sigma\n" +
			"image.sha";
		// The script's parameters are typed in a preamble, so the servers know
		// them.
		assertTrue(document(code).text().startsWith(
			"import appose.python_worker; import numpy; image: numpy.ndarray; " +
				"sigma: float; task: appose.python_worker.Task\n"));
	}

	@Test
	public void testEnvironmentWithoutOne() {
		// No script environment has been built: the server's own Python.
		final Environment env = plugin.environment(new TextDocumentItem(
			"untitled:/a.py", "appose-python", 2, "import os\nos.pa"));
		assertNull(env.interpreter());
		assertTrue(env.paths().isEmpty());
	}

	@Test
	public void testOverlay() throws Exception {
		// A script declaring a dependency its environment lacks.
		final List<List<String>> installed = new ArrayList<>();
		environments.setOverlays(new DependencyOverlays((python, dependencies,
			dir) -> {
			installed.add(dependencies);
			return "/overlays/blur";
		}, new File("overlays"), 0, System::currentTimeMillis));
		final String code = "# /// script\n# dependencies = [\"tifffile\"]\n" +
			"# ///\nimport tifffile\ntifffile.im";
		final TextDocumentItem doc = new TextDocumentItem("untitled:/a.py",
			"appose-python", 1, code);
		// On opening the script, its dependencies are settled: the overlay is
		// installed; once ready, the server searches it.
		plugin.environment(doc);
		for (int i = 0; i < 100 && installed.isEmpty(); i++) Thread.sleep(20);
		Thread.sleep(50);
		assertEquals(Collections.singletonList(Collections.singletonList(
			"tifffile")), installed);
		assertEquals(Collections.singletonList("/overlays/blur"), plugin.environment(
			doc).paths());
	}

	@Test
	public void testScriptWorkersUntouched() {
		// Finding where a script runs must not create (or replace) a worker for
		// it, even as its metadata is edited.
		final String script = "# /// script\n# dependencies = [\"appose\"]\n" +
			"# ///\nimport os\nos.pa";
		plugin.environment(new TextDocumentItem("untitled:/a.py", "appose-python", 2,
			script));
		plugin.environment(new TextDocumentItem("untitled:/a.py", "appose-python", 3,
			script.replace("appose", "appose, numpy")));
		assertTrue(context.service(ResidentWorkerService.class).workers()
			.isEmpty());
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
			final File script = tmp.resolve("blur.py").toFile();
			final String header = "# /// script\n# dependencies = [\"numpy\"]\n" +
				"# ///\nimport numpy\n";
			final String envName = ScriptEnvironment.of(header, script.getPath(),
				null, null).name;
			final java.nio.file.Path python = tmp.resolve(envName).resolve(
				".pixi/envs/default/python.exe");
			java.nio.file.Files.createDirectories(python.getParent());
			java.nio.file.Files.createFile(python);

			final Environment env = plugin.environment(new TextDocumentItem(script
				.toURI().toString(), "appose-python", 2, header + "numpy.ze"));
			// The server analyzes against the environment's Python...
			assertEquals(python.toFile().getAbsolutePath(), env.interpreter());
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
				ApposePythonEnvironments.pythonExecutable(env(tmp.resolve("pixi"),
					pixiEnv.resolve("bin"))));
			// uv on Windows: python.exe is in the reported Scripts directory.
			final java.nio.file.Path scripts = tmp.resolve("uv/Scripts");
			java.nio.file.Files.createDirectories(scripts);
			final java.nio.file.Path uvPython = java.nio.file.Files.createFile(
				scripts.resolve("python.exe"));
			assertEquals(uvPython.toFile().getAbsolutePath(),
				ApposePythonEnvironments.pythonExecutable(env(tmp.resolve("uv"),
					scripts)));
			// No Python at all.
			assertNull(ApposePythonEnvironments.pythonExecutable(env(tmp.resolve(
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
					ApposePythonEnvironments.pythonOnDisk(env.toFile()));
			}
			assertNull(ApposePythonEnvironments.pythonOnDisk(tmp.resolve("missing")
				.toFile()));
		}
		finally {
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

	@Test
	public void testServerOptions() {
		final JediLanguageServerSpec spec = new JediLanguageServerSpec(() -> null,
			new File("workspace"), new File("server.log"));
		assertEquals("python", spec.languageId());
		final String diagnostics = "diagnostics={enable=true, didOpen=true, " +
			"didChange=true, didSave=false}";
		assertEquals("{workspace={environmentPath=/env/bin/python, " +
			"extraPaths=[/overlay]}, " + diagnostics + "}", String.valueOf(spec
				.initializationOptions(new Environment(
					"/env/bin/python", Collections.singletonList("/overlay"), null))));
		// Without a script environment: the server's own.
		assertEquals("{workspace={extraPaths=[]}, " + diagnostics + "}", String
			.valueOf(spec.initializationOptions(
				Environment.NONE)));
		assertTrue(spec.rootUri(Environment.NONE)
			.startsWith("file:"));
		new File("workspace").delete();
	}

	// -- Helper methods --

	/** The script as plain Python, as the plugin's servers see it. */
	private ScriptDocument document(final String code) {
		return ScriptDocument.of(code, context.service(LanguageServerService.class)
			.scriptParameters(code), plugin.dialect());
	}

	/** Completes with the syntax-only server. */
	private List<String> complete(final String code) {
		return items(code).stream().map(CompletionItem::getLabel).collect(
			Collectors.toList());
	}

	/** The detail of a completion with the syntax-only server, or null. */
	private String detail(final String code, final String label) {
		return items(code).stream().filter(i -> i.getLabel().equals(label))
			.findFirst().map(CompletionItem::getDetail).orElse(null);
	}

	private List<CompletionItem> items(final String code) {
		try (ScriptSession session = ScriptSession.owning(
			new PythonSyntaxLanguageServer(), language, this::document))
		{
			return session.completion(code, code.length()).get(10,
				TimeUnit.SECONDS).getItems();
		}
		catch (final Exception exc) {
			throw new RuntimeException(exc);
		}
	}

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
}
