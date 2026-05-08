package ru.artlebedev.parser3;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiFile;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.codeStyle.CodeStyleSettingsManager;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings;
import ru.artlebedev.parser3.formatting.Parser3HashCommentPreFormatProcessor;
import ru.artlebedev.parser3.formatting.Parser3HashCommentPostFormatProcessor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Тесты форматирования Parser3 кода.
 * Проверяет что форматер корректно обрабатывает отступы, комментарии и структуру кода.
 */
public class x8_FormattingTest extends Parser3TestCase {

	@Override
	protected void setUp() throws Exception {
		super.setUp();

		// Настраиваем табы для форматирования ПРОЕКТА
		CodeStyleSettings settings = CodeStyleSettingsManager.getSettings(getProject());
		CommonCodeStyleSettings.IndentOptions indentOptions =
				settings.getCommonSettings(Parser3Language.INSTANCE).getIndentOptions();
		if (indentOptions != null) {
			indentOptions.USE_TAB_CHARACTER = true;
			indentOptions.TAB_SIZE = 4;
			indentOptions.INDENT_SIZE = 4;
		}
	}

	/**
	 * Тест базового форматирования:
	 * - Hash-комментарии остаются в колонке 0
	 * - Методы получают корректные отступы
	 * - Комментарии внутри выражений сохраняются
	 */
	public void testBasicFormatting() throws IOException {
		// Загружаем входной файл
		Path inputPath = Paths.get("src/test/resources/formatting/input/formatting_test.p");
		// реальный минимальный formatting fixture из test resources.
		String input = Files.readString(inputPath, StandardCharsets.UTF_8);

		// Создаём файл в тестовом проекте
		PsiFile file = createParser3File("test.p", input);

		// Форматируем
		WriteCommandAction.runWriteCommandAction(getProject(), () -> {
			CodeStyleSettings settings = CodeStyleSettingsManager.getSettings(getProject());
			Document document = com.intellij.psi.PsiDocumentManager.getInstance(getProject()).getDocument(file);

			// PreFormat
			Parser3HashCommentPreFormatProcessor preProcessor = new Parser3HashCommentPreFormatProcessor();
			preProcessor.process(file.getNode(), new TextRange(0, file.getTextLength()));

			// Format
			CodeStyleManager.getInstance(getProject()).reformat(file);

			Document documentAfter = com.intellij.psi.PsiDocumentManager.getInstance(getProject()).getDocument(file);

			// PostFormat
			Parser3HashCommentPostFormatProcessor postProcessor = new Parser3HashCommentPostFormatProcessor();
			postProcessor.processText(file, new TextRange(0, file.getTextLength()), settings);

			// Commit changes
			com.intellij.psi.PsiDocumentManager.getInstance(getProject()).commitDocument(documentAfter);
		});

		// Получаем результат
		String result = file.getText();

		// Загружаем ожидаемый результат
		Path expectedPath = Paths.get("src/test/resources/formatting/expected/formatting_test.p");
		String expected = Files.readString(expectedPath, StandardCharsets.UTF_8);

		// Сравниваем
		assertEquals("Форматирование должно совпадать с ожидаемым", expected, result);
	}

	/**
	 * Тест форматирования с сохранением hash-комментариев в колонке 0
	 */
	public void testHashCommentsInColumnZero() {
		String input = "@main[]\n# реальный минимальный кейс Parser3 fixture\n	# неправильный отступ\n" +
				"# правильный\n" +
				"@method[]\n" +
				"# комментарий в методе\n" +
				"^process[]";

		PsiFile file = createParser3File("test.p", input);

		WriteCommandAction.runWriteCommandAction(getProject(), () -> {
			CodeStyleManager.getInstance(getProject()).reformat(file);
		});

		String result = file.getText();

		// Hash-комментарии должны остаться в колонке 0
		assertTrue("Комментарий должен остаться в колонке 0",
				result.contains("\n# правильный\n"));
		assertTrue("Комментарий в методе должен быть в колонке 0",
				result.contains("\n# комментарий в методе\n"));
	}

	/**
	 * Тест форматирования вложенных блоков
	 */
	public void testNestedBlocks() {
		// реальный минимальный formatting-кейс вложенных блоков Parser3.
		String input = "^if($a){\n" +
				"^if($b){\n" +
				"^process[]\n" +
				"}\n" +
				"}";

		PsiFile file = createParser3File("test.p", input);

		WriteCommandAction.runWriteCommandAction(getProject(), () -> {
			CodeStyleManager.getInstance(getProject()).reformat(file);
		});

		String result = file.getText();

		// Проверяем что есть отступы (табы)
		assertTrue("Должен быть отступ перед внутренним ^if",
				result.contains("\t^if($b)"));
		assertTrue("Должен быть двойной отступ перед ^process",
				result.contains("\t\t^process[]"));
	}

	/**
	 * Тест сохранения комментариев внутри выражений
	 */
	public void testCommentsInExpressions() {
		// реальный минимальный formatting-кейс комментария внутри выражения Parser3.
		String input = "^if(\n" +
				"$var # комментарий\n" +
				"|| $other\n" +
				"){\n" +
				"^process[]\n" +
				"}";

		PsiFile file = createParser3File("test.p", input);

		WriteCommandAction.runWriteCommandAction(getProject(), () -> {
			CodeStyleManager.getInstance(getProject()).reformat(file);
		});

		String result = file.getText();

		// Комментарий должен сохраниться
		assertTrue("Комментарий в выражении должен сохраниться",
				result.contains("# комментарий"));
	}
}
