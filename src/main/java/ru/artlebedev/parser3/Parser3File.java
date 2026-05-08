package ru.artlebedev.parser3;

import com.intellij.extapi.psi.PsiFileBase;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.psi.FileViewProvider;
import org.jetbrains.annotations.NotNull;

public class Parser3File extends PsiFileBase {
	public Parser3File(@NotNull FileViewProvider viewProvider) {
		super(viewProvider, Parser3Language.INSTANCE);
	}

	@NotNull
	@Override
	public FileType getFileType() {
		return Parser3FileType.INSTANCE;
	}

	@Override
	public String toString() {
		return "Parser3 File";
	}
}