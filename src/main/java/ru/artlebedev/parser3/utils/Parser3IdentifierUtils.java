package ru.artlebedev.parser3.utils;

/**
 * Единые правила идентификаторов Parser3, используемые в индексах, completion и лексерах.
 *
 * Имена классов и переменных могут начинаться с цифры после сигила ($, ^) и в @CLASS/@BASE.
 * Дефис разрешён только во "внутренних" символах переменных/методов, где это уже поддержано исторически.
 */
public final class Parser3IdentifierUtils {

	public static final String NAME_REGEX = "[\\p{L}0-9_][\\p{L}0-9_]*";
	public static final String METHOD_NAME_REGEX = "[\\p{L}0-9_][\\p{L}0-9_-]*";

	private Parser3IdentifierUtils() {
	}

	public static boolean isIdentifierStart(char ch) {
		return Character.isLetterOrDigit(ch) || ch == '_';
	}

	public static boolean isIdentifierChar(char ch) {
		return Character.isLetterOrDigit(ch) || ch == '_';
	}

	public static boolean isVariableIdentifierChar(char ch) {
		return isIdentifierChar(ch) || ch == '-';
	}
}
