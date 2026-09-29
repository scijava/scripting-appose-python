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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import net.imglib2.appose.ShmImg;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.util.Intervals;
import net.imglib2.view.Views;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.scijava.Context;
import org.scijava.event.EventHandler;
import org.scijava.event.EventService;
import org.scijava.log.LogLevel;
import org.scijava.log.LogMessage;
import org.scijava.module.ModuleService;
import org.scijava.plugins.scripting.appose.python._internal.SciJavaTasks;
import org.scijava.script.ScriptInfo;
import org.scijava.script.ScriptModule;
import org.scijava.task.Task;
import org.scijava.task.event.TaskEvent;

/**
 * End-to-end tests of {@link ApposePythonScriptEngine}, running real Python
 * scripts through the SciJava module framework.
 * <p>
 * The first run builds a small uv environment (see
 * {@code appose-test/requirements.txt}), which requires network access.
 * </p>
 *
 * @author Curtis Rueden
 */
public class ApposePythonIntegrationTest {

	private static final String HEADER = "#!appose-python\n" +
		"#@script(env=\"requirements.txt\", scheme=\"requirements.txt\")\n";

	private static Context context;
	private static File scriptDir;
	private static TaskRecorder tasks;

	private StringWriter out;
	private StringWriter err;

	@BeforeClass
	public static void setUp() throws URISyntaxException {
		context = new Context();
		tasks = new TaskRecorder();
		context.service(EventService.class).subscribe(tasks);
		scriptDir = new File(ApposePythonIntegrationTest.class.getResource(
			"/appose-test/requirements.txt").toURI()).getParentFile();
	}

	@AfterClass
	public static void tearDown() {
		if (context != null) context.dispose();
	}

	@Test
	public void testScalars() throws Exception {
		final Map<String, Object> inputs = new HashMap<>();
		inputs.put("a", 3);
		inputs.put("b", 0.5);
		inputs.put("name", "Appose");
		final ScriptModule module = run("scalars", HEADER + //
			"#@ int a\n" + //
			"#@ double b\n" + //
			"#@ String name\n" + //
			"#@output String greeting\n" + //
			"#@output double total\n" + //
			"greeting = f'Hello, {name}!'\n" + //
			"total = a + b\n", inputs);
		assertEquals("", err.toString());
		assertEquals("Hello, Appose!", module.getOutput("greeting"));
		assertEquals(3.5, module.getOutput("total"));
	}

	@Test
	public void testImageRoundTrip() throws Exception {
		final byte[] data = { 1, 2, 3, 4, 5, 6 };
		final Map<String, Object> inputs = new HashMap<>();
		inputs.put("image", ArrayImgs.unsignedBytes(data, 3, 2));
		final ScriptModule module = run("images", HEADER + //
			"#@ Img image\n" + //
			"#@output Img doubled\n" + //
			"#@output Object shape\n" + //
			"shape = list(image.shape)\n" + //
			"doubled = image * 2\n", inputs);
		assertEquals("", err.toString());

		// NumPy sees the image in C order: (rows, columns).
		assertEquals(2, ((Number) ((List<?>) module.getOutput("shape")).get(0)).intValue());
		assertEquals(3, ((Number) ((List<?>) module.getOutput("shape")).get(1)).intValue());

		@SuppressWarnings("unchecked")
		final Img<UnsignedByteType> doubled =
			(Img<UnsignedByteType>) module.getOutput("doubled");
		assertArrayEquals(new long[] { 3, 2 }, Intervals.dimensionsAsLongArray(doubled));
		int i = 0;
		for (final UnsignedByteType t : Views.flatIterable(doubled)) {
			assertEquals(2 * data[i++], t.get());
		}
	}

	@Test
	public void testShmImgInputSurvives() throws Exception {
		final ShmImg<UnsignedByteType> image =
			ShmImg.copyOf(ArrayImgs.unsignedBytes(new byte[] { 7, 8 }, 2));
		final Map<String, Object> inputs = new HashMap<>();
		inputs.put("image", image);
		final ScriptModule module = run("shm", HEADER + //
			"#@ Img image\n" + //
			"#@output int total\n" + //
			"total = int(image.sum())\n", inputs);
		assertEquals("", err.toString());
		assertEquals(15, module.getOutput("total"));
		// The engine must not close shared memory it does not own.
		assertEquals(7, image.firstElement().get());
	}

	@Test
	public void testPrintAndStderr() throws Exception {
		run("print", HEADER + //
			"import sys\n" + //
			"print('to stdout')\n" + //
			"print('to stderr', file=sys.stderr)\n", new HashMap<>());
		assertTrue(out.toString(), out.toString().contains("to stdout"));
		assertTrue(err.toString(), err.toString().contains("to stderr"));

		// The output also goes to the run's task log.
		final List<String> lines = tasks.log("Running print.py", LogLevel.INFO);
		assertTrue(lines.toString(), lines.contains("to stdout"));
		assertTrue(lines.toString(), lines.contains("to stderr"));
	}

	@Test
	public void testErrorReportsScriptLine() throws Exception {
		final ScriptModule module = run("failing", HEADER + //
			"#@output String x\n" + //
			"x = 'set'\n" + //
			"raise ValueError('boom')\n", new HashMap<>());
		final String error = err.toString();
		assertTrue(error, error.contains("ValueError: boom"));
		assertTrue(error, error.contains(new File(scriptDir, "failing.py").getPath()));
		assertTrue(error, error.contains("line 5"));
		assertNull(module.getOutput("x"));

		final List<String> errors = tasks.log("Running failing.py", LogLevel.ERROR);
		assertEquals(1, errors.size());
		assertTrue(errors.get(0), errors.get(0).contains("ValueError: boom"));
	}

	@Test
	public void testInlineEnvironment() throws Exception {
		final ScriptModule module = run("inline", "#!appose-python\n" + //
			"# /// script\n" + //
			"# requires-python = \">=3.12,<3.13\"\n" + //
			"# dependencies = [\"appose==0.12.0\"]\n" + //
			"#\n" + //
			"# [tool.pixi.dependencies]\n" + //
			"# numpy = \"*\"\n" + //
			"# ///\n" + //
			"#@output String python\n" + //
			"#@output String numpy_version\n" + //
			"import sys, numpy\n" + //
			"python = '%d.%d' % sys.version_info[:2]\n" + //
			"numpy_version = numpy.__version__\n", new HashMap<>());
		assertEquals("", err.toString());
		assertEquals("3.12", module.getOutput("python"));
		// Note: numpy comes only from the conda dependencies in tool.pixi.
		assertNotNull(module.getOutput("numpy_version"));
	}

	@Test
	public void testReturnValue() throws Exception {
		final Map<String, Object> inputs = new HashMap<>();
		inputs.put("a", 21);
		final ScriptModule module = run("result", HEADER + //
			"#@ int a\n" + //
			"a * 2\n", inputs);
		assertEquals("", err.toString());
		assertEquals(42, ((Number) module.getReturnValue()).intValue());
	}

	@Test
	public void testUndefinedOutput() throws Exception {
		final ScriptModule module = run("undefined", HEADER + //
			"#@output String missing\n" + //
			"#@output String present\n" + //
			"present = 'here'\n", new HashMap<>());
		assertEquals("", err.toString());
		assertNull(module.getOutput("missing"));
		assertEquals("here", module.getOutput("present"));
	}

	@Test
	public void testFileInput() throws Exception {
		final File file = new File("some-file.txt");
		final Map<String, Object> inputs = new HashMap<>();
		inputs.put("f", file);
		final ScriptModule module = run("files", HEADER + //
			"#@ File f\n" + //
			"#@output String path\n" + //
			"path = f\n", inputs);
		assertEquals("", err.toString());
		assertEquals(file.getAbsolutePath(), module.getOutput("path"));
	}

	@Test
	public void testWorkerIsReused() throws Exception {
		final int first = pid();
		assertEquals(first, pid());
	}

	@Test
	public void testFreshNamespacePerRun() throws Exception {
		run("leave", HEADER + "leftover = 1\n", new HashMap<>());
		final ScriptModule module = run("check", HEADER + //
			"#@output boolean seen\n" + //
			"seen = 'leftover' in globals()\n", new HashMap<>());
		assertEquals("", err.toString());
		assertEquals(false, module.getOutput("seen"));
	}

	@Test
	public void testExportPersistsAcrossRuns() throws Exception {
		run("export", HEADER + "task.export(cached=41)\n", new HashMap<>());
		final ScriptModule module = run("use", HEADER + //
			"#@output int value\n" + //
			"value = cached + 1\n", new HashMap<>());
		assertEquals("", err.toString());
		assertEquals(42, module.getOutput("value"));
	}

	@Test
	public void testRunIsSciJavaTask() throws Exception {
		run("tracked", HEADER + //
			"task.update('halfway', current=1, maximum=2)\n", new HashMap<>());
		final Task task = tasks.find("Running tracked.py");
		assertNotNull(task);
		assertTrue(task.isDone());
		assertEquals(2, task.getProgressMaximum());
		assertEquals(Collections.singletonList("halfway"), tasks.log(
			"Running tracked.py", LogLevel.INFO));
	}

	@Test
	public void testCancelCooperativeScript() throws Exception {
		final int before = pid();
		final Future<?> future = runAsync("polite", HEADER + //
			"import time\n" + //
			"while not task.cancel_requested:\n" + //
			"    time.sleep(0.05)\n");
		tasks.await("Running polite.py").cancel("test");
		future.get(10, TimeUnit.SECONDS);
		// The script stopped by itself, so its worker was left alive.
		assertEquals(before, pid());
	}

	@Test
	public void testCancelUncooperativeScript() throws Exception {
		final int before = pid();
		final Future<?> future = runAsync("stubborn", HEADER + //
			"import time\n" + //
			"while True:\n" + //
			"    time.sleep(0.05)\n");
		tasks.await("Running stubborn.py").cancel("test");
		future.get(SciJavaTasks.CANCEL_GRACE_MILLIS + 10000, TimeUnit.MILLISECONDS);
		assertTrue(err.toString(), err.toString().contains("canceled"));
		// The worker had to be stopped; the next run gets a new one.
		assertNotEquals(before, pid());
	}

	private int pid() throws Exception {
		final ScriptModule module = run("pid", HEADER + //
			"#@output int pid\n" + //
			"import os\n" + //
			"pid = os.getpid()\n", new HashMap<>());
		return (Integer) module.getOutput("pid");
	}

	private ScriptModule run(final String name, final String script,
		final Map<String, Object> inputs) throws Exception
	{
		final ModuleService moduleService = context.service(ModuleService.class);
		final ScriptModule module = module(name, script);
		moduleService.run(module, true, inputs).get();
		return module;
	}

	private Future<?> runAsync(final String name, final String script)
		throws Exception
	{
		final ModuleService moduleService = context.service(ModuleService.class);
		return moduleService.run(module(name, script), true, new HashMap<>());
	}

	private ScriptModule module(final String name, final String script)
		throws Exception
	{
		final File scriptFile = writeScript(name, script);
		final ScriptInfo info = new ScriptInfo(context, scriptFile);
		final ModuleService moduleService = context.service(ModuleService.class);
		final ScriptModule module = (ScriptModule) moduleService.createModule(info);
		out = new StringWriter();
		err = new StringWriter();
		module.setOutputWriter(out);
		module.setErrorWriter(err);
		return module;
	}

	/** Records the SciJava tasks that have been started. */
	public static class TaskRecorder {

		private final Map<String, Task> tasks = new ConcurrentHashMap<>();
		private final Map<Task, List<LogMessage>> logs = new ConcurrentHashMap<>();

		@EventHandler
		public void onEvent(final TaskEvent event) {
			final Task task = event.getTask();
			tasks.put(task.getName(), task);
			// Note: A task's first event arrives before it produces any output.
			logs.computeIfAbsent(task, t -> {
				final List<LogMessage> messages = new CopyOnWriteArrayList<>();
				t.log().addLogListener(messages::add);
				return messages;
			});
		}

		public Task find(final String name) {
			return tasks.get(name);
		}

		/** Gets the lines logged to the named task at the given level. */
		public List<String> log(final String name, final int level) {
			final List<String> lines = new ArrayList<>();
			for (final LogMessage message : logs.get(find(name))) {
				if (message.level() == level) lines.add(message.text());
			}
			return lines;
		}

		public Task await(final String name) throws InterruptedException {
			final long deadline = System.currentTimeMillis() + 10000;
			while (System.currentTimeMillis() < deadline) {
				final Task task = tasks.get(name);
				if (task != null && !task.isDone()) return task;
				Thread.sleep(50);
			}
			throw new AssertionError("No running task named " + name);
		}
	}

	private static File writeScript(final String name, final String script)
		throws IOException
	{
		final File file = new File(scriptDir, name + ".py");
		Files.write(file.toPath(), script.getBytes(StandardCharsets.UTF_8));
		return file;
	}
}
