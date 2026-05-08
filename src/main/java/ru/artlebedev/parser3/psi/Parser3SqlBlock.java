package ru.artlebedev.parser3.psi;

import com.intellij.extapi.psi.ASTWrapperPsiElement;
import com.intellij.lang.ASTNode;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.LiteralTextEscaper;
import com.intellij.psi.PsiLanguageInjectionHost;
import org.jetbrains.annotations.NotNull;

/**
 * PSI-хост для блока SQL внутри конструкций вида ^table::sql{ ... } и т.п.
 * Сам элемент содержит только текст между фигурными скобками, без внешних { и }.
 */
public class Parser3SqlBlock extends ASTWrapperPsiElement implements PsiLanguageInjectionHost {

	public Parser3SqlBlock(@NotNull ASTNode node) {
		super(node);
	}

	@Override
	public boolean isValidHost() {
		return true;
	}

	@Override
	public PsiLanguageInjectionHost updateText(@NotNull String text) {
		// На данном этапе поддержка редактирования через инъекцию не требуется.
		// Текст будет обновлён стандартными механизмами редактирования файла.
		return this;
	}

	@Override
	public @NotNull LiteralTextEscaper<? extends PsiLanguageInjectionHost> createLiteralTextEscaper() {
		return new LiteralTextEscaper<Parser3SqlBlock>(this) {
			@Override
			public boolean isOneLine() {
				return false;
			}

			@Override
			public boolean decode(@NotNull TextRange rangeInsideHost, @NotNull StringBuilder outChars) {
				CharSequence text = myHost.getText();
				int start = Math.max(0, rangeInsideHost.getStartOffset());
				int end = Math.min(text.length(), rangeInsideHost.getEndOffset());
				if (start > end) {
					return false;
				}
				outChars.append(text, start, end);
				return true;
			}

			@Override
			public int getOffsetInHost(int offsetInDecoded, @NotNull TextRange rangeInsideHost) {
				int result = rangeInsideHost.getStartOffset() + offsetInDecoded;
				if (result < rangeInsideHost.getStartOffset()) {
					return rangeInsideHost.getStartOffset();
				}
				if (result > rangeInsideHost.getEndOffset()) {
					return rangeInsideHost.getEndOffset();
				}
				return result;
			}

			@Override
			public @NotNull TextRange getRelevantTextRange() {
				return TextRange.from(0, myHost.getTextLength());
			}
		};
	}
}