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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.function.Consumer;

import javax.script.Bindings;
import javax.script.ScriptEngine;
import javax.script.ScriptException;
import javax.script.SimpleBindings;

import net.imglib2.appose.WrappedNDArray;

import org.apposed.appose.Appose;
import org.apposed.appose.BuildException;
import org.apposed.appose.Builder;
import org.apposed.appose.NDArray;
import org.apposed.appose.Service;
import org.apposed.appose.Service.TaskStatus;
import org.apposed.appose.TaskException;
import org.scijava.Context;
import org.scijava.app.StatusService;
import org.scijava.convert.ConvertService;
import org.scijava.log.LogService;
import org.scijava.log.Logger;
import org.scijava.module.ModuleItem;
import org.scijava.plugin.Parameter;
import org.scijava.plugins.scripting.appose.python._internal.ResidentWorker;
import org.scijava.plugins.scripting.appose.python._internal.ResidentWorkerService;
import org.scijava.plugins.scripting.appose.python._internal.SciJavaTasks;
import org.scijava.script.AbstractScriptEngine;
import org.scijava.script.ScriptInfo;
import org.scijava.script.ScriptModule;
import org.scijava.task.Task;
import org.scijava.task.TaskService;

/**
 * A script engine for Python (CPython, not Jython!), backed by
 * <a href="https://github.com/apposed/appose-java">Appose</a>.
 * <p>
 * Scripts declare their Appose environment via the {@code #@script} directive:
 * </p>
 * <pre>
 * #@script(env="myenv.toml", scheme="pixi.toml")
 * </pre>
 * <p>
 * The engine lazily builds the environment on first use, and keeps one Python
 * worker process per environment alive across script runs, so that imported
 * modules, and objects a script keeps via {@code task.export(...)}, need not
 * be loaded again. Each run still gets a fresh namespace. Builds and runs
 * appear as SciJava {@link Task}s, and canceling a run's task cancels the
 * script. Inputs of array-compatible types (e.g., ImgLib2 {@code Img})
 * are automatically converted to Appose {@link NDArray} before being passed to
 * Python, and back again on the output side. Both conversions are delegated to
 * SciJava's {@link ConvertService}, so they work for any type that has a
 * registered converter to/from {@link NDArray}—such as the converters provided
 * by {@code imglib2-appose}.
 * </p>
 * <p>
 * Anything the script writes to stdout (e.g. via {@code print}) is forwarded
 * to the script context's writer, and anything written to stderr to its error
 * writer.
 * </p>
 *
 * @author Curtis Rueden
 * @see ScriptEngine
 */
public class ApposePythonScriptEngine extends AbstractScriptEngine {

	/** Task output key holding the value of the script's last expression. */
	static final String RETURN_VALUE_KEY = "_appose_return_value";

	/** Appose task script that runs the user script; see wrapper.py. */
	private static final String WRAPPER_SCRIPT = loadWrapperScript();

	/**
	 * How long to wait, after a run finishes, for the rest of its stderr output
	 * to arrive.
	 */
	private static final long OUTPUT_DRAIN_MILLIS = 2000;

	@Parameter
	private ConvertService convertService;

	@Parameter
	private LogService log;

	@Parameter
	private ResidentWorkerService workerService;

	@Parameter(required = false)
	private StatusService statusService;

	@Parameter(required = false)
	private TaskService taskService;

	public ApposePythonScriptEngine(final Context context) {
		context.inject(this);
		setLogService(log);
		engineScopeBindings = new SimpleBindings();
	}

	// -- ScriptEngine methods --

	@Override
	public Object eval(final String script) throws ScriptException {
		// Retrieve the ScriptModule (and hence ScriptInfo) from engine bindings.
		// ScriptModule.run() always injects itself before calling eval().
		final Object moduleObj = get(ScriptModule.class.getName());
		final ScriptInfo info = moduleObj instanceof ScriptModule ?
			((ScriptModule) moduleObj).getInfo() : null;

		// Retrieve the resident worker for the script's environment.
		final ResidentWorker worker = worker(info);

		// Collect declared inputs, converting array-like values to NDArray.
		final Map<String, Object> taskInputs = new HashMap<>();
		final List<String> arrayInputNames = new ArrayList<>();
		final List<NDArray> ownedNDArrays = new ArrayList<>(); // closed after task
		try {
			for (final ModuleItem<?> item : info.inputs()) {
				final String name = item.getName();
				final Object value = get(name);
				final NDArray nd = toNDArray(value);
				if (nd != null) {
					taskInputs.put(name, nd);
					arrayInputNames.add(name);
					if (ownsNDArray(value, nd)) ownedNDArrays.add(nd);
					continue;
				}
				final Object marshalled = marshal(value);
				if (marshalled == UNSUPPORTED) {
					log.warn("[appose-python] Skipping input '" + name +
						"': cannot pass " + value.getClass().getName() + " to Python");
					continue;
				}
				taskInputs.put(name, marshalled);
			}

			final List<String> outputNames = new ArrayList<>();
			for (final ModuleItem<?> item : info.outputs()) {
				final String name = item.getName();
				if (!ScriptModule.RETURN_VALUE.equals(name)) outputNames.add(name);
			}

			final String scriptPath = info.getPath();
			taskInputs.put("_appose_script", script);
			taskInputs.put("_appose_script_path",
				scriptPath == null ? "<script>" : scriptPath);
			taskInputs.put("_appose_array_inputs", arrayInputNames);
			taskInputs.put("_appose_outputs", outputNames);

			return runTask(worker, taskInputs, info);
		}
		finally {
			ownedNDArrays.forEach(NDArray::close);
		}
	}

	@Override
	public Object eval(final Reader reader) throws ScriptException {
		final StringBuilder sb = new StringBuilder();
		final char[] buf = new char[65536];
		try {
			int n;
			while ((n = reader.read(buf)) != -1) sb.append(buf, 0, n);
		}
		catch (final IOException e) {
			throw new ScriptException(e);
		}
		return eval(sb.toString());
	}

	@Override
	public Bindings createBindings() {
		return new SimpleBindings();
	}

	// -- Helper methods --

	/**
	 * Runs the wrapped script as an Appose task on the environment's resident
	 * worker, storing its outputs into the engine bindings.
	 *
	 * @return The value of the script's last expression, if any.
	 */
	private Object runTask(final ResidentWorker worker,
		final Map<String, Object> taskInputs, final ScriptInfo info)
		throws ScriptException
	{
		// Note: Runs on a worker are serialized, because its output cannot
		// otherwise be attributed to the script that produced it.
		final Lock lock = worker.exclusive();
		try {
			lock.lockInterruptibly();
		}
		catch (final InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ScriptException("Python script interrupted");
		}
		final OutputForwarder forwarder = new OutputForwarder();
		taskInputs.put("_appose_end_marker", forwarder.endMarker);
		worker.debug(forwarder);
		Service.Task task = null;
		Task progress = null;
		try {
			task = worker.task(WRAPPER_SCRIPT, taskInputs);
			task.listen(event -> {
				if (event.message != null) log.info("[appose-python] " + event.message);
			});
			progress = SciJavaTasks.track(taskService, statusService, "Running " +
				scriptName(info), task, worker::kill);
			if (progress != null) forwarder.taskLog = progress.log();
			try {
				task.waitFor();
			}
			finally {
				if (task.status == TaskStatus.COMPLETE || task.status == TaskStatus.FAILED) {
					forwarder.awaitEnd();
				}
			}

			// Unmarshal outputs from task.outputs back into engine bindings.
			for (final ModuleItem<?> item : info.outputs()) {
				final String name = item.getName();
				if (ScriptModule.RETURN_VALUE.equals(name)) continue;
				put(name, unmarshal(task.outputs.get(name), item.getType()));
			}
			return unmarshal(task.outputs.get(RETURN_VALUE_KEY), Object.class);
		}
		catch (final BuildException e) {
			throw scriptException("Failed to build Appose environment '" + worker
				.environment().name() + "': " + e.getMessage(), e);
		}
		catch (final TaskException e) {
			if (progress != null && progress.isCanceled()) {
				// Note: No cause, since ScriptModule reports the innermost cause,
				// which would say the worker crashed if it had to be stopped.
				throw new ScriptException("Python script canceled");
			}
			if (progress != null) progress.log().error(e.getMessage());
			throw scriptException("Python script failed: " + e.getMessage(), e);
		}
		catch (final InterruptedException e) {
			worker.kill();
			Thread.currentThread().interrupt();
			throw new ScriptException("Python script interrupted");
		}
		catch (final RuntimeException e) {
			// E.g. UncheckedIOException when the worker process fails to launch.
			worker.kill();
			throw scriptException("Python script failed: " + e.getMessage(), e);
		}
		finally {
			worker.debug(null);
			if (progress != null) progress.finish();
			if (statusService != null) statusService.clearStatus();
			lock.unlock();
		}
	}

	/** Gets a human-friendly name for the given script. */
	private static String scriptName(final ScriptInfo info) {
		final String path = info.getPath();
		return path == null ? "script" : new File(path).getName();
	}

	/**
	 * Forwards the worker process's stdout (other than Appose protocol
	 * messages) and stderr to the script context's writers, and to the run's
	 * task logger, if any.
	 * <p>
	 * Note: This relies on the format of Appose's debug messages: stderr lines
	 * arrive as {@code [WORKER-n] line} and non-protocol stdout lines as
	 * {@code [SERVICE-n] <INVALID> line}.
	 * </p>
	 */
	private class OutputForwarder implements Consumer<String> {

		/**
		 * Line the wrapper script writes to stderr when it is done, since stderr
		 * lines may still arrive after the task has reported completion.
		 */
		private final String endMarker = "[appose-python-end " + UUID.randomUUID() +
			"]";
		private final CountDownLatch ended = new CountDownLatch(1);

		/** Logger of the run's SciJava task, if any. */
		private volatile Logger taskLog;

		@Override
		public void accept(final String message) {
			final int end = message.indexOf("] ");
			if (end < 0) return;
			final String prefix = message.substring(0, end);
			final String line = message.substring(end + 2);
			if (prefix.startsWith("[WORKER-")) {
				if (line.equals(endMarker)) ended.countDown();
				else output(getContext().getErrorWriter(), line);
			}
			else if (prefix.startsWith("[SERVICE-") && line.startsWith("<INVALID> ")) {
				output(getContext().getWriter(), line.substring(10));
			}
		}

		private void output(final Writer writer, final String line) {
			writeLine(writer, line);
			// Note: Info level for stderr too, since Python writes ordinary
			// output there as well, e.g. via the logging module.
			final Logger logger = taskLog;
			if (logger != null) logger.info(line);
		}

		/** Waits until the wrapper script's stderr output has all arrived. */
		private void awaitEnd() throws InterruptedException {
			ended.await(OUTPUT_DRAIN_MILLIS, TimeUnit.MILLISECONDS);
		}
	}

	private void writeLine(final Writer writer, final String line) {
		if (writer == null) return;
		try {
			writer.write(line);
			writer.write(System.lineSeparator());
			writer.flush();
		}
		catch (final IOException e) {
			log.debug(e);
		}
	}

	/** Converts the given value to an {@link NDArray}, if possible. */
	private NDArray toNDArray(final Object value) {
		if (value == null) return null;
		if (value instanceof NDArray) return (NDArray) value;
		if (!convertService.supports(value, NDArray.class)) return null;
		return convertService.convert(value, NDArray.class);
	}

	/**
	 * Converts an output value received from Python to the given type. Numeric
	 * and other plain values are passed through as is, to be converted later
	 * by the {@link ScriptModule}.
	 */
	private Object unmarshal(final Object raw, final Class<?> type) {
		if (!(raw instanceof NDArray)) return raw;
		final NDArray nd = (NDArray) raw;
		if (type.isInstance(nd)) return nd;
		final Object converted = convertService.convert(nd, type);
		if (converted == null) return nd;
		if (converted != nd) {
			// Note: By Appose convention, the service side frees shared memory,
			// even when the worker allocated it. The worker also stays alive
			// after the run, so nothing else would ever free this block.
			nd.shm().unlinkOnClose(true);
			nd.close();
		}
		return converted;
	}

	/** Marker for values that cannot be passed to Python. */
	static final Object UNSUPPORTED = new Object();

	/**
	 * Converts an input value to a form Appose can encode as JSON, or returns
	 * {@link #UNSUPPORTED} if there is no sensible representation.
	 */
	static Object marshal(final Object value) {
		if (value == null || value instanceof Boolean ||
			value instanceof Number || value instanceof CharSequence ||
			value instanceof Collection || value instanceof Map)
		{
			return value;
		}
		if (value instanceof Character || value instanceof Enum) {
			return value.toString();
		}
		if (value instanceof File) return ((File) value).getAbsolutePath();
		if (value.getClass().isArray()) {
			final Class<?> component = value.getClass().getComponentType();
			if (component.isPrimitive() || component == String.class) return value;
		}
		return UNSUPPORTED;
	}

	/**
	 * Whether the given NDArray was newly created to hold the given value,
	 * and is therefore ours to close once the task finishes.
	 */
	static boolean ownsNDArray(final Object value, final NDArray nd) {
		// Note: A WrappedNDArray (e.g. ShmImg) hands out its backing NDArray
		// rather than a copy; closing it would break the caller's image.
		return nd != value && !(value instanceof WrappedNDArray);
	}

	/**
	 * Gets the resident worker for the Appose environment described by the
	 * {@code env} and {@code scheme} attributes of the script's
	 * {@code #@script} directive. The environment itself is built lazily, by
	 * the worker's first task.
	 */
	private ResidentWorker worker(final ScriptInfo info) throws ScriptException {
		final String envRef = info == null ? null : info.get("env");
		if (envRef == null) {
			throw new ScriptException(
				"No Appose environment configured. " +
				"Add #@script(env=\"myenv.toml\") to declare one.");
		}

		final File envFile = resolveEnvFile(envRef, info.getPath());
		if (!envFile.isFile()) {
			throw new ScriptException("Appose environment file not found: " +
				envFile.getAbsolutePath());
		}
		final String content;
		try {
			content = new String(Files.readAllBytes(envFile.toPath()),
				StandardCharsets.UTF_8);
		}
		catch (final IOException e) {
			throw scriptException("Cannot read Appose environment file: " +
				envFile.getAbsolutePath(), e);
		}
		final String envName = envName(envFile);
		final String scheme = info.get("scheme");

		return workerService.worker(envName, scheme + "\n" + content, () -> {
			final Builder<?> builder = Appose.content(content);
			return scheme == null ? builder : builder.scheme(scheme);
		},
			// Note: On Windows, importing numpy from a task hangs unless numpy
			// was imported during worker initialization.
			env -> env.python().init(
				"try:\n    import numpy\nexcept ImportError:\n    pass\n"));
	}

	/** Resolves an (optionally relative) env file path against the script path. */
	static File resolveEnvFile(final String envRef, final String scriptPath) {
		final File envFile = new File(envRef);
		if (envFile.isAbsolute()) return envFile;
		if (scriptPath != null) {
			final File parent = new File(scriptPath).getAbsoluteFile().getParentFile();
			if (parent != null) return new File(parent, envRef);
		}
		return envFile;
	}

	/**
	 * Derives a valid Appose environment name from an environment file: its
	 * base name, plus a hash of its absolute path to avoid collisions between
	 * like-named files in different directories. E.g.:
	 * {@code /path/to/cellcast.toml} becomes {@code cellcast-1a2b3c4d}.
	 */
	static String envName(final File envFile) {
		final String fileName = envFile.getName();
		final int dot = fileName.lastIndexOf('.');
		final String baseName = dot > 0 ? fileName.substring(0, dot) : fileName;
		final String path = envFile.getAbsoluteFile().toPath().normalize().toString();
		return baseName.replaceAll("[^a-zA-Z0-9_-]", "_") + "-" +
			String.format("%08x", path.hashCode());
	}

	private static ScriptException scriptException(final String message,
		final Throwable cause)
	{
		final ScriptException se = new ScriptException(message);
		se.initCause(cause);
		return se;
	}

	private static String loadWrapperScript() {
		try (final InputStream in = ApposePythonScriptEngine.class
			.getResourceAsStream("wrapper.py"))
		{
			if (in == null) throw new IllegalStateException("wrapper.py not found");
			final ByteArrayOutputStream out = new ByteArrayOutputStream();
			final byte[] buf = new byte[8192];
			int n;
			while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
			return new String(out.toByteArray(), StandardCharsets.UTF_8);
		}
		catch (final IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
