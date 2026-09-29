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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;

import org.junit.Test;

/**
 * Tests {@link InlineMetadata}.
 *
 * @author Curtis Rueden
 */
public class InlineMetadataTest {

	private static final String SCRIPT = String.join("\n", //
		"#!appose-python", //
		"# /// script", //
		"# requires-python = \">=3.12\"", //
		"# dependencies = [", //
		"#   \"appose>=0.12\",", //
		"#   \"numpy<3; python_version >= '3.10'\",", //
		"# ]", //
		"#", //
		"# [tool.pixi.dependencies]", //
		"# pytorch-gpu = \"*\"", //
		"# ///", //
		"#@ Img image", //
		"print(image.shape)", //
		"");

	@Test
	public void testExtract() {
		final String metadata = InlineMetadata.extract(SCRIPT);
		assertEquals(String.join("\n", //
			"requires-python = \">=3.12\"", //
			"dependencies = [", //
			"  \"appose>=0.12\",", //
			"  \"numpy<3; python_version >= '3.10'\",", //
			"]", //
			"", //
			"[tool.pixi.dependencies]", //
			"pytorch-gpu = \"*\""), metadata.trim());
	}

	@Test
	public void testExtractCRLF() {
		final String metadata =
			InlineMetadata.extract(SCRIPT.replace("\n", "\r\n"));
		assertTrue(metadata, metadata.startsWith("requires-python = \">=3.12\"\n"));
	}

	@Test
	public void testExtractNone() {
		assertNull(InlineMetadata.extract("print('hi')\n"));
		// An unterminated block is not a block.
		assertNull(InlineMetadata.extract("# /// script\n# dependencies = []\n"));
	}

	@Test
	public void testExtractIgnoresOtherTypes() {
		assertNull(InlineMetadata.extract(
			"# /// other\n# key = 1\n# ///\nprint('hi')\n"));
	}

	@Test
	public void testExtractRejectsMultipleBlocks() {
		final String block = "# /// script\n# dependencies = []\n# ///\n";
		assertThrows(IllegalArgumentException.class,
			() -> InlineMetadata.extract(block + "x = 1\n" + block));
	}

	@Test
	public void testToPyProject() throws Exception {
		final JsonNode pyproject = pyproject(InlineMetadata.extract(SCRIPT));

		final JsonNode project = pyproject.get("project");
		assertEquals("my-env", project.get("name").asText());
		assertEquals(">=3.12", project.get("requires-python").asText());
		assertEquals(2, project.get("dependencies").size());
		assertEquals("numpy<3; python_version >= '3.10'",
			project.get("dependencies").get(1).asText());

		final JsonNode pixi = pyproject.get("tool").get("pixi");
		assertEquals("*", pixi.get("dependencies").get("pytorch-gpu").asText());
		// Defaults are filled in, as pixi requires them in a workspace.
		assertEquals("conda-forge",
			pixi.get("workspace").get("channels").get(0).asText());
		assertEquals(InlineMetadata.condaPlatform(),
			pixi.get("workspace").get("platforms").get(0).asText());
	}

	@Test
	public void testToPyProjectKeepsDeclaredWorkspace() throws Exception {
		final JsonNode pyproject = pyproject(String.join("\n", //
			"dependencies = [\"appose\"]", //
			"[tool.pixi.workspace]", //
			"channels = [\"pytorch\", \"conda-forge\"]", //
			"platforms = [\"linux-64\", \"win-64\"]", //
			"[tool.other]", //
			"setting = true", //
			""));
		final JsonNode workspace =
			pyproject.get("tool").get("pixi").get("workspace");
		assertEquals("pytorch", workspace.get("channels").get(0).asText());
		assertEquals(2, workspace.get("platforms").size());
		assertTrue(pyproject.get("tool").get("other").get("setting").asBoolean());
		assertFalse(pyproject.get("project").has("requires-python"));
	}

	@Test
	public void testToPyProjectAcceptsPixiScriptFields() throws Exception {
		final JsonNode pyproject = pyproject(String.join("\n", //
			"requires-python = \">=3.12\"", //
			"[tool.pixi.workspace]", //
			"channels = [\"conda-forge\"]", //
			"channel-priority = \"strict\"", //
			"[tool.pixi.system-requirements]", //
			"cuda = \"12\"", //
			"[tool.pixi.target.linux-64.dependencies]", //
			"pytorch-gpu = \"*\"", //
			"[tool.pixi.activation.env]", //
			"MY_VAR = \"1\"", //
			""));
		final JsonNode pixi = pyproject.get("tool").get("pixi");
		assertEquals("12", pixi.get("system-requirements").get("cuda").asText());
		assertEquals("*", pixi.get("target").get("linux-64").get("dependencies")
			.get("pytorch-gpu").asText());
		// pyproject.toml needs a dependencies list, even if empty.
		assertEquals(0, pyproject.get("project").get("dependencies").size());
	}

	@Test
	public void testToPyProjectRejectsInvalidMetadata() {
		for (final String metadata : new String[] { //
			"name = \"not allowed\"\n", // unknown field
			"dependencies = \"appose\"\n", // not an array
			"requires-python = 3\n", // not a string
			"tool = 5\n", // not a table
			"[tool]\npixi = 1\n", // pixi not a table
			"dependencies = [\n", // not TOML
			// Unsupported by pixi run --script:
			"[tool.pixi.tasks]\nstart = \"python x.py\"\n",
			"[tool.pixi.workspace]\nname = \"x\"\n",
			"[tool.pixi.target.linux-64.tasks]\nstart = \"x\"\n",
			"[tool.pixi.target]\nlinux-64 = 1\n",
		})
		{
			assertThrows(metadata, IllegalArgumentException.class,
				() -> InlineMetadata.toPyProject(metadata, "x"));
		}
	}

	@Test
	public void testCondaPlatform() {
		assertTrue(InlineMetadata.condaPlatform().matches(
			"(linux|osx|win)-(64|32|arm64|aarch64|ppc64le)"));
	}

	private static JsonNode pyproject(final String metadata) throws Exception {
		return new TomlMapper().readTree(InlineMetadata.toPyProject(metadata,
			"my-env"));
	}
}
