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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.Test;

/**
 * Tests that {@link ApposePythonEnvironments} builds the environments scripts
 * declare once they settle (with a fake build: nothing is built).
 *
 * @author Gabriel Selzer
 */
public class ApposePythonEnvironmentsTest {

	/** The configurations built, in order. */
	private final List<String> built = new CopyOnWriteArrayList<>();

	private final ApposePythonEnvironments environments =
		new ApposePythonEnvironments(null, 200, env -> built.add(env.config()));

	@Test
	public void testBuildsOnceSettled() throws Exception {
		// Being typed: not built...
		where(script("nu"));
		Thread.sleep(100);
		where(script("numpy"));
		Thread.sleep(100);
		assertTrue(built.isEmpty());
		// ...until it has stayed the same for a while: then once.
		Thread.sleep(400);
		assertEquals(1, built.size());
		assertTrue(built.get(0), built.get(0).contains("numpy"));
		where(script("numpy"));
		Thread.sleep(400);
		assertEquals(1, built.size());
		// Changed, then changed back: built again.
		where(script("scipy"));
		Thread.sleep(400);
		where(script("numpy"));
		Thread.sleep(400);
		assertEquals(3, built.size());
	}

	@Test
	public void testNothingDeclared() throws Exception {
		where("import os\nos.pa");
		Thread.sleep(400);
		assertTrue(built.isEmpty());
	}

	// -- Helper methods --

	/** Asks where an unsaved script runs. */
	private void where(final String script) {
		environments.environment(script, null);
	}

	/** An unsaved script declaring a dependency, in its inline metadata. */
	private static String script(final String dependency) {
		return "# /// script\n# dependencies = [\"" + dependency + "\"]\n" +
			"# ///\nimport os\n";
	}
}
