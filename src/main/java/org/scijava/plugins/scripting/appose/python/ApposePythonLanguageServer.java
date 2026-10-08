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
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionOptions;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.SignatureHelp;
import org.eclipse.lsp4j.SignatureHelpOptions;
import org.eclipse.lsp4j.SignatureHelpParams;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;
import org.scijava.code.lsp.Environment;

/**
 * The language server of Python scripts run via Appose: the syntax-only
 * server (see {@link PythonSyntaxLanguageServer}), instant and needing no
 * Python; and, if offered, jedi-language-server, knowing more.
 * <ul>
 * <li>Completions: jedi's, then the syntax server's that complete other
 * names. (Without jedi, the syntax server's.)</li>
 * <li>Signature help and hover: jedi's, if any; otherwise the syntax
 * server's.</li>
 * <li>Problems: jedi's (the syntax server finds none).</li>
 * </ul>
 * <p>
 * Each script is analyzed by a jedi of where it runs: its environment's
 * Python, once built, and an overlay of the packages it declares but its
 * environment lacks (see {@link ApposePythonEnvironments}). As that changes
 * (as the script's metadata is edited, or its environment is built), the
 * script moves to another jedi.
 * </p>
 *
 * @author Gabriel Selzer
 */
public class ApposePythonLanguageServer implements LanguageServer,
	TextDocumentService, WorkspaceService, LanguageClientAware
{

	private final LanguageServer syntax;
	private final Function<TextDocumentItem, Environment> environment;
	private final Function<Environment, LanguageServer> jedi;
	private final ApposePythonEnvironments environments;
	private final Runnable relocateAll = this::relocateAll;

	/** The open documents, by URI. */
	private final Map<String, Doc> docs = new ConcurrentHashMap<>();

	private volatile LanguageClient client;

	/**
	 * @param syntax The syntax-only server.
	 * @param environment Gets where a script runs.
	 * @param jedi Makes a jedi for scripts running in an environment; or null,
	 *          if jedi is not offered.
	 * @param environments Tells when where scripts run may have changed without
	 *          them changing (e.g. as environments are built); or null.
	 */
	ApposePythonLanguageServer(final LanguageServer syntax,
		final Function<TextDocumentItem, Environment> environment,
		final Function<Environment, LanguageServer> jedi,
		final ApposePythonEnvironments environments)
	{
		this.syntax = syntax;
		this.environment = environment;
		this.jedi = jedi;
		this.environments = environments;
		if (syntax instanceof LanguageClientAware) {
			((LanguageClientAware) syntax).connect(new Forwarder(null));
		}
		if (jedi != null && environments != null) {
			environments.addListener(relocateAll);
		}
	}

	@Override
	public void connect(final LanguageClient c) {
		client = c;
	}

	// -- LanguageServer --

	@Override
	public CompletableFuture<InitializeResult> initialize(
		final InitializeParams params)
	{
		syntax.initialize(params).thenRun(() -> syntax.initialized(
			new InitializedParams()));
		final ServerCapabilities capabilities = new ServerCapabilities();
		capabilities.setTextDocumentSync(TextDocumentSyncKind.Full);
		capabilities.setCompletionProvider(new CompletionOptions(true,
			Collections.singletonList(".")));
		capabilities.setSignatureHelpProvider(new SignatureHelpOptions(Collections
			.singletonList("(")));
		capabilities.setHoverProvider(true);
		return CompletableFuture.completedFuture(new InitializeResult(
			capabilities));
	}

	@Override
	public CompletableFuture<Object> shutdown() {
		if (environments != null) environments.removeListener(relocateAll);
		for (final String uri : new ArrayList<>(docs.keySet())) {
			didClose(new DidCloseTextDocumentParams(new TextDocumentIdentifier(
				uri)));
		}
		return syntax.shutdown();
	}

	@Override
	public void exit() {
		syntax.exit();
	}

	@Override
	public TextDocumentService getTextDocumentService() {
		return this;
	}

	@Override
	public WorkspaceService getWorkspaceService() {
		return this;
	}

	// -- TextDocumentService: documents --

	@Override
	public void didOpen(final DidOpenTextDocumentParams params) {
		syntax.getTextDocumentService().didOpen(params);
		if (jedi == null) return;
		final Doc doc = new Doc(params.getTextDocument());
		docs.put(doc.item.getUri(), doc);
		synchronized (doc) {
			doc.environment = environment.apply(doc.item);
			doc.jedi = start(doc.environment);
			doc.jedi.getTextDocumentService().didOpen(params);
		}
	}

	@Override
	public void didChange(final DidChangeTextDocumentParams params) {
		syntax.getTextDocumentService().didChange(params);
		final Doc doc = docs.get(params.getTextDocument().getUri());
		final List<TextDocumentContentChangeEvent> changes = params
			.getContentChanges();
		if (doc == null || changes.isEmpty()) return;
		synchronized (doc) {
			// NB: Full sync: the last change is the whole text.
			doc.item = new TextDocumentItem(doc.item.getUri(), doc.item
				.getLanguageId(), params.getTextDocument().getVersion(), changes.get(
					changes.size() - 1).getText());
			if (!relocate(doc)) doc.jedi.getTextDocumentService().didChange(params);
		}
	}

	@Override
	public void didClose(final DidCloseTextDocumentParams params) {
		syntax.getTextDocumentService().didClose(params);
		final Doc doc = docs.remove(params.getTextDocument().getUri());
		if (doc == null) return;
		synchronized (doc) {
			stop(doc, params.getTextDocument().getUri());
		}
	}

	@Override
	public void didSave(final DidSaveTextDocumentParams params) {
		// NB: Changes come with didChange.
	}

	// -- TextDocumentService: requests --

	@Override
	public CompletableFuture<Either<List<CompletionItem>, CompletionList>>
		completion(final CompletionParams params)
	{
		final CompletableFuture<CompletionList> own = orNull(syntax
			.getTextDocumentService().completion(params)).thenApply(
				ApposePythonLanguageServer::list);
		final LanguageServer j = jedi(params.getTextDocument().getUri());
		if (j == null) return own.thenApply(Either::forRight);
		return orNull(j.getTextDocumentService().completion(params)).thenApply(
			ApposePythonLanguageServer::list).thenCombine(own, (theirs,
				mine) -> Either.forRight(merge(theirs, mine, params.getTextDocument()
					.getUri())));
	}

	@Override
	public CompletableFuture<CompletionItem> resolveCompletionItem(
		final CompletionItem item)
	{
		if (!(item.getData() instanceof Source)) {
			// NB: Not merged (e.g. without jedi): the syntax server's.
			return syntax.getTextDocumentService().resolveCompletionItem(item);
		}
		final Source from = (Source) item.getData();
		final LanguageServer server = from.jedi ? jedi(from.uri) : syntax;
		final String sortText = item.getSortText();
		item.setData(from.data);
		item.setSortText(from.sortText);
		if (server == null) {
			item.setData(from);
			item.setSortText(sortText);
			return CompletableFuture.completedFuture(item);
		}
		return orNull(server.getTextDocumentService().resolveCompletionItem(item))
			.thenApply(resolved -> {
				final CompletionItem out = resolved != null ? resolved : item;
				out.setData(from);
				out.setSortText(sortText);
				return out;
			});
	}

	@Override
	public CompletableFuture<SignatureHelp> signatureHelp(
		final SignatureHelpParams params)
	{
		return first(params.getTextDocument().getUri(), s -> s.signatureHelp(
			params), h -> h != null && h.getSignatures() != null && !h
				.getSignatures().isEmpty());
	}

	@Override
	public CompletableFuture<Hover> hover(final HoverParams params) {
		return first(params.getTextDocument().getUri(), s -> s.hover(params),
			h -> h != null && h.getContents() != null);
	}

	// -- WorkspaceService --

	@Override
	public void didChangeConfiguration(final DidChangeConfigurationParams p) {
		// NB: Nothing to configure.
	}

	@Override
	public void didChangeWatchedFiles(final DidChangeWatchedFilesParams p) {
		// NB: Nothing watched.
	}

	// -- Helper methods --

	/** The jedi analyzing a document, or null if none. */
	private LanguageServer jedi(final String uri) {
		final Doc doc = docs.get(uri);
		if (doc == null) return null;
		synchronized (doc) {
			return doc.jedi;
		}
	}

	/** Starts a jedi for scripts running in an environment. */
	private LanguageServer start(final Environment env) {
		final LanguageServer server = jedi.apply(env);
		if (server instanceof LanguageClientAware) {
			((LanguageClientAware) server).connect(new Forwarder(server));
		}
		server.initialize(new InitializeParams()).thenRun(() -> server
			.initialized(new InitializedParams()));
		return server;
	}

	/** Closes a document on its jedi, and lets that jedi go. */
	private static void stop(final Doc doc, final String uri) {
		doc.jedi.getTextDocumentService().didClose(new DidCloseTextDocumentParams(
			new TextDocumentIdentifier(uri)));
		doc.jedi.shutdown();
	}

	/**
	 * Moves a document to a jedi of where its script runs now, if that changed.
	 *
	 * @return Whether it moved (and the new jedi has its current text).
	 */
	private boolean relocate(final Doc doc) {
		final Environment env = environment.apply(doc.item);
		if (env.equals(doc.environment)) return false;
		// TODO: Settle how often this may happen while dependencies are edited
		// (debounce?).
		stop(doc, doc.item.getUri());
		doc.environment = env;
		doc.jedi = start(env);
		doc.jedi.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(
			new TextDocumentItem(doc.item.getUri(), doc.item.getLanguageId(), doc.item
				.getVersion(), doc.item.getText())));
		return true;
	}

	/**
	 * Moves each open document to a jedi of where its script runs now, if that
	 * changed without the script changing (e.g. its environment was built).
	 */
	void relocateAll() {
		for (final Doc doc : docs.values()) {
			synchronized (doc) {
				relocate(doc);
			}
		}
	}

	/** Asks jedi, then (if its answer is not accepted) the syntax server. */
	private <T> CompletableFuture<T> first(final String uri,
		final Function<TextDocumentService, CompletableFuture<T>> request,
		final java.util.function.Predicate<T> accepted)
	{
		final LanguageServer j = jedi(uri);
		final CompletableFuture<T> theirs = j == null ? CompletableFuture
			.completedFuture(null) : orNull(request.apply(j
				.getTextDocumentService()));
		return theirs.thenCompose(t -> accepted.test(t) ? CompletableFuture
			.completedFuture(t) : orNull(request.apply(syntax
				.getTextDocumentService())));
	}

	/**
	 * Jedi's completions, then the syntax server's whose names jedi has not
	 * completed, each in its own order. Each remembers whose it is, for
	 * resolving.
	 */
	private static CompletionList merge(final CompletionList theirs,
		final CompletionList own, final String uri)
	{
		final List<CompletionItem> out = new ArrayList<>();
		final Set<String> names = new HashSet<>();
		int i = 0;
		for (final CompletionItem item : sorted(theirs)) {
			names.add(name(item));
			mark(item, true, uri, i++);
			out.add(item);
		}
		for (final CompletionItem item : sorted(own)) {
			if (names.contains(name(item))) continue;
			mark(item, false, uri, i++);
			out.add(item);
		}
		final boolean incomplete = theirs != null && theirs.isIncomplete() ||
			own != null && own.isIncomplete();
		return new CompletionList(incomplete, out);
	}

	private static void mark(final CompletionItem item, final boolean fromJedi,
		final String uri, final int position)
	{
		item.setData(new Source(fromJedi, uri, item.getData(), item
			.getSortText()));
		item.setSortText(String.format("%d%06d", fromJedi ? 0 : 1, position));
	}

	private static List<CompletionItem> sorted(final CompletionList list) {
		final List<CompletionItem> items = new ArrayList<>();
		if (list != null && list.getItems() != null) items.addAll(list.getItems());
		items.sort(Comparator.comparing(c -> c.getSortText() != null ? c
			.getSortText() : c.getLabel()));
		return items;
	}

	/** What a completion completes: its label, without any parameters. */
	private static String name(final CompletionItem item) {
		final String label = item.getLabel();
		final int paren = label.indexOf('(');
		return (paren < 0 ? label : label.substring(0, paren)).trim();
	}

	private static CompletionList list(
		final Either<List<CompletionItem>, CompletionList> answer)
	{
		if (answer == null) return null;
		return answer.isRight() ? answer.getRight() : new CompletionList(answer
			.getLeft());
	}

	/** The answer; or null, if the server failed (e.g. it stopped). */
	private static <T> CompletableFuture<T> orNull(
		final CompletableFuture<T> answer)
	{
		return answer == null ? CompletableFuture.completedFuture(null) : answer
			.exceptionally(error -> null);
	}

	/** An open document, and the jedi analyzing it. */
	private static final class Doc {

		private TextDocumentItem item;
		private Environment environment;
		private LanguageServer jedi;

		private Doc(final TextDocumentItem item) {
			this.item = item;
		}
	}

	/** Remembers whose a merged completion is. */
	private static final class Source {

		private final boolean jedi;
		private final String uri;
		private final Object data;
		private final String sortText;

		private Source(final boolean jedi, final String uri, final Object data,
			final String sortText)
		{
			this.jedi = jedi;
			this.uri = uri;
			this.data = data;
			this.sortText = sortText;
		}
	}

	/**
	 * Passes problems on to the client: jedi's, from the jedi analyzing their
	 * document; or, without jedi, the syntax server's (none).
	 */
	private final class Forwarder implements LanguageClient {

		/** The jedi, or null for the syntax server. */
		private final LanguageServer source;

		private Forwarder(final LanguageServer source) {
			this.source = source;
		}

		@Override
		public void publishDiagnostics(final PublishDiagnosticsParams p) {
			final LanguageClient c = client;
			if (c == null) return;
			if (source == null) {
				if (jedi == null) c.publishDiagnostics(p);
				return;
			}
			// NB: Only from the jedi analyzing the document now.
			if (jedi(p.getUri()) == source) c.publishDiagnostics(p);
		}

		@Override
		public void telemetryEvent(final Object object) {}

		@Override
		public void showMessage(final MessageParams m) {
			final LanguageClient c = client;
			if (c != null) c.showMessage(m);
		}

		@Override
		public CompletableFuture<MessageActionItem> showMessageRequest(
			final ShowMessageRequestParams r)
		{
			return CompletableFuture.completedFuture(null);
		}

		@Override
		public void logMessage(final MessageParams m) {
			final LanguageClient c = client;
			if (c != null) c.logMessage(m);
		}
	}
}
