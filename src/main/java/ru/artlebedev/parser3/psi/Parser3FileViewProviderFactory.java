package ru.artlebedev.parser3.psi;

import com.intellij.lang.Language;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.FileViewProvider;
import com.intellij.psi.FileViewProviderFactory;
import com.intellij.psi.PsiManager;
import org.jetbrains.annotations.NotNull;

public final class Parser3FileViewProviderFactory implements FileViewProviderFactory {
	@Override
	public @NotNull FileViewProvider createFileViewProvider(
			@NotNull VirtualFile file,
			Language language,
			@NotNull PsiManager psiManager,
			boolean eventSystemEnabled
	) {
		return new Parser3FileViewProvider(psiManager, file, eventSystemEnabled);
	}
}