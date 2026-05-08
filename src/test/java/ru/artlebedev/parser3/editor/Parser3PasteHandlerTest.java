package ru.artlebedev.parser3.editor;

import com.intellij.openapi.application.ApplicationManager;
import ru.artlebedev.parser3.Parser3TestCase;

/**
 * Regression-тесты для обработчика вставки Parser3.
 */
public class Parser3PasteHandlerTest extends Parser3TestCase {

	public void testCustomPasteDisabledOutsideWriteAction() {
		assertFalse("Кастомная вставка не должна синхронно запрашивать write action из write-intent paste action",
				Parser3PasteHandler.canUseCustomPasteNow());
	}

	public void testCustomPasteAllowedInsideWriteAction() {
		ApplicationManager.getApplication().runWriteAction(() ->
				assertTrue("Кастомная вставка допустима только когда write access уже получен",
						Parser3PasteHandler.canUseCustomPasteNow())
		);
	}
}
