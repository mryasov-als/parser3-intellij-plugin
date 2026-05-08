package ru.artlebedev.parser3.folding;

import com.intellij.lang.folding.FoldingDescriptor;
import com.intellij.openapi.editor.Document;
import com.intellij.psi.PsiFile;
import ru.artlebedev.parser3.Parser3TestCase;

import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Тест для проверки корректности folding в Parser3.
 * Проверяет:
 * 1. Границы folding regions (startOffset, endOffset)
 * 2. Placeholders (текст, который показывается вместо свёрнутого блока)
 *
 * Использует реальный Parser3FoldingBuilder с PSI.
 * Запускается через Gradle: ./gradlew test или через таск parser3-intellij [test]
 */
public class Parser3FoldingTest extends Parser3TestCase {

	/**
	 * Тестирует folding для всех файлов в resources/folding/input/
	 */
	public void testFolding() throws Exception {
		URL inputUrl = getClass().getResource("/folding/input");
		if (inputUrl == null) {
			System.out.println("Папка /folding/input не найдена в resources");
			return;
		}

		Path inputDir = Paths.get(inputUrl.getPath().replaceFirst("^/([A-Z]:)", "$1"));
		Path expectedDir = inputDir.getParent().resolve("expected");

		List<String> allErrors = new ArrayList<>();

		try (DirectoryStream<Path> stream = Files.newDirectoryStream(inputDir, "*.p")) {
			for (Path inputFile : stream) {
				List<String> errors = testFile(inputFile, expectedDir);
				allErrors.addAll(errors);
			}
		}

		if (!allErrors.isEmpty()) {
			StringBuilder sb = new StringBuilder();
			sb.append("Ошибки folding (").append(allErrors.size()).append("):\n");
			for (String error : allErrors) {
				sb.append("  - ").append(error).append("\n");
			}
			fail(sb.toString());
		}
	}

	private List<String> testFile(Path inputFile, Path expectedDir) throws Exception {
		List<String> errors = new ArrayList<>();

		String fileName = inputFile.getFileName().toString();
		String jsonName = fileName.replaceFirst("\\.p$", ".json");
		Path expectedFile = expectedDir.resolve(jsonName);

		if (!Files.exists(expectedFile)) {
			errors.add(fileName + ": нет expected файла. Запустите FoldingExpectedGenerator.");
			return errors;
		}

		String content = Files.readString(inputFile, StandardCharsets.UTF_8);
		List<ExpectedFolding> expected = parseExpectedFile(expectedFile);

		// Создаём реальный Parser3 файл в тестовом проекте
		PsiFile psiFile = createParser3File(fileName, content);
		Document document = myFixture.getEditor().getDocument();

		// Получаем actual folding regions от реального Parser3FoldingBuilder
		Parser3FoldingBuilder builder = new Parser3FoldingBuilder();
		FoldingDescriptor[] descriptors = builder.buildFoldRegions(psiFile, document, false);

		// Сравниваем количество
		if (expected.size() != descriptors.length) {
			errors.add(String.format("%s: количество regions: ожидалось %d, получено %d",
					fileName, expected.size(), descriptors.length));
		}

		// Сравниваем каждый region
		int count = Math.min(expected.size(), descriptors.length);
		for (int i = 0; i < count; i++) {
			ExpectedFolding exp = expected.get(i);
			FoldingDescriptor actual = descriptors[i];

			int actualStart = actual.getRange().getStartOffset();
			int actualEnd = actual.getRange().getEndOffset();
			String actualPlaceholder = actual.getPlaceholderText();

			List<String> regionErrors = new ArrayList<>();

			// Проверяем границы
			if (exp.startOffset != actualStart || exp.endOffset != actualEnd) {
				regionErrors.add(String.format("границы [%d,%d] != [%d,%d]",
						exp.startOffset, exp.endOffset, actualStart, actualEnd));
			}

			// Проверяем placeholder
			if (!Objects.equals(exp.placeholder, actualPlaceholder)) {
				regionErrors.add(String.format("placeholder '%s' != '%s'",
						escape(exp.placeholder), escape(actualPlaceholder)));
			}

			if (!regionErrors.isEmpty()) {
				int line = document.getLineNumber(actualStart) + 1;
				errors.add(String.format("%s: region %d [строка %d]: %s",
						fileName, i, line, String.join(", ", regionErrors)));
			}
		}

		// Лишние regions
		if (descriptors.length > expected.size()) {
			for (int i = expected.size(); i < descriptors.length; i++) {
				FoldingDescriptor extra = descriptors[i];
				int line = document.getLineNumber(extra.getRange().getStartOffset()) + 1;
				errors.add(String.format("%s: лишний region %d [строка %d]: [%d,%d] '%s'",
						fileName, i, line,
						extra.getRange().getStartOffset(),
						extra.getRange().getEndOffset(),
						escape(extra.getPlaceholderText())));
			}
		}

		// Недостающие regions
		if (expected.size() > descriptors.length) {
			for (int i = descriptors.length; i < expected.size(); i++) {
				ExpectedFolding missing = expected.get(i);
				errors.add(String.format("%s: отсутствует region %d [строка %d]: [%d,%d] '%s'",
						fileName, i, missing.startLine,
						missing.startOffset, missing.endOffset,
						escape(missing.placeholder)));
			}
		}

		return errors;
	}

	/**
	 * Парсит JSON Lines файл с ожидаемыми folding regions
	 */
	private List<ExpectedFolding> parseExpectedFile(Path file) throws IOException {
		List<ExpectedFolding> result = new ArrayList<>();

		try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (line.trim().isEmpty()) continue;
				ExpectedFolding folding = parseJsonLine(line);
				if (folding != null) {
					result.add(folding);
				}
			}
		}

		return result;
	}

	/**
	 * Парсит одну JSON строку
	 */
	private ExpectedFolding parseJsonLine(String json) {
		try {
			int startOffset = getIntValue(json, "startOffset");
			int endOffset = getIntValue(json, "endOffset");
			int startLine = getIntValue(json, "startLine");
			int endLine = getIntValue(json, "endLine");
			String placeholder = getStringValue(json, "placeholder");

			return new ExpectedFolding(startOffset, endOffset, startLine, endLine, placeholder);
		} catch (Exception e) {
			return null;
		}
	}

	private int getIntValue(String json, String key) {
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

	private String getStringValue(String json, String key) {
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

	private String escape(String text) {
		if (text == null) return "null";
		return text
				.replace("\\", "\\\\")
				.replace("\n", "\\n")
				.replace("\r", "\\r")
				.replace("\t", "\\t");
	}

	/**
	 * Ожидаемый folding region
	 */
	private static class ExpectedFolding {
		final int startOffset;
		final int endOffset;
		final int startLine;
		final int endLine;
		final String placeholder;

		ExpectedFolding(int startOffset, int endOffset, int startLine, int endLine, String placeholder) {
			this.startOffset = startOffset;
			this.endOffset = endOffset;
			this.startLine = startLine;
			this.endLine = endLine;
			this.placeholder = placeholder;
		}
	}
}
