package ru.artlebedev.parser3.lexer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Общие утилиты для тестов лексера Parser3.
 * Позволяет загружать тестовые файлы и ожидаемые токены из ресурсов,
 * а также проверять результаты токенизации.
 */
public final class LexerTestUtils {

	private LexerTestUtils() {
	}

	/**
	 * Структура для хранения ожидаемого токена.
	 */
	public static class ExpectedToken {
		public final int start;
		public final int end;
		public final String type;

		public ExpectedToken(int start, int end, String type) {
			this.start = start;
			this.end = end;
			this.type = type;
		}
	}

	/**
	 * Загружает текстовый файл из ресурсов.
	 *
	 * @param resourcePath путь к ресурсу (например "/lexer_test_input.p")
	 * @param clazz класс для загрузки ресурса
	 * @return содержимое файла
	 */
	public static String loadResource(String resourcePath, Class<?> clazz) throws IOException {
		try (InputStream is = clazz.getResourceAsStream(resourcePath)) {
			if (is == null) {
				throw new IOException("Файл " + resourcePath + " не найден в ресурсах");
			}
			return new String(is.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	/**
	 * Загружает ожидаемые токены из файла.
	 * Формат: start,end,type (по одной строке).
	 * Строки начинающиеся с # — комментарии.
	 *
	 * @param resourcePath путь к ресурсу (например "/expected_tokens.txt")
	 * @param clazz класс для загрузки ресурса
	 * @return список ожидаемых токенов
	 */
	public static List<ExpectedToken> loadExpectedTokens(String resourcePath, Class<?> clazz) throws IOException {
		List<ExpectedToken> tokens = new ArrayList<>();
		try (InputStream is = clazz.getResourceAsStream(resourcePath);
			 BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {

			if (is == null) {
				throw new IOException("Файл " + resourcePath + " не найден. Запустите генератор токенов.");
			}

			String line;
			while ((line = reader.readLine()) != null) {
				line = line.trim();
				// Пропускаем комментарии и пустые строки
				if (line.isEmpty() || line.startsWith("#")) {
					continue;
				}

				String[] parts = line.split(",", 3);
				if (parts.length == 3) {
					int start = Integer.parseInt(parts[0]);
					int end = Integer.parseInt(parts[1]);
					String type = parts[2];
					tokens.add(new ExpectedToken(start, end, type));
				}
			}
		}
		return tokens;
	}

	/**
	 * Проверяет что токены совпадают с ожидаемыми.
	 *
	 * @param input исходный текст (для вывода диагностики)
	 * @param actualTokens реальные токены
	 * @param expectedTokens ожидаемые токены
	 */
	public static void assertTokensMatch(
			String input,
			List<Parser3LexerCore.CoreToken> actualTokens,
			List<ExpectedToken> expectedTokens
	) {
		// Проверяем количество токенов
		assertEquals("Количество токенов должно совпадать",
				expectedTokens.size(), actualTokens.size());

		// Проверяем каждый токен
		for (int i = 0; i < actualTokens.size(); i++) {
			Parser3LexerCore.CoreToken actual = actualTokens.get(i);
			ExpectedToken expected = expectedTokens.get(i);

			String tokenText = input.substring(actual.start, Math.min(actual.end, actual.start + 30))
					.replace("\n", "\\n")
					.replace("\t", "\\t");

			assertEquals("Токен " + i + ": неверный start (текст: '" + tokenText + "')",
					expected.start, actual.start);
			assertEquals("Токен " + i + ": неверный end (текст: '" + tokenText + "')",
					expected.end, actual.end);
			assertEquals("Токен " + i + " [" + expected.start + "," + expected.end + "]: неверный type (текст: '" + tokenText + "')",
					expected.type, actual.type);
		}
	}

	/**
	 * Проверяет что токены покрывают весь текст без пропусков.
	 *
	 * @param input исходный текст
	 * @param tokens токены
	 */
	public static void assertNoGaps(String input, List<Parser3LexerCore.CoreToken> tokens) {
		int expectedPos = 0;
		for (int i = 0; i < tokens.size(); i++) {
			Parser3LexerCore.CoreToken token = tokens.get(i);
			assertEquals("Токен " + i + " должен начинаться с позиции " + expectedPos +
							" (тип: " + token.type + ")",
					expectedPos, token.start);
			assertTrue("Токен " + i + " должен иметь положительную длину",
					token.end > token.start);
			expectedPos = token.end;
		}
		assertEquals("Токены должны покрывать весь текст", input.length(), expectedPos);
	}

	/**
	 * Генерирует строку с токенами в формате для файла ожидаемых токенов.
	 *
	 * @param tokens токены
	 * @return строка в формате "start,end,type\n..."
	 */
	public static String generateExpectedTokensContent(List<Parser3LexerCore.CoreToken> tokens) {
		StringBuilder sb = new StringBuilder();
		sb.append("# Автоматически сгенерированный файл\n");
		sb.append("# Формат: start,end,type\n");
		sb.append("# Для регенерации запустите соответствующий генератор\n\n");

		for (Parser3LexerCore.CoreToken token : tokens) {
			sb.append(token.start).append(",")
					.append(token.end).append(",")
					.append(token.type).append("\n");
		}
		return sb.toString();
	}
}