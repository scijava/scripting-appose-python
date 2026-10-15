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
 * A {@link LanguageServerPlugin} for Appose-Python scripts, backed by
 * jedi-language-server.
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
	public boolean supports(final String languageId) {
		// NB: Not "python", which this builds on. Without jedi, there is
		// nothing to serve.
		return "appose-python".equals(languageId) && jediOffered();
	}

	@Override
	public boolean supports(final ScriptLanguage language) {
		// NB: Without jedi, there is nothing to serve.
		return language instanceof ApposePythonScriptLanguage && jediOffered();
	}

	@Override
	public LanguageServer create(final String languageId,
		final Environment environment)
	{
		// NB: Where each script runs is as its text says (see
		// ApposePythonEnvironments), whatever the caller thinks; and may change
		// as it is edited, moving it to another Python server.
		return new ApposePythonLanguageServer(new RoutingLanguageServer(
			env -> servers.launch("python", env), ApposePythonEnvironments.of(
				workers)::environment), SIGNATURES);
	}

	@Override
	public ScriptDialect dialect() {
		return DIALECT;
	}

	/** Whether jedi-language-server (or another Python server) is offered. */
	boolean jediOffered() {
		return servers.supports("python");
	}
}
