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
import ru.artlebedev.parser3.model.P3MethodDeclaration;

import javax.swing.*;

/**
 * Виртуальный PSI-элемент для показа документации пользовательских методов.
 * Используется в Parser3DocumentationProvider для показа Ctrl+Q в автокомплите.
 */
public class Parser3UserMethodDocPsiElement extends LightElement implements NavigationItem {

	private final @NotNull P3MethodDeclaration declaration;

	public Parser3UserMethodDocPsiElement(@NotNull PsiManager manager, @NotNull P3MethodDeclaration declaration) {
		super(manager, Parser3Language.INSTANCE);
		this.declaration = declaration;
	}

	public @NotNull P3MethodDeclaration getDeclaration() {
		return declaration;
	}

	// NavigationItem

	public @Nullable String getName() {
		return declaration.getName();
	}

	public void navigate(boolean requestFocus) {
		// Можно добавить навигацию к определению метода
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
				return "@" + declaration.getName();
			}

			public @Nullable String getLocationString() {
				return declaration.getFile().getName();
			}

			public @Nullable Icon getIcon(boolean unused) {
				return Parser3Icons.FileMethod;
			}

			public @Nullable TextAttributesKey getTextAttributesKey() {
				return null;
			}
		};
	}

	public String toString() {
		return "Parser3UserMethodDocPsiElement(" + declaration.getName() + ')';
	}
}