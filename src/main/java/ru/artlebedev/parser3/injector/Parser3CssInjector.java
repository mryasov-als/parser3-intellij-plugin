package ru.artlebedev.parser3.injector;

import com.intellij.lang.Language;
import com.intellij.lang.injection.MultiHostInjector;
import com.intellij.lang.injection.MultiHostRegistrar;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;
import ru.artlebedev.parser3.psi.Parser3CssBlock;

import java.util.Collections;
import java.util.List;

/**
 * Инжектор CSS для токенов CSS_DATA.
 *
 * Собирает все CSS_DATA токены внутри одного <style> блока в единый виртуальный документ.
 * Parser3 конструкции ($var, ^method[]) заменяются пробелами.
 *
 * ПРИМЕЧАНИЕ: Этот инжектор работает параллельно с HTML инжектором.
 * HTML инжектор вставляет CSS как текст внутри <style>, а этот инжектор
 * создаёт отдельный CSS документ для более точного анализа.
 */
public class Parser3CssInjector implements MultiHostInjector {

	@Override
	public void getLanguagesToInject(@NotNull MultiHostRegistrar registrar, @NotNull PsiElement context) {
		if (!(context instanceof Parser3CssBlock)) {
			return;
		}

		Parser3CssBlock host = (Parser3CssBlock) context;

		// Собираем все CSS_DATA блоки между <style> и </style>
		List<InjectorUtils.InjectionPart<Parser3CssBlock>> parts =
				InjectorUtils.collectPartsForHtmlTag(Parser3CssBlock.class, host, "<style", "</style");

		if (parts.isEmpty()) {
			return;
		}

		// Проверяем что текущий host входит в список
		boolean hostInside = false;
		for (InjectorUtils.InjectionPart<Parser3CssBlock> part : parts) {
			if (part.host == host) {
				hostInside = true;
				break;
			}
		}
		if (!hostInside) {
			return;
		}

		// Ищем CSS язык
		Language css = Language.findLanguageByID("CSS");
		if (css == null) {
			Parser3DebugLog.log("CSS", "CSS language not found!");
			return;
		}

		// Логируем только для первого хоста
		if (parts.get(0).host == host) {
			String fileName = host.getContainingFile() != null ? host.getContainingFile().getName() : "unknown";
			Parser3DebugLog.logSection("CSS INJECTION: " + fileName);
			Parser3DebugLog.log("CSS", "Файл: " + fileName);
			Parser3DebugLog.log("CSS", "Частей инжекции: " + parts.size());

			String virtualDoc = InjectorUtils.buildVirtualDocument(parts);
			Parser3DebugLog.logMultiline("CSS", "VIRTUAL CSS DOCUMENT", virtualDoc);
		}

		registrar.startInjecting(css);

		for (InjectorUtils.InjectionPart<Parser3CssBlock> part : parts) {
			int length = part.host.getTextLength();
			if (length <= 0) {
				continue;
			}
			registrar.addPlace(part.prefix, part.suffix, part.host, new TextRange(0, length));
		}

		registrar.doneInjecting();
	}

	@NotNull
	@Override
	public List<? extends Class<? extends PsiElement>> elementsToInjectIn() {
		return Collections.singletonList(Parser3CssBlock.class);
	}
}