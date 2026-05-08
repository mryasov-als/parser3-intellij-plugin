package ru.artlebedev.parser3.index;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.util.indexing.FileBasedIndex;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Поиск CSS-классов только внутри Parser3-файлов.
 * Внешние css/scss/less-файлы здесь намеренно не обрабатываются:
 * их и так умеет резолвить сама IDE, а самописный глобальный скан
 * приводил к тяжелым фризам на больших проектах.
 */
public class P3CssClassIndex {

	private static final boolean DEBUG = false;

	private final Project project;

	private P3CssClassIndex(@NotNull Project project) {
		this.project = project;
	}

	public static P3CssClassIndex getInstance(@NotNull Project project) {
		return new P3CssClassIndex(project);
	}

	public static class CssClassLocation {
		public final VirtualFile file;
		public final int offset;

		public CssClassLocation(@NotNull VirtualFile file, int offset) {
			this.file = file;
			this.offset = offset;
		}
	}

	public @NotNull List<CssClassLocation> findDefinitions(@NotNull String className) {
		List<CssClassLocation> result = new ArrayList<>();
		findInParser3Index(className, result);

		if (DEBUG) {
			System.out.println("[P3CssClassIndex] findDefinitions('" + className + "'): " + result.size() + " results");
		}

		return result;
	}

	public @NotNull Map<String, String> getAllClassNames() {
		Map<String, String> result = new LinkedHashMap<>();

		FileBasedIndex index = FileBasedIndex.getInstance();
		GlobalSearchScope scope = P3IndexMaintenance.getParser3IndexScope(project);

		index.processAllKeys(P3CssClassFileIndex.NAME, className -> {
			if (result.containsKey(className)) {
				return true;
			}

			Collection<VirtualFile> files = index.getContainingFiles(P3CssClassFileIndex.NAME, className, scope);
			if (!files.isEmpty()) {
				result.put(className, files.iterator().next().getName());
			}
			return true;
		}, scope, null);

		if (DEBUG) {
			System.out.println("[P3CssClassIndex] getAllClassNames: " + result.size() + " classes");
		}

		return result;
	}

	private void findInParser3Index(@NotNull String className, @NotNull List<CssClassLocation> result) {
		FileBasedIndex index = FileBasedIndex.getInstance();
		GlobalSearchScope scope = P3IndexMaintenance.getParser3IndexScope(project);

		Collection<VirtualFile> files = index.getContainingFiles(P3CssClassFileIndex.NAME, className, scope);

		for (VirtualFile file : files) {
			if (!file.isValid()) {
				continue;
			}

			Map<String, List<P3CssClassFileIndex.CssClassInfo>> fileData =
					index.getFileData(P3CssClassFileIndex.NAME, file, project);

			List<P3CssClassFileIndex.CssClassInfo> infos = fileData.get(className);
			if (infos == null) {
				continue;
			}

			for (P3CssClassFileIndex.CssClassInfo info : infos) {
				result.add(new CssClassLocation(file, info.offset));
			}
		}
	}

	public @Nullable PsiElement resolveToElement(@NotNull CssClassLocation location) {
		PsiManager psiManager = PsiManager.getInstance(project);
		PsiFile psiFile = psiManager.findFile(location.file);
		if (psiFile == null) {
			return null;
		}
		return psiFile.findElementAt(location.offset);
	}
}
