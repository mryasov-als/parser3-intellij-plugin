package ru.artlebedev.parser3.lexer;

import org.junit.Test;
import ru.artlebedev.parser3.lexer.Parser3LexerCore.CoreToken;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;

/**
 * Генератор expected-файлов для тестов токенизации лексера.
 *
 * Запуск: через Gradle таск [test] с фильтром --tests "*.LexerExpectedGenerator"
 */
public class LexerExpectedGenerator {

	@Test
	public void testGenerateExpectedFiles() throws Exception {
		Path inputDir = Paths.get("src/test/resources/lexer/input");
		Path tokensDir = Paths.get("src/test/resources/lexer/tokens");

		if (!Files.exists(inputDir)) {
			System.out.println("Папка input не найдена: " + inputDir.toAbsolutePath());
			return;
		}

		Files.createDirectories(tokensDir);

		try (DirectoryStream<Path> stream = Files.newDirectoryStream(inputDir, "*.txt")) {
			for (Path inputFile : stream) {
				generateExpected(inputFile, tokensDir);
			}
		}

		System.out.println("\nГотово!");
	}

	private void generateExpected(Path inputFile, Path tokensDir) throws IOException {
		String fileName = inputFile.getFileName().toString();
		String jsonName = fileName.replaceFirst("\\.txt$", ".json");
		Path outputFile = tokensDir.resolve(jsonName);

		System.out.println("Обрабатываю: " + fileName);

		String input = Files.readString(inputFile, StandardCharsets.UTF_8);
		List<CoreToken> tokens = Parser3LexerCore.tokenize(input, 0, input.length());
		int[] lineNumbers = computeLineNumbers(input);

		StringBuilder out = new StringBuilder();
		for (CoreToken token : tokens) {
			int line = token.start < lineNumbers.length ? lineNumbers[token.start] : -1;
			out.append("{\"start\":")
					.append(token.start)
					.append(",\"end\":")
					.append(token.end)
					.append(",\"type\":\"")
					.append(escapeJson(token.type))
					.append("\",\"line\":")
					.append(line)
					.append(",\"text\":\"")
					.append(escapeJson(token.debugText != null ? token.debugText : ""))
					.append("\"}")
					.append('\n');
		}

		Files.writeString(outputFile, out.toString(), StandardCharsets.UTF_8);
		System.out.println("  -> " + outputFile.getFileName() + " (" + tokens.size() + " токенов)");
	}

	private int[] computeLineNumbers(String text) {
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

	private String escapeJson(String text) {
		StringBuilder sb = new StringBuilder();
		for (char c : text.toCharArray()) {
			switch (c) {
				case '"': sb.append("\\\""); break;
				case '\\': sb.append("\\\\"); break;
				case '\n': sb.append("\\n"); break;
				case '\r': sb.append("\\r"); break;
				case '\t': sb.append("\\t"); break;
				default:
					if (c < 32) {
						sb.append(String.format("\\u%04x", (int) c));
					} else {
						sb.append(c);
					}
			}
		}
		return sb.toString();
	}
}
