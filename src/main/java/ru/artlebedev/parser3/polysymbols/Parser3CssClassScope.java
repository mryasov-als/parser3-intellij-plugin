package ru.artlebedev.parser3.polysymbols;

import com.intellij.model.Pointer;
import com.intellij.polySymbols.PolySymbol;
import com.intellij.polySymbols.PolySymbolKind;
import com.intellij.polySymbols.PolySymbolQualifiedName;
import com.intellij.polySymbols.completion.PolySymbolCodeCompletionItem;
import com.intellij.polySymbols.css.CssSymbolKinds;
import com.intellij.polySymbols.query.PolySymbolCodeCompletionQueryParams;
import com.intellij.polySymbols.query.PolySymbolListSymbolsQueryParams;
import com.intellij.polySymbols.query.PolySymbolNameMatchQueryParams;
import com.intellij.polySymbols.query.PolySymbolQueryStack;
import com.intellij.polySymbols.query.PolySymbolScope;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.SmartPsiElementPointer;
import org.jetbrains.annotations.NotNull;
import ru.artlebedev.parser3.injector.Parser3DebugLog;
import ru.artlebedev.parser3.psi.Parser3CssBlock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Parser3CssClassScope implements PolySymbolScope {

	private static final Pattern CSS_CLASS_PATTERN = Pattern.compile("\\.([a-zA-Z_][a-zA-Z0-9_-]*)");

	private final SmartPsiElementPointer<PsiFile> filePointer;
	private final String fileName;

	public Parser3CssClassScope(@NotNull PsiFile file) {
		this.filePointer = SmartPointerManager.createPointer(file);
		this.fileName = file.getName();
		Parser3DebugLog.log("PolySymbols", "Создан Parser3CssClassScope для: " + fileName);
	}

	@NotNull
	@Override
	public List<PolySymbol> getSymbols(
			@NotNull PolySymbolKind kind,
			@NotNull PolySymbolListSymbolsQueryParams params,
			@NotNull PolySymbolQueryStack stack
	) {
		if (!CssSymbolKinds.CSS_CLASSES.equals(kind)) {
			return Collections.emptyList();
		}
		return collectSymbols(null);
	}

	@NotNull
	@Override
	public List<PolySymbol> getMatchingSymbols(
			@NotNull PolySymbolQualifiedName qualifiedName,
			@NotNull PolySymbolNameMatchQueryParams params,
			@NotNull PolySymbolQueryStack stack
	) {
		if (!CssSymbolKinds.CSS_CLASSES.equals(qualifiedName.getKind())) {
			return Collections.emptyList();
		}
		return collectSymbols(qualifiedName.getName());
	}

	private @NotNull List<PolySymbol> collectSymbols(String filterName) {
		PsiFile file = filePointer.getElement();
		if (file == null) {
			Parser3DebugLog.log("PolySymbols", "Файл недоступен для scope: " + fileName);
			return Collections.emptyList();
		}

		List<PolySymbol> result = new ArrayList<>();
		PsiElement child = file.getFirstChild();
		while (child != null) {
			if (child instanceof Parser3CssBlock) {
				String text = child.getText();
				if (text != null) {
					extractCssClasses(text, child, filterName, result);
				}
			}
			child = child.getNextSibling();
		}
		return result;
	}

	private void extractCssClasses(
			@NotNull String cssText,
			@NotNull PsiElement source,
			String filterName,
			@NotNull List<PolySymbol> result
	) {
		Matcher matcher = CSS_CLASS_PATTERN.matcher(cssText);
		while (matcher.find()) {
			String className = matcher.group(1);
			if (filterName != null && !filterName.equals(className)) {
				continue;
			}

			int start = matcher.start();
			if (start > 0) {
				char prevChar = cssText.charAt(start - 1);
				if (Character.isLetterOrDigit(prevChar) || prevChar == '-' || prevChar == '_') {
					continue;
				}
			}

			result.add(new Parser3CssClassSymbol(className, source));
		}
	}

	@NotNull
	@Override
	public List<PolySymbolCodeCompletionItem> getCodeCompletions(
			@NotNull PolySymbolQualifiedName qualifiedName,
			@NotNull PolySymbolCodeCompletionQueryParams params,
			@NotNull PolySymbolQueryStack stack
	) {
		return Collections.emptyList();
	}

	@Override
	public boolean isExclusiveFor(@NotNull PolySymbolKind kind) {
		return false;
	}

	@Override
	public long getModificationCount() {
		return 0;
	}

	@NotNull
	@Override
	public Pointer<Parser3CssClassScope> createPointer() {
		return new Parser3CssClassScopePointer(filePointer);
	}

	private static class Parser3CssClassScopePointer implements Pointer<Parser3CssClassScope> {
		private final SmartPsiElementPointer<PsiFile> filePointer;

		Parser3CssClassScopePointer(SmartPsiElementPointer<PsiFile> filePointer) {
			this.filePointer = filePointer;
		}

		@Override
		public Parser3CssClassScope dereference() {
			PsiFile file = filePointer.getElement();
			if (file != null && file.isValid()) {
				return new Parser3CssClassScope(file);
			}
			return null;
		}
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof Parser3CssClassScope that)) return false;
		PsiFile thisFile = filePointer.getElement();
		PsiFile thatFile = that.filePointer.getElement();
		if (thisFile == null || thatFile == null) {
			return thisFile == thatFile;
		}
		return thisFile.equals(thatFile);
	}

	@Override
	public int hashCode() {
		PsiFile file = filePointer.getElement();
		return file != null ? file.hashCode() : 0;
	}
}
