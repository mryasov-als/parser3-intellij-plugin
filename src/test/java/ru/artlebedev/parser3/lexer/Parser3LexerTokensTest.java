package ru.artlebedev.parser3.lexer;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import ru.artlebedev.parser3.lexer.Parser3LexerCore.CoreToken;

import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static org.junit.Assert.*;

/**
 * Параметризованный тест лексера.
 * Проходит по всем файлам в resources/lexer/input/ и сравнивает
 * результат токенизации с ожидаемым в resources/lexer/tokens/
 */
@RunWith(Parameterized.class)
public class Parser3LexerTokensTest {

	private final String testName;
	private final String inputContent;
	private final List<ExpectedToken> expectedTokens;

	public Parser3LexerTokensTest(String testName, String inputContent, List<ExpectedToken> expectedTokens) {
		this.testName = testName;
		this.inputContent = inputContent;
		this.expectedTokens = expectedTokens;
	}

	@Parameterized.Parameters(name = "{0}")
	public static Collection<Object[]> data() throws IOException {
		List<Object[]> testCases = new ArrayList<>();

		// Находим папку input
		URL inputUrl = Parser3LexerTokensTest.class.getResource("/lexer/input");
		if (inputUrl == null) {
			System.err.println("Папка /lexer/input не найдена в resources");
			return testCases;
		}

		Path inputDir = Paths.get(inputUrl.getPath().replaceFirst("^/([A-Z]:)", "$1"));
		Path tokensDir = inputDir.getParent().resolve("tokens");

		// Проходим по всем .txt файлам в input
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(inputDir, "*.txt")) {
			for (Path inputFile : stream) {
				String fileName = inputFile.getFileName().toString();
				String jsonName = fileName.replaceFirst("\\.txt$", ".json");
				Path tokensFile = tokensDir.resolve(jsonName);

				if (!Files.exists(tokensFile)) {
					System.err.println("Нет файла токенов для: " + fileName +
							". Запустите LexerTokensGenerator для генерации.");
					continue;
				}

				String inputContent = Files.readString(inputFile, StandardCharsets.UTF_8);
				List<ExpectedToken> expectedTokens = parseTokensFile(tokensFile);

				testCases.add(new Object[]{fileName, inputContent, expectedTokens});
			}
		}

		return testCases;
	}

	@Test
	public void testTokenization() {
		List<CoreToken> actualTokens = Parser3LexerCore.tokenize(inputContent, 0, inputContent.length());

		// Вычисляем номера строк
		int[] lineNumbers = computeLineNumbers(inputContent);

		// Собираем все ошибки
		List<String> errors = new ArrayList<>();

		// Сравниваем количество
		if (expectedTokens.size() != actualTokens.size()) {
			errors.add(String.format("Количество токенов: ожидалось %d, получено %d",
					expectedTokens.size(), actualTokens.size()));
		}

		// Сравниваем каждый токен
		int count = Math.min(expectedTokens.size(), actualTokens.size());
		for (int i = 0; i < count; i++) {
			ExpectedToken expected = expectedTokens.get(i);
			CoreToken actual = actualTokens.get(i);
			int actualLine = actual.start < lineNumbers.length ? lineNumbers[actual.start] : -1;

			List<String> tokenErrors = new ArrayList<>();

			// Проверяем позицию
			if (expected.start != actual.start || expected.end != actual.end) {
				tokenErrors.add(String.format("позиция [%d,%d] != [%d,%d]",
						expected.start, expected.end, actual.start, actual.end));
			}

			// Проверяем тип
			if (!expected.type.equals(actual.type)) {
				tokenErrors.add(String.format("тип %s != %s", expected.type, actual.type));
			}

			// Проверяем текст
			String actualText = actual.debugText != null ? actual.debugText : "";
			if (!expected.text.equals(actualText)) {
				tokenErrors.add(String.format("текст '%s' != '%s'",
						escapeForDisplay(expected.text), escapeForDisplay(actualText)));
			}

			if (!tokenErrors.isEmpty()) {
				errors.add(String.format("Токен %d [строка %d]: %s",
						i, actualLine, String.join(", ", tokenErrors)));
			}
		}

		// Если есть лишние токены
		if (actualTokens.size() > expectedTokens.size()) {
			for (int i = expectedTokens.size(); i < actualTokens.size(); i++) {
				CoreToken extra = actualTokens.get(i);
				int line = extra.start < lineNumbers.length ? lineNumbers[extra.start] : -1;
				errors.add(String.format("Лишний токен %d [строка %d]: %s '%s' [%d,%d]",
						i, line, extra.type, escapeForDisplay(extra.debugText), extra.start, extra.end));
			}
		}

		// Выводим все ошибки
		if (!errors.isEmpty()) {
			StringBuilder sb = new StringBuilder();
			sb.append("Ошибки в ").append(testName).append(" (").append(errors.size()).append("):\n");
			for (String error : errors) {
				sb.append("  - ").append(error).append("\n");
			}
			fail(sb.toString());
		}
	}

	/**
	 * Парсит JSON Lines файл с токенами.
	 */
	private static List<ExpectedToken> parseTokensFile(Path file) throws IOException {
		List<ExpectedToken> tokens = new ArrayList<>();

		try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (line.trim().isEmpty()) {
					continue;
				}
				ExpectedToken token = parseJsonLine(line);
				if (token != null) {
					tokens.add(token);
				}
			}
		}

		return tokens;
	}

	/**
	 * Парсит JSON строку токена (простой парсер без библиотек).
	 */
	private static ExpectedToken parseJsonLine(String json) {
		try {
			int start = getIntValue(json, "start");
			int end = getIntValue(json, "end");
			String type = getStringValue(json, "type");
			int line = getIntValue(json, "line");
			String text = getStringValue(json, "text");

			return new ExpectedToken(start, end, type, line, text);
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * Извлекает int значение из JSON.
	 */
	private static int getIntValue(String json, String key) {
		String pattern = "\"" + key + "\":";
		int idx = json.indexOf(pattern);
		if (idx < 0) return -1;
		idx += pattern.length();
		int endIdx = idx;
		while (endIdx < json.length() && (Character.isDigit(json.charAt(endIdx)) || json.charAt(endIdx) == '-')) {
			endIdx++;
		}
		return Integer.parseInt(json.substring(idx, endIdx));
	}

	/**
	 * Извлекает String значение из JSON.
	 */
	private static String getStringValue(String json, String key) {
		String pattern = "\"" + key + "\":\"";
		int idx = json.indexOf(pattern);
		if (idx < 0) return "";
		idx += pattern.length();
		StringBuilder sb = new StringBuilder();
		boolean escaped = false;
		while (idx < json.length()) {
			char c = json.charAt(idx);
			if (escaped) {
				switch (c) {
					case 'n': sb.append('\n'); break;
					case 'r': sb.append('\r'); break;
					case 't': sb.append('\t'); break;
					case '"': sb.append('"'); break;
					case '\\': sb.append('\\'); break;
					case 'u':
						// Unicode escape
						if (idx + 4 < json.length()) {
							String hex = json.substring(idx + 1, idx + 5);
							sb.append((char) Integer.parseInt(hex, 16));
							idx += 4;
						}
						break;
					default: sb.append(c);
				}
				escaped = false;
			} else if (c == '\\') {
				escaped = true;
			} else if (c == '"') {
				break;
			} else {
				sb.append(c);
			}
			idx++;
		}
		return sb.toString();
	}

	/**
	 * Экранирует строку для отображения в сообщении об ошибке.
	 */
	private static String escapeForDisplay(String text) {
		if (text == null) return "null";
		return text
				.replace("\\", "\\\\")
				.replace("\n", "\\n")
				.replace("\r", "\\r")
				.replace("\t", "\\t");
	}

	/**
	 * Вычисляет номер строки для каждой позиции.
	 */
	private static int[] computeLineNumbers(String text) {
		int[] result = new int[text.length() + 1];
		int line = 1;
		for (int i = 0; i < text.length(); i++) {
			result[i] = line;
			if (text.charAt(i) == '\n') {
				line++;
			}
		}
		result[text.length()] = line;
		return result;
	}

	/**
	 * Ожидаемый токен.
	 */
	private static class ExpectedToken {
		final int start;
		final int end;
		final String type;
		final int line;
		final String text;

		ExpectedToken(int start, int end, String type, int line, String text) {
			this.start = start;
			this.end = end;
			this.type = type;
			this.line = line;
			this.text = text != null ? text : "";
		}
	}
}