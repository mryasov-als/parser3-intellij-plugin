package ru.artlebedev.parser3.references;

import com.intellij.psi.PsiElement;
import com.intellij.psi.tree.IElementType;
import com.intellij.usages.impl.rules.UsageType;
import com.intellij.usages.impl.rules.UsageTypeProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.artlebedev.parser3.Parser3TokenTypes;

/**
 * Provides usage type "Use" for ^use[] and @USE references.
 */
public final class P3UsageTypeProvider implements UsageTypeProvider {

	private static final UsageType USE = new UsageType(() -> "@USE");

	@Override
	public @Nullable UsageType getUsageType(@NotNull PsiElement element) {
		if (element.getNode() == null) {
			return null;
		}

		IElementType type = element.getNode().getElementType();

		// USE_PATH токен — это уже путь в @USE
		if (type == Parser3TokenTypes.USE_PATH) {
			return USE;
		}

		// STRING токен — проверяем контекст ^use[]
		if (type == Parser3TokenTypes.STRING) {
			if (isInUseContext(element)) {
				return USE;
			}
		}

		return null;
	}

	private boolean isInUseContext(@NotNull PsiElement element) {
		// Проверяем ^use[] - ищем KW_USE слева
		PsiElement prev = element.getPrevSibling();
		while (prev != null) {
			if (prev.getNode() != null) {
				IElementType type = prev.getNode().getElementType();

				if (type == Parser3TokenTypes.KW_USE) {
					return true;
				}

				// Выход
				if (type == Parser3TokenTypes.RBRACKET ||
						type == Parser3TokenTypes.METHOD ||
						type == Parser3TokenTypes.DEFINE_METHOD ||
						type == Parser3TokenTypes.SPECIAL_METHOD) {
					break;
				}
			}
			prev = prev.getPrevSibling();
		}

		return false;
	}
}