package ru.artlebedev.parser3.utils;

import org.jetbrains.annotations.NotNull;

public final class Parser3TextUtils {
	private static final char[] ESCAPABLE = new char[] {
			'^', '$', ';', '@', '(', ')', '[', ']', '{', '}', '"', ':', '#'
	};

	private Parser3TextUtils() {}

	// все символы слева пробельные
	public static boolean isLeftWhitespace (String text, int startPosition) {
		int pos = startPosition;
		while (pos >= 0) {
			char s = text.charAt(pos);
			if (!Character.isWhitespace(s)) return false;
			pos--;
		}
		return true;
	}

	public static @NotNull String extractLeftSegment(@NotNull CharSequence text, int offset) {
		return extractLeftSegment(text, offset, null);
	}

	private static class ExtractResult {
		public final String str;
		public final int position;

		public ExtractResult(String str, int position) {
			this.str = str;
			this.position = position;
		}
	}


	public static @NotNull ExtractResult extractLeftSegmentMeta(@NotNull CharSequence text, int offset, Character symbol) {
		int i = offset - 1;
		int bracketDepth = 0;
		StringBuilder sb = new StringBuilder();
		while (i >= 0) {
			char c = text.charAt(i);
			if (bracketDepth > 0) {
				if (c == ']' && !isEscaped(text, i)) bracketDepth++;
				if (c == '[' && !isEscaped(text, i)) bracketDepth--;
				sb.append(c);
				i--;
				continue;
			}
			if (c == ']' && !isEscaped(text, i)) {
				bracketDepth = 1;
				sb.append(c);
				i--;
				continue;
			}
			if (symbol != null) {
				if (c == symbol && !isEscaped(text, i)) break;
			} else {
				if (Character.isWhitespace(c)) break;
			}
			sb.append(c);
			i--;
		}
		return new ExtractResult(sb.reverse().toString(), i);
	}

	// извлечение строки слева до символа или whitespace
	public static @NotNull String extractLeftSegment(@NotNull CharSequence text, int offset, Character symbol) {
		ExtractResult data = extractLeftSegmentMeta(text, offset, symbol);
		if (data == null) return "";
		return data.str;
	}

/*
	// текущая позиция в методе или переменной?
	public static @NotNull boolean inMethodOrVariable(@NotNull CharSequence text, int offset) {

		return false;
	}
*/

	public static boolean isEscaped(CharSequence text, int pos) {
		int count = 0;
		for (int i = pos - 1; i >= 0; i--) {
			if (text.charAt(i) == '^') count++;
			else break;
		}
		return (count % 2) == 1;
	}

	public static @NotNull String escapedString(@NotNull CharSequence text) {
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);

			if (c == '^' && i + 1 < text.length()) {
				char next = text.charAt(i + 1);
				if (isEscapable(next)) {
					out.append(next);
					i++;
					continue;
				}
			}

			out.append(c);
		}
		return out.toString();
	}

	private static boolean isEscapable(char c) {
		for (char e : ESCAPABLE) if (e == c) return true;
		return false;
	}
}