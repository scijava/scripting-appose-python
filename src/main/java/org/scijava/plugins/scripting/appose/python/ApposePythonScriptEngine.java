/*
 * #%L
 * Python scripting language plugin to be used via scyjava.
 * %%
 * Copyright (C) 2021 - 2025 SciJava developers.
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

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.script.Bindings;
import javax.script.ScriptEngine;
import javax.script.ScriptException;

import org.apposed.appose.Appose;
import org.apposed.appose.BuildException;
import org.apposed.appose.Builder;
import org.apposed.appose.Environment;
import org.apposed.appose.NDArray;
import org.apposed.appose.Service;
import org.apposed.appose.TaskException;
import org.scijava.Context;
import org.scijava.convert.ConvertService;
import org.scijava.log.LogService;
import org.scijava.module.ModuleItem;
import org.scijava.plugin.Parameter;
import org.scijava.script.AbstractScriptEngine;
import org.scijava.script.ScriptInfo;
import org.scijava.script.ScriptModule;

/**
 * A script engine for Python (CPython, not Jython!), backed by
 * <a href="https://github.com/apposed/appose-java">Appose</a>.
 * <p>
 * Scripts declare their Appose environment via the {@code #@script} directive:
 * </p>
 * <pre>
 * #@script(language="appose-python", env="myenv.toml", scheme="pixi.toml")
 * </pre>
 * <p>
 * The engine lazily builds the environment on first use and caches it for
 * subsequent calls. Inputs of array-compatible types (e.g., ImgLib2 {@code Img})
 * are automatically converted to Appose {@link NDArray} before being passed to
 * Python, and back again on the output side. Both conversions are delegated to
 * SciJava's {@link ConvertService}, so they work for any type that has a
 * registered converter to/from {@link NDArray}—such as the converters provided
 * by {@code imglib2-appose}.
 * </p>
 *
 * @author Curtis Rueden
 * @see ScriptEngine
 */
public class ApposePythonScriptEngine extends AbstractScriptEngine {

	@Parameter
	private ConvertService convertService;

	@Parameter
	private LogService log;

	public ApposePythonScriptEngine(final Context context) {
		context.inject(this);
		setLogService(log);
		engineScopeBindings = new ScriptBindings();
	}

	// -- ScriptEngine methods --

	@Override
	public Object eval(final String script) throws ScriptException {
		// Retrieve the ScriptModule (and hence ScriptInfo) from engine bindings.
		// ScriptModule.run() always injects itself before calling eval().
		final ScriptModule module =
			(ScriptModule) get(ScriptModule.class.getName());
		final ScriptInfo info = module == null ? null : module.getInfo();

		// Build (or retrieve from cache) the Appose environment.
		final Environment env = buildEnvironment(info);
		if (env == null) {
			throw new ScriptException(
				"No Appose environment configured. " +
				"Add #@script(env=\"myenv.toml\") to declare one.");
		}

		// Collect declared inputs, converting array-like values to NDArray.
		// Track which inputs were converted for the Python preamble.
		final Map<String, Object> taskInputs = new HashMap<>();
		final List<String> arrayInputNames = new ArrayList<>();
		final List<NDArray> ownedNDArrays = new ArrayList<>(); // closed after task

		if (info != null) {
			for (final ModuleItem<?> item : info.inputs()) {
				final String name = item.getName();
				final Object value = get(name);
				final NDArray nd = convertService.convert(value, NDArray.class);
				if (nd != null) {
					taskInputs.put(name, nd);
					arrayInputNames.add(name);
					ownedNDArrays.add(nd);
				}
				else {
					taskInputs.put(name, value);
				}
			}
		}
		else {
			// No ScriptInfo (e.g., REPL): pass all engine-scope bindings as inputs.
			taskInputs.putAll(engineScopeBindings);
		}

		// Determine which declared outputs need Python-side NDArray packing and
		// Java-side conversion (array-like), vs. plain pass-through.
		final List<String> arrayOutputNames = new ArrayList<>();
		final List<String> plainOutputNames = new ArrayList<>();
		if (info != null) {
			for (final ModuleItem<?> item : info.outputs()) {
				final String name = item.getName();
				if (ScriptModule.RETURN_VALUE.equals(name)) continue;
				if (convertService.supports(NDArray.class, item.getType())) {
					arrayOutputNames.add(name);
				}
				else {
					plainOutputNames.add(name);
				}
			}
		}

		// Wrap the user's script with preamble (unpack inputs) and postamble
		// (pack outputs into task.outputs).
		final String wrappedScript = buildWrappedScript(
			script, arrayInputNames, arrayOutputNames, plainOutputNames);

		// Execute the script via Appose and wait for it to finish.
		try (Service python = env.python()) {
			final Service.Task task = python.task(wrappedScript, taskInputs);
			task.listen(event -> {
				if (event.message != null) log.info("[appose-python] " + event.message);
			});
			task.waitFor();

			// Unmarshal outputs from task.outputs back into engine bindings.
			if (info != null) {
				for (final ModuleItem<?> item : info.outputs()) {
					final String name = item.getName();
					if (ScriptModule.RETURN_VALUE.equals(name)) continue;
					final Object raw = task.outputs.get(name);
					if (raw == null) continue;
					final Object value;
					if (raw instanceof NDArray) {
						final NDArray nd = (NDArray) raw;
						final Object converted = convertService.convert(nd, item.getType());
						value = converted != null ? converted : nd;
						if (converted != null) nd.close();
					}
					else {
						value = raw;
					}
					put(name, value);
				}
			}
			else {
				task.outputs.forEach(this::put);
			}
		}
		catch (final TaskException e) {
			throw new ScriptException("Python task failed: " + e.getMessage());
		}
		catch (final InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ScriptException("Python task interrupted");
		}
		finally {
			ownedNDArrays.forEach(NDArray::close);
		}

		return null; // outputs are surfaced via bindings, not as a return value
	}

	@Override
	public Object eval(final Reader reader) throws ScriptException {
		final StringBuilder sb = new StringBuilder();
		final char[] buf = new char[65536];
		try {
			int n;
			while ((n = reader.read(buf)) > 0) sb.append(buf, 0, n);
		}
		catch (final IOException e) {
			throw new ScriptException(e);
		}
		return eval(sb.toString());
	}

	@Override
	public Bindings createBindings() {
		return new ScriptBindings();
	}

	// -- Helper methods --

	/**
	 * Lazily builds the Appose {@link Environment} described by the {@code env}
	 * and {@code scheme} attributes of the script's {@code #@script} directive.
	 */
	private Environment buildEnvironment(final ScriptInfo info)
		throws ScriptException
	{
		if (info == null) return null;

		final String envValue = info.get("env");
		final File envFile = envValue == null ? null : resolveEnvFile(envValue, info.getPath());
		final String envName = sanitizeEnvName(info.getPath());

		final String scheme = info.get("scheme");
		final List<String> pypi = toListOfStrings(info.get("pypi"));
		final List<String> conda = toListOfStrings(info.get("conda"));
		final String python = info.get("python");

		log.info("Building Appose environment: " + envName);

		try {
			final Builder<?> builder;
			if (envFile == null) {
				// No environment file given -- use inline builder.
				if (conda.isEmpty()) {
					// No conda packages required -- use uv.
					String[] pypiPkgs = pypi.toArray(new String[0]);
					builder = python == null ?
						Appose.uv().include(pypiPkgs) :
						Appose.uv().python(python).include(pypiPkgs);
				}
				else {
					// Conda packages specified -- use pixi.
					List<String> condaPkgList = new ArrayList<>();
					if (conda.stream().anyMatch(v -> v.matches("python\\b.*"))) {
						// Python was not included in the conda package list -- add it manually.
						// If python version value was given, use that, otherwise open-ended.
						condaPkgList.add(python != null ? "python=" + python : "python");
					}
					condaPkgList.addAll(conda);
					String[] condaPkgs = condaPkgList.toArray(new String[0]);
					String[] pypiPkgs = pypi.toArray(new String[0]);
					builder = Appose.pixi().conda(condaPkgs).pypi(pypiPkgs);
				}
			}
			else {
				// Build with the given environment file.
				builder = scheme == null ?
					Appose.file(envFile) :
					Appose.file(envFile).scheme(scheme);
			}
			return builder.name(envName).build();
		}
		catch (final BuildException e) {
			ScriptException se = new ScriptException(
				"Failed to build Appose environment");
			se.initCause(e);
			throw se;
		}
	}

	private List<String> toListOfStrings(String value) {
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException("Unimplemented method 'toListOfStrings'");
	}

	/** Resolves an (optionally relative) env file path against the script path. */
	private static File resolveEnvFile(final String envRef,
		final String scriptPath)
	{
		final File envFile = new File(envRef);
		if (envFile.isAbsolute()) return envFile;
		if (scriptPath != null) {
			final File parent = new File(scriptPath).getParentFile();
			if (parent != null) return new File(parent, envRef);
		}
		return envFile;
	}

	/**
	 * Converts a script path to a valid Appose environment name by replacing
	 * all non-alphanumeric characters (except {@code -}) with {@code _}.
	 */
	private static String sanitizeEnvName(final String scriptPath) {
		if (scriptPath == null) return "scripting-appose-python";
		return new File(scriptPath).getAbsolutePath()
			.replaceAll("[^a-zA-Z0-9_]", "-");
	}

	/**
	 * Wraps the user's script with a preamble and postamble that handle
	 * input transformations and {@code task.outputs} capture for array types.
	 * <p>
	 * The preamble defines a {@code _pack_img} helper, then converts each
	 * array-typed input from its incoming NDArray to a numpy array. The
	 * postamble captures each declared output into {@code task.outputs},
	 * packing array outputs back into NDArray for Java.
	 * </p>
	 */
	private static String buildWrappedScript(
		final String userScript,
		final List<String> arrayInputNames,
		final List<String> arrayOutputNames,
		final List<String> plainOutputNames)
	{
		final StringBuilder sb = new StringBuilder();

		// Preamble: helper functions.
		sb.append("import numpy as _np\n");
		sb.append("from appose import NDArray as _NDArray\n");
		sb.append("\n");
		sb.append("def _pack_img(arr):\n");
		sb.append("    \"\"\"Pack a numpy array into a new shared-memory NDArray.\"\"\"\n");
		sb.append("    nd = _NDArray(str(arr.dtype), arr.shape)\n");
		sb.append("    nd.ndarray()[:] = arr\n");
		sb.append("    return nd\n");
		sb.append("\n");

		// Convert each array input: NDArray → numpy array.
		for (final String name : arrayInputNames) {
			sb.append(name).append(" = ").append(name).append(".ndarray()\n");
		}
		if (!arrayInputNames.isEmpty()) sb.append("\n");

		// User script (with #@ lines already stripped by the script processor).
		sb.append(userScript).append("\n");

		// Postamble: capture declared outputs into task.outputs.
		for (final String name : arrayOutputNames) {
			sb.append("task.outputs[\"").append(name)
				.append("\"] = _pack_img(").append(name).append(")\n");
		}
		for (final String name : plainOutputNames) {
			sb.append("task.outputs[\"").append(name)
				.append("\"] = ").append(name).append("\n");
		}

		return sb.toString();
	}

	// -- Inner class --

	private static class ScriptBindings implements Bindings {

		private final Map<String, Object> map = new HashMap<>();

		@Override public int size()                              { return map.size(); }
		@Override public boolean isEmpty()                       { return map.isEmpty(); }
		@Override public boolean containsValue(final Object v)   { return map.containsValue(v); }
		@Override public void clear()                            { map.clear(); }
		@Override public Set<String> keySet()                    { return map.keySet(); }
		@Override public Collection<Object> values()             { return map.values(); }
		@Override public Set<Entry<String, Object>> entrySet()   { return map.entrySet(); }
		@Override public Object put(final String k, final Object v) { return map.put(k, v); }
		@Override public void putAll(final Map<? extends String, ?> m) { map.putAll(m); }
		@Override public boolean containsKey(final Object k)     { return map.containsKey(k); }
		@Override public Object get(final Object k)              { return map.get(k); }
		@Override public Object remove(final Object k)           { return map.remove(k); }
	}
}
