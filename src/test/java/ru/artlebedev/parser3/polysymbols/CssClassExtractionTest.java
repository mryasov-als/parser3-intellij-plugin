package ru.artlebedev.parser3.polysymbols;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

/**
 * Тест извлечения CSS классов из текста.
 * Проверяет что regex работает правильно.
 */
public class CssClassExtractionTest {

	// Тот же паттерн что в Parser3CssClassScope
	private static final Pattern CSS_CLASS_PATTERN = Pattern.compile("\\.([a-zA-Z_][a-zA-Z0-9_-]*)");

	@Test
	public void testSimpleClass() {
		List<String> classes = extractClasses(".item { color: red; }");
		assertEquals(1, classes.size());
		assertEquals("item", classes.get(0));
	}

	@Test
	public void testMultipleClasses() {
		List<String> classes = extractClasses(".item { } .aabbcc { } .abc { }");
		assertEquals(3, classes.size());
		assertTrue(classes.contains("item"));
		assertTrue(classes.contains("aabbcc"));
		assertTrue(classes.contains("abc"));
	}

	@Test
	public void testClassWithDash() {
		List<String> classes = extractClasses(".list-item { } .my-class-name { }");
		assertEquals(2, classes.size());
		assertTrue(classes.contains("list-item"));
		assertTrue(classes.contains("my-class-name"));
	}

	@Test
	public void testClassWithUnderscore() {
		List<String> classes = extractClasses(".list_item { } ._private { }");
		assertEquals(2, classes.size());
		assertTrue(classes.contains("list_item"));
		assertTrue(classes.contains("_private"));
	}

	@Test
	public void testNestedClasses() {
		String css = ".list {\n" +
				"    .list-item {\n" +
				"        color: red;\n" +
				"    }\n" +
				"}";
		List<String> classes = extractClasses(css);
		assertEquals(2, classes.size());
		assertTrue(classes.contains("list"));
		assertTrue(classes.contains("list-item"));
	}

	@Test
	public void testDoesNotMatchPropertyValues() {
		// .5em не должен матчиться как класс (начинается с цифры)
		List<String> classes = extractClasses(".item { margin: .5em; opacity: .75; }");
		assertEquals(1, classes.size());
		assertEquals("item", classes.get(0));
	}

	@Test
	public void testRealWorldCss() {
		String css = ".item {\n" +
				"    font-weight: bold;\n" +
				"    opacity: 1;\n" +
				"    color: red;\n" +
				"}\n" +
				".aabbcc {\n" +
				"    font-size: 15px;\n" +
				"    font-weight: bold;\n" +
				"}\n" +
				".abc {\n" +
				"\n" +
				"}";
		List<String> classes = extractClasses(css);
		System.out.println("Найденные классы: " + classes);
		assertEquals(3, classes.size());
		assertTrue(classes.contains("item"));
		assertTrue(classes.contains("aabbcc"));
		assertTrue(classes.contains("abc"));
	}

	/**
	 * Извлекает CSS классы из текста (упрощённая версия без PSI).
	 */
	private List<String> extractClasses(String cssText) {
		List<String> result = new ArrayList<>();
		Matcher matcher = CSS_CLASS_PATTERN.matcher(cssText);
		while (matcher.find()) {
			String className = matcher.group(1);

			// Проверяем что это селектор, а не часть свойства
			int start = matcher.start();
			if (start > 0) {
				char prevChar = cssText.charAt(start - 1);
				if (Character.isLetterOrDigit(prevChar) || prevChar == '-' || prevChar == '_') {
					continue;
				}
			}

			result.add(className);
		}
		return result;
	}
}