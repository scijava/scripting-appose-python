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

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TSTree;
import org.treesitter.TreeSitterPython;

/**
 * Syntactic analysis of a Python script at the caret, using tree-sitter: what
 * kind of completion the caret calls for, and which names are in scope there.
 * <p>
 * This needs no Python, so it works instantly, before (or without) any Appose
 * environment. It knows nothing of types, though: members of objects come
 * from jedi-language-server (see {@link ApposePythonLanguageServer}).
 * </p>
 *
 * @author Gabriel Selzer
 */
public final class PythonSyntax {

	/** Identifier marking the caret position in the parsed source. */
	static final String CARET = "__scijava_caret__";

	/** What the caret calls for. */
	public enum Kind {
			/** Nothing: the caret is in a comment, string or directive. */
			NONE,
			/** A name, e.g. a variable, function or keyword. */
			NAME,
			/** An attribute of the expression before a dot. */
			ATTRIBUTE,
			/** A module or name in an import statement. */
			IMPORT
	}

	/** The completion context at the caret. */
	public static final class Context {

		/** What the caret calls for. */
		public final Kind kind;

		/** The partial identifier being typed (possibly empty). */
		public final String prefix;

		/** Offset where the partial identifier starts. */
		public final int replaceStart;

		/** For {@link Kind#ATTRIBUTE}: the source of the expression, or null. */
		public final String receiver;

		/** Names in scope at the caret, innermost first. */
		public final Set<String> names;

		/**
		 * The types names in scope are annotated with, as written (e.g.
		 * {@code numpy.ndarray} for {@code image: numpy.ndarray}), by name.
		 */
		public final Map<String, String> types;

		/** For {@link Kind#ATTRIBUTE}: attributes used on the same receiver. */
		public final Set<String> attributesUsed;

		private Context(final Kind kind, final String prefix,
			final int replaceStart, final String receiver, final Set<String> names,
			final Map<String, String> types, final Set<String> attributesUsed)
		{
			this.kind = kind;
			this.prefix = prefix;
			this.replaceStart = replaceStart;
			this.receiver = receiver;
			this.names = names;
			this.types = types;
			this.attributesUsed = attributesUsed;
		}
	}

	/** Python keywords, including soft keywords. */
	public static final List<String> KEYWORDS = Arrays.asList("False", "None",
		"True", "and", "as", "assert", "async", "await", "break", "case", "class",
		"continue", "def", "del", "elif", "else", "except", "finally", "for",
		"from", "global", "if", "import", "in", "is", "lambda", "match",
		"nonlocal", "not", "or", "pass", "raise", "return", "try", "while",
		"with", "yield");

	/** Python's built-in functions, types and constants. */
	public static final List<String> BUILTINS = Arrays.asList("abs", "aiter",
		"all", "anext", "any", "ascii", "bin", "bool", "breakpoint", "bytearray",
		"bytes", "callable", "chr", "classmethod", "compile", "complex",
		"delattr", "dict", "dir", "divmod", "enumerate", "eval", "exec",
		"filter", "float", "format", "frozenset", "getattr", "globals",
		"hasattr", "hash", "help", "hex", "id", "input", "int", "isinstance",
		"issubclass", "iter", "len", "list", "locals", "map", "max",
		"memoryview", "min", "next", "object", "oct", "open", "ord", "pow",
		"print", "property", "range", "repr", "reversed", "round", "set",
		"setattr", "slice", "sorted", "staticmethod", "str", "sum", "super",
		"tuple", "type", "vars", "zip", "__name__", "__file__", "Ellipsis",
		"NotImplemented", "Exception", "BaseException", "ValueError",
		"TypeError", "KeyError", "IndexError", "RuntimeError", "AttributeError",
		"ImportError", "OSError", "FileNotFoundError", "StopIteration",
		"NotImplementedError", "ZeroDivisionError");

	private static final Pattern IMPORT_LINE = Pattern.compile(
		"^\\s*(from\\s+\\S*(\\s+import\\b.*)?|import\\s.*)$");

	/** Node types that open a new scope. */
	private static final Set<String> SCOPES = new LinkedHashSet<>(Arrays.asList(
		"function_definition", "class_definition", "lambda", "list_comprehension",
		"set_comprehension", "dictionary_comprehension", "generator_expression"));

	private PythonSyntax() {
		// NB: Prevent instantiation of utility class.
	}

	/** Analyzes the given script at the given caret offset. */
	public static Context analyze(final String text, final int caret) {
		final int lineStart = text.lastIndexOf('\n', caret - 1) + 1;
		final String line = text.substring(lineStart, caret);
		int start = caret;
		while (start > lineStart && isIdentifierPart(text.charAt(start - 1))) {
			start--;
		}
		final String prefix = text.substring(start, caret);
		final String before = text.substring(lineStart, start);
		final String textBefore = text.substring(0, start);
		final String closers = closers(textBefore);
		if (closers == null || line.trim().startsWith("#")) return none(prefix,
			start);
		if (!prefix.isEmpty() && Character.isDigit(prefix.charAt(0))) {
			return none(prefix, start);
		}

		final Kind kind;
		String receiver = null;
		if (IMPORT_LINE.matcher(before + prefix).matches()) kind = Kind.IMPORT;
		else if (before.trim().endsWith(".") && !before.trim().endsWith("..")) {
			receiver = receiver(before.substring(0, before.lastIndexOf('.')));
			if (receiver != null && receiver.matches("\\d+")) {
				return none(prefix, start); // a number, e.g. "1."
			}
			kind = Kind.ATTRIBUTE;
		}
		else kind = Kind.NAME;

		// Parse the script up to the caret, with the partial identifier replaced
		// by a placeholder, and any open brackets closed: a clean tree there.
		final String source = textBefore + CARET + closers;
		final Set<String> names = new LinkedHashSet<>();
		final Map<String, String> types = new HashMap<>();
		if (kind == Kind.NAME) namesInScope(source, textBefore.length(), names,
			types);
		final Set<String> used = kind == Kind.ATTRIBUTE && receiver != null
			? attributesUsed(text, receiver, start) : new LinkedHashSet<>();
		return new Context(kind, prefix, start, receiver, names, types, used);
	}

	// -- Helper methods --

	private static Context none(final String prefix, final int start) {
		return new Context(Kind.NONE, prefix, start, null, new LinkedHashSet<>(),
			new HashMap<>(), new LinkedHashSet<>());
	}

	/**
	 * Gets the closing brackets for those left open at the end of
	 * {@code text}, or null if the text ends inside a string or comment.
	 */
	static String closers(final String text) {
		final Deque<Character> open = new ArrayDeque<>();
		int i = 0;
		final int n = text.length();
		while (i < n) {
			final char c = text.charAt(i);
			if (c == '#') {
				final int end = text.indexOf('\n', i);
				if (end < 0) return null;
				i = end + 1;
			}
			else if (c == '\'' || c == '"') {
				final boolean triple = text.startsWith(String.valueOf(c) + c + c, i);
				final String quote = triple ? String.valueOf(c) + c + c : String
					.valueOf(c);
				int j = i + quote.length();
				while (true) {
					if (j >= n) return null;
					final char d = text.charAt(j);
					if (d == '\\') j += 2;
					else if (!triple && d == '\n') return null; // unterminated line
					else if (text.startsWith(quote, j)) break;
					else j++;
				}
				i = j + quote.length();
			}
			else {
				if (c == '(') open.push(')');
				else if (c == '[') open.push(']');
				else if (c == '{') open.push('}');
				else if ((c == ')' || c == ']' || c == '}') && !open.isEmpty() && open
					.peek() == c) open.pop();
				i++;
			}
		}
		final StringBuilder sb = new StringBuilder();
		for (final char c : open) sb.append(c);
		return sb.toString();
	}

	/**
	 * Gets the source of the expression ending the given text, e.g.
	 * {@code np.random} in {@code x = np.random}, or null if there is none.
	 */
	static String receiver(final String text) {
		int i = text.length();
		while (i > 0 && Character.isWhitespace(text.charAt(i - 1))) i--;
		final int end = i;
		int depth = 0;
		while (i > 0) {
			final char c = text.charAt(i - 1);
			if (c == ')' || c == ']' || c == '}') depth++;
			else if (c == '(' || c == '[' || c == '{') {
				if (depth == 0) break;
				depth--;
			}
			else if (depth == 0 && !isIdentifierPart(c) && c != '.') break;
			i--;
		}
		final String receiver = text.substring(i, end).trim();
		return receiver.isEmpty() ? null : receiver;
	}

	/** Attributes used on {@code receiver} anywhere in the script. */
	private static Set<String> attributesUsed(final String text,
		final String receiver, final int exclude)
	{
		final Set<String> used = new LinkedHashSet<>();
		// NB: Only spaces around the dot: "a.\nprint" is not an attribute.
		final Matcher m = Pattern.compile("(?<![\\w.])" + Pattern.quote(receiver) +
			"[ \\t]*\\.[ \\t]*([A-Za-z_]\\w*)").matcher(text);
		while (m.find()) {
			if (m.start(1) != exclude) used.add(m.group(1));
		}
		return used;
	}

	/**
	 * Finds the names in scope at the placeholder, which starts at
	 * {@code caret}, and the types they are annotated with.
	 */
	private static void namesInScope(final String source, final int caret,
		final Set<String> names, final Map<String, String> types)
	{
		final byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
		final int caretByte = source.substring(0, caret).getBytes(
			StandardCharsets.UTF_8).length;
		final TSParser parser = new TSParser();
		try {
			parser.setLanguage(new TreeSitterPython());
			final TSTree tree = parser.parseString(null, source);
			final TSNode root = tree.getRootNode();
			TSNode node = root.getNamedDescendantForByteRange(caretByte, caretByte +
				CARET.length());
			// Walk outward through the enclosing scopes, innermost first.
			boolean inFunction = false;
			for (; node != null && !node.isNull(); node = node.getParent()) {
				final String type = node.getType();
				if (!SCOPES.contains(type)) continue;
				// NB: Class bodies are not visible from their methods.
				if (type.equals("class_definition") && inFunction) continue;
				if (type.equals("function_definition") || type.equals("lambda")) {
					inFunction = true;
				}
				final TSNode params = node.getChildByFieldName("parameters");
				if (params != null && !params.isNull()) {
					addTargets(params, bytes, names);
				}
				final TSNode body = node.getChildByFieldName("body");
				if (type.endsWith("comprehension") || type.equals(
					"generator_expression"))
				{
					collectBindings(node, bytes, names, types, true);
				}
				else if (body != null && !body.isNull() && !type.equals("lambda")) {
					collectBindings(body, bytes, names, types, false);
				}
			}
			collectBindings(root, bytes, names, types, false);
		}
		catch (final RuntimeException | LinkageError exc) {
			// NB: Native parser unavailable or failed: no names, then.
		}
		finally {
			parser.close();
		}
		names.remove(CARET);
	}

	/**
	 * Collects the names bound by statements within {@code node}, without
	 * descending into nested scopes (whose own names are bound here, though);
	 * and the types annotated names are declared with.
	 */
	private static void collectBindings(final TSNode node, final byte[] bytes,
		final Set<String> names, final Map<String, String> types,
		final boolean comprehension)
	{
		final int n = node.getNamedChildCount();
		for (int i = 0; i < n; i++) {
			final TSNode child = node.getNamedChild(i);
			final String type = child.getType();
			switch (type) {
				case "function_definition":
				case "class_definition":
					addIdentifier(child.getChildByFieldName("name"), bytes, names);
					continue; // NB: A nested scope.
				case "lambda":
				case "list_comprehension":
				case "set_comprehension":
				case "dictionary_comprehension":
				case "generator_expression":
					if (!comprehension) continue; // NB: A nested scope.
					break;
				case "assignment":
					addType(child, bytes, types);
					addTargets(child.getChildByFieldName("left"), bytes, names);
					break;
				case "augmented_assignment":
				case "for_statement":
				case "for_in_clause":
					addTargets(child.getChildByFieldName("left"), bytes, names);
					break;
				case "named_expression":
					addIdentifier(child.getChildByFieldName("name"), bytes, names);
					break;
				case "as_pattern_target":
					addTargets(child, bytes, names);
					break;
				case "import_statement":
					addImported(child, bytes, names, true);
					continue;
				case "import_from_statement":
					addImported(child, bytes, names, false);
					continue;
				default:
			}
			collectBindings(child, bytes, names, types, comprehension);
		}
	}

	/** Adds the type of an annotated assignment, e.g. {@code x: int = 1}. */
	private static void addType(final TSNode assignment, final byte[] bytes,
		final Map<String, String> types)
	{
		final TSNode left = assignment.getChildByFieldName("left");
		final TSNode type = assignment.getChildByFieldName("type");
		if (left == null || left.isNull() || !left.getType().equals(
			"identifier") || type == null || type.isNull()) return;
		types.putIfAbsent(text(left, bytes), text(type, bytes));
	}

	/** Adds the names an import statement binds. */
	private static void addImported(final TSNode statement, final byte[] bytes,
		final Set<String> names, final boolean plainImport)
	{
		final int n = statement.getNamedChildCount();
		for (int i = 0; i < n; i++) {
			if (!"name".equals(statement.getFieldNameForNamedChild(i))) continue;
			final TSNode child = statement.getNamedChild(i);
			if (child.getType().equals("aliased_import")) {
				addIdentifier(child.getChildByFieldName("alias"), bytes, names);
			}
			else if (child.getType().equals("dotted_name")) {
				// "import a.b" binds a; "from x import y" binds y.
				final int count = child.getNamedChildCount();
				if (count > 0) addIdentifier(child.getNamedChild(plainImport ? 0
					: count - 1), bytes, names);
			}
		}
	}

	/**
	 * Adds the identifiers bound by an assignment target or parameter list,
	 * e.g. {@code a, (b, c)} or {@code x, y: int = 1, *args, **kwargs}, but not
	 * attributes or subscripts.
	 */
	private static void addTargets(final TSNode node, final byte[] bytes,
		final Set<String> names)
	{
		if (node == null || node.isNull()) return;
		switch (node.getType()) {
			case "identifier":
				addIdentifier(node, bytes, names);
				return;
			case "attribute":
			case "subscript":
			case "type":
				return;
			case "default_parameter":
			case "typed_default_parameter":
				addIdentifier(node.getChildByFieldName("name"), bytes, names);
				return;
			case "typed_parameter":
				// NB: Its name is its first child (e.g. x in "x: int").
				if (node.getNamedChildCount() > 0) addTargets(node.getNamedChild(0),
					bytes, names);
				return;
			default:
				for (int i = 0; i < node.getNamedChildCount(); i++) {
					addTargets(node.getNamedChild(i), bytes, names);
				}
		}
	}

	private static void addIdentifier(final TSNode node, final byte[] bytes,
		final Set<String> names)
	{
		if (node == null || node.isNull() || !node.getType().equals(
			"identifier")) return;
		names.add(text(node, bytes));
	}

	private static String text(final TSNode node, final byte[] bytes) {
		return new String(bytes, node.getStartByte(), node.getEndByte() - node
			.getStartByte(), StandardCharsets.UTF_8);
	}

	private static boolean isIdentifierPart(final char c) {
		return Character.isLetterOrDigit(c) || c == '_';
	}

	/** For tests: the names found by the given parse, as a list. */
	static List<String> names(final String text, final int caret) {
		return new ArrayList<>(analyze(text, caret).names);
	}
}
