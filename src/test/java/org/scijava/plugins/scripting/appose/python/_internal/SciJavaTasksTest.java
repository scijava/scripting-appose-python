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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.scijava.Context;
import org.scijava.event.EventHandler;
import org.scijava.event.EventService;
import org.scijava.log.LogLevel;
import org.scijava.log.LogMessage;
import org.scijava.task.Task;
import org.scijava.task.TaskService;
import org.scijava.task.event.TaskEvent;

/**
 * Tests {@link SciJavaTasks}.
 *
 * @author Curtis Rueden
 */
public class SciJavaTasksTest {

	private Context context;
	private final List<LogMessage> messages = new CopyOnWriteArrayList<>();
	private Task buildTask;

	@Before
	public void setUp() {
		context = new Context();
		context.service(EventService.class).subscribe(this);
	}

	@After
	public void tearDown() {
		context.dispose();
	}

	@EventHandler
	public void onEvent(final TaskEvent event) {
		if (buildTask != null) return;
		buildTask = event.getTask();
		buildTask.log().addLogListener(messages::add);
	}

	@Test
	public void testBuildOutputGoesToTaskLog() {
		final BuildListener listener = SciJavaTasks.buildListener(context.service(
			TaskService.class), null, null);
		listener.buildStarted("myenv");
		assertNotNull(buildTask);
		listener.buildOutput("myenv", "  resolving\n");
		listener.buildError("myenv", "installed\r\n");
		listener.buildProgress("myenv", "Downloading", 1, 2);
		final RuntimeException failure = new RuntimeException("no space left");
		listener.buildFinished("myenv", failure);

		assertTrue(buildTask.isDone());
		assertEquals(3, messages.size());
		// Note: Build tools write ordinary status to stderr, so it is not an error.
		assertEquals(LogLevel.INFO, messages.get(0).level());
		assertEquals("  resolving", messages.get(0).text());
		assertEquals(LogLevel.INFO, messages.get(1).level());
		assertEquals("installed", messages.get(1).text());
		assertEquals(LogLevel.ERROR, messages.get(2).level());
		assertSame(failure, messages.get(2).throwable());
	}
}
