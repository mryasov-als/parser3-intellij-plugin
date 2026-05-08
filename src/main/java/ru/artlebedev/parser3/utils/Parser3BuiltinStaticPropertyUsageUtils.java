package ru.artlebedev.parser3.utils;

import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Собирает локально использованные свойства встроенных классов из текста текущего файла.
 *
 * Нужен для completion в контекстах вроде:
 * - $form:user
 * - ^form:user.bool(false)
 * - $form:[action.x]
 */
public final class Parser3BuiltinStaticPropertyUsageUtils {

	private Parser3BuiltinStaticPropertyUsageUtils() {
	}

	public static @NotNull String getLocalPropertyTypeText(@NotNull String className) {
		if ("form".equals(className)) {
			return "string";
		}
		return "local";
	}

	public static @NotNull Set<String> collectPropertyNames(
			@NotNull CharSequence text,
			@NotNull String className
	) {
		return collectPropertyNames(text, className, -1);
	}

	public static @NotNull Set<String> collectPropertyNames(
			@NotNull CharSequence text,
			@NotNull String className,
			int cursorOffset
	) {
		LinkedHashSet<String> result = new LinkedHashSet<>();
		String dollarPrefix = "$" + className + ":";
		String caretPrefix = "^" + className + ":";

		for (int i = 0; i < text.length(); i++) {
			char ch = text.charAt(i);
			String prefix;
			if (ch == '$') {
				prefix = dollarPrefix;
			} else if (ch == '^') {
				prefix = caretPrefix;
			} else {
				continue;
			}

			if (!startsWith(text, i, prefix)) {
				continue;
			}

			int propertyStart = i + prefix.length();
			String propertyName = readPropertyName(text, propertyStart);
			if (propertyName == null || propertyName.isEmpty()) {
				continue;
			}

			int propertyEnd = propertyStart + propertyName.length();
			if (!hasCompletedPropertyTerminator(text, propertyEnd)) {
				continue;
			}
			if (isCurrentPropertyOccurrence(text, propertyStart, propertyEnd, cursorOffset)) {
				continue;
			}

			result.add(propertyName);
			i = propertyEnd - 1;
		}

		return result;
	}

	private static boolean startsWith(
			@NotNull CharSequence text,
			int offset,
			@NotNull String prefix
	) {
		if (offset + prefix.length() > text.length()) {
			return false;
		}
		for (int i = 0; i < prefix.length(); i++) {
			if (text.charAt(offset + i) != prefix.charAt(i)) {
				return false;
			}
		}
		return true;
	}

	private static String readPropertyName(@NotNull CharSequence text, int offset) {
		if (offset >= text.length()) {
			return null;
		}

		char first = text.charAt(offset);
		if (first == '[') {
			int closingBracket = findClosingBracket(text, offset);
			if (closingBracket <= offset + 1) {
				return null;
			}
			return text.subSequence(offset, closingBracket + 1).toString();
		}

		if (!Parser3IdentifierUtils.isVariableIdentifierChar(first)) {
			return null;
		}

		int end = offset + 1;
		while (end < text.length() && Parser3IdentifierUtils.isVariableIdentifierChar(text.charAt(end))) {
			end++;
		}
		return text.subSequence(offset, end).toString();
	}

	private static int findClosingBracket(@NotNull CharSequence text, int offset) {
		int depth = 0;
		for (int i = offset; i < text.length(); i++) {
			char ch = text.charAt(i);
			if (ch == '[') {
				depth++;
				continue;
			}
			if (ch == ']') {
				depth--;
				if (depth == 0) {
					return i;
				}
			}
		}
		return -1;
	}

	private static boolean hasCompletedPropertyTerminator(@NotNull CharSequence text, int offset) {
		if (offset >= text.length()) {
			return false;
		}
		char nextChar = text.charAt(offset);
		if (Character.isWhitespace(nextChar)) {
			return true;
		}
		return Parser3VariableTailUtils.isTailStopChar(nextChar);
	}

	private static boolean isCurrentPropertyOccurrence(
			@NotNull CharSequence text,
			int propertyStart,
			int propertyEnd,
			int cursorOffset
	) {
		if (cursorOffset < propertyStart || cursorOffset > propertyEnd) {
			return false;
		}
		if (cursorOffset < text.length()) {
			char chAtCursor = text.charAt(cursorOffset);
			if (Parser3IdentifierUtils.isVariableIdentifierChar(chAtCursor) || chAtCursor == '[') {
				return true;
			}
		}
		if (cursorOffset > propertyStart) {
			char beforeCursor = text.charAt(cursorOffset - 1);
			if (Parser3IdentifierUtils.isVariableIdentifierChar(beforeCursor) || beforeCursor == ']') {
				return true;
			}
		}
		return false;
	}
}
