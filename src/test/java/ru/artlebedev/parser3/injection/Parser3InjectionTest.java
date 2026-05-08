package ru.artlebedev.parser3.injection;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import ru.artlebedev.parser3.Parser3TestCase;
import ru.artlebedev.parser3.injector.InjectorUtils;
import ru.artlebedev.parser3.injector.Parser3HtmlInjector;
import ru.artlebedev.parser3.psi.Parser3CssBlock;
import ru.artlebedev.parser3.psi.Parser3HtmlBlock;
import ru.artlebedev.parser3.psi.Parser3JsBlock;

import java.lang.reflect.Method;
import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Тест для проверки корректности injection в Parser3.
 * Проверяет посимвольно, что виртуальные документы (HTML, CSS, JS)
 * соответствуют ожидаемым слепкам.
 *
 * Запускается через Gradle: ./gradlew test или через таск parser3-intellij [test]
 */
public class Parser3InjectionTest extends Parser3TestCase {

	/**
	 * Тестирует injection для всех файлов в resources/injection/input/
	 */
	public void testInjection() throws Exception {
		URL inputUrl = getClass().getResource("/injection/input");
		if (inputUrl == null) {
			System.out.println("Папка /injection/input не найдена в resources");
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
			sb.append("Ошибки injection (").append(allErrors.size()).append("):\n");
			for (String error : allErrors) {
				sb.append("  - ").append(error).append("\n");
			}
			fail(sb.toString());
		}
	}

	public void testHtmlInjectionContinuesAfterEmptyStyleTag() {
		// реальный минимальный regression-кейс Parser3; при расширении сверять с тестами Parser3.
		String content = "@test[]\n" +
				"<style id=\"pattern-anons\"></style>\n" +
				"<div class=\"after-style\">ok</div>\n";

		PsiFile psiFile = createParser3File("inline_empty_style_injection.p", content);

		String html = buildHtmlVirtualDocument(psiFile);

		assertTrue("HTML injection должна включать пустой style-тег", html.contains("<style id=\"pattern-anons\"></style>"));
		assertTrue("HTML injection должна продолжаться после пустого style-тега", html.contains("<div class=\"after-style\">ok</div>"));
	}

	public void testNestedVariableBlockInsideMethodBracketCollapsesToSinglePlaceholderSpace() {
		// реальный минимальный regression-кейс Parser3; при расширении сверять с тестами Parser3.
		String content = "@test[]\n" +
				"<style>\n" +
				"\t.list {\n" +
				"\t\t^method_inner[\n" +
				"\t\t\t$.var{\n" +
				"\t\t\t\t.x-n-css {\n" +
				"\t\t\t\t}\n" +
				"\t\t\t}\n" +
				"\t\t]\n" +
				"\t}\n" +
				"</style>\n";

		PsiFile psiFile = createParser3File("inline_nested_variable_css_injection.p", content);

		String css = buildCssVirtualDocument(psiFile);

		assertFalse("В подавленной Parser3-области не должно оставаться несколько пробелов на строке селектора",
				css.contains("\t\t\t\t   \n"));
		assertTrue("Строка с подавленным селектором должна схлопываться в один placeholder-space",
				css.contains("\t\t\t\t \n"));
	}

	public void testAutoMethodDoesNotBecomeHtmlBeforeTemplateMethod() throws Exception {
		// реальный минимальный regression-кейс Parser3; при расширении сверять с тестами Parser3.
		String content = "@USE\n" +
				"Debug.p\n\n" +
				"@auto[]\n" +
				"$STORAGE[\n" +
				"\t$.persons[^hash::create[]]\n" +
				"]\n" +
				"$USER[^getLoggedUser[]]\n" +
				"$ADMINS[\n" +
				"\t$.tema[]\n" +
				"\t$.moko[]\n" +
				"]\n\n" +
				"^if(^ADMINS.contains[$USER] && def $cookie:login_emulated && !in '/login-emulate/'){\n" +
				"\t$USER[$cookie:login_emulated]\n" +
				"}\n" +
				"$USER_IS_ADMIN(^ADMINS.contains[$USER])\n" +
				"$PERSON[^hash::create[]]\n\n" +
				"@template[title;code]\n" +
				"<!DOCTYPE html>\n" +
				"<html lang=\"RU\">\n" +
				"<body>$code</body>\n" +
				"</html>\n";

		PsiFile psiFile = createParser3File("inline_auto_before_template_injection.p", content);

		int templateOffset = content.indexOf("@template[title;code]");
		assertTrue("В тестовом тексте должен быть @template", templateOffset >= 0);

		for (PsiElement child = psiFile.getFirstChild(); child != null; child = child.getNextSibling()) {
			if (child instanceof Parser3HtmlBlock) {
				assertTrue(
						"HTML блок не должен появляться до @template, но найден на offset=" + child.getTextOffset() +
								" текст=" + escape(child.getText()),
						child.getTextOffset() >= templateOffset
				);
			}
		}

		Parser3HtmlBlock firstHtmlBlock = null;
		for (PsiElement child = psiFile.getFirstChild(); child != null; child = child.getNextSibling()) {
			if (child instanceof Parser3HtmlBlock) {
				firstHtmlBlock = (Parser3HtmlBlock) child;
				break;
			}
		}
		assertNotNull("В template-методе должен быть найден HTML блок", firstHtmlBlock);

		Object boundary = findHtmlMethodBoundary(firstHtmlBlock);
		assertNotNull("Для HTML блока должны определяться границы метода", boundary);
		int startOffset = readBoundaryInt(boundary, "startOffset");
		assertEquals("HTML injector должен начинать диапазон ровно с @template, а не с @auto/@getUser", templateOffset, startOffset);

		String html = buildHtmlVirtualDocument(psiFile);

		assertFalse("До @template в виртуальный HTML не должен попадать код из @auto[]", html.contains("$STORAGE["));
		assertFalse("До @template в виртуальный HTML не должен попадать код из @auto[]", html.contains("$USER[^getLoggedUser[]]"));
		assertTrue("HTML injection должен содержать template-метод", html.contains("<!DOCTYPE html>"));
	}

	private Object findHtmlMethodBoundary(Parser3HtmlBlock host) throws Exception {
		Method method = Parser3HtmlInjector.class.getDeclaredMethod("findMethodBoundary", Parser3HtmlBlock.class);
		method.setAccessible(true);
		return method.invoke(null, host);
	}

	private int readBoundaryInt(Object boundary, String fieldName) throws Exception {
		java.lang.reflect.Field field = boundary.getClass().getDeclaredField(fieldName);
		field.setAccessible(true);
		return field.getInt(boundary);
	}

	private List<String> testFile(Path inputFile, Path expectedDir) throws Exception {
		List<String> errors = new ArrayList<>();

		String fileName = inputFile.getFileName().toString();
		String baseName = fileName.replaceFirst("\\.p$", "");

		// реальный минимальный regression-кейс Parser3; при расширении сверять с тестами Parser3.
		String content = Files.readString(inputFile, StandardCharsets.UTF_8);

		// Создаём реальный Parser3 файл в тестовом проекте
		PsiFile psiFile = createParser3File(fileName, content);

		// Собираем виртуальные документы напрямую через InjectorUtils
		Map<String, String> actualDocs = new LinkedHashMap<>();
		actualDocs.put("HTML", buildHtmlVirtualDocument(psiFile));
		actualDocs.put("CSS", buildCssVirtualDocument(psiFile));
		actualDocs.put("JS", buildJsVirtualDocument(psiFile));

		// Проверяем каждый язык
		String[] languages = {"HTML", "CSS", "JS"};
		String[] extensions = {".html", ".css", ".js"};

		for (int i = 0; i < languages.length; i++) {
			String lang = languages[i];
			String ext = extensions[i];
			Path expectedFile = expectedDir.resolve(baseName + ext + ".txt");

			String actual = actualDocs.getOrDefault(lang, "");

			if (!Files.exists(expectedFile)) {
				if (!actual.isEmpty()) {
					errors.add(fileName + " [" + lang + "]: нет expected файла, но есть injection (" + actual.length() + " символов). Запустите InjectionExpectedGenerator.");
				}
				continue;
			}

			String expected = Files.readString(expectedFile, StandardCharsets.UTF_8);

			// Сравниваем посимвольно
			List<String> diffErrors = compareTexts(fileName, lang, expected, actual);
			errors.addAll(diffErrors);
		}

		return errors;
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
	 * Собирает виртуальный CSS документ
	 */
	private String buildCssVirtualDocument(PsiFile psiFile) {
		for (PsiElement child = psiFile.getFirstChild(); child != null; child = child.getNextSibling()) {
			if (child instanceof Parser3CssBlock) {
				Parser3CssBlock cssBlock = (Parser3CssBlock) child;
				List<InjectorUtils.InjectionPart<Parser3CssBlock>> parts =
						InjectorUtils.collectPartsForHtmlTag(Parser3CssBlock.class, cssBlock, "<style", "</style");
				if (!parts.isEmpty()) {
					return InjectorUtils.buildVirtualDocument(parts);
				}
			}
		}
		return "";
	}

	/**
	 * Собирает виртуальный JS документ
	 */
	private String buildJsVirtualDocument(PsiFile psiFile) {
		for (PsiElement child = psiFile.getFirstChild(); child != null; child = child.getNextSibling()) {
			if (child instanceof Parser3JsBlock) {
				Parser3JsBlock jsBlock = (Parser3JsBlock) child;
				List<InjectorUtils.InjectionPart<Parser3JsBlock>> parts =
						InjectorUtils.collectPartsForHtmlTag(Parser3JsBlock.class, jsBlock, "<script", "</script");
				if (!parts.isEmpty()) {
					return InjectorUtils.buildVirtualDocument(parts);
				}
			}
		}
		return "";
	}

	/**
	 * Сравнивает два текста посимвольно и возвращает список ошибок
	 */
	private List<String> compareTexts(String fileName, String lang, String expected, String actual) {
		List<String> errors = new ArrayList<>();

		if (expected.equals(actual)) {
			return errors;
		}

		// Находим первое отличие
		int minLen = Math.min(expected.length(), actual.length());
		int firstDiff = -1;
		for (int i = 0; i < minLen; i++) {
			if (expected.charAt(i) != actual.charAt(i)) {
				firstDiff = i;
				break;
			}
		}

		if (firstDiff == -1 && expected.length() != actual.length()) {
			firstDiff = minLen;
		}

		if (firstDiff >= 0) {
			// Вычисляем строку и колонку
			int line = 1;
			int col = 1;
			for (int i = 0; i < firstDiff && i < expected.length(); i++) {
				if (expected.charAt(i) == '\n') {
					line++;
					col = 1;
				} else {
					col++;
				}
			}

			char expectedChar = firstDiff < expected.length() ? expected.charAt(firstDiff) : '\0';
			char actualChar = firstDiff < actual.length() ? actual.charAt(firstDiff) : '\0';

			// Контекст вокруг ошибки
			int contextStart = Math.max(0, firstDiff - 20);
			int contextEnd = Math.min(Math.max(expected.length(), actual.length()), firstDiff + 20);

			String expectedContext = expected.substring(contextStart, Math.min(contextEnd, expected.length()));
			String actualContext = actual.substring(contextStart, Math.min(contextEnd, actual.length()));

			errors.add(String.format(
					"%s [%s]: первое отличие на позиции %d (строка %d, колонка %d): " +
							"ожидалось '%s' (0x%04X), получено '%s' (0x%04X)\n" +
							"    Ожидалось: ...%s...\n" +
							"    Получено:  ...%s...",
					fileName, lang, firstDiff, line, col,
					escapeChar(expectedChar), (int) expectedChar,
					escapeChar(actualChar), (int) actualChar,
					escape(expectedContext),
					escape(actualContext)
			));
		}

		// Общая информация о длине
		if (expected.length() != actual.length()) {
			errors.add(String.format("%s [%s]: разная длина: ожидалось %d, получено %d",
					fileName, lang, expected.length(), actual.length()));
		}

		return errors;
	}

	private String escape(String text) {
		if (text == null) return "null";
		return text
				.replace("\\", "\\\\")
				.replace("\n", "\\n")
				.replace("\r", "\\r")
				.replace("\t", "\\t");
	}

	private String escapeChar(char c) {
		if (c == '\0') return "EOF";
		if (c == '\n') return "\\n";
		if (c == '\r') return "\\r";
		if (c == '\t') return "\\t";
		if (c == ' ') return "SPACE";
		if (c < 32) return String.format("\\x%02X", (int) c);
		return String.valueOf(c);
	}
}
