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

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apposed.appose.Appose;
import org.apposed.appose.Builder;
import org.scijava.plugins.scripting.appose.python._internal.InlineMetadata;
import org.scijava.plugins.scripting.appose.python._internal.ResidentWorker;
import org.scijava.plugins.scripting.appose.python._internal.ResidentWorkerService;

/**
 * The Appose environment a script declares: either inline, via a PEP 723
 * {@code # /// script} metadata block, or in a file named by the {@code env}
 * (and optionally {@code scheme}) attributes of its {@code #@script}
 * directive.
 * <p>
 * Both running a script ({@link ApposePythonScriptEngine}) and completing code
 * in it ({@link
 * org.scijava.plugins.scripting.appose.python.completion.ApposePythonDialect})
 * resolve its environment here, so that they agree on the environment's name
 * and configuration.
 * </p>
 */
public final class ScriptEnvironment {

	/** The {@code #@script(...)} directive. */
	private static final Pattern SCRIPT_DIRECTIVE = Pattern.compile(
		"^#@\\s*script\\s*\\((.*)\\)\\s*$", Pattern.MULTILINE);

	/** An attribute of a directive: key="value", key='value' or key=value. */
	private static final Pattern ATTRIBUTE = Pattern.compile(
		"(\\w+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^,\\s)]+))");

	/** Name of the environment. */
	public final String name;

	/** The environment's configuration, e.g. the contents of its file. */
	final String content;

	/** The configuration's scheme (e.g. {@code pixi.toml}), or null if implied. */
	final String scheme;

	private ScriptEnvironment(final String name, final String content,
		final String scheme)
	{
		this.name = name;
		this.content = content;
		this.scheme = scheme;
	}

	/**
	 * Resolves the environment declared by a script.
	 *
	 * @param script The script's source code.
	 * @param scriptPath The script's file path, or null if unsaved.
	 * @param envRef The {@code env} attribute of its {@code #@script} directive,
	 *          or null.
	 * @param scheme The {@code scheme} attribute of its {@code #@script}
	 *          directive, or null.
	 * @return The environment, or null if the script declares none.
	 * @throws IllegalArgumentException If the declaration is invalid, e.g.
	 *           malformed, given twice, or naming a missing file.
	 * @throws IOException If the environment file cannot be read.
	 */
	public static ScriptEnvironment of(final String script,
		final String scriptPath, final String envRef, final String scheme)
		throws IOException
	{
		final String metadata = InlineMetadata.extract(script);
		if (metadata != null) {
			if (envRef != null) {
				throw new IllegalArgumentException("The script declares its " +
					"environment twice: inline, and via #@script(env=\"" + envRef +
					"\"). Remove one of them.");
			}
			final String name = inlineName(metadata, scriptPath);
			return new ScriptEnvironment(name, InlineMetadata.toPyProject(metadata,
				name), InlineMetadata.SCHEME);
		}
		if (envRef == null) return null;

		final File envFile = resolveEnvFile(envRef, scriptPath);
		if (!envFile.isFile()) {
			throw new IllegalArgumentException("Appose environment file not " +
				"found: " + envFile.getAbsolutePath());
		}
		final String content;
		try {
			content = new String(Files.readAllBytes(envFile.toPath()),
				StandardCharsets.UTF_8);
		}
		catch (final IOException e) {
			throw new IOException("Cannot read Appose environment file: " + envFile
				.getAbsolutePath(), e);
		}
		return new ScriptEnvironment(envName(envFile), content, scheme);
	}

	/**
	 * Gets the name of the environment a script declares inline.
	 *
	 * @param metadata The script's metadata block (see
	 *          {@link InlineMetadata#extract}).
	 * @param scriptPath The script's file path, or null if unsaved.
	 */
	public static String inlineName(final String metadata,
		final String scriptPath)
	{
		// Note: Name the environment after the script, as for environment
		// files, so that editing the metadata updates the environment and
		// replaces its worker, rather than leaving the old one running.
		// Unsaved scripts, e.g. new from a template, have no path to go by.
		return scriptPath == null ? "script-" + String.format("%08x", metadata
			.hashCode()) : envName(new File(scriptPath));
	}

	/**
	 * Gets the attributes of a script's {@code #@script(...)} directive, e.g.
	 * {@code env} and {@code scheme}, without fully parsing its parameters.
	 */
	public static Map<String, String> directive(final String script) {
		final Map<String, String> attrs = new HashMap<>();
		final Matcher m = SCRIPT_DIRECTIVE.matcher(script);
		if (!m.find()) return attrs;
		final Matcher a = ATTRIBUTE.matcher(m.group(1));
		while (a.find()) {
			final String value = a.group(2) != null ? a.group(2) : a.group(3) != null
				? a.group(3) : a.group(4);
			attrs.put(a.group(1), value);
		}
		return attrs;
	}

	/**
	 * The key under which {@link
	 * org.scijava.plugins.scripting.appose.python._internal.ResidentWorkerService}
	 * keeps the environment: changes to it replace the environment's worker.
	 */
	public String config() {
		return scheme + "\n" + content;
	}

	/**
	 * Gets the resident worker of this environment (see
	 * {@link ResidentWorkerService#worker}), as scripts run it: replacing the
	 * environment's worker of an earlier configuration, if any. The
	 * environment is built by the worker's first task, or when asked (see
	 * {@link ResidentWorker#environment()}).
	 */
	public ResidentWorker worker(final ResidentWorkerService workers) {
		return workers.worker(name, config(), this::builder,
			ApposePythonScriptEngine::launch);
	}

	/** Creates a builder for the environment. */
	Builder<?> builder() {
		final Builder<?> builder = Appose.content(content);
		return scheme == null ? builder : builder.scheme(scheme);
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
}
