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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apposed.appose.Service;
import org.apposed.appose.Service.ResponseType;
import org.scijava.app.StatusService;
import org.scijava.log.LogService;
import org.scijava.task.Task;
import org.scijava.task.TaskService;

/**
 * Presents Appose activity as SciJava {@link Task}s, so that it appears
 * wherever running things appear (the task list, the status bar) and can be
 * canceled from there.
 */
public final class SciJavaTasks {

	/**
	 * How long a canceled Appose task gets to stop on its own before its
	 * worker is stopped for it.
	 */
	public static final long CANCEL_GRACE_MILLIS = 3000;

	private SciJavaTasks() {
		// Prevent instantiation of utility class.
	}

	/**
	 * Creates a {@link BuildListener} that shows each environment build as a
	 * SciJava task of its own. The build tool's output goes to the task's
	 * logger, and to the application log at debug level.
	 * <p>
	 * A build gets its own task, rather than borrowing that of the run which
	 * needs it, because a build can take minutes: a run stuck at 0% for that
	 * long says the wrong thing about what is going on.
	 * </p>
	 *
	 * @param tasks Service for creating tasks; may be null.
	 * @param status Service for status bar updates; may be null.
	 * @param log Service for logging build output; may be null.
	 */
	public static BuildListener buildListener(final TaskService tasks,
		final StatusService status, final LogService log)
	{
		return new BuildListener() {

			private final Map<String, Task> building = new ConcurrentHashMap<>();

			@Override
			public void buildStarted(final String envName) {
				if (log != null) log.info("Checking Appose environment: " + envName);
				if (tasks == null) return;
				final Task task = tasks.createTask("Building environment: " +
					envName);
				task.setStatusMessage("Checking " + envName);
				task.start();
				building.put(envName, task);
			}

			@Override
			public void buildProgress(final String envName, final String title,
				final long current, final long maximum)
			{
				final Task task = building.get(envName);
				if (task != null) {
					task.setStatusMessage(title);
					task.setProgressValue(current);
					task.setProgressMaximum(maximum);
				}
				if (status != null) {
					status.showStatus((int) current, (int) maximum,
						"Building " + envName + ": " + title);
				}
			}

			@Override
			public void buildOutput(final String envName, final String text) {
				output(envName, text);
			}

			@Override
			public void buildError(final String envName, final String text) {
				// Note: Not error level! Build tools write ordinary status to stderr.
				output(envName, text);
			}

			private void output(final String envName, final String text) {
				final String line = chomp(text);
				if (log != null) log.debug(line);
				final Task task = building.get(envName);
				if (task != null) task.log().info(line);
			}

			@Override
			public void buildFinished(final String envName, final Throwable error) {
				final Task task = building.remove(envName);
				if (task != null) {
					if (error != null) {
						task.setStatusMessage("Failed: " + error.getMessage());
						task.log().error("Build failed", error);
					}
					task.finish();
				}
				if (status != null) status.clearStatus();
			}
		};
	}

	/**
	 * Creates a SciJava task that tracks the given Appose task: the Appose
	 * task's progress updates drive the SciJava task's status and progress,
	 * and canceling the SciJava task asks the Appose task to cancel.
	 * <p>
	 * Cancelation of an Appose task is cooperative: the script must check
	 * {@code task.cancel_requested} to notice it. Scripts that never check
	 * would be uncancelable, so if the Appose task has not stopped within
	 * {@link #CANCEL_GRACE_MILLIS}, {@code stopWorker} is invoked to end it the
	 * hard way.
	 * </p>
	 * <p>
	 * Must be called before the Appose task starts. The caller is responsible
	 * for calling {@link Task#finish()} on the returned task.
	 * </p>
	 *
	 * @param tasks Service for creating tasks; may be null.
	 * @param status Service for status bar updates; may be null.
	 * @param name Name of the SciJava task.
	 * @param apposeTask The Appose task to track.
	 * @param stopWorker Action forcibly ending the Appose task's worker; may be
	 *          null.
	 * @return The newly started SciJava task, or null if {@code tasks} is null.
	 */
	public static Task track(final TaskService tasks, final StatusService status,
		final String name, final Service.Task apposeTask,
		final Runnable stopWorker)
	{
		final AtomicBoolean finished = new AtomicBoolean();
		final Task task = tasks == null ? null : tasks.createTask(name);
		apposeTask.listen(event -> {
			if (event.responseType.isTerminal()) finished.set(true);
			if (event.responseType != ResponseType.UPDATE) return;
			if (task != null) {
				if (event.message != null) {
					task.setStatusMessage(event.message);
					task.log().info(event.message);
				}
				task.setProgressValue(event.current);
				task.setProgressMaximum(event.maximum);
			}
			if (status != null && event.maximum > 0) {
				status.showStatus((int) event.current, (int) event.maximum,
					event.message);
			}
		});
		if (task == null) return null;
		task.setCancelCallBack(() -> {
			task.setStatusMessage("Canceling");
			apposeTask.cancel();
			if (stopWorker == null) return;
			final Thread reaper = new Thread(() -> {
				try {
					TimeUnit.MILLISECONDS.sleep(CANCEL_GRACE_MILLIS);
				}
				catch (final InterruptedException exc) {
					Thread.currentThread().interrupt();
					return;
				}
				if (!finished.get()) stopWorker.run();
			}, "Appose-Cancel-" + apposeTask.uuid);
			reaper.setDaemon(true);
			reaper.start();
		});
		task.setStatusMessage("Running");
		task.start();
		return task;
	}

	/** Strips trailing line breaks, keeping any indentation. */
	private static String chomp(final String text) {
		return text.replaceAll("[\\r\\n]+$", "");
	}
}
