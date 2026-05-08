package ru.artlebedev.parser3.injection;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import ru.artlebedev.parser3.Parser3TestCase;
import ru.artlebedev.parser3.injector.InjectorUtils;
import ru.artlebedev.parser3.psi.Parser3CssBlock;
import ru.artlebedev.parser3.psi.Parser3HtmlBlock;
import ru.artlebedev.parser3.psi.Parser3JsBlock;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Генератор expected-файлов для тестов injection.
 * Создаёт слепки виртуальных документов (HTML, CSS, JS) для каждого входного файла.
 *
 * Запуск: через Gradle таск [test] с фильтром --tests "*.InjectionExpectedGenerator"
 *
 * Использование:
 * 1. Положить .p файл в src/test/resources/injection/input/
 * 2. Запустить этот тест
 * 3. Файлы .html.txt, .css.txt, .js.txt появятся в src/test/resources/injection/expected/
 */
public class InjectionExpectedGenerator extends Parser3TestCase {

	/**
	 * Генерирует expected-файлы для всех .p файлов в input/
	 */
	public void testGenerateExpectedFiles() throws Exception {
		Path inputDir = Paths.get("src/test/resources/injection/input");
		Path expectedDir = Paths.get("src/test/resources/injection/expected");

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
		String baseName = fileName.replaceFirst("\\.p$", "");

		System.out.println("Обрабатываю: " + fileName);

		String content = Files.readString(inputFile, StandardCharsets.UTF_8);

		// Создаём реальный Parser3 файл в тестовом проекте
		PsiFile psiFile = createParser3File(fileName, content);

		// Собираем виртуальные документы напрямую через InjectorUtils
		String htmlDoc = buildHtmlVirtualDocument(psiFile);
		String cssDoc = buildCssVirtualDocument(psiFile);
		String jsDoc = buildJsVirtualDocument(psiFile);

		// Записываем файлы
		writeIfNotEmpty(expectedDir, baseName, ".html", htmlDoc);
		writeIfNotEmpty(expectedDir, baseName, ".css", cssDoc);
		writeIfNotEmpty(expectedDir, baseName, ".js", jsDoc);
	}

	/**
	 * Собирает виртуальный HTML документ
	 */
	private String buildHtmlVirtualDocument(PsiFile psiFile) {
		List<InjectorUtils.InjectionPart<Parser3HtmlBlock>> parts =
				InjectorUtils.collectPartsForHtml(psiFile, 0, psiFile.getTextLength());
		return InjectorUtils.buildVirtualDocument(parts);
	}

	/**
	 * Собирает виртуальный CSS документ — находит все <style> блоки
	 */
	private String buildCssVirtualDocument(PsiFile psiFile) {
		StringBuilder result = new StringBuilder();

		// Ищем все CSS блоки
		for (PsiElement child = psiFile.getFirstChild(); child != null; child = child.getNextSibling()) {
			if (child instanceof Parser3CssBlock) {
				Parser3CssBlock cssBlock = (Parser3CssBlock) child;
				List<InjectorUtils.InjectionPart<Parser3CssBlock>> parts =
						InjectorUtils.collectPartsForHtmlTag(Parser3CssBlock.class, cssBlock, "<style", "</style");
				if (!parts.isEmpty()) {
					String doc = InjectorUtils.buildVirtualDocument(parts);
					if (result.length() == 0 || result.toString().length() < doc.length()) {
						result.setLength(0);
						result.append(doc);
					}
				}
				break; // Берём только первый style блок
			}
		}

		return result.toString();
	}

	/**
	 * Собирает виртуальный JS документ — находит все <script> блоки
	 */
	private String buildJsVirtualDocument(PsiFile psiFile) {
		StringBuilder result = new StringBuilder();

		// Ищем все JS блоки
		for (PsiElement child = psiFile.getFirstChild(); child != null; child = child.getNextSibling()) {
			if (child instanceof Parser3JsBlock) {
				Parser3JsBlock jsBlock = (Parser3JsBlock) child;
				List<InjectorUtils.InjectionPart<Parser3JsBlock>> parts =
						InjectorUtils.collectPartsForHtmlTag(Parser3JsBlock.class, jsBlock, "<script", "</script");
				if (!parts.isEmpty()) {
					String doc = InjectorUtils.buildVirtualDocument(parts);
					if (result.length() == 0 || result.toString().length() < doc.length()) {
						result.setLength(0);
						result.append(doc);
					}
				}
				break; // Берём только первый script блок
			}
		}

		return result.toString();
	}

	private void writeIfNotEmpty(Path expectedDir, String baseName, String ext, String content) throws IOException {
		if (content == null || content.isEmpty()) {
			System.out.println("  " + ext.substring(1).toUpperCase() + ": пусто, пропускаю");
			return;
		}

		Path outputFile = expectedDir.resolve(baseName + ext + ".txt");

		// Пропускаем если файл уже существует
		if (Files.exists(outputFile)) {
			System.out.println("  " + outputFile.getFileName() + ": уже существует, пропускаю");
			return;
		}

		Files.writeString(outputFile, content, StandardCharsets.UTF_8);
		System.out.println("  -> " + outputFile.getFileName() + " (" + content.length() + " символов)");
	}
}
