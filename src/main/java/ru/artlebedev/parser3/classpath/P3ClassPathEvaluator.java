package ru.artlebedev.parser3.classpath;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import org.jetbrains.annotations.NotNull;
import ru.artlebedev.parser3.settings.Parser3ProjectSettings;
import ru.artlebedev.parser3.visibility.P3AutoChainService;

import java.util.ArrayList;
import java.util.List;

/**
 * Вычисляет эффективный CLASS_PATH для заданного контекста.
 *
 * Логика:
 * - $CLASS_PATH[...] (ASSIGN) — полностью заменяет CLASS_PATH
 * - ^CLASS_PATH.append{...} (APPEND) — добавляет к существующему
 *
 * Порядок обработки файлов:
 * 1. main_auto (конфигурационный auto.p)
 * 2. auto.p chain (от корня к текущему файлу)
 * 3. Текущий файл (до позиции курсора) — TODO
 */
public final class P3ClassPathEvaluator {

	private final Project project;
	private final PsiManager psiManager;
	private final CachedValuesManager cachedValuesManager;

	public P3ClassPathEvaluator(@NotNull Project project) {
		this.project = project;
		this.psiManager = PsiManager.getInstance(project);
		this.cachedValuesManager = CachedValuesManager.getManager(project);
	}

	/**
	 * Вычисляет эффективный CLASS_PATH для заданного файлового контекста.
	 * Результат кешируется и инвалидируется при изменении любого из задействованных файлов.
	 */
	public @NotNull ClassPathResult evaluate(@NotNull VirtualFile contextFile) {

		PsiFile psiFile = psiManager.findFile(contextFile);
		if (psiFile == null) {
			return ClassPathResult.unknown("Файл не найден в PSI");
		}

		// getCachedValue возвращает результат напрямую
		ClassPathResult result = cachedValuesManager.getCachedValue(
				psiFile,
				() -> {
					// Собираем все зависимости для инвалидации кеша
					List<Object> dependencies = new ArrayList<>();
					dependencies.add(psiFile);

					Parser3ProjectSettings settings = Parser3ProjectSettings.getInstance(project);

					// Добавляем main_auto как зависимость
					VirtualFile mainAuto = settings.getMainAuto();
					if (mainAuto != null && mainAuto.isValid()) {
						PsiFile mainAutoPsi = psiManager.findFile(mainAuto);
						if (mainAutoPsi != null) {
							dependencies.add(mainAutoPsi);
						}
					}

					// Добавляем все auto.p из chain как зависимости
					P3AutoChainService autoChainService = P3AutoChainService.getInstance(project);
					List<VirtualFile> autoChain = autoChainService.buildChain(contextFile);
					for (VirtualFile autoP : autoChain) {
						PsiFile autoPsi = psiManager.findFile(autoP);
						if (autoPsi != null) {
							dependencies.add(autoPsi);
						}
					}

					ClassPathResult computedResult = evaluateInternal(contextFile);
					return CachedValueProvider.Result.create(
							computedResult,
							dependencies.toArray()
					);
				}
		);

		return result;
	}

	private @NotNull ClassPathResult evaluateInternal(@NotNull VirtualFile contextFile) {
		List<String> currentPaths = new ArrayList<>();
		Parser3ProjectSettings settings = Parser3ProjectSettings.getInstance(project);

		// 1. Обработать main_auto
		VirtualFile mainAuto = settings.getMainAuto();
		if (mainAuto != null && mainAuto.isValid()) {
			currentPaths = applyFileOperations(mainAuto, currentPaths);
		}

		// 2. Обработать auto.p chain
		P3AutoChainService autoChainService = P3AutoChainService.getInstance(project);
		List<VirtualFile> autoChain = autoChainService.buildChain(contextFile);

		for (VirtualFile autoP : autoChain) {
			// Пропускаем main_auto если он уже обработан и есть в chain
			if (mainAuto != null && autoP.equals(mainAuto)) {
				continue;
			}
			currentPaths = applyFileOperations(autoP, currentPaths);
		}

		// 3. Обработать текущий файл (CLASS_PATH может быть переопределён в нём)
		// Это важно для случаев когда $CLASS_PATH задан прямо в файле
		currentPaths = applyFileOperations(contextFile, currentPaths);

		if (currentPaths.isEmpty()) {
			return ClassPathResult.unknown("Присваивания CLASS_PATH не найдены");
		}

		return ClassPathResult.partial(currentPaths, "Найдено " + currentPaths.size() + " путей");
	}

	/**
	 * Применяет операции CLASS_PATH из файла к текущему списку путей.
	 *
	 * @param file файл для анализа
	 * @param currentPaths текущий CLASS_PATH
	 * @return новый CLASS_PATH после применения операций
	 */
	private @NotNull List<String> applyFileOperations(@NotNull VirtualFile file, @NotNull List<String> currentPaths) {
		PsiFile psiFile = psiManager.findFile(file);
		if (psiFile == null) {
			return currentPaths;
		}

		List<ClassPathOperation> operations = P3ClassPathExtractor.extractOperations(psiFile);

		if (operations.isEmpty()) {
			return currentPaths;
		}

		// Создаём копию для модификации
		List<String> result = new ArrayList<>(currentPaths);

		for (ClassPathOperation op : operations) {
			switch (op.getType()) {
				case ASSIGN:
					// Полная замена CLASS_PATH
					result = new ArrayList<>(op.getPaths());
					break;
				case APPEND:
					// Добавление к существующему (без дубликатов)
					for (String path : op.getPaths()) {
						if (!result.contains(path)) {
							result.add(path);
						}
					}
					break;
			}
		}

		return result;
	}

	/**
	 * Резолвит путь CLASS_PATH в директорию.
	 *
	 * Правила:
	 * - Абсолютный CLASS_PATH (начинается с /) — резолвится от document_root
	 * - Относительный CLASS_PATH — резолвится от директории контекстного файла
	 *
	 * @param classPath путь из CLASS_PATH
	 * @param contextFile файл, относительно которого резолвится путь
	 * @return директория CLASS_PATH или null если не найдена
	 */
	public @org.jetbrains.annotations.Nullable VirtualFile resolveClassPathDir(
			@NotNull String classPath,
			@NotNull VirtualFile contextFile
	) {
		Parser3ProjectSettings settings = Parser3ProjectSettings.getInstance(project);
		VirtualFile documentRoot = settings.getDocumentRootWithFallback();

		if (classPath.startsWith("/")) {
			// Абсолютный CLASS_PATH — от document_root
			if (documentRoot == null) {
				return null;
			}
			String relPath = classPath.substring(1);
			return documentRoot.findFileByRelativePath(relPath);
		} else {
			// Относительный CLASS_PATH — от директории контекстного файла
			VirtualFile contextDir = contextFile.getParent();
			if (contextDir == null) {
				return null;
			}
			return contextDir.findFileByRelativePath(classPath);
		}
	}

	/**
	 * Резолвит файл по пути относительно CLASS_PATH.
	 *
	 * @param filePath путь к файлу (например "User.p" или "models/User.p")
	 * @param contextFile файл, из которого происходит поиск
	 * @return найденный файл или null
	 */
	public @org.jetbrains.annotations.Nullable VirtualFile resolveInClassPath(
			@NotNull String filePath,
			@NotNull VirtualFile contextFile
	) {
		ClassPathResult classPathResult = evaluate(contextFile);

		if (classPathResult.getConfidence() == ClassPathResult.Confidence.UNKNOWN) {
			return null;
		}

		for (String classPath : classPathResult.getPaths()) {
			VirtualFile classPathDir = resolveClassPathDir(classPath, contextFile);

			if (classPathDir != null && classPathDir.isDirectory()) {
				VirtualFile file = classPathDir.findFileByRelativePath(filePath);
				if (file != null && file.exists()) {
					return file;
				}
			}
		}

		return null;
	}

	/**
	 * Возвращает все директории CLASS_PATH для контекста.
	 *
	 * @param contextFile файл контекста
	 * @return список директорий CLASS_PATH
	 */
	public @NotNull List<VirtualFile> getClassPathDirs(@NotNull VirtualFile contextFile) {
		List<VirtualFile> result = new ArrayList<>();
		ClassPathResult classPathResult = evaluate(contextFile);

		if (classPathResult.getConfidence() == ClassPathResult.Confidence.UNKNOWN) {
			return result;
		}

		for (String classPath : classPathResult.getPaths()) {
			VirtualFile classPathDir = resolveClassPathDir(classPath, contextFile);
			if (classPathDir != null && classPathDir.isDirectory()) {
				result.add(classPathDir);
			}
		}

		return result;
	}
}