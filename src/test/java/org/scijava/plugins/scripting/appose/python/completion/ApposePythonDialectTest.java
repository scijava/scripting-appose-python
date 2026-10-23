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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.imglib2.img.Img;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.scijava.Context;
import org.scijava.code.lsp.Environment;
import org.scijava.code.lsp.ScriptDialect;
import org.scijava.code.lsp.ScriptDialect.Header;
import org.scijava.code.lsp.ScriptDialect.Variable;
import org.scijava.plugin.PluginService;
import org.scijava.plugins.scripting.appose.python.ScriptEnvironment;
import org.scijava.plugins.scripting.appose.python._internal.ResidentWorkerService;
import org.scijava.script.ScriptLanguage;
import org.scijava.script.ScriptService;

/**
 * Tests {@link ApposePythonDialect}.
 *
 * @author Gabriel Selzer
 */
public class ApposePythonDialectTest {

	private Context context;
	private ScriptLanguage language;
	private ApposePythonDialect dialect;

	@Before
	public void setUp() {
		context = new Context(ScriptService.class, ResidentWorkerService.class);
		language = context.service(ScriptService.class).getLanguageByName(
			"appose-python");
		dialect = context.service(PluginService.class).createInstancesOfType(
			ScriptDialect.class).stream().filter(
				p -> p instanceof ApposePythonDialect).map(
				p -> (ApposePythonDialect) p)
			.findFirst().get();
	}

	@After
	public void tearDown() {
		context.dispose();
	}

	@Test
	public void testScriptsLanguageAdherence() {
		// Ensure the dialect supports the Appose Python script language
		assertTrue(dialect.supports(language));
	}

	@Test
	public void testParameterType() {
		// Script parameters have the types Appose converts them to
		assertEquals("numpy.ndarray", ApposePythonDialect.parameterType(
			Img.class));
		assertEquals("float", ApposePythonDialect.parameterType(double.class));
		assertEquals("int", ApposePythonDialect.parameterType(Long.class));
		assertEquals("str", ApposePythonDialect.parameterType(String.class));
		assertEquals("bool", ApposePythonDialect.parameterType(boolean.class));
		assertEquals("list", ApposePythonDialect.parameterType(int[].class));
		assertEquals("dict", ApposePythonDialect.parameterType(Map.class));
		// Types Appose cannot convert are not declared
		assertNull(ApposePythonDialect.parameterType(Context.class));
	}

	@Test
	public void testHeader() {
		// SciJava Script parameters should be declared in Python code with the
		// types Appose converts them to, e.g. "image: numpy.ndarray"; and
		// scripts can use Appose's task variable
		final Map<String, Variable> parameters = new LinkedHashMap<>();
		parameters.put("image", Variable.ofType(Img.class));
		parameters.put("sigma", Variable.ofType(double.class));
		parameters.put("context", Variable.ofType(Context.class));
		Header header = dialect.header(parameters);
		assertEquals("import appose.python_worker; import numpy; " +
			"image: numpy.ndarray; sigma: float; " +
			"task: appose.python_worker.Task", header.code());
		// The imports' names are the header's alone
		assertEquals(Arrays.asList("appose", "numpy"), Arrays.asList(header
			.hiddenNames().toArray()));
		// numpy is imported only when a parameter needs it
		header = dialect.header(Collections.singletonMap("sigma", Variable
			.ofType(double.class)));
		assertEquals("import appose.python_worker; sigma: float; " +
			"task: appose.python_worker.Task", header.code());
		assertEquals(Collections.singleton("appose"), header.hiddenNames());
	}

	@Test
	public void testEnvironmentWithoutOne() {
		// No script environment has been built: the server's own Python.
		final Environment env = dialect.environment("import os\nos.pa", Optional
			.empty());
		assertEquals(Optional.empty(), env.interpreter());
		assertTrue(env.paths().isEmpty());
	}

	@Test
	public void testScriptWorkersUntouched() {
		// Finding where a script runs does not create (or replace) a worker for
		// it at once, even as its metadata is edited: only once the metadata
		// has settled (see ApposePythonEnvironmentsTest).
		final String script = "# /// script\n# dependencies = [\"appose\"]\n" +
			"# ///\nimport os\nos.pa";
		dialect.environment(script, Optional.empty());
		dialect.environment(script.replace("appose", "appose, numpy"), Optional
			.empty());
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

			final Environment env = dialect.environment(header + "numpy.ze",
				Optional.of(script));
			// The server analyzes against the environment's Python...
			assertEquals(Optional.of(python.toFile().getAbsolutePath()), env
				.interpreter());
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
}
