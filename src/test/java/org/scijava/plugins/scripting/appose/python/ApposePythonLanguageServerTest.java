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

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;
import org.junit.Before;
import org.junit.Test;
import org.scijava.code.lsp.Environment;

/**
 * Tests {@link ApposePythonLanguageServer}, with fake servers: how it combines
 * jedi and the syntax-only server, and moves scripts between jedis as where
 * they run changes.
 *
 * @author Gabriel Selzer
 */
public class ApposePythonLanguageServerTest {

	private static final String URI = "untitled:/a.py";

	/** The jedis started, in order. */
	private final List<Fake> jedis = new CopyOnWriteArrayList<>();
	private final List<PublishDiagnosticsParams> published =
		new CopyOnWriteArrayList<>();

	/** Where scripts run when their text does not say (e.g. once built). */
	private volatile String fallback = "A";

	private Fake syntax;
	private ApposePythonLanguageServer server;

	@Before
	public void setUp() {
		syntax = new Fake("syntax", "max", "min");
		server = server(env -> {
			final Fake jedi = new Fake("jedi:" + env.interpreter(), "max", "map");
			jedis.add(jedi);
			return jedi;
		});
	}

	@Test
	public void testRelocation() {
		open("x = 1");
		assertEquals(1, jedis.size());
		assertEquals(Collections.singletonList("open 1 x = 1"), jedis.get(0).events);

		// Its text says it runs elsewhere: it moves to a jedi there.
		change("#env=B\nx = 1", 2);
		assertEquals(Arrays.asList("open 1 x = 1", "close", "shutdown"), jedis.get(
			0).events);
		assertEquals("jedi:B", jedis.get(1).name);
		assertEquals(Collections.singletonList("open 2 #env=B\nx = 1"), jedis.get(
			1).events);
		// Otherwise, changes go to the same jedi.
		change("#env=B\nx = 2", 3);
		assertEquals("change 3 #env=B\nx = 2", jedis.get(1).events.get(1));
		assertEquals(2, jedis.size());

		// Where it runs changes without it changing (e.g. its environment was
		// built): nothing moves until told so.
		change("y = 1", 4);
		assertEquals("jedi:A", jedis.get(2).name);
		fallback = "C";
		assertEquals(3, jedis.size());
		server.relocateAll();
		assertEquals(Arrays.asList("open 4 y = 1", "close", "shutdown"), jedis.get(
			2).events);
		assertEquals("jedi:C", jedis.get(3).name);
		assertEquals(Collections.singletonList("open 4 y = 1"), jedis.get(3).events);

		// Closed: on the syntax server and its jedi, which is let go.
		server.didClose(new DidCloseTextDocumentParams(new TextDocumentIdentifier(
			URI)));
		assertEquals(Arrays.asList("open 4 y = 1", "close", "shutdown"), jedis.get(
			3).events);
		assertEquals("close", syntax.events.get(syntax.events.size() - 1));
	}

	@Test
	public void testCompletions() throws Exception {
		open("ma");
		final CompletionList list = complete();
		// Jedi's, then the syntax server's for names jedi did not complete; each
		// in its own order.
		assertEquals(Arrays.asList("map", "max", "min"), labels(list));

		// Resolved by the server each came from, with its own data.
		final CompletionItem min = list.getItems().get(2);
		final Object marker = min.getData();
		final CompletionItem resolved = server.resolveCompletionItem(min).get(10,
			TimeUnit.SECONDS);
		assertEquals("syntax resolved min (data syntax)", resolved.getDetail());
		assertEquals(marker, resolved.getData());
		assertEquals("jedi:A resolved map (data jedi:A)", server
			.resolveCompletionItem(list.getItems().get(0)).get(10, TimeUnit.SECONDS)
			.getDetail());
	}

	@Test
	public void testDiagnostics() {
		open("x = 1");
		final Fake a = jedis.get(0);
		// The syntax server's are not passed on (jedi's are the problems).
		syntax.publish(URI, 1, "syntax");
		a.publish(URI, 1, "from A");
		assertEquals(Collections.singletonList("from A"), messages());

		// Moved: the old jedi's are out of date.
		change("#env=B\nx = 1", 2);
		a.publish(URI, 1, "stale");
		jedis.get(1).publish(URI, 2, "from B");
		assertEquals(Arrays.asList("from A", "from B"), messages());
	}

	@Test
	public void testWithoutJedi() throws Exception {
		server = server(null);
		open("ma");
		// The syntax server's answers, as they are.
		final CompletionList list = complete();
		assertEquals(Arrays.asList("max", "min"), labels(list));
		assertEquals("syntax", list.getItems().get(0).getData());
		assertEquals("syntax resolved max (data syntax)", server
			.resolveCompletionItem(list.getItems().get(0)).get(10, TimeUnit.SECONDS)
			.getDetail());
		syntax.publish(URI, 1, "syntax");
		assertEquals(Collections.singletonList("syntax"), messages());
	}

	// -- Helper methods --

	private ApposePythonLanguageServer server(
		final Function<Environment, LanguageServer> jedi)
	{
		final ApposePythonLanguageServer s = new ApposePythonLanguageServer(syntax,
			doc -> new Environment(doc.getText().contains("#env=B") ? "B"
				: fallback, null, null, null), jedi, null);
		s.connect(new Client());
		s.initialize(new InitializeParams());
		return s;
	}

	private void open(final String text) {
		server.didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(URI,
			"appose-python", 1, text)));
	}

	private void change(final String text, final int version) {
		server.didChange(new DidChangeTextDocumentParams(
			new VersionedTextDocumentIdentifier(URI, version), Collections
				.singletonList(new TextDocumentContentChangeEvent(text))));
	}

	private CompletionList complete() throws Exception {
		return server.completion(new CompletionParams(new TextDocumentIdentifier(
			URI), new Position(0, 2))).get(10, TimeUnit.SECONDS).getRight();
	}

	private static List<String> labels(final CompletionList list) {
		return list.getItems().stream().map(CompletionItem::getLabel).collect(
			Collectors.toList());
	}

	private List<String> messages() {
		final List<String> out = new ArrayList<>();
		for (final PublishDiagnosticsParams p : published) {
			for (final Diagnostic d : p.getDiagnostics()) {
				out.add(d.getMessage().getLeft());
			}
		}
		return out;
	}

	private final class Client implements LanguageClient {

		@Override
		public void publishDiagnostics(final PublishDiagnosticsParams p) {
			published.add(p);
		}

		@Override
		public void telemetryEvent(final Object object) {}

		@Override
		public void showMessage(final MessageParams m) {}

		@Override
		public CompletableFuture<MessageActionItem> showMessageRequest(
			final ShowMessageRequestParams r)
		{
			return CompletableFuture.completedFuture(null);
		}

		@Override
		public void logMessage(final MessageParams m) {}
	}

	/** A fake server, recording what it is told, completing given labels. */
	private static final class Fake implements LanguageServer,
		TextDocumentService, WorkspaceService, LanguageClientAware
	{

		private final String name;
		private final List<String> labels;
		private final List<String> events = new CopyOnWriteArrayList<>();
		private LanguageClient client;

		private Fake(final String name, final String... labels) {
			this.name = name;
			this.labels = Arrays.asList(labels);
		}

		void publish(final String uri, final int version, final String message) {
			client.publishDiagnostics(new PublishDiagnosticsParams(uri,
				Collections.singletonList(new Diagnostic(new Range(new Position(0, 0),
					new Position(0, 1)), message)), version));
		}

		@Override
		public void connect(final LanguageClient c) {
			client = c;
		}

		@Override
		public CompletableFuture<InitializeResult> initialize(
			final InitializeParams params)
		{
			return CompletableFuture.completedFuture(new InitializeResult(
				new ServerCapabilities()));
		}

		@Override
		public CompletableFuture<Object> shutdown() {
			events.add("shutdown");
			return CompletableFuture.completedFuture(null);
		}

		@Override
		public void exit() {}

		@Override
		public TextDocumentService getTextDocumentService() {
			return this;
		}

		@Override
		public WorkspaceService getWorkspaceService() {
			return this;
		}

		@Override
		public void didOpen(final DidOpenTextDocumentParams p) {
			final TextDocumentItem d = p.getTextDocument();
			events.add("open " + d.getVersion() + " " + d.getText());
		}

		@Override
		public void didChange(final DidChangeTextDocumentParams p) {
			events.add("change " + p.getTextDocument().getVersion() + " " + p
				.getContentChanges().get(0).getText());
		}

		@Override
		public void didClose(final DidCloseTextDocumentParams p) {
			events.add("close");
		}

		@Override
		public void didSave(final DidSaveTextDocumentParams p) {}

		@Override
		public CompletableFuture<Either<List<CompletionItem>, CompletionList>>
			completion(final CompletionParams p)
		{
			final List<CompletionItem> items = new ArrayList<>();
			for (final String label : labels) {
				final CompletionItem item = new CompletionItem(label);
				item.setData(name);
				items.add(item);
			}
			return CompletableFuture.completedFuture(Either.forRight(
				new CompletionList(items)));
		}

		@Override
		public CompletableFuture<CompletionItem> resolveCompletionItem(
			final CompletionItem item)
		{
			item.setDetail(name + " resolved " + item.getLabel() + " (data " + item
				.getData() + ")");
			return CompletableFuture.completedFuture(item);
		}

		@Override
		public void didChangeConfiguration(final DidChangeConfigurationParams p) {}

		@Override
		public void didChangeWatchedFiles(final DidChangeWatchedFilesParams p) {}
	}
}
