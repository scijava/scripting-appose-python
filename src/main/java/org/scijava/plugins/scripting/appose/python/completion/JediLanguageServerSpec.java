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

import org.scijava.code.lsp.Environment;
import org.scijava.code.lsp.launch.LspConnection;
import org.scijava.code.lsp.launch.LspServerSpec;

/**
 * Starts jedi-language-server, with the Python of a tooling environment, to
 * analyze scripts against their environment's Python (if known) and extra
 * search paths (e.g. a dependency overlay).
 *
 * @author Gabriel Selzer
 */
public class JediLanguageServerSpec implements LspServerSpec {

	/**
	 * Runs jedi-language-server, exiting once the given process (this JVM) is
	 * gone. NB: The server does not notice by itself; on Windows, it then even
	 * loops, failing to write to the closed pipe.
	 */
	public static final String LAUNCHER = String.join("\n", //
		"import os, sys, threading, time", //
		"parent = int(sys.argv.pop(1))", //
		"def watch():", //
		"    if os.name == 'nt':", //
		"        import ctypes", //
		"        k = ctypes.windll.kernel32", //
		"        h = k.OpenProcess(0x00100000, False, parent)  # SYNCHRONIZE", //
		"        if h: k.WaitForSingleObject(h, 0xFFFFFFFF)", //
		"    else:", //
		"        while os.getppid() == parent: time.sleep(2)", //
		"    os._exit(0)", //
		"threading.Thread(target=watch, daemon=True).start()", //
		"from jedi_language_server.cli import cli", //
		"cli()");

	/** How large the error log may grow before it is started anew, in bytes. */
	private static final long MAX_LOG = 5 * 1024 * 1024;

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
	public LspConnection start(final Environment env)
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
		if (log.length() > MAX_LOG) log.delete();
		final ProcessBuilder pb = new ProcessBuilder(exe, "-c", LAUNCHER, String
			.valueOf(ProcessHandle.current().pid()));
		pb.redirectError(ProcessBuilder.Redirect.appendTo(log));
		return LspConnection.of(pb.start());
	}

	@Override
	public Object initializationOptions(final Environment env) {
		final Map<String, Object> workspaceOptions = new LinkedHashMap<>();
		// NB: Without an environment, Jedi uses the server's own Python.
		if (env.interpreter() != null) {
			workspaceOptions.put("environmentPath", env.interpreter());
		}
		workspaceOptions.put("extraPaths", env.paths());
		final Map<String, Object> options = new LinkedHashMap<>();
		options.put("workspace", workspaceOptions);
		// NB: Jedi's diagnostics are syntax errors, found as the script changes.
		final Map<String, Object> diagnostics = map("enable", true);
		diagnostics.put("didOpen", true);
		diagnostics.put("didChange", true);
		diagnostics.put("didSave", false);
		options.put("diagnostics", diagnostics);
		return options;
	}

	@Override
	public String rootUri(final Environment env) {
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
