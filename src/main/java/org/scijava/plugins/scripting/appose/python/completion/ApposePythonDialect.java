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

import java.io.File;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import net.imglib2.RandomAccessibleInterval;

import org.apposed.appose.NDArray;
import org.scijava.code.lsp.Environment;
import org.scijava.code.lsp.ScriptDialect;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.plugins.scripting.appose.python.ApposePythonScriptLanguage;
import org.scijava.plugins.scripting.appose.python._internal.ResidentWorkerService;
import org.scijava.script.ScriptLanguage;

/**
 * Describes how to turn Appose python scripts into plain python code, and
 * where they run (see {@link ApposePythonEnvironments}).
 *
 * @author Gabriel Selzer
 */
@Plugin(type = ScriptDialect.class, name = "Appose-Python")
public class ApposePythonDialect implements ScriptDialect {

	/** The type of the {@code task} variable scripts can use. */
	private static final String TASK_TYPE = "appose.python_worker.Task";

	@Parameter(required = false)
	private ResidentWorkerService workers;

	private ApposePythonEnvironments environments;

	@Override
	public boolean supports(final ScriptLanguage language) {
		return language instanceof ApposePythonScriptLanguage;
	}

	@Override
	public String languageId() {
		return "python";
	}

	@Override
	public Environment environment(final String text, final File file) {
		// NB: As its #@script line, or its inline metadata, says.
		return environments().environment(text, file == null ? null : file
			.getPath());
	}

	@Override
	public String runtimeType(final Class<?> type) {
		if (type == null) return null;
		if (RandomAccessibleInterval.class.isAssignableFrom(type) || NDArray.class
			.isAssignableFrom(type)) return "numpy.ndarray";
		if (type == boolean.class || type == Boolean.class) return "bool";
		if (type == int.class || type == long.class || type == short.class ||
			type == byte.class || type == Integer.class || type == Long.class ||
			type == Short.class || type == Byte.class || type == BigInteger.class)
		{
			return "int";
		}
		if (type == float.class || type == double.class || type == Float.class ||
			type == Double.class || type == BigDecimal.class) return "float";
		if (CharSequence.class.isAssignableFrom(type) || type == char.class ||
			type == Character.class || type.isEnum() || type == File.class)
		{
			return "str";
		}
		if (type.isArray() || Collection.class.isAssignableFrom(type)) {
			return "list";
		}
		if (Map.class.isAssignableFrom(type)) return "dict";
		return null;
	}

	@Override
	public String declare(final String name, final String type) {
		return name + ": " + type;
	}

	@Override
	public List<String> imports(final Collection<String> types) {
		final List<String> imports = new ArrayList<>();
		imports.add("import appose.python_worker");
		if (types.contains("numpy.ndarray")) imports.add("import numpy");
		return imports;
	}

	@Override
	public String imported(final String statement) {
		// NB: "import a.b" binds a.
		return statement.startsWith("import ") ? statement.substring(7).split(
			"[.\\s]")[0] : null;
	}

	@Override
	public List<String> extraDeclarations() {
		return Collections.singletonList(declare("task", TASK_TYPE));
	}

	/** Gets where scripts run, creating it on first use. */
	private synchronized ApposePythonEnvironments environments() {
		if (environments == null) {
			environments = new ApposePythonEnvironments(workers);
		}
		return environments;
	}
}
