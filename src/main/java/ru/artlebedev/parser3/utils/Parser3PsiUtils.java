package ru.artlebedev.parser3.utils;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import ru.artlebedev.parser3.Parser3FileType;
import ru.artlebedev.parser3.Parser3Language;

public class Parser3PsiUtils {
	public static boolean isParser3File(PsiFile file) {
		if (file == null) return false;
		if (file.getFileType() instanceof Parser3FileType) return true;
		if (file.getViewProvider().getBaseLanguage().isKindOf(Parser3Language.INSTANCE)) return true;
		if (file.getLanguage().isKindOf(Parser3Language.INSTANCE)) return true;
		// Для injected HTML/CSS/JS внутри .p файлов — проверяем host
		PsiElement context = file.getContext();
		if (context != null) {
			PsiFile hostFile = context.getContainingFile();
			if (hostFile != null && hostFile != file) {
				return isParser3File(hostFile);
			}
		}
		return false;
	}

	public static boolean isInjectedFragment(PsiFile file) {
		if (file == null) return false;
		PsiElement context = file.getContext();
		if (context == null) return false;
		PsiFile hostFile = context.getContainingFile();
		return hostFile != null && hostFile != file;
	}

}
