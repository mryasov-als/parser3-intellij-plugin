package ru.artlebedev.parser3.references;

import com.intellij.openapi.application.QueryExecutorBase;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.tree.IElementType;
import com.intellij.util.Processor;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Searches for references to Parser3 files in ^use[] and @USE directives.
 * Enables Find Usages for .p files.
 *
 * Использует P3UsageService как единый источник истины для поиска использований.
 */
public final class P3FileReferencesSearcher extends QueryExecutorBase<PsiReference, ReferencesSearch.SearchParameters> {

	public P3FileReferencesSearcher() {
		super(true); // require read action
	}

	@Override
	public void processQuery(
			@NotNull ReferencesSearch.SearchParameters queryParameters,
			@NotNull Processor<? super PsiReference> consumer
	) {
		PsiElement target = queryParameters.getElementToSearch();

		if (target instanceof PsiFile) {
			processFileQuery((PsiFile) target, consumer);
		} else if (target instanceof PsiDirectory) {
			processDirectoryQuery((PsiDirectory) target, consumer);
		}
	}

	private void processFileQuery(
			@NotNull PsiFile targetFile,
			@NotNull Processor<? super PsiReference> consumer
	) {
		VirtualFile targetVFile = targetFile.getVirtualFile();
		if (targetVFile == null) {
			return;
		}

		// Только Parser3 файлы
		if (!(targetVFile.getFileType() instanceof ru.artlebedev.parser3.Parser3FileType)) {
			return;
		}

		Project project = targetFile.getProject();
		searchReferencesToFile(project, targetVFile, consumer);
	}

	private void processDirectoryQuery(
			@NotNull PsiDirectory targetDir,
			@NotNull Processor<? super PsiReference> consumer
	) {
		VirtualFile targetVDir = targetDir.getVirtualFile();
		Project project = targetDir.getProject();

		// Используем P3UsageService для поиска файлов в директории
		P3UsageService usageService = P3UsageService.getInstance(project);
		List<VirtualFile> filesInDir = usageService.findFilesInDirectory(targetVDir);

		// Ищем references на каждый файл
		for (VirtualFile fileInDir : filesInDir) {
			searchReferencesToFile(project, fileInDir, consumer);
		}
	}

	private void searchReferencesToFile(
			@NotNull Project project,
			@NotNull VirtualFile targetVFile,
			@NotNull Processor<? super PsiReference> consumer
	) {
		// Используем P3UsageService — единый источник истины для поиска использований
		P3UsageService usageService = P3UsageService.getInstance(project);
		List<P3UsageService.FileUsage> usages = usageService.findUsages(targetVFile);

		PsiManager psiManager = PsiManager.getInstance(project);

		for (P3UsageService.FileUsage usage : usages) {
			PsiFile psiFile = psiManager.findFile(usage.sourceFile);
			if (psiFile == null) {
				continue;
			}

			// Находим PSI элемент по offset
			PsiElement element = psiFile.findElementAt(usage.offset);
			if (element == null) {
				continue;
			}

			// Поднимаемся к STRING, USE_PATH или комментарию
			while (element != null && element.getNode() != null) {
				IElementType type = element.getNode().getElementType();
				if (type == ru.artlebedev.parser3.Parser3TokenTypes.STRING ||
						type == ru.artlebedev.parser3.Parser3TokenTypes.USE_PATH ||
						type == ru.artlebedev.parser3.Parser3TokenTypes.BLOCK_COMMENT ||
						type == ru.artlebedev.parser3.Parser3TokenTypes.LINE_COMMENT) {
					break;
				}
				element = element.getParent();
			}

			if (element == null) {
				continue;
			}

			// Проверяем что element имеет containingFile (иначе будет NPE)
			if (element.getContainingFile() == null) {
				continue;
			}

			PsiReference ref = createReference(element, usage.usePath, usage.offset);
			if (ref != null) {
				if (!consumer.process(ref)) {
					return;
				}
			}
		}
	}

	private PsiReference createReference(@NotNull PsiElement element, @NotNull String path, int usageOffset) {
		String text = element.getText();
		int elementStart = element.getTextOffset();

		// Для комментариев — ищем путь по offset внутри текста элемента
		IElementType type = element.getNode() != null ? element.getNode().getElementType() : null;
		if (type == ru.artlebedev.parser3.Parser3TokenTypes.BLOCK_COMMENT ||
				type == ru.artlebedev.parser3.Parser3TokenTypes.LINE_COMMENT) {
			// usageOffset указывает на начало пути в файле
			int localOffset = usageOffset - elementStart;
			if (localOffset >= 0 && localOffset + path.length() <= text.length()) {
				TextRange range = TextRange.create(localOffset, localOffset + path.length());
				return new P3UseFileReference(element, range, path);
			}
			return null;
		}

		// Вычисляем TextRange для обычных токенов
		int start = 0;
		int end = text.length();

		// Убираем кавычки если есть
		if (text.length() >= 2) {
			char first = text.charAt(0);
			char last = text.charAt(text.length() - 1);
			if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
				start = 1;
				end = text.length() - 1;
			}
		}

		TextRange range = TextRange.create(start, end);
		return new P3UseFileReference(element, range, path);
	}
}