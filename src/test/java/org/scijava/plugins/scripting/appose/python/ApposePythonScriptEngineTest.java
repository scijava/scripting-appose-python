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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.StringReader;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import javax.script.ScriptEngine;
import javax.script.ScriptException;

import net.imglib2.appose.NDArrays;
import net.imglib2.appose.ShmImg;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.type.numeric.integer.UnsignedByteType;

import org.apposed.appose.NDArray;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.scijava.Context;
import org.scijava.script.ScriptInfo;
import org.scijava.script.ScriptLanguage;
import org.scijava.script.ScriptModule;
import org.scijava.script.ScriptService;

/**
 * Tests {@link ApposePythonScriptEngine} behavior that does not require a
 * Python environment.
 *
 * @author Curtis Rueden
 */
public class ApposePythonScriptEngineTest {

	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	private Context context;

	@Before
	public void setUp() {
		context = new Context();
	}

	@After
	public void tearDown() {
		context.dispose();
	}

	@Test
	public void testLanguageRegistered() {
		final ScriptService scriptService = context.service(ScriptService.class);
		final ScriptLanguage lang = scriptService.getLanguageByName("appose-python");
		assertTrue(lang instanceof ApposePythonScriptLanguage);
		assertEquals(Collections.singletonList("py"), lang.getExtensions());
		assertTrue(lang.getScriptEngine() instanceof ApposePythonScriptEngine);
	}

	@Test
	public void testMarshalPassesThroughJsonTypes() {
		for (final Object o : new Object[] { true, 5, 2.5, "text",
			Collections.singletonList(1), Collections.singletonMap("k", "v"),
			new int[] { 1, 2 }, new String[] { "a" } })
		{
			assertSame(o, ApposePythonScriptEngine.marshal(o));
		}
		assertSame(null, ApposePythonScriptEngine.marshal(null));
	}

	@Test
	public void testMarshalConvertsToString() {
		assertEquals("c", ApposePythonScriptEngine.marshal('c'));
		assertEquals("SECONDS", ApposePythonScriptEngine.marshal(TimeUnit.SECONDS));
		final File file = new File("relative.txt");
		assertEquals(file.getAbsolutePath(), ApposePythonScriptEngine.marshal(file));
	}

	@Test
	public void testMarshalRejectsOtherObjects() {
		for (final Object o : new Object[] { new Object(), context,
			new Object[] { "a" } })
		{
			assertSame(ApposePythonScriptEngine.UNSUPPORTED,
				ApposePythonScriptEngine.marshal(o));
		}
	}

	@Test
	public void testOwnsCopiedNDArray() {
		final ArrayImg<UnsignedByteType, ?> img = ArrayImgs.unsignedBytes(2, 3);
		try (final NDArray nd = NDArrays.asNDArray(img)) {
			assertTrue(ApposePythonScriptEngine.ownsNDArray(img, nd));
		}
	}

	@Test
	public void testDoesNotOwnWrappedNDArray() {
		final ShmImg<UnsignedByteType> img =
			ShmImg.copyOf(ArrayImgs.unsignedBytes(2, 3));
		try (final NDArray nd = img.ndArray()) {
			assertSame(nd, NDArrays.asNDArray(img));
			assertFalse(ApposePythonScriptEngine.ownsNDArray(img, nd));
			assertFalse(ApposePythonScriptEngine.ownsNDArray(nd, nd));
		}
	}

	@Test
	public void testEnvName() {
		final String name =
			ApposePythonScriptEngine.envName(new File("/a/b/cellcast.toml"));
		assertTrue(name, name.matches("cellcast-[0-9a-f]{8}"));

		// Same file via a different path spelling.
		assertEquals(name,
			ApposePythonScriptEngine.envName(new File("/a/c/../b/cellcast.toml")));

		// Same file name in a different directory.
		assertNotEquals(name,
			ApposePythonScriptEngine.envName(new File("/a/cellcast.toml")));

		assertTrue(ApposePythonScriptEngine.envName(new File("/x/my env.v2.toml"))
			.matches("my_env_v2-[0-9a-f]{8}"));
		assertTrue(ApposePythonScriptEngine.envName(new File("/x/requirements"))
			.matches("requirements-[0-9a-f]{8}"));
	}

	@Test
	public void testResolveEnvFile() {
		final File abs = new File("/some/where/env.toml").getAbsoluteFile();
		assertEquals(abs, ApposePythonScriptEngine.resolveEnvFile(abs.getPath(),
			"/other/script.py"));

		final File scriptDir = new File("/scripts").getAbsoluteFile();
		assertEquals(new File(scriptDir, "env.toml"), ApposePythonScriptEngine
			.resolveEnvFile("env.toml", new File(scriptDir, "s.py").getPath()));

		// Relative script path resolves relative to the working directory.
		assertEquals(new File("sub", "env.toml").getAbsoluteFile(),
			ApposePythonScriptEngine.resolveEnvFile("env.toml", "sub/s.py")
				.getAbsoluteFile());

		assertEquals(new File("env.toml"), ApposePythonScriptEngine
			.resolveEnvFile("env.toml", null));
	}

	@Test
	public void testEvalWithoutModule() {
		final ScriptEngine engine = new ApposePythonScriptEngine(context);
		final ScriptException e =
			assertThrows(ScriptException.class, () -> engine.eval("1 + 2"));
		assertTrue(e.getMessage(), e.getMessage().contains(
			"No Appose environment configured"));
	}

	@Test
	public void testEvalWithoutEnvDirective() throws Exception {
		final String script = "#@ int x\nx + 1\n";
		final ScriptException e =
			assertThrows(ScriptException.class, () -> eval("test.py", script));
		assertTrue(e.getMessage(), e.getMessage().contains(
			"No Appose environment configured"));
	}

	@Test
	public void testEvalWithMissingEnvFile() throws Exception {
		final File scriptFile = new File(tmp.getRoot(), "test.py");
		final String script = "#@script(env=\"missing.toml\")\nprint('hi')\n";
		final ScriptException e = assertThrows(ScriptException.class,
			() -> eval(scriptFile.getPath(), script));
		assertTrue(e.getMessage(), e.getMessage().contains(
			"Appose environment file not found"));
		assertTrue(e.getMessage(), e.getMessage().contains(
			new File(tmp.getRoot(), "missing.toml").getPath()));
	}

	/** Evaluates a script the way {@link ScriptModule#run()} does. */
	private void eval(final String path, final String script) throws Exception {
		final ScriptInfo info =
			new ScriptInfo(context, path, new StringReader(script));
		info.inputs(); // Note: Parses the script, as module preprocessing would.
		final ScriptModule module = info.createModule();
		final ScriptEngine engine = new ApposePythonScriptEngine(context);
		engine.put(ScriptModule.class.getName(), module);
		engine.eval(info.getProcessedScript());
	}
}
