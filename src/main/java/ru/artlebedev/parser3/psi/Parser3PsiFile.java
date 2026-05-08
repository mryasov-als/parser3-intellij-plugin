package ru.artlebedev.parser3.psi;

import com.intellij.extapi.psi.PsiFileBase;
import com.intellij.psi.FileViewProvider;
import com.intellij.psi.templateLanguages.TemplateDataElementType;
import org.jetbrains.annotations.NotNull;
import ru.artlebedev.parser3.Parser3Language;
import ru.artlebedev.parser3.Parser3FileType;

public final class Parser3PsiFile extends PsiFileBase {
	public Parser3PsiFile(@NotNull FileViewProvider viewProvider) {
		super(viewProvider, Parser3Language.INSTANCE);
	}

	@Override
	public @NotNull Parser3FileType getFileType() {
		return Parser3FileType.INSTANCE;
	}

	@Override
	public TemplateDataElementType getContentElementType() {
		return Parser3TemplateDataElementType.INSTANCE;
	}

	@Override
	public @NotNull String toString() {
		return "Parser3 File";
	}
}