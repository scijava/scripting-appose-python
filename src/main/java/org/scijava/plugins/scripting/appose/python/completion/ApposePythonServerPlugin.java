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

import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.services.LanguageServer;
import org.scijava.Priority;
import org.scijava.code.lsp.Environment;
import org.scijava.code.lsp.LanguageServerPlugin;
import org.scijava.code.lsp.LanguageServerService;
import org.scijava.code.lsp.ScriptDialect;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.plugins.scripting.appose.python.ApposePythonScriptLanguage;
import org.scijava.plugins.scripting.appose.python._internal.ResidentWorkerService;
import org.scijava.script.ScriptLanguage;

/**
 * Offers the language server of Python scripts run via Appose (see
 * {@link ApposePythonLanguageServer}): jedi-language-server (see
 * {@link JediServerProcessPlugin}), for members of objects and modules,
 * signatures, documentation, hover and syntax errors. Once a script's
 * environment is built (by running the script), jedi analyzes the script
 * against it, so its packages are known; packages the script declares but its
 * environment lacks are installed for completion only (see
 * {@link DependencyOverlays}). Jedi sees the script as plain Python, its
 * parameters declared with the types they have when it runs (e.g. images as
 * NumPy arrays; see {@link #dialect()}).
 * <p>
 * Completion never builds a script's environment, and never disturbs its
 * worker.
 * </p>
 *
 * @author Gabriel Selzer
 */
@Plugin(type = LanguageServerPlugin.class, name = "appose-python",
	priority = Priority.HIGH)
public class ApposePythonServerPlugin implements LanguageServerPlugin {

	/** How many completions get signatures, once a word is begun. */
	private static final int SIGNATURES = 30;

	private static final ApposePythonDialect DIALECT = new ApposePythonDialect();

	@Parameter
	private LanguageServerService servers;

	@Parameter
	private ResidentWorkerService workers;

	@Override
	public boolean supports(final ScriptLanguage language) {
		// NB: Without jedi, there is nothing to serve.
		return language instanceof ApposePythonScriptLanguage && jediOffered();
	}

	@Override
	public LanguageServer create(final Environment environment) {
		return new ApposePythonLanguageServer(this::environment, this::jedi,
			ApposePythonEnvironments.of(workers));
	}

	@Override
	public ScriptDialect dialect() {
		return DIALECT;
	}

	/** Whether jedi-language-server is offered. */
	boolean jediOffered() {
		return servers.launch("python", Environment.NONE) != null;
	}

	/**
	 * Gets where a script runs, for jedi: its environment's Python (once built),
	 * and an overlay of the packages it declares but its environment lacks.
	 * On opening the script, its dependencies are settled.
	 */
	Environment environment(final TextDocumentItem document) {
		return ApposePythonEnvironments.of(workers).environment(document
			.getText(), document.getUri(), document.getVersion() <= 1);
	}

	/** Makes a jedi for scripts running in an environment. */
	private LanguageServer jedi(final Environment environment) {
		return new PythonCompletionServer(servers.launch("python", environment),
			SIGNATURES);
	}
}
