package ru.artlebedev.parser3.injector;

import com.intellij.lang.Language;
import com.intellij.lang.injection.MultiHostInjector;
import com.intellij.lang.injection.MultiHostRegistrar;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;
import ru.artlebedev.parser3.psi.Parser3JsBlock;

import java.util.Collections;
import java.util.List;

/**
 * Инжектор JavaScript для токенов JS_DATA.
 *
 * Собирает все JS_DATA токены внутри одного <script> блока в единый виртуальный документ.
 * Parser3 конструкции ($var, ^method[]) заменяются пробелами.
 */
public class Parser3JsInjector implements MultiHostInjector {

	@Override
	public void getLanguagesToInject(@NotNull MultiHostRegistrar registrar, @NotNull PsiElement context) {
		if (!(context instanceof Parser3JsBlock)) {
			return;
		}

		Parser3JsBlock host = (Parser3JsBlock) context;

		// Собираем все JS_DATA блоки между <script> и </script>
		List<InjectorUtils.InjectionPart<Parser3JsBlock>> parts =
				InjectorUtils.collectPartsForHtmlTag(Parser3JsBlock.class, host, "<script", "</script");

		if (parts.isEmpty()) {
			return;
		}

		// Проверяем что текущий host входит в список
		boolean hostInside = false;
		for (InjectorUtils.InjectionPart<Parser3JsBlock> part : parts) {
			if (part.host == host) {
				hostInside = true;
				break;
			}
		}
		if (!hostInside) {
			return;
		}

		// Ищем JavaScript язык
		Language js = Language.findLanguageByID("JavaScript");
		if (js == null) {
			js = Language.findLanguageByID("ECMAScript 6");
		}
		if (js == null) {
			return;
		}

		// Логируем полный виртуальный JS документ (только для первого хоста)
		if (InjectorDebug.ENABLED && parts.get(0).host == host) {
			String virtualDoc = InjectorUtils.buildVirtualDocument(parts);
			InjectorDebug.logVirtualDocument("JS", virtualDoc);
		}

		registrar.startInjecting(js);

		for (InjectorUtils.InjectionPart<Parser3JsBlock> part : parts) {
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
		return Collections.singletonList(Parser3JsBlock.class);
	}
}