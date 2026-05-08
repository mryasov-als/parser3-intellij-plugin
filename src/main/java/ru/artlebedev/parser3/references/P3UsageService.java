package ru.artlebedev.parser3.references;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.util.indexing.FileBasedIndex;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.artlebedev.parser3.Parser3FileType;
import ru.artlebedev.parser3.index.P3IndexMaintenance;
import ru.artlebedev.parser3.use.P3UseResolver;
import ru.artlebedev.parser3.utils.P3UsePathUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * ЕДИНЫЙ сервис для работы с use-ссылками в Parser3 файлах.
 *
 * Это ЕДИНСТВЕННЫЙ источник истины для:
 * - Поиска всех ссылок на файл (findUsages)
 * - Обновления ссылок при перемещении/переименовании (updateUsages)
 * - Вычисления новых путей (computeNewPath)
 *
 * Все остальные классы должны использовать этот сервис:
 * - P3FileReferencesSearcher -> findUsages()
 * - P3RefactoringListenerProvider -> findUsages() + updateUsages()
 * - P3UseFileReference -> computeNewPath()
 */
@Service(Service.Level.PROJECT)
public final class P3UsageService {

	private static final Logger LOG = Logger.getInstance(P3UsageService.class);

	private final Project project;

	public P3UsageService(@NotNull Project project) {
		this.project = project;
	}

	public static P3UsageService getInstance(@NotNull Project project) {
		return project.getService(P3UsageService.class);
	}

	/**
	 * Информация о найденном использовании файла.
	 */
	public static final class FileUsage {
		public final VirtualFile sourceFile;
		public final String sourceFilePath;
		public final String usePath;
		public final int lineNumber;
		public final int offset;
		public final P3UsePathUtils.UseDirectiveType directiveType;

		public FileUsage(@NotNull VirtualFile sourceFile, @NotNull String usePath,
						 int lineNumber, int offset,
						 @Nullable P3UsePathUtils.UseDirectiveType directiveType) {
			this.sourceFile = sourceFile;
			this.sourceFilePath = sourceFile.getPath();
			this.usePath = usePath;
			this.lineNumber = lineNumber;
			this.offset = offset;
			this.directiveType = directiveType;
		}
	}

	/**
	 * Находит все использования указанного файла в проекте.
	 *
	 * ОПТИМИЗИРОВАНО: использует индекс P3UseFileIndex для быстрого поиска.
	 * Вместо сканирования всех файлов проекта, ищем только файлы которые
	 * содержат USE с именем целевого файла.
	 *
	 * Используется для:
	 * - Find Usages в IDEA
	 * - Подготовки к рефакторингу (move/rename)
	 *
	 * @param targetFile файл, использования которого ищем
	 * @return список всех мест где файл используется
	 */
	public @NotNull List<FileUsage> findUsages(@NotNull VirtualFile targetFile) {
		List<FileUsage> usages = new ArrayList<>();

		String targetFileName = targetFile.getName();

		FileBasedIndex index = FileBasedIndex.getInstance();
		P3UseResolver resolver = P3UseResolver.getInstance(project);
		GlobalSearchScope scope = P3IndexMaintenance.getParser3IndexScope(project);

		// Получаем файлы которые содержат USE с именем целевого файла
		Collection<VirtualFile> candidateFiles = index.getContainingFiles(
				ru.artlebedev.parser3.index.P3UseFileIndex.NAME,
				targetFileName,
				scope
		);

		for (VirtualFile sourceFile : candidateFiles) {

			// Не ищем ссылки в самом целевом файле
			if (sourceFile.equals(targetFile)) {
				continue;
			}

			// Получаем данные индекса для этого файла
			Map<String, List<ru.artlebedev.parser3.index.P3UseFileIndex.UseInfo>> fileData =
					index.getFileData(ru.artlebedev.parser3.index.P3UseFileIndex.NAME, sourceFile, project);

			List<ru.artlebedev.parser3.index.P3UseFileIndex.UseInfo> useInfos = fileData.get(targetFileName);
			if (useInfos == null) {
				continue;
			}

			for (ru.artlebedev.parser3.index.P3UseFileIndex.UseInfo useInfo : useInfos) {

				// Резолвим путь и проверяем что он указывает на целевой файл
				VirtualFile resolved = resolver.resolve(useInfo.fullPath, sourceFile);

				if (resolved != null && resolved.equals(targetFile)) {
					// Вычисляем номер строки из offset
					int lineNumber = computeLineNumber(sourceFile, useInfo.offset);

					usages.add(new FileUsage(
							sourceFile,
							useInfo.fullPath,
							lineNumber,
							useInfo.offset,
							null // directiveType не храним в индексе
					));
				}
			}
		}

		return usages;
	}

	/**
	 * Вычисляет номер строки по offset.
	 */
	private int computeLineNumber(@NotNull VirtualFile file, int offset) {
		try {
			com.intellij.openapi.editor.Document document =
					com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(file);
			if (document != null) {
				return document.getLineNumber(offset) + 1;
			}
		} catch (Exception e) {
			// Игнорируем ошибки
		}
		return 1;
	}

	/**
	 * Вычисляет новый путь для use-директивы после перемещения файла.
	 * Использует P3UsePathUtils как единый источник истины.
	 *
	 * @param usage информация об использовании
	 * @param oldTargetPath старый путь целевого файла
	 * @param newTargetPath новый путь целевого файла
	 * @return новый путь для use-директивы, или null если не удалось вычислить
	 */
	public @Nullable String computeNewUsePath(@NotNull FileUsage usage,
											  @NotNull String oldTargetPath,
											  @NotNull String newTargetPath) {
		LOG.warn("[P3Usage] computeNewUsePath: usePath='" + usage.usePath +
				"', old=" + oldTargetPath + ", new=" + newTargetPath);

		// Используем единую функцию из P3UsePathUtils
		String result = P3UsePathUtils.computeNewUsePath(usage.usePath, oldTargetPath, newTargetPath);

		LOG.warn("[P3Usage] computeNewUsePath: result='" + result + "'");
		return result;
	}

	/**
	 * Находит все файлы в директории (рекурсивно).
	 */
	public @NotNull List<VirtualFile> findFilesInDirectory(@NotNull VirtualFile directory) {
		List<VirtualFile> files = new ArrayList<>();
		collectFilesRecursively(directory, files);
		return files;
	}

	private void collectFilesRecursively(@NotNull VirtualFile dir, @NotNull List<VirtualFile> result) {
		for (VirtualFile child : dir.getChildren()) {
			if (child.isDirectory()) {
				collectFilesRecursively(child, result);
			} else if (child.getFileType() == Parser3FileType.INSTANCE) {
				result.add(child);
			}
		}
	}
}
