package ru.artlebedev.parser3.polysymbols;

import com.intellij.model.Pointer;
import com.intellij.polySymbols.PolySymbolKind;
import com.intellij.polySymbols.css.CssSymbolKinds;
import com.intellij.polySymbols.search.PsiSourcedPolySymbol;
import com.intellij.psi.PsiElement;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.SmartPsiElementPointer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class Parser3CssClassSymbol implements PsiSourcedPolySymbol {

	private final String className;
	private final SmartPsiElementPointer<PsiElement> sourcePointer;

	public Parser3CssClassSymbol(@NotNull String className, @NotNull PsiElement source) {
		this.className = className;
		this.sourcePointer = SmartPointerManager.createPointer(source);
	}

	@NotNull
	@Override
	public PolySymbolKind getKind() {
		return CssSymbolKinds.CSS_CLASSES;
	}

	@NotNull
	@Override
	public String getName() {
		return className;
	}

	@Nullable
	@Override
	public PsiElement getSource() {
		return sourcePointer.getElement();
	}

	@NotNull
	@Override
	public Pointer<Parser3CssClassSymbol> createPointer() {
		return new Parser3CssClassSymbolPointer(className, sourcePointer);
	}

	private static class Parser3CssClassSymbolPointer implements Pointer<Parser3CssClassSymbol> {
		private final String className;
		private final SmartPsiElementPointer<PsiElement> sourcePointer;

		Parser3CssClassSymbolPointer(String className, SmartPsiElementPointer<PsiElement> sourcePointer) {
			this.className = className;
			this.sourcePointer = sourcePointer;
		}

		@Nullable
		@Override
		public Parser3CssClassSymbol dereference() {
			PsiElement element = sourcePointer.getElement();
			if (element != null && element.isValid()) {
				return new Parser3CssClassSymbol(className, element);
			}
			return null;
		}
	}
}
