package ru.artlebedev.parser3.psi;

import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.SingleRootFileViewProvider;
import org.jetbrains.annotations.NotNull;
import ru.artlebedev.parser3.Parser3Language;

/**
 * ViewProvider для Parser3 файлов.
 * Parser3 - единственный язык, HTML пока отключен.
 */
public class Parser3FileViewProvider extends SingleRootFileViewProvider {

	public Parser3FileViewProvider(
			@NotNull PsiManager manager,
			@NotNull VirtualFile file,
			boolean eventSystemEnabled
	) {
		super(manager, file, eventSystemEnabled, Parser3Language.INSTANCE);
	}
}