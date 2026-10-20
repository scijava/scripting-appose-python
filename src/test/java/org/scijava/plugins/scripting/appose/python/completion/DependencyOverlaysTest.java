/*-
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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.Test;
import org.scijava.code.lsp.Environment;

/**
 * Tests {@link DependencyOverlays}.
 *
 * @author Gabriel Selzer
 */
public class DependencyOverlaysTest {

	private final AtomicLong now = new AtomicLong();
	private final List<List<String>> installs = new ArrayList<>();

	private final DependencyOverlays overlays = new DependencyOverlays((python,
		dependencies, dir) -> {
		synchronized (installs) {
			installs.add(dependencies);
		}
		return dir.getPath();
	}, new File("overlays"), 1500, now::get);

	@Test
	public void testWaitsForDependenciesToSettle() throws Exception {
		final List<String> deps = Arrays.asList("numpy", "scikit-ima");
		// Still being typed: nothing is installed...
		assertNull(overlays.overlay("blur", null, deps, false));
		now.addAndGet(500);
		assertNull(overlays.overlay("blur", null, deps, false));
		final List<String> typed = Arrays.asList("numpy", "scikit-image");
		assertNull(overlays.overlay("blur", null, typed, false));
		now.addAndGet(1000);
		assertNull(overlays.overlay("blur", null, typed, false));
		Thread.sleep(100);
		assertEquals(Collections.emptyList(), installs);
		// ...until the dependencies have stayed the same for a while.
		now.addAndGet(1000);
		assertNull(overlays.overlay("blur", null, typed, false));
		assertEquals(new File("overlays", "blur").getPath(), awaitOverlay("blur",
			null, typed));
		assertEquals(Collections.singletonList(typed), installs);
	}

	@Test
	public void testSettledAndReused() throws Exception {
		final List<String> deps = Collections.singletonList("tifffile");
		// Known not to be changing (e.g. just opened): installed at once.
		assertNull(overlays.overlay("read", "python", deps, true));
		final String overlay = awaitOverlay("read", "python", deps);
		assertEquals(new File("overlays", "read").getPath(), overlay);
		// Asked again: the same overlay, not installed again.
		assertEquals(overlay, overlays.overlay("read", "python", deps, false));
		assertEquals(1, installs.size());
		// Nothing declared: no overlay.
		assertNull(overlays.overlay("read", "python", Collections.emptyList(),
			true));
	}

	@Test
	public void testEnvironment() throws Exception {
		final Environment env = new Environment("/env/python", Collections
			.singletonList("/stubs"));
		// A script declaring a dependency, asked about for the first time (e.g.
		// just opened): its dependencies are settled, so the overlay is
		// installed; once ready, it is searched too.
		final String script = "# /// script\n# dependencies = [\"tifffile\"]\n" +
			"# ///\nimport tifffile\ntifffile.im";
		assertSame(env, overlays.environment(script, null, env));
		Environment overlaid = env;
		for (int i = 0; i < 100 && overlaid == env; i++) {
			Thread.sleep(20);
			overlaid = overlays.environment(script, null, env);
		}
		assertEquals(Collections.singletonList(Collections.singletonList(
			"tifffile")), installs);
		assertEquals("/env/python", overlaid.interpreter());
		assertEquals(Arrays.asList("/stubs", new File("overlays", "unsaved")
			.getPath()), overlaid.paths());
		// Nothing declared, or half-typed: as it is.
		final String b = new File("b.py").getPath();
		assertSame(env, overlays.environment("import os", b, env));
		assertSame(env, overlays.environment(
			"# /// script\n# dependencies = [\"tiff\n# ///\n", b, env));
		// A script typed from scratch: its dependencies, being typed, settle
		// first.
		assertSame(env, overlays.environment("# /// script\n" +
			"# dependencies = [\"n\"]\n# ///\n", b, env));
		Thread.sleep(100);
		assertEquals(1, installs.size());
	}

	@Test
	public void testNames() {
		// Untitled scripts share one overlay; files get one each.
		assertEquals("unsaved", DependencyOverlays.name(null));
		final String name = DependencyOverlays.name(new File("a", "my blur.py")
			.getPath());
		assertTrue(name.startsWith("my_blur.py-"));
		assertNotEquals(name, DependencyOverlays.name(new File("b", "my blur.py")
			.getPath()));
	}

	@Test
	public void testDistributions() {
		// As pip normalizes them (PEP 503), whatever their versions.
		assertEquals("scikit-image", DependencyOverlays.distribution(
			"scikit_image>=0.22"));
		assertEquals("numpy", DependencyOverlays.distribution("NumPy"));
		assertEquals("zope-interface", DependencyOverlays.distribution(
			" zope.interface ; python_version > '3'"));
		assertNull(DependencyOverlays.distribution(">=1"));
	}

	/** Waits for the overlay to be ready. */
	private String awaitOverlay(final String env, final String python,
		final List<String> deps) throws InterruptedException
	{
		for (int i = 0; i < 100; i++) {
			final String overlay = overlays.overlay(env, python, deps, false);
			if (overlay != null) return overlay;
			Thread.sleep(20);
		}
		return null;
	}
}
