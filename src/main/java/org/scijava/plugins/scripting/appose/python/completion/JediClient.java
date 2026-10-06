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

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import org.scijava.code.api.Completion;
import org.scijava.code.api.SignatureHelp;

/**
 * Asks Jedi for completions without ever making the caller wait long: the
 * editor calls completers on its UI thread, on every keystroke, while Jedi's
 * answers can take a while (its first for a script's environment, seconds;
 * its tooling environment, minutes to build).
 * <p>
 * Requests run one at a time, in the background, and their answers come as
 * futures. Answers are cached by a key, which callers choose so that
 * keystrokes within a word share one answer. A new query supersedes queued
 * ones that have not started.
 * </p>
 *
 * @author Gabriel Selzer
 */
public final class JediClient {

	/** What to ask Jedi. */
	public static final class Query {

		/** The source to analyze. */
		public final String source;

		/** The caret's line (1-based) in {@link #source}. */
		public final int line;

		/** The caret's column (0-based) in {@link #source}. */
		public final int column;

		/** The script's path, or null if unsaved. */
		public final String path;

		/**
		 * The Python executable of the script's environment, or null to analyze
		 * with Jedi's own interpreter (standard library only).
		 */
		public final String python;

		/**
		 * How many completions to give signatures, which are expensive (e.g.
		 * those of NumPy's many overloaded functions).
		 */
		public final int signatures;

		/**
		 * A directory of packages the environment lacks (see
		 * {@link DependencyOverlays}), to search too; or null.
		 */
		public final String overlay;

		public Query(final String source, final int line, final int column,
			final String path, final String python)
		{
			this(source, line, column, path, python, 0, null);
		}

		public Query(final String source, final int line, final int column,
			final String path, final String python, final int signatures,
			final String overlay)
		{
			this.source = source;
			this.line = line;
			this.column = column;
			this.path = path;
			this.python = python;
			this.signatures = signatures;
			this.overlay = overlay;
		}
	}

	/** Does the actual work: asks Jedi, e.g. by running a task on a worker. */
	public interface Backend {

		/** Gets completions for a query, remembering them by its key. */
		List<Completion> complete(String key, Query query);

		/** Gets the documentation of a completion of the query with this key. */
		String describe(String key, Query query, String name);

		/** Gets the signatures of the call around the query's caret. */
		default SignatureHelp signatures(final String key, final Query query) {
			return SignatureHelp.NONE;
		}
	}

	/** Number of answers to remember. */
	private static final int CACHE_SIZE = 32;

	/**
	 * How long an answer stays good, in milliseconds: long enough for typing
	 * a word, short enough to notice an environment that changed meanwhile
	 * (e.g. was rebuilt by running the script).
	 */
	private static final long ANSWER_LIFETIME = 20000;

	private final Backend backend;
	private final ExecutorService executor = Executors.newSingleThreadExecutor(
		r -> {
			final Thread t = new Thread(r, "appose-python-completion");
			t.setDaemon(true);
			return t;
		});

	/** Answers (or pending answers), by key, least recently used first. */
	private final Map<String, CompletableFuture<List<Completion>>> answers =
		new LinkedHashMap<String, CompletableFuture<List<Completion>>>(16, 0.75f,
			true)
		{

			@Override
			protected boolean removeEldestEntry(
				final Map.Entry<String, CompletableFuture<List<Completion>>> eldest)
			{
				return size() > CACHE_SIZE;
			}
		};

	private CompletableFuture<List<Completion>> latest;

	/** When each cached answer was asked for, by key. */
	private final Map<String, Long> askedAt = new HashMap<>();

	/**
	 * @param backend Asks Jedi; called on a background thread, one request at
	 *          a time.
	 */
	public JediClient(final Backend backend) {
		this.backend = backend;
	}

	/**
	 * @param completer Computes completions for a query; there is no
	 *          documentation.
	 */
	public JediClient(final Function<Query, List<Completion>> completer) {
		this(new Backend() {

			@Override
			public List<Completion> complete(final String key, final Query query) {
				return completer.apply(query);
			}

			@Override
			public String describe(final String key, final Query query,
				final String name)
			{
				return null;
			}
		});
	}

	/**
	 * Gets Jedi's completions for a query: the answer, or a pending one.
	 *
	 * @param key Identifies the answer: queries with the same key share one.
	 * @param query What to ask, should the answer not be known yet.
	 * @return The completions (or null, if Jedi has none), once known. Fails if
	 *         they could not be computed; the next request with the same key
	 *         then asks again.
	 */
	public CompletableFuture<List<Completion>> answer(final String key,
		final Query query)
	{
		synchronized (answers) {
			final CompletableFuture<List<Completion>> known = answers.get(key);
			final Long asked = askedAt.get(key);
			if (known != null && !known.isCancelled() && !known
				.isCompletedExceptionally() && asked != null && System
					.currentTimeMillis() - asked < ANSWER_LIFETIME) return known;
			// Supersede the previous query, unless it has started already: a
			// cancelled future's task is skipped when its turn comes.
			if (latest != null) latest.cancel(false);
			final CompletableFuture<List<Completion>> f = CompletableFuture
				.supplyAsync(() -> backend.complete(key, query), executor);
			answers.put(key, f);
			askedAt.keySet().retainAll(answers.keySet());
			askedAt.put(key, System.currentTimeMillis());
			latest = f;
			return f;
		}
	}

	/**
	 * Gets Jedi's completions for a query, if available within the time budget.
	 *
	 * @return The completions, or null if they are not available in time (or
	 *         could not be computed).
	 */
	public List<Completion> complete(final String key, final Query query,
		final long budgetMillis)
	{
		return await(answer(key, query), budgetMillis);
	}

	/**
	 * Gets the documentation of a completion, waiting at most the given time.
	 *
	 * @param key The key of the query that yielded the completion.
	 * @param query That query.
	 * @param name The completion's name.
	 * @return Its documentation, or null if none (or not available in time).
	 */
	public String describe(final String key, final Query query,
		final String name, final long budgetMillis)
	{
		return await(CompletableFuture.supplyAsync(() -> backend.describe(key,
			query, name), executor), budgetMillis);
	}

	/**
	 * Gets the signatures of the call around the query's caret (see
	 * {@link org.scijava.code.api.CodeCompleter#signatureHelp}), once known.
	 */
	public CompletableFuture<SignatureHelp> signatures(final String key,
		final Query query)
	{
		return CompletableFuture.supplyAsync(() -> backend.signatures(key, query),
			executor);
	}

	/** Stops the background thread. */
	public void dispose() {
		executor.shutdownNow();
	}

	private static <T> T await(final CompletableFuture<T> f,
		final long budgetMillis)
	{
		try {
			return f.get(budgetMillis, TimeUnit.MILLISECONDS);
		}
		catch (final TimeoutException | ExecutionException exc) {
			return null; // NB: Not (yet) available.
		}
		catch (final java.util.concurrent.CancellationException exc) {
			return null;
		}
		catch (final InterruptedException exc) {
			Thread.currentThread().interrupt();
			return null;
		}
	}
}
