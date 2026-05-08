package ru.artlebedev.parser3.lang;

import com.intellij.navigation.ItemPresentation;
import com.intellij.navigation.NavigationItem;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.psi.PsiManager;
import com.intellij.psi.impl.light.LightElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.artlebedev.parser3.Parser3Language;
import ru.artlebedev.parser3.icons.Parser3Icons;

import javax.swing.*;

/**
 * Виртуальный PSI-элемент для показа локальных HTML-доков Parser3.
 * Используется только внутри Parser3DocumentationProvider.
 */
public class Parser3DocPsiElement extends LightElement implements NavigationItem {

	private final @NotNull String fileName;

	public Parser3DocPsiElement(@NotNull PsiManager manager, @NotNull String fileName) {
		super(manager, Parser3Language.INSTANCE);
		this.fileName = fileName;
	}

	public @NotNull String getDocFileName() {
		return fileName;
	}

	// NavigationItem

	public @Nullable String getName() {
		return fileName;
	}

	public void navigate(boolean requestFocus) {
		// Никуда не навигируем, элемент существует только для док-попапа.
	}

	public boolean canNavigate() {
		return false;
	}

	public boolean canNavigateToSource() {
		return false;
	}

	public @Nullable ItemPresentation getPresentation() {
		return new ItemPresentation() {

			public @Nullable String getPresentableText() {
				return "doc title";
			}

			public @Nullable String getLocationString() {
				return "Parser3 documentation";
			}

			public @Nullable Icon getIcon(boolean unused) {
				return Parser3Icons.File;
			}

			public @Nullable TextAttributesKey getTextAttributesKey() {
				return null;
			}
		};
	}

	public String toString() {
		return "Parser3DocPsiElement(" + this.fileName + ')';
	}

	public String getFileName() {
		return this.fileName;
	}
}