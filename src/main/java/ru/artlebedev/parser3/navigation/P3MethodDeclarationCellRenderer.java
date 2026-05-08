package ru.artlebedev.parser3.navigation;

import com.intellij.ide.util.PsiElementListCellRenderer;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.Nullable;
import ru.artlebedev.parser3.icons.Parser3Icons;
import ru.artlebedev.parser3.utils.Parser3PathFormatter;

import javax.swing.*;

/**
 * Рендерер для отображения методов в popup.
 * Формат: "method_name (path/to/file.p)"
 */
public final class P3MethodDeclarationCellRenderer extends PsiElementListCellRenderer<PsiElement> {

	@Override
	public String getElementText(PsiElement element) {
		// Имя метода - берём текст элемента
		String methodText = element.getText();

		// Извлекаем имя метода из @method_name[...]
		if (methodText.startsWith("@")) {
			int bracket = methodText.indexOf('[');
			if (bracket > 0) {
				methodText = methodText.substring(1, bracket);
			}
		}

		return methodText;
	}

	@Override
	protected @Nullable String getContainerText(PsiElement element, String name) {
		// Путь к файлу относительно document_root или проекта
		PsiFile containingFile = element.getContainingFile();
		if (containingFile == null) {
			return null;
		}

		VirtualFile virtualFile = containingFile.getVirtualFile();
		if (virtualFile == null) {
			return containingFile.getName();
		}

		return Parser3PathFormatter.formatFilePathForUI(virtualFile, element.getProject());
	}

	@Override
	protected @Nullable Icon getIcon(PsiElement element) {
		return Parser3Icons.File;
	}
}