package ru.artlebedev.parser3.lexer.debug;

import ru.artlebedev.parser3.lexer.Parser3LexerCore;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;

/**
 * Генератор файлов с токенами для тестов.
 * Проходит по всем .txt файлам в resources/lexer/input/
 * и создаёт соответствующие .json файлы в resources/lexer/tokens/
 *
 * Формат выходного файла (JSON Lines):
 * {"start":0,"end":5,"type":"METHOD","line":1,"text":"^void"}
 *
 * Запуск: java LexerTokensGenerator
 */
public class LexerTokensGenerator {

	public static void main(String[] args) throws IOException {
		// Определяем пути
		Path projectRoot = findProjectRoot();
		Path inputDir = projectRoot.resolve("src/test/resources/lexer/input");
		Path tokensDir = projectRoot.resolve("src/test/resources/lexer/tokens");

		// Создаём папки если нет
		Files.createDirectories(inputDir);
		Files.createDirectories(tokensDir);

		// Проверяем есть ли входные файлы
		boolean hasFiles = false;
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(inputDir, "*.txt")) {
			for (Path inputFile : stream) {
				hasFiles = true;
				processFile(inputFile, tokensDir);
			}
		}

		if (!hasFiles) {
			System.out.println("Папка input пуста: " + inputDir);
			System.out.println("Положите туда .txt файлы с тестовым кодом Parser3.");
			System.out.println();
			System.out.println("Например, скопируйте существующие тестовые файлы:");
			System.out.println("  lexer_test_input.p -> lexer/input/lexer_test.txt");
			System.out.println("  sql_lexer_test_input.p -> lexer/input/sql_lexer_test.txt");
			return;
		}

		System.out.println("Готово!");
	}

	private static Path findProjectRoot() {
		// Ищем корень проекта (где есть src/test/resources)
		Path current = Paths.get("").toAbsolutePath();

		// Пробуем разные варианты
		Path[] candidates = {
				current,
				current.resolve("parser3-intellij"),
				current.getParent(),
				current.getParent().resolve("parser3-intellij")
		};

		for (Path candidate : candidates) {
			if (Files.exists(candidate.resolve("src/test/resources"))) {
				return candidate;
			}
		}

		// Fallback — текущая директория
		System.err.println("Не найден корень проекта, используем: " + current);
		return current;
	}

	private static void processFile(Path inputFile, Path tokensDir) throws IOException {
		String fileName = inputFile.getFileName().toString();
		// Меняем расширение на .json
		String outputName = fileName.replaceFirst("\\.txt$", ".json");
		Path outputFile = tokensDir.resolve(outputName);

		// Если файл уже существует — пропускаем
		if (Files.exists(outputFile)) {
			System.out.println("Пропускаю (уже есть): " + fileName);
			return;
		}

		System.out.println("Обрабатываю: " + fileName);

		// Читаем входной файл
		String content = Files.readString(inputFile, StandardCharsets.UTF_8);

		// Токенизируем
		List<Parser3LexerCore.CoreToken> tokens = Parser3LexerCore.tokenize(content, 0, content.length());

		// Вычисляем номера строк для каждой позиции
		int[] lineNumbers = computeLineNumbers(content);

		// Записываем результат в JSON Lines формате
		try (BufferedWriter writer = Files.newBufferedWriter(outputFile, StandardCharsets.UTF_8)) {
			for (Parser3LexerCore.CoreToken token : tokens) {
				int lineNum = token.start < lineNumbers.length ? lineNumbers[token.start] : -1;
				String json = toJson(token.start, token.end, token.type, lineNum, token.debugText);
				writer.write(json);
				writer.newLine();
			}
		}

		System.out.println("  -> " + outputFile.getFileName() + " (" + tokens.size() + " токенов)");
	}

	/**
	 * Вычисляет номер строки для каждой позиции в тексте.
	 * lineNumbers[pos] = номер строки (начиная с 1)
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
	 * Формирует JSON-строку для токена (без внешних библиотек).
	 */
	private static String toJson(int start, int end, String type, int line, String text) {
		StringBuilder sb = new StringBuilder();
		sb.append("{\"start\":").append(start);
		sb.append(",\"end\":").append(end);
		sb.append(",\"type\":\"").append(type).append("\"");
		sb.append(",\"line\":").append(line);
		sb.append(",\"text\":\"").append(escapeJson(text)).append("\"");
		sb.append("}");
		return sb.toString();
	}

	/**
	 * Экранирует строку для JSON.
	 */
	private static String escapeJson(String text) {
		if (text == null) {
			return "";
		}
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
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