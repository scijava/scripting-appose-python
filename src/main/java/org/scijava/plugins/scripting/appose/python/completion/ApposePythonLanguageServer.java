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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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
 * The language server of Python scripts run via Appose: jedi-language-server,
 * analyzing each script against where it runs (its environment's Python, once
 * built, and an overlay of the packages it declares but its environment lacks;
 * see {@link ApposePythonEnvironments}).
 * <p>
 * Jedi learns where scripts run only when it starts, so each script is
 * analyzed by a jedi of its own environment. As that changes (as the script's
 * metadata is edited, or its environment is built), the script moves to
 * another jedi.
 * </p>
 *
 * @author Gabriel Selzer
 */
public class ApposePythonLanguageServer implements LanguageServer,
	TextDocumentService, WorkspaceService, LanguageClientAware
{

	private final Function<TextDocumentItem, Environment> environment;
	private final Function<Environment, LanguageServer> jedi;
	private final ApposePythonEnvironments environments;
	private final Runnable relocateAll = this::relocateAll;

	/** The open documents, by URI. */
	private final Map<String, Doc> docs = new ConcurrentHashMap<>();

	private volatile LanguageClient client;

	/**
	 * @param environment Gets where a script runs.
	 * @param jedi Makes a jedi for scripts running in an environment.
	 * @param environments Tells when where scripts run may have changed without
	 *          them changing (e.g. as environments are built); or null.
	 */
	ApposePythonLanguageServer(
		final Function<TextDocumentItem, Environment> environment,
		final Function<Environment, LanguageServer> jedi,
		final ApposePythonEnvironments environments)
	{
		this.environment = environment;
		this.jedi = jedi;
		this.environments = environments;
		if (environments != null) environments.addListener(relocateAll);
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
		return CompletableFuture.completedFuture(null);
	}

	@Override
	public void exit() {
		// NB: The jedis are let go as their documents close.
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
		final String uri = params.getTextDocument().getUri();
		final LanguageServer j = jedi(uri);
		if (j == null) return CompletableFuture.completedFuture(Either.forRight(
			new CompletionList()));
		return orNull(j.getTextDocumentService().completion(params)).thenApply(
			answer -> Either.forRight(mark(list(answer), uri)));
	}

	@Override
	public CompletableFuture<CompletionItem> resolveCompletionItem(
		final CompletionItem item)
	{
		if (!(item.getData() instanceof Source)) {
			return CompletableFuture.completedFuture(item);
		}
		final Source from = (Source) item.getData();
		final LanguageServer j = jedi(from.uri);
		if (j == null) return CompletableFuture.completedFuture(item);
		item.setData(from.data);
		return orNull(j.getTextDocumentService().resolveCompletionItem(item))
			.thenApply(resolved -> {
				final CompletionItem out = resolved != null ? resolved : item;
				out.setData(from);
				return out;
			});
	}

	@Override
	public CompletableFuture<SignatureHelp> signatureHelp(
		final SignatureHelpParams params)
	{
		final LanguageServer j = jedi(params.getTextDocument().getUri());
		return j == null ? CompletableFuture.completedFuture(null) : orNull(j
			.getTextDocumentService().signatureHelp(params));
	}

	@Override
	public CompletableFuture<Hover> hover(final HoverParams params) {
		final LanguageServer j = jedi(params.getTextDocument().getUri());
		return j == null ? CompletableFuture.completedFuture(null) : orNull(j
			.getTextDocumentService().hover(params));
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

	/**
	 * Marks each completion with its document, so that it is resolved by the
	 * jedi analyzing it.
	 */
	private static CompletionList mark(final CompletionList list,
		final String uri)
	{
		if (list == null) return new CompletionList();
		for (final CompletionItem item : list.getItems()) {
			item.setData(new Source(uri, item.getData()));
		}
		return list;
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

	/** Remembers which document a completion is of, for resolving. */
	private static final class Source {

		private final String uri;
		private final Object data;

		private Source(final String uri, final Object data) {
			this.uri = uri;
			this.data = data;
		}
	}

	/**
	 * Passes the problems the jedi analyzing their document finds on to the
	 * client; and its messages.
	 */
	private final class Forwarder implements LanguageClient {

		private final LanguageServer source;

		private Forwarder(final LanguageServer source) {
			this.source = source;
		}

		@Override
		public void publishDiagnostics(final PublishDiagnosticsParams p) {
			final LanguageClient c = client;
			// NB: Only from the jedi analyzing the document now.
			if (c != null && jedi(p.getUri()) == source) c.publishDiagnostics(p);
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
