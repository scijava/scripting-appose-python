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


package org.scijava.plugins.scripting.appose.python._internal;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;

import org.apposed.appose.util.Platforms;

/**
 * Support for environments declared inline in a Python script, via a
 * <a href="https://packaging.python.org/en/latest/specifications/inline-script-metadata/">PEP
 * 723</a> metadata block, including the {@code [tool.pixi.*]} extensions
 * that {@code pixi run --script} understands:
 *
 * <pre>
 * # /// script
 * # requires-python = "&gt;=3.12"
 * # dependencies = ["appose", "cellcast"]
 * #
 * # [tool.pixi.dependencies]
 * # pytorch-gpu = "*"
 * # ///
 * </pre>
 * <p>
 * Appose builds environments in a directory of their own, rather than in
 * pixi's script cache, so the metadata is translated into an equivalent
 * {@code pyproject.toml}, which pixi builds like any other workspace.
 * </p>
 */
public final class InlineMetadata {

	/** Scheme to build the result of {@link #toPyProject} with. */
	public static final String SCHEME = "pyproject.toml";

	/** Channels used when the metadata declares none, as {@code pixi init} does. */
	private static final String DEFAULT_CHANNEL = "conda-forge";

	/**
	 * Regular expression for metadata blocks, from the specification's
	 * reference implementation.
	 */
	private static final Pattern BLOCK = Pattern.compile(
		"(?m)^# /// (?<type>[a-zA-Z0-9-]+)$\\s(?<content>(^#(| .*)$\\s)+)^# ///$");

	private InlineMetadata() {
		// Prevent instantiation of utility class.
	}

	/**
	 * Extracts the TOML content of a script's {@code script} metadata block.
	 *
	 * @param script Source code of the script.
	 * @return The block's TOML content, or null if there is no block.
	 * @throws IllegalArgumentException If there is more than one block.
	 */
	public static String extract(final String script) {
		// Note: Normalize line endings, since the pattern expects one
		// whitespace character between lines.
		final Matcher m = BLOCK.matcher(script.replace("\r\n", "\n"));
		String content = null;
		while (m.find()) {
			if (!"script".equals(m.group("type"))) continue;
			if (content != null) {
				throw new IllegalArgumentException(
					"Multiple '# /// script' metadata blocks found");
			}
			content = uncomment(m.group("content"));
		}
		return content;
	}

	/**
	 * Translates the TOML content of a {@code script} metadata block into an
	 * equivalent {@code pyproject.toml}:
	 * <ul>
	 * <li>{@code requires-python} and {@code dependencies} move into the
	 * {@code [project]} table;</li>
	 * <li>{@code [tool.*]} tables are kept as they are, though unsupported
	 * {@code [tool.pixi.*]} keys are rejected, as by {@code pixi run --script};</li>
	 * <li>{@code [tool.pixi.workspace]} gains {@code channels} (conda-forge)
	 * and {@code platforms} (the running platform) if it lacks them, as
	 * {@code pixi run --script} would assume.</li>
	 * </ul>
	 *
	 * @param metadata TOML content of the metadata block.
	 * @param name Project name for the result.
	 * @return The {@code pyproject.toml} content.
	 * @throws IllegalArgumentException If the metadata is not valid.
	 */
	public static String toPyProject(final String metadata, final String name) {
		final TomlMapper mapper = new TomlMapper();
		final JsonNode parsed;
		try {
			parsed = mapper.readTree(metadata);
		}
		catch (final IOException e) {
			throw new IllegalArgumentException(
				"Invalid TOML in script metadata: " + e.getMessage(), e);
		}

		final ObjectNode pyproject = mapper.createObjectNode();
		final ObjectNode project = pyproject.putObject("project");
		project.put("name", name);
		project.put("version", "0.0.0");
		final Iterator<Map.Entry<String, JsonNode>> fields = parsed.fields();
		while (fields.hasNext()) {
			final Map.Entry<String, JsonNode> field = fields.next();
			final String key = field.getKey();
			final JsonNode value = field.getValue();
			if ("requires-python".equals(key)) {
				if (!value.isTextual()) throw invalid(key + " must be a string");
				project.set(key, value);
			}
			else if ("dependencies".equals(key)) {
				if (!value.isArray()) throw invalid(key + " must be an array");
				project.set(key, value);
			}
			else if ("tool".equals(key)) {
				if (!value.isObject()) throw invalid(key + " must be a table");
				pyproject.set(key, value);
			}
			else throw invalid("unknown field '" + key + "'");
		}

		if (!project.has("dependencies")) project.putArray("dependencies");

		final ObjectNode pixi = table(table(pyproject, "tool"), "pixi");
		validatePixi(pixi);
		final ObjectNode workspace = table(pixi, "workspace");
		if (!workspace.has("channels")) {
			workspace.putArray("channels").add(DEFAULT_CHANNEL);
		}
		if (!workspace.has("platforms")) {
			workspace.putArray("platforms").add(condaPlatform());
		}

		try {
			return mapper.writeValueAsString(pyproject);
		}
		catch (final IOException e) {
			throw new IllegalArgumentException(
				"Cannot express script metadata as pyproject.toml: " + e.getMessage(),
				e);
		}
	}

	/** Gets the conda platform name (e.g. {@code linux-64}) of this machine. */
	public static String condaPlatform() {
		final String os;
		switch (Platforms.OS) {
			case LINUX: os = "linux"; break;
			case MACOS: os = "osx"; break;
			case WINDOWS: os = "win"; break;
			default: throw new IllegalStateException("Unsupported OS: " + Platforms.OS);
		}
		switch (Platforms.ARCH) {
			case X64: return os + "-64";
			case X32: return os + "-32";
			case ARM64: return os + (Platforms.OS == Platforms.OperatingSystem.LINUX ?
				"-aarch64" : "-arm64");
			case PPC64LE: return os + "-ppc64le";
			default: throw new IllegalStateException("Unsupported architecture: " +
				Platforms.ARCH);
		}
	}

	// -- Helper methods --

	/**
	 * Rejects {@code [tool.pixi.*]} keys that {@code pixi run --script} does not
	 * support, so that a script behaves the same here as there. The lists
	 * follow pixi's {@code validate_subset}.
	 */
	private static void validatePixi(final ObjectNode pixi) {
		final List<String> unsupported = new ArrayList<>();
		unsupported(pixi, "tool.pixi", unsupported, "activation", "constraints",
			"dependencies", "pypi-dependencies", "system-requirements", "target",
			"workspace");
		final JsonNode workspace = pixi.get("workspace");
		if (workspace != null && workspace.isObject()) {
			unsupported(workspace, "tool.pixi.workspace", unsupported,
				"channel-priority", "channels", "platforms", "preview", "pypi-options",
				"requires-pixi", "solve-strategy");
		}
		final JsonNode targets = pixi.get("target");
		if (targets != null && targets.isObject()) {
			final Iterator<Map.Entry<String, JsonNode>> it = targets.fields();
			while (it.hasNext()) {
				final Map.Entry<String, JsonNode> target = it.next();
				final String path = "tool.pixi.target." + target.getKey();
				if (!target.getValue().isObject()) unsupported.add(path);
				else unsupported(target.getValue(), path, unsupported, "activation",
					"constraints", "dependencies", "pypi-dependencies");
			}
		}
		if (!unsupported.isEmpty()) {
			Collections.sort(unsupported);
			throw invalid("unsupported field(s) " + String.join(", ", unsupported));
		}
	}

	/** Adds the keys of the given table not among those allowed. */
	private static void unsupported(final JsonNode table, final String prefix,
		final List<String> unsupported, final String... allowed)
	{
		final List<String> allowedKeys = Arrays.asList(allowed);
		table.fieldNames().forEachRemaining(key -> {
			if (!allowedKeys.contains(key)) unsupported.add(prefix + "." + key);
		});
	}

	/** Removes the comment prefix from each line, as the specification says. */
	private static String uncomment(final String content) {
		final StringBuilder sb = new StringBuilder();
		for (final String line : content.split("\n", -1)) {
			if (line.startsWith("# ")) sb.append(line, 2, line.length());
			else if (line.startsWith("#")) sb.append(line, 1, line.length());
			sb.append('\n');
		}
		return sb.toString();
	}

	/** Gets the named child table, creating it if needed. */
	private static ObjectNode table(final ObjectNode parent, final String key) {
		final JsonNode child = parent.get(key);
		if (child == null) return parent.putObject(key);
		if (!child.isObject()) throw invalid("'" + key + "' must be a table");
		return (ObjectNode) child;
	}

	private static IllegalArgumentException invalid(final String message) {
		return new IllegalArgumentException("Invalid script metadata: " + message);
	}
}
