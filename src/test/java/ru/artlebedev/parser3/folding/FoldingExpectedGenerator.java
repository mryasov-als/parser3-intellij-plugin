package ru.artlebedev.parser3.folding;

import com.intellij.lang.folding.FoldingDescriptor;
import com.intellij.openapi.editor.Document;
import com.intellij.psi.PsiFile;
import ru.artlebedev.parser3.Parser3TestCase;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/**
 * Генератор expected-файлов для тестов folding.
 * Использует реальный Parser3FoldingBuilder с PSI.
 *
 * Запуск: через Gradle таск [test] с фильтром --tests "*.FoldingExpectedGenerator"
 *
 * Использование:
 * 1. Положить .p файл в src/test/resources/folding/input/
 * 2. Запустить этот тест
 * 3. Файл .json появится в src/test/resources/folding/expected/
 */
public class FoldingExpectedGenerator extends Parser3TestCase {

	/**
	 * Генерирует expected-файлы для всех .p файлов в input/
	 */
	public void testGenerateExpectedFiles() throws Exception {
		Path inputDir = Paths.get("src/test/resources/folding/input");
		Path expectedDir = Paths.get("src/test/resources/folding/expected");

		if (!Files.exists(inputDir)) {
			System.out.println("Папка input не найдена: " + inputDir.toAbsolutePath());
			return;
		}

		Files.createDirectories(expectedDir);

		try (DirectoryStream<Path> stream = Files.newDirectoryStream(inputDir, "*.p")) {
			for (Path inputFile : stream) {
				generateExpected(inputFile, expectedDir);
			}
		}

		System.out.println("\nГотово!");
	}

	private void generateExpected(Path inputFile, Path expectedDir) throws Exception {
		String fileName = inputFile.getFileName().toString();
		String jsonName = fileName.replaceFirst("\\.p$", ".json");
		Path outputFile = expectedDir.resolve(jsonName);

		// Пропускаем если файл уже существует
		if (Files.exists(outputFile)) {
			System.out.println("Пропускаю (уже есть): " + fileName);
			return;
		}

		System.out.println("Обрабатываю: " + fileName);

		String content = Files.readString(inputFile, StandardCharsets.UTF_8);

		// Создаём реальный Parser3 файл в тестовом проекте
		PsiFile psiFile = createParser3File(fileName, content);
		Document document = myFixture.getEditor().getDocument();

		// Получаем folding regions от реального Parser3FoldingBuilder
		Parser3FoldingBuilder builder = new Parser3FoldingBuilder();
		FoldingDescriptor[] descriptors = builder.buildFoldRegions(psiFile, document, false);

		// Записываем JSON
		try (BufferedWriter writer = Files.newBufferedWriter(outputFile, StandardCharsets.UTF_8)) {
			for (FoldingDescriptor descriptor : descriptors) {
				int startOffset = descriptor.getRange().getStartOffset();
				int endOffset = descriptor.getRange().getEndOffset();
				int startLine = document.getLineNumber(startOffset) + 1; // 1-based
				int endLine = document.getLineNumber(endOffset) + 1;
				String placeholder = descriptor.getPlaceholderText();

				// JSON Lines формат
				writer.write(String.format(
						"{\"startOffset\":%d,\"endOffset\":%d,\"startLine\":%d,\"endLine\":%d,\"placeholder\":\"%s\"}",
						startOffset, endOffset, startLine, endLine, escapeJson(placeholder)
				));
				writer.newLine();
			}
		}

		System.out.println("  -> " + outputFile.getFileName() + " (" + descriptors.length + " regions)");
	}

	/**
	 * Экранирует строку для JSON
	 */
	private String escapeJson(String text) {
		if (text == null) return "";
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
