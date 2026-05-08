package ru.artlebedev.parser3.injector;

import com.intellij.lang.Language;
import com.intellij.lang.injection.MultiHostInjector;
import com.intellij.lang.injection.MultiHostRegistrar;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.artlebedev.parser3.Parser3TokenTypes;
import ru.artlebedev.parser3.psi.Parser3HtmlBlock;
import ru.artlebedev.parser3.utils.P3MethodDeclarationUtils;

import java.util.Collections;
import java.util.List;

/**
 * MultiHost-инжектор для HTML внутри Parser3 файлов.
 *
 * Собирает все HTML_DATA токены в пределах одного метода в единый
 * виртуальный HTML документ для подсветки и автокомплита.
 *
 * Использует InjectorUtils.collectPartsForHtml() — единую функцию очистки Parser3 конструкций.
 */
public class Parser3HtmlInjector implements MultiHostInjector {

	@Override
	public void getLanguagesToInject(@NotNull MultiHostRegistrar registrar, @NotNull PsiElement context) {
		if (!(context instanceof Parser3HtmlBlock)) {
			return;
		}

		Parser3HtmlBlock host = (Parser3HtmlBlock) context;
		PsiFile file = host.getContainingFile();
		if (file == null) {
			return;
		}

		// Находим границы текущего метода
		MethodBoundary boundary = findMethodBoundary(host);
		if (boundary == null) {
			return;
		}

		// Используем единую функцию из InjectorUtils
		List<InjectorUtils.InjectionPart<Parser3HtmlBlock>> parts =
				InjectorUtils.collectPartsForHtml(file, boundary.startOffset, boundary.endOffset);

		if (parts.isEmpty()) {
			return;
		}

		// Проверяем что текущий host входит в список
		boolean hostInside = false;
		for (InjectorUtils.InjectionPart<Parser3HtmlBlock> part : parts) {
			if (part.host == host) {
				hostInside = true;
				break;
			}
		}
		if (!hostInside) {
			return;
		}

		// Ищем HTML язык
		Language html = Language.findLanguageByID("HTML");
		if (html == null) {
			return;
		}

		// Логируем виртуальный документ только один раз (для первого хоста)
		if (InjectorDebug.HTML_INJECTION_LOG && parts.get(0).host == host) {
			String virtualDoc = InjectorUtils.buildVirtualDocument(parts);
			InjectorDebug.logVirtualDocument("HTML", virtualDoc);
		}

		registrar.startInjecting(html);

		for (InjectorUtils.InjectionPart<Parser3HtmlBlock> part : parts) {
			int length = part.host.getTextLength();
			if (length <= 0) {
				continue;
			}
			registrar.addPlace(
					part.prefix,
					part.suffix,
					part.host,
					new TextRange(0, length)
			);
		}

		registrar.doneInjecting();
	}

	@NotNull
	@Override
	public List<? extends Class<? extends PsiElement>> elementsToInjectIn() {
		return Collections.singletonList(Parser3HtmlBlock.class);
	}

	/**
	 * Границы метода в файле.
	 */
	private static final class MethodBoundary {
		final int startOffset;
		final int endOffset;

		MethodBoundary(int startOffset, int endOffset) {
			this.startOffset = startOffset;
			this.endOffset = endOffset;
		}
	}

	/**
	 * Находит границы метода, в котором находится данный HTML блок.
	 */
	@Nullable
	private static MethodBoundary findMethodBoundary(@NotNull Parser3HtmlBlock host) {
		int hostOffset = host.getTextOffset();
		PsiFile file = host.getContainingFile();
		if (file == null) {
			return null;
		}

		int methodStart = 0;
		int methodEnd = file.getTextLength();

		// Ищем начало и конец метода по любому @-методу/директиве.
		PsiElement child = file.getFirstChild();
		int lastMethodStart = 0;

		while (child != null) {
			IElementType type = child.getNode() != null ? child.getNode().getElementType() : null;
			boolean isMethodBoundary = P3MethodDeclarationUtils.isMethodDeclarationToken(type);

			if (isMethodBoundary) {
				int offset = child.getTextOffset();
				if (offset <= hostOffset) {
					lastMethodStart = offset;
				} else {
					methodEnd = offset;
					break;
				}
			}

			child = child.getNextSibling();
		}

		methodStart = lastMethodStart;
		return new MethodBoundary(methodStart, methodEnd);
	}
}
