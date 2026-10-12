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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;
import org.scijava.plugins.scripting.appose.python.completion.PythonSyntaxLanguageServer.Context;
import org.scijava.plugins.scripting.appose.python.completion.PythonSyntaxLanguageServer.Kind;

/**
 * Tests how {@link PythonSyntaxLanguageServer} analyzes scripts.
 *
 * @author Gabriel Selzer
 */
public class PythonSyntaxLanguageServerTest {

	@Test
	public void testNamesInScope() {
		final String code = "import numpy as np, os.path\n" + //
			"from os import path as p, sep\n" + //
			"x = 1\n" + //
			"def f(a, b=2, *args, c: int = 3, **kw):\n" + //
			"    loc = 3\n" + //
			"    lo";
		final List<String> names = PythonSyntaxLanguageServer.names(code, code.length());
		for (final String name : new String[] { "loc", "a", "b", "args", "c", "kw",
			"f", "x", "np", "os", "p", "sep" })
		{
			assertTrue(name + " in " + names, names.contains(name));
		}
		// Innermost scope first.
		assertTrue(names.indexOf("loc") < names.indexOf("x"));
		assertFalse(names.contains(PythonSyntaxLanguageServer.CARET));
	}

	@Test
	public void testScopeRules() {
		// Class bodies are not visible from their methods.
		final String cls = "class C:\n    attr = 1\n    def m(self):\n        at";
		final List<String> inMethod = PythonSyntaxLanguageServer.names(cls, cls.length());
		assertTrue(inMethod.contains("self"));
		assertTrue(inMethod.contains("C"));
		assertFalse(inMethod.contains("attr"));
		// Functions' locals are not visible outside them.
		final String fn = "def g():\n    inner = 1\nin";
		assertFalse(PythonSyntaxLanguageServer.names(fn, fn.length()).contains("inner"));
		// Comprehension, with, except and walrus targets.
		final String comp = "[val for val in range(3) if va";
		assertTrue(PythonSyntaxLanguageServer.names(comp, comp.length()).contains("val"));
		final String blocks = "with open(f) as fh:\n    try:\n        pass\n" +
			"    except E as err:\n        if (n := 2):\n            e";
		final List<String> names = PythonSyntaxLanguageServer.names(blocks, blocks.length());
		assertTrue(names.toString(), names.contains("fh") && names.contains(
			"err") && names.contains("n"));
		// Unclosed brackets before the caret.
		final String open = "def h(alpha):\n    print(1, [2, al";
		assertTrue(PythonSyntaxLanguageServer.names(open, open.length()).contains("alpha"));
	}

	@Test
	public void testAttributeContext() {
		final String code = "import numpy as np\nnp.random.ra";
		final Context ctx = PythonSyntaxLanguageServer.analyze(code, code.length());
		assertEquals(Kind.ATTRIBUTE, ctx.kind);
		assertEquals("np.random", ctx.receiver);
		assertEquals("ra", ctx.prefix);
		assertEquals(code.length() - 2, ctx.replaceStart);
		assertEquals("foo(x)[0]", PythonSyntaxLanguageServer.analyze("y = foo(x)[0].", 14)
			.receiver);
		// Attributes used elsewhere on the same receiver.
		final String img = "img.shape\nimg.dtype\nmyimg.size\nimg.sh";
		final Context used = PythonSyntaxLanguageServer.analyze(img, img.length());
		assertTrue(used.attributesUsed.contains("shape"));
		assertTrue(used.attributesUsed.contains("dtype"));
		assertFalse(used.attributesUsed.contains("size"));
		// A dot ending a line is not followed by an attribute on the next one.
		final String nextLine = "a = numpy.ndarray(3)\na.\nprint(a.shape)\n";
		final Context dot = PythonSyntaxLanguageServer.analyze(nextLine, nextLine.indexOf(
			"a.\n") + 2);
		assertTrue(dot.attributesUsed.contains("shape"));
		assertFalse(dot.attributesUsed.toString(), dot.attributesUsed.contains(
			"print"));
	}

	@Test
	public void testNoCompletion() {
		for (final String code : new String[] { "s = 'np.ra", "# np.ra",
			"x = 1  # np.", "d = \"\"\"doc\nmore np.", "#@ String na", "x = 1.",
			"f\"{x}.y" })
		{
			assertEquals(code, Kind.NONE, PythonSyntaxLanguageServer.analyze(code, code.length())
				.kind);
		}
		// But after a closed string, completion works again.
		final String closed = "s = 'a.b'\nx = s.up";
		assertEquals(Kind.ATTRIBUTE, PythonSyntaxLanguageServer.analyze(closed, closed.length())
			.kind);
	}

	@Test
	public void testImports() {
		assertEquals(Kind.IMPORT, PythonSyntaxLanguageServer.analyze("import nu", 9).kind);
		assertEquals(Kind.IMPORT, PythonSyntaxLanguageServer.analyze("from scipy.nd", 13).kind);
		assertEquals(Kind.IMPORT, PythonSyntaxLanguageServer.analyze(
			"from scipy import ndim", 22).kind);
	}

	@Test
	public void testUnicode() {
		final String code = "s = '\u00e9t\u00e9'\nvaleur = 1\nval";
		assertTrue(PythonSyntaxLanguageServer.names(code, code.length()).contains("valeur"));
	}
}
