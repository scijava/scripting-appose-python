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

package org.scijava.plugins.scripting.appose.python._internal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;

import org.apposed.appose.BuildException;
import org.apposed.appose.Builder;
import org.apposed.appose.Environment;
import org.apposed.appose.Service;

/**
 * An Appose worker that stays resident across tasks: its environment is
 * built on first use, its worker process is started once and reused, and
 * both are released only on request.
 * <p>
 * Keeping the worker alive is what makes repeated runs fast: modules the
 * worker has imported stay imported, and objects a task hands to
 * {@code task.export(...)} stay available to later tasks. The price is that
 * the worker's memory, GPU memory included, stays claimed until
 * {@link #release()} ends the process, which is the only reliable way to
 * free it.
 * </p>
 * <p>
 * If the worker dies (e.g. it crashes, or {@link #kill()} is called), the
 * next task transparently starts a new one.
 * </p>
 */
public class ResidentWorker implements AutoCloseable {

	private final String name;
	private final Builder<?> builder;
	private final Function<Environment, Service> launcher;
	private final List<BuildListener> listeners = new CopyOnWriteArrayList<>();
	private final Lock exclusive = new ReentrantLock();

	private volatile Consumer<String> debug;
	private Environment env;
	private Service service;

	/**
	 * @param name Name of the environment, as reported to listeners.
	 * @param builder Builder for the environment; built on first use.
	 * @param launcher Creates the worker's service from the built environment,
	 *          e.g. {@code env -> env.python().init("import numpy")}.
	 */
	public ResidentWorker(final String name, final Builder<?> builder,
		final Function<Environment, Service> launcher)
	{
		this.name = name;
		this.launcher = launcher;
		// Note: Subscribe once, here, and dispatch to the current listeners,
		// because builders accumulate subscribers with every call.
		this.builder = builder //
			.subscribeProgress((title, current, maximum) -> listeners.forEach(
				l -> l.buildProgress(name, title, current, maximum))) //
			.subscribeOutput(text -> listeners.forEach(l -> l.buildOutput(name,
				text))) //
			.subscribeError(text -> listeners.forEach(l -> l.buildError(name,
				text)));
	}

	public String name() {
		return name;
	}

	/**
	 * Gets a lock for callers needing exclusive use of the worker, e.g. to
	 * attribute its output to a single task. The worker's other methods do not
	 * take this lock, so it can be released or killed while someone holds it.
	 */
	public Lock exclusive() {
		return exclusive;
	}

	public ResidentWorker addBuildListener(final BuildListener listener) {
		listeners.add(listener);
		return this;
	}

	/**
	 * Sets where the worker's debug output goes: stderr lines, non-protocol
	 * stdout lines and protocol traffic, as described by
	 * {@link Service#debug}. May be changed at any time, including while the
	 * worker is running; {@code null} discards the output.
	 */
	public void debug(final Consumer<String> listener) {
		debug = listener;
	}

	/** Gets the environment, building it if this is its first use. */
	public synchronized Environment environment() throws BuildException {
		if (env != null) return env;
		listeners.forEach(l -> l.buildStarted(name));
		try {
			env = builder.build();
		}
		catch (final BuildException | RuntimeException exc) {
			listeners.forEach(l -> l.buildFinished(name, exc));
			throw exc;
		}
		listeners.forEach(l -> l.buildFinished(name, null));
		return env;
	}

	/**
	 * Gets the worker's service, building the environment and starting the
	 * worker process first if needed.
	 *
	 * @throws UncheckedIOException If the worker process fails to launch.
	 */
	public synchronized Service service() throws BuildException {
		if (service != null && service.isAlive()) return service;
		final Service s = launcher.apply(environment());
		s.debug(msg -> {
			final Consumer<String> listener = debug;
			if (listener != null) listener.accept(msg);
		});
		try {
			s.start();
		}
		catch (final IOException exc) {
			throw new UncheckedIOException(exc);
		}
		service = s;
		return service;
	}

	/** Creates a task to run on the resident worker. */
	public Service.Task task(final String script,
		final Map<String, Object> inputs) throws BuildException
	{
		return service().task(script, inputs);
	}

	/** Whether the worker process is currently running. */
	public synchronized boolean isAlive() {
		return service != null && service.isAlive();
	}

	/**
	 * Ends the worker process gently, letting pending tasks finish, and
	 * releasing everything it holds. The environment stays built.
	 */
	public synchronized void release() {
		if (service == null) return;
		service.close();
		service = null;
	}

	/** Ends the worker process immediately, interrupting any pending tasks. */
	public synchronized void kill() {
		if (service == null) return;
		service.kill();
		service = null;
	}

	@Override
	public void close() {
		release();
	}
}
