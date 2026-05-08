package ru.artlebedev.parser3.index;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.util.indexing.FileBasedIndex;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.artlebedev.parser3.model.P3MethodDeclaration;
import ru.artlebedev.parser3.model.P3MethodParameter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Сервис для поиска объявлений методов в Parser3 файлах.
 */
@Service(Service.Level.PROJECT)
public final class P3MethodIndex {

	private final Project project;
	@SuppressWarnings("unused")
	private final PsiManager psiManager;

	public P3MethodIndex(@NotNull Project project) {
		this.project = project;
		this.psiManager = PsiManager.getInstance(project);
	}

	public static P3MethodIndex getInstance(@NotNull Project project) {
		return project.getService(P3MethodIndex.class);
	}

	public @NotNull List<P3MethodDeclaration> findByName(@NotNull String name) {
		List<P3MethodDeclaration> result = new ArrayList<>();
		FileBasedIndex index = FileBasedIndex.getInstance();
		GlobalSearchScope scope = P3IndexMaintenance.getParser3IndexScope(project);

		Collection<VirtualFile> files = index.getContainingFiles(P3MethodFileIndex.NAME, name, scope);
		for (VirtualFile file : files) {
			if (!file.isValid()) continue;
			result.addAll(findInFileByName(file, name));
		}

		return result;
	}

	public @NotNull List<P3MethodDeclaration> findInFile(@NotNull VirtualFile file) {
		List<P3MethodDeclaration> result = new ArrayList<>();
		FileBasedIndex index = FileBasedIndex.getInstance();
		var fileData = index.getFileData(P3MethodFileIndex.NAME, file, project);

		for (var entry : fileData.entrySet()) {
			String methodName = entry.getKey();
			for (P3MethodFileIndex.MethodInfo info : entry.getValue()) {
				result.add(createDeclaration(methodName, file, info));
			}
		}

		return result;
	}

	private @NotNull List<P3MethodDeclaration> findInFileByName(@NotNull VirtualFile file, @NotNull String name) {
		List<P3MethodDeclaration> result = new ArrayList<>();
		FileBasedIndex index = FileBasedIndex.getInstance();
		List<P3MethodFileIndex.MethodInfo> infos = index.getFileData(P3MethodFileIndex.NAME, file, project).get(name);

		if (infos != null) {
			for (P3MethodFileIndex.MethodInfo info : infos) {
				result.add(createDeclaration(name, file, info));
			}
		}

		return result;
	}

	private @NotNull P3MethodDeclaration createDeclaration(
			@NotNull String name,
			@NotNull VirtualFile file,
			@NotNull P3MethodFileIndex.MethodInfo info
	) {
		List<P3MethodParameter> docParams = new ArrayList<>();
		for (P3MethodFileIndex.DocParam param : info.docParams) {
			docParams.add(new P3MethodParameter(param.name, param.type, param.description, false));
		}

		P3MethodParameter docResult = null;
		if (info.docResult != null) {
			docResult = new P3MethodParameter(
					info.docResult.name,
					info.docResult.type,
					info.docResult.description,
					true
			);
		}

		return new P3MethodDeclaration(
				name,
				file,
				info.offset,
				null,
				info.ownerClass,
				info.parameterNames,
				info.docText,
				docParams,
				docResult,
				info.inferredResult,
				info.isGetter,
				info.callType
		);
	}

	public @Nullable P3MethodDeclaration findLastInFiles(
			@NotNull String name,
			@NotNull List<VirtualFile> visibleFiles
	) {
		P3MethodDeclaration last = null;
		for (VirtualFile file : visibleFiles) {
			if (!file.isValid()) continue;
			List<P3MethodDeclaration> fileDecls = findInFileByName(file, name);
			for (P3MethodDeclaration decl : fileDecls) {
				last = decl;
			}
		}
		return last;
	}

	public @NotNull List<P3MethodDeclaration> findInFiles(
			@NotNull String name,
			@NotNull List<VirtualFile> visibleFiles
	) {
		List<P3MethodDeclaration> result = new ArrayList<>();
		for (VirtualFile file : visibleFiles) {
			if (!file.isValid()) continue;
			result.addAll(findInFileByName(file, name));
		}
		return result;
	}

	public @NotNull Collection<String> getAllMethodNames() {
		List<String> names = new ArrayList<>();
		FileBasedIndex.getInstance().processAllKeys(
				P3MethodFileIndex.NAME,
				name -> {
					names.add(name);
					return true;
				},
				project
		);
		return names;
	}

	public @NotNull Collection<String> getMethodNamesInFiles(@NotNull List<VirtualFile> files) {
		List<String> names = new ArrayList<>();
		FileBasedIndex index = FileBasedIndex.getInstance();

		for (VirtualFile file : files) {
			if (!file.isValid()) continue;
			var fileData = index.getFileData(P3MethodFileIndex.NAME, file, project);
			names.addAll(fileData.keySet());
		}

		return names;
	}
}
