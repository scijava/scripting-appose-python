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
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

import org.scijava.code.api.EnvironmentContext;
import org.scijava.code.lsp.LspConnection;
import org.scijava.code.lsp.LspServerSpec;

/**
 * Starts jedi-language-server, with the Python of a tooling environment, to
 * analyze scripts against their environment's Python (if known) and extra
 * search paths (e.g. a dependency overlay).
 *
 * @author Gabriel Selzer
 */
public class JediLanguageServerSpec implements LspServerSpec {

	private final Callable<String> python;
	private final File workspace;
	private final File log;

	/**
	 * @param python Gets the Python executable of the environment
	 *          jedi-language-server is installed in (building it, if needed).
	 * @param workspace An (empty) directory to serve as workspace root.
	 * @param log Where to append the server's error output.
	 */
	public JediLanguageServerSpec(final Callable<String> python,
		final File workspace, final File log)
	{
		this.python = python;
		this.workspace = workspace;
		this.log = log;
	}

	@Override
	public String languageId() {
		return "python";
	}

	@Override
	public LspConnection start(final EnvironmentContext env)
		throws IOException
	{
		final String exe;
		try {
			exe = python.call();
		}
		catch (final IOException exc) {
			throw exc;
		}
		catch (final Exception exc) {
			throw new IOException("No Python for jedi-language-server", exc);
		}
		if (exe == null) throw new IOException(
			"No Python for jedi-language-server");
		log.getParentFile().mkdirs();
		final ProcessBuilder pb = new ProcessBuilder(exe, "-c",
			"from jedi_language_server.cli import cli; cli()");
		pb.redirectError(ProcessBuilder.Redirect.appendTo(log));
		return LspConnection.of(pb.start());
	}

	@Override
	public Object initializationOptions(final EnvironmentContext env) {
		final Map<String, Object> workspaceOptions = new LinkedHashMap<>();
		// NB: Without an environment, Jedi uses the server's own Python.
		if (env.interpreter() != null) {
			workspaceOptions.put("environmentPath", env.interpreter());
		}
		workspaceOptions.put("extraPaths", env.searchPaths());
		final Map<String, Object> options = new LinkedHashMap<>();
		options.put("workspace", workspaceOptions);
		options.put("diagnostics", map("enable", false));
		return options;
	}

	@Override
	public String rootUri(final EnvironmentContext env) {
		// NB: jedi-language-server applies its workspace options only with a
		// root. An empty one, so that nothing irrelevant gets indexed.
		workspace.mkdirs();
		return workspace.toURI().toString();
	}

	private static Map<String, Object> map(final String key,
		final Object value)
	{
		final Map<String, Object> map = new LinkedHashMap<>();
		map.put(key, value);
		return map;
	}
}
