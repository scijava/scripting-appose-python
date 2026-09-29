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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.apposed.appose.Appose;
import org.apposed.appose.Builder;
import org.junit.Test;

/**
 * Tests {@link LazyEnvironment} and {@link ResidentWorker}.
 * <p>
 * Uses the same requirements as {@code ApposePythonIntegrationTest}, which
 * require network access the first time the environment is built.
 * </p>
 *
 * @author Curtis Rueden
 */
public class ResidentWorkerTest {

	private static final String PID = "import os\ntask.outputs['pid'] = os.getpid()";

	@Test
	public void testWorkersShareEnvironment() throws Exception {
		final LazyEnvironment env = environment();
		final AtomicInteger builds = new AtomicInteger();
		final AtomicReference<Throwable> failure = new AtomicReference<>();
		env.addBuildListener(new BuildListener() {

			@Override
			public void buildStarted(final String envName) {
				builds.incrementAndGet();
			}

			@Override
			public void buildFinished(final String envName, final Throwable error) {
				failure.set(error);
			}
		});

		try (final ResidentWorker a = new ResidentWorker(env, e -> e.python());
			final ResidentWorker b = new ResidentWorker(env, e -> e.python()))
		{
			final int pidA = pid(a);
			final int pidB = pid(b);
			assertEquals(1, builds.get());
			assertNull(failure.get());
			assertNotEquals(pidA, pidB);

			// Each worker stays resident across tasks.
			assertEquals(pidA, pid(a));

			// A killed worker is replaced on its next task.
			a.kill();
			assertNotEquals(pidA, pid(a));
			assertEquals(1, builds.get());
		}
	}

	private static int pid(final ResidentWorker worker) throws Exception {
		return ((Number) worker.task(PID, null).waitFor().outputs.get("pid"))
			.intValue();
	}

	private static LazyEnvironment environment() throws Exception {
		final File file = new File(ResidentWorkerTest.class.getResource(
			"/appose-test/requirements.txt").toURI());
		final String content = new String(Files.readAllBytes(file.toPath()),
			StandardCharsets.UTF_8);
		final Builder<?> builder = Appose.content(content).scheme(
			"requirements.txt");
		return new LazyEnvironment("scripting-appose-python-test", builder);
	}
}
