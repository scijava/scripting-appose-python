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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.CompletionList;
import org.scijava.code.lsp.LanguageServerService;
import org.scijava.code.lsp.jvm.ScriptLanguageServer;
import org.scijava.plugins.scripting.appose.python.completion.ApposePythonDialect;
import org.scijava.plugins.scripting.appose.python.completion.PythonSyntax;
import org.scijava.plugins.scripting.appose.python.completion.PythonSyntax.Kind;

/**
 * An in-process language server for Python scripts run via Appose, from the
 * syntax alone ({@link PythonSyntax}: tree-sitter): instantly, with no Python
 * needed, names in scope, the script's parameters, {@code task}, builtins and
 * keywords; and, after a dot, the names used after dots elsewhere in the
 * script. (A Python language server, e.g. jedi-language-server, knows more;
 * see {@link JediServerPlugin}.)
 *
 * @author Gabriel Selzer
 */
public class PythonSyntaxLanguageServer extends ScriptLanguageServer {

	static final ApposePythonDialect DIALECT = new ApposePythonDialect();

	private final LanguageServerService servers;

	/** @param servers Reads the scripts' parameters. */
	public PythonSyntaxLanguageServer(final LanguageServerService servers) {
		super(null);
		this.servers = servers;
	}

	@Override
	protected CompletionList complete(final Document doc, final int caret) {
		final PythonSyntax.Context ctx = PythonSyntax.analyze(doc.text(), caret);
		if (ctx.kind == Kind.NONE || ctx.kind == Kind.NAME && ctx.prefix
			.isEmpty())
		{
			return list(doc, caret, caret, new ArrayList<>());
		}
		return list(doc, ctx.replaceStart, caret, localCompletions(ctx, parameters(
			doc.text())));
	}

	/**
	 * The script's parameters, with the Python types they have when the script
	 * runs (null if unknown).
	 */
	private Map<String, String> parameters(final String text) {
		final Map<String, String> params = new LinkedHashMap<>();
		if (servers == null) return params;
		servers.scriptParameters(text).forEach((name, type) -> params.put(name,
			DIALECT.runtimeType(type)));
		return params;
	}

	private static CompletionItem item(final String name,
		final CompletionItemKind kind, final String detail)
	{
		final CompletionItem item = new CompletionItem(name);
		item.setKind(kind);
		item.setInsertText(name);
		item.setDetail(detail);
		return item;
	}

	/** Completions from the local (syntactic) analysis alone. */
	private static List<CompletionItem> localCompletions(
		final PythonSyntax.Context ctx, final Map<String, String> params)
	{
		final List<CompletionItem> out = new ArrayList<>();
		final String prefix = ctx.prefix;
		if (ctx.kind == Kind.ATTRIBUTE) {
			for (final String name : ctx.attributesUsed) {
				if (matches(name, prefix)) out.add(item(name, CompletionItemKind.Text,
					"Used elsewhere in this script"));
			}
			return out;
		}
		if (ctx.kind != Kind.NAME) return out;
		for (final String name : ctx.names) {
			if (matches(name, prefix)) out.add(item(name,
				CompletionItemKind.Variable, null));
		}
		for (final Map.Entry<String, String> p : params.entrySet()) {
			if (matches(p.getKey(), prefix)) out.add(item(p.getKey(),
				CompletionItemKind.Variable, p.getValue() == null ? "Script parameter"
					: p.getValue() + " (script parameter)"));
		}
		if (matches("task", prefix)) out.add(item("task",
			CompletionItemKind.Variable, ApposePythonDialect.TASK_TYPE));
		for (final String name : PythonSyntax.BUILTINS) {
			if (matches(name, prefix)) out.add(item(name, CompletionItemKind.Function,
				"builtin"));
		}
		for (final String keyword : PythonSyntax.KEYWORDS) {
			if (matches(keyword, prefix)) out.add(item(keyword,
				CompletionItemKind.Keyword, null));
		}
		return out;
	}

	private static boolean matches(final String name, final String prefix) {
		if (name.startsWith("__") && !prefix.startsWith("_")) return false;
		return name.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(
			Locale.ROOT)) && !name.equals(prefix);
	}
}
