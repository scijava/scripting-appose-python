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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.scijava.app.StatusService;
import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.service.AbstractService;
import org.scijava.service.SciJavaService;
import org.scijava.service.Service;
import org.scijava.task.TaskService;

/**
 * Keeps one {@link ResidentWorker} per Appose environment for the lifetime of
 * the application context, showing environment builds as SciJava tasks, and
 * ending every worker when the context is disposed.
 */
@Plugin(type = Service.class)
public class ResidentWorkerService extends AbstractService implements
	SciJavaService
{

	@Parameter(required = false)
	private TaskService taskService;

	@Parameter(required = false)
	private StatusService statusService;

	@Parameter(required = false)
	private LogService log;

	/** Workers by environment name, each with the configuration it was made for. */
	private final Map<String, Entry> workers = new HashMap<>();

	/**
	 * Gets the resident worker for the named environment, creating it if
	 * needed.
	 * <p>
	 * If the environment's configuration has changed since its worker was
	 * created, as indicated by {@code config}, the old worker is released and
	 * replaced, so that the next run uses the updated environment.
	 * </p>
	 *
	 * @param name Name of the environment.
	 * @param config The environment's configuration, e.g. its file contents.
	 * @param factory Creates a worker for the environment.
	 */
	public ResidentWorker worker(final String name, final String config,
		final Supplier<ResidentWorker> factory)
	{
		final ResidentWorker stale;
		final ResidentWorker worker;
		synchronized (workers) {
			final Entry entry = workers.get(name);
			if (entry != null && entry.config.equals(config)) return entry.worker;
			stale = entry == null ? null : entry.worker;
			worker = factory.get();
			worker.addBuildListener(SciJavaTasks.buildListener(taskService,
				statusService, log));
			workers.put(name, new Entry(config, worker));
		}
		if (stale != null) stale.release();
		return worker;
	}

	/** Gets the current resident workers. */
	public List<ResidentWorker> workers() {
		final List<ResidentWorker> list = new ArrayList<>();
		synchronized (workers) {
			for (final Entry entry : workers.values()) list.add(entry.worker);
		}
		return list;
	}

	/**
	 * Ends every resident worker process, freeing the memory they hold. Each
	 * one starts afresh on its next task.
	 */
	public void releaseAll() {
		workers().forEach(ResidentWorker::release);
	}

	// -- Disposable methods --

	@Override
	public void dispose() {
		releaseAll();
		synchronized (workers) {
			workers.clear();
		}
	}

	// -- Helper classes --

	private static class Entry {

		private final String config;
		private final ResidentWorker worker;

		private Entry(final String config, final ResidentWorker worker) {
			this.config = config;
			this.worker = worker;
		}
	}
}
