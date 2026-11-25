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
import java.util.List;
import java.util.Map;

import net.imglib2.RandomAccessibleInterval;

import org.apposed.appose.NDArray;
import org.scijava.lsp.Environment;
import org.scijava.lsp.ScriptDialect;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.plugins.scripting.appose.python.ApposePythonScriptLanguage;
import org.scijava.plugins.scripting.appose.python._internal.ResidentWorkerService;
import org.scijava.script.ScriptLanguage;

/**
 * Describes how to parse Appose python scripts into plain python code and
 * report the environment where they run
 *
 * @author Gabriel Selzer
 */
@Plugin(type = ScriptDialect.class, name = "Appose-Python")
public class ApposePythonDialect implements ScriptDialect {

	/** The type of images, as Appose passes them. */
	private static final String NDARRAY = "numpy.ndarray";

	/**
	 * The names the header imports modules as: private, so that the header
	 * binds no name a script might use itself (e.g. {@code numpy}, which a
	 * script that does not import it cannot use).
	 */
	private static final String NUMPY = "_scijava_numpy",
			WORKER = "_scijava_python_worker";

	@Parameter
	private ResidentWorkerService workers;

	private ApposePythonEnvironments environments;

	@Override
	public boolean supports(final ScriptLanguage language) {
		return language instanceof ApposePythonScriptLanguage;
	}

	@Override
	public String outputLanguage() {
		return "python";
	}

	@Override
	public Environment environment(final String text, final File file) {
		// NB: As its #@script line, or its inline metadata, says.
		return environments().environment(text, file == null ? null : file
			.getPath());
	}

	@Override
	public Header header(final Map<String, Variable> variables) {
		final List<String> code = new ArrayList<>();
		final List<String> hidden = new ArrayList<>();
		// Each Appose-Python script has access to its task
		code.add("import appose.python_worker as " + WORKER);
		hidden.add(WORKER);
		// And numpy is imported for the server whenever an image parameter is
		// provided (the script itself does not get it)
		if (variables.values().stream().map(v -> parameterType(v.type()))
			.anyMatch(NDARRAY::equals))
		{
			code.add("import numpy as " + NUMPY);
			hidden.add(NUMPY);
		}
		// Declare the types of each parameter for type reasoning. NB: Assigned
		// (to ...), as well as annotated: some servers (e.g. ty) take a name that
		// is only annotated to be unbound, and complete none of its members.
		variables.forEach((name, v) -> {
			final String t = parameterType(v.type());
			// NB: Values Appose cannot pass are not declared.
			if (t == null) return;
			final String declared = NDARRAY.equals(t) ? NUMPY + ".ndarray" : t;
			code.add(name + ": " + declared + " = ...");
		});
		// Declare the type of task for type reasoning
		code.add("task: " + WORKER + ".Task = ...");
		// Combine into a Header
		return new Header(String.join("; ", code), hidden);
	}

	/**
	 * A {@link String} representation of the Python type corresponding to a
	 * (Java-based) SciJava script parameter type.
	 * <p>
	 * NOTE: This is a best-effort attempt at code that could likely belong
	 * elsewhere.
	 * </p>
	 * @param type the Java type (of a SciJava Script parameter) declared in the
	 *          script
	 * @return the Python type of the object given to the script by Appose, or
	 *         null if Appose cannot pass it
	 */
	static String parameterType(final Class<?> type) {
		if (RandomAccessibleInterval.class.isAssignableFrom(type) || NDArray.class
			.isAssignableFrom(type)) return NDARRAY;
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

	/** Gets where scripts run, creating it on first use. */
	private synchronized ApposePythonEnvironments environments() {
		if (environments == null) {
			environments = new ApposePythonEnvironments(workers);
		}
		return environments;
	}
}
