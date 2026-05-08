package ru.artlebedev.parser3.references;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.artlebedev.parser3.classpath.P3ClassPathEvaluator;
import ru.artlebedev.parser3.use.P3UseResolver;

/**
 * PSI reference for ^use[file] and @USE file paths.
 * Enables: Find Usages, Safe Delete preview, Go to Declaration, Rename refactoring.
 */
public final class P3UseFileReference extends PsiReferenceBase<PsiElement> {

	private static final Logger LOG = Logger.getInstance(P3UseFileReference.class);

	private final String usePath;

	// Кешируем путь к resolved файлу для корректного refactoring
	private String resolvedFilePath;

	// Кешируем компоненты resolved пути для определения что переименовывается
	private String[] resolvedPathComponents;

	// Кешируем resolved элемент
	private PsiElement resolvedElement;

	public P3UseFileReference(@NotNull PsiElement element, @NotNull TextRange range, @NotNull String usePath) {
		super(element, range, true); // true = hard reference (supports rename)
		this.usePath = usePath;
	}

	@Override
	public @Nullable PsiElement resolve() {
		PsiFile file = getElement().getContainingFile();
		if (file == null || file.getVirtualFile() == null) {
			return null;
		}

		// Резолвим путь через P3UseResolver
		P3UseResolver resolver = P3UseResolver.getInstance(getElement().getProject());
		VirtualFile targetFile = resolver.resolve(usePath, file.getVirtualFile());

		if (targetFile == null) {
			resolvedFilePath = null;
			resolvedPathComponents = null;
			resolvedElement = null;
			return null;
		}

		// Сохраняем путь для использования в refactoring
		resolvedFilePath = targetFile.getPath();
		resolvedPathComponents = resolvedFilePath.replace('\\', '/').split("/");

		// Возвращаем PsiFile
		PsiManager psiManager = PsiManager.getInstance(getElement().getProject());
		resolvedElement = psiManager.findFile(targetFile);
		return resolvedElement;
	}

	@Override
	public @NotNull Object[] getVariants() {
		// Автодополнение через P3UseCompletionContributor
		return EMPTY_ARRAY;
	}

	/**
	 * Обработка переименования файла или директории.
	 * Вызывается когда пользователь переименовывает целевой элемент.
	 */
	@Override
	public PsiElement handleElementRename(@NotNull String newElementName) throws IncorrectOperationException {

		// Получаем старое имя директории из P3RefactoringListenerProvider
		String oldDirName = P3RefactoringListenerProvider.getRenamingDirectoryOldName();

		int lastSlash = usePath.lastIndexOf('/');

		if (lastSlash < 0) {
			// Простой путь без слэша (например "helper.p")
			// Это может быть:
			// 1. Имя файла — тогда newElementName тоже должно быть именем файла
			// 2. CLASS_PATH путь к файлу — если переименовывается директория, НЕ меняем

			// Проверяем: это переименование директории?
			if (oldDirName != null && !oldDirName.isEmpty()) {
				// Если usePath не содержит oldDirName — это CLASS_PATH путь, не трогаем
				if (!usePath.equals(oldDirName) && !usePath.contains(oldDirName + "/") && !usePath.contains("/" + oldDirName)) {
					return getElement(); // Не меняем
				}
			}

			// Проверяем что newElementName похоже на имя файла (содержит расширение)
			if (!newElementName.contains(".") && usePath.contains(".")) {
				// newElementName — имя директории, а usePath — имя файла
				// Это переименование директории, но usePath не содержит эту директорию
				// Значит это CLASS_PATH путь — не трогаем
				return getElement();
			}

			registerChange(usePath, newElementName);
			return updateElementText(newElementName);
		}

		// Получаем имя файла из usePath
		String fileName = usePath.substring(lastSlash + 1);

		// Проверяем: это переименование файла или директории?
		// Если oldDirName совпадает с именем файла — это переименование файла, не директории
		if (oldDirName != null && oldDirName.equals(fileName)) {
			// oldDirName на самом деле имя файла — это переименование файла
			String newPath = replaceFileName(usePath, newElementName);
			registerChange(usePath, newPath);
			return updateElementText(newPath);
		}

		// Если есть старое имя директории и оно есть в usePath как ДИРЕКТОРИЯ (не файл) — используем его
		if (oldDirName != null && !oldDirName.isEmpty()) {
			// Проверяем что oldDirName есть в usePath как компонент ДИРЕКТОРИИ (с / после него)
			String searchPattern1 = "/" + oldDirName + "/";
			String searchPattern2 = oldDirName + "/"; // в начале пути

			if (usePath.contains(searchPattern1) || usePath.startsWith(searchPattern2)) {
				// Это переименование директории
				String newPath = replaceDirectoryByName(usePath, oldDirName, newElementName);
				registerChange(usePath, newPath);
				return updateElementText(newPath);
			}
		}

		// Это переименование файла
		String newPath = replaceFileName(usePath, newElementName);
		registerChange(usePath, newPath);
		return updateElementText(newPath);
	}

	/**
	 * Регистрирует изменение USE пути для уведомления.
	 */
	private void registerChange(@NotNull String oldPath, @NotNull String newPath) {
		if (oldPath.equals(newPath)) {
			return;
		}

		PsiFile containingFile = getElement().getContainingFile();
		if (containingFile == null) {
			return;
		}

		VirtualFile vFile = containingFile.getVirtualFile();
		String filePath = vFile != null ? vFile.getPath() : "";
		String fileName = vFile != null ? vFile.getName() : "unknown";
		int offset = getElement().getTextOffset();

		// Вычисляем номер строки
		int lineNumber = 0;
		com.intellij.openapi.editor.Document document =
				com.intellij.psi.PsiDocumentManager.getInstance(containingFile.getProject()).getDocument(containingFile);
		if (document != null) {
			lineNumber = document.getLineNumber(offset) + 1; // +1 т.к. строки 1-based
		}

		P3RefactoringListenerProvider.registerUsePathChange(filePath, fileName, oldPath, newPath, offset, lineNumber);
	}

	/**
	 * Заменяет директорию с указанным именем на новое имя.
	 */
	private @NotNull String replaceDirectoryByName(@NotNull String path, @NotNull String oldDirName, @NotNull String newDirName) {
		// Заменяем точное вхождение как компонент пути
		String result = path;

		// Проверяем разные варианты
		if (result.startsWith(oldDirName + "/")) {
			result = newDirName + result.substring(oldDirName.length());
		} else if (result.startsWith("/" + oldDirName + "/")) {
			result = "/" + newDirName + result.substring(oldDirName.length() + 1);
		} else if (result.contains("/" + oldDirName + "/")) {
			result = result.replace("/" + oldDirName + "/", "/" + newDirName + "/");
		}

		return result;
	}

	/**
	 * Обработка перемещения файла или директории.
	 * Вызывается когда целевой файл/директория перемещается.
	 */
	@Override
	public PsiElement bindToElement(@NotNull PsiElement element) throws IncorrectOperationException {

		if (element instanceof PsiFile) {
			return bindToFile((PsiFile) element);
		}

		if (element instanceof PsiDirectory) {
			return bindToDirectory((PsiDirectory) element);
		}

		return getElement();
	}

	private PsiElement bindToFile(@NotNull PsiFile targetFile) throws IncorrectOperationException {

		VirtualFile targetVFile = targetFile.getVirtualFile();
		if (targetVFile == null) {
			return getElement();
		}


		// Если у нас есть сохранённый путь к старому файлу — используем простую замену
		// Это гарантирует что мы не перепутаем CLASS_PATH и относительный путь
		if (resolvedFilePath != null) {
			String oldPath = resolvedFilePath;
			String newPath = targetVFile.getPath();

			// Вычисляем как изменился путь файла
			String updatedUsePath = updatePathBasedOnFileMove(usePath, oldPath, newPath);

			if (updatedUsePath != null) {
				registerChange(usePath, updatedUsePath);
				return updateElementText(updatedUsePath);
			}
		}

		// Fallback: используем старую логику вычисления пути
		String newPath = computeNewPath(targetVFile);

		if (newPath == null) {
			return getElement();
		}

		registerChange(usePath, newPath);
		return updateElementText(newPath);
	}

	/**
	 * Обновляет путь в use на основе перемещения файла.
	 * Использует P3UsageService как единый источник истины для вычисления путей.
	 */
	private @Nullable String updatePathBasedOnFileMove(@NotNull String usePath, @NotNull String oldFilePath, @NotNull String newFilePath) {
		// Используем общую логику из P3UsageService
		return ru.artlebedev.parser3.utils.P3UsePathUtils.computeNewUsePath(usePath, oldFilePath, newFilePath);
	}

	private PsiElement bindToDirectory(@NotNull PsiDirectory targetDir) throws IncorrectOperationException {
		VirtualFile targetVDir = targetDir.getVirtualFile();

		// При перемещении директории, usePath содержит путь к файлу внутри этой директории
		// targetVDir — перемещённая директория
		// Нужно найти файл внутри и вычислить новый путь

		// Извлекаем имя файла из usePath
		String fileName = extractFileName(usePath);
		if (fileName == null) {
			return getElement();
		}

		// Ищем файл в новой директории
		VirtualFile targetFile = findFileInDirectory(targetVDir, fileName);
		if (targetFile == null) {
			return getElement();
		}

		// Если есть сохранённый путь — используем его для точного вычисления
		if (resolvedFilePath != null) {
			String oldPath = resolvedFilePath;
			String newPath = targetFile.getPath();

			String updatedUsePath = updatePathBasedOnFileMove(usePath, oldPath, newPath);
			if (updatedUsePath != null) {
				registerChange(usePath, updatedUsePath);
				return updateElementText(updatedUsePath);
			}
		}

		// Fallback: используем computeNewPath
		String newPath = computeNewPath(targetFile);
		if (newPath == null) {
			return getElement();
		}

		registerChange(usePath, newPath);
		return updateElementText(newPath);
	}

	/**
	 * Извлекает имя файла из пути.
	 */
	private @Nullable String extractFileName(@NotNull String path) {
		int lastSlash = path.lastIndexOf('/');
		if (lastSlash >= 0 && lastSlash < path.length() - 1) {
			return path.substring(lastSlash + 1);
		}
		return path; // путь без слэшей — это и есть имя файла
	}

	/**
	 * Ищет файл по имени в директории (рекурсивно, если путь содержит поддиректории).
	 */
	private @Nullable VirtualFile findFileInDirectory(@NotNull VirtualFile dir, @NotNull String fileName) {
		// Сначала проверяем прямо в директории
		VirtualFile direct = dir.findChild(fileName);
		if (direct != null && !direct.isDirectory()) {
			return direct;
		}

		// Рекурсивно ищем в поддиректориях
		for (VirtualFile child : dir.getChildren()) {
			if (child.isDirectory()) {
				VirtualFile found = findFileInDirectory(child, fileName);
				if (found != null) {
					return found;
				}
			}
		}

		return null;
	}

	/**
	 * Заменяет имя файла в пути, сохраняя директорию.
	 * Расширение берётся из нового имени файла.
	 */
	private @NotNull String replaceFileName(@NotNull String path, @NotNull String newFileName) {
		int lastSlash = path.lastIndexOf('/');
		String directory = lastSlash >= 0 ? path.substring(0, lastSlash + 1) : "";

		return directory + newFileName;
	}

	/**
	 * Вычисляет новый путь для файла.
	 * Пытается сохранить стиль пути (абсолютный/относительный/CLASS_PATH).
	 */
	private @Nullable String computeNewPath(@NotNull VirtualFile targetFile) {
		Project project = getElement().getProject();
		PsiFile containingFile = getElement().getContainingFile();
		if (containingFile == null) {
			return null;
		}

		VirtualFile currentFile = containingFile.getVirtualFile();
		if (currentFile == null) {
			return null;
		}

		// Определяем тип пути
		PathType pathType = getPathType(usePath);

		switch (pathType) {
			case ABSOLUTE:
				// Абсолютный путь — вычисляем от document_root
				return computeAbsolutePath(project, targetFile);

			case RELATIVE:
				// Относительный путь — вычисляем от текущего файла
				return computeRelativePath(currentFile, targetFile);

			case CLASS_PATH:
				// CLASS_PATH путь — пробуем найти в CLASS_PATH
				return computeClassPathPath(project, targetFile);

			default:
				return null;
		}
	}

	/**
	 * Тип пути в ^use[].
	 */
	private enum PathType {
		ABSOLUTE,    // /classes/User.p
		RELATIVE,    // ../classes/User.p или ./User.p
		CLASS_PATH   // User.p (просто имя файла или путь без / в начале и без ../)
	}

	/**
	 * Определяет тип пути.
	 */
	private PathType getPathType(@NotNull String path) {
		if (path.startsWith("/")) {
			return PathType.ABSOLUTE;
		}
		if (path.startsWith("../") || path.startsWith("./")) {
			return PathType.RELATIVE;
		}
		// Путь без / в начале и без ../ — это CLASS_PATH путь
		// Например: User.p, subdir/User.p, inner_classes/helper.p
		return PathType.CLASS_PATH;
	}

	/**
	 * Вычисляет путь относительно CLASS_PATH.
	 * Возвращает null если файл не в CLASS_PATH — reference не будет обновлён.
	 */
	private @Nullable String computeClassPathPath(@NotNull Project project, @NotNull VirtualFile targetFile) {
		// Получаем CLASS_PATH директории через P3ClassPathEvaluator
		PsiFile containingFile = getElement().getContainingFile();
		if (containingFile == null || containingFile.getVirtualFile() == null) {
			return null;
		}

		ru.artlebedev.parser3.classpath.P3ClassPathEvaluator evaluator =
				new ru.artlebedev.parser3.classpath.P3ClassPathEvaluator(project);
		ru.artlebedev.parser3.classpath.ClassPathResult result =
				evaluator.evaluate(containingFile.getVirtualFile());

		java.util.List<String> classPathStrings = result.getPaths();
		String targetPath = targetFile.getPath();

		// Получаем document_root для резолва относительных CLASS_PATH
		ru.artlebedev.parser3.settings.Parser3ProjectSettings settings =
				ru.artlebedev.parser3.settings.Parser3ProjectSettings.getInstance(project);
		VirtualFile documentRoot = settings.getDocumentRootWithFallback();

		// Ищем, находится ли файл в одной из CLASS_PATH директорий
		for (String classPathStr : classPathStrings) {
			String classPathFullPath;
			if (classPathStr.startsWith("/") && documentRoot != null) {
				// Абсолютный путь от document_root
				classPathFullPath = documentRoot.getPath() + classPathStr;
			} else {
				classPathFullPath = classPathStr;
			}

			// Нормализуем путь (убираем /../ и т.д.)
			try {
				classPathFullPath = new java.io.File(classPathFullPath).getCanonicalPath().replace('\\', '/');
			} catch (java.io.IOException e) {
				// Fallback - используем как есть
			}

			if (targetPath.startsWith(classPathFullPath + "/")) {
				// Файл внутри CLASS_PATH директории
				return targetPath.substring(classPathFullPath.length() + 1);
			}
		}

		// Файл не в CLASS_PATH — не можем обновить путь
		return null;
	}

	/**
	 * Вычисляет абсолютный путь от document_root.
	 * Поддерживает пути вида /../data/... (выход за пределы document_root).
	 */
	private @Nullable String computeAbsolutePath(@NotNull Project project, @NotNull VirtualFile targetFile) {
		ru.artlebedev.parser3.settings.Parser3ProjectSettings settings =
				ru.artlebedev.parser3.settings.Parser3ProjectSettings.getInstance(project);
		VirtualFile documentRoot = settings.getDocumentRootWithFallback();

		if (documentRoot == null) {
			return null;
		}

		String targetPath = targetFile.getPath();
		String rootPath = documentRoot.getPath();

		// Проверяем, использовался ли путь с /../ (выход за document_root)
		if (usePath.startsWith("/../")) {
			// Это путь вида /../data/classes/file.p
			// Нужно вычислить относительный путь от родителя document_root
			VirtualFile rootParent = documentRoot.getParent();
			if (rootParent != null) {
				String parentPath = rootParent.getPath();
				if (targetPath.startsWith(parentPath + "/")) {
					String relativePath = targetPath.substring(parentPath.length());
					return "/.." + relativePath;
				}
			}
		}

		if (targetPath.startsWith(rootPath)) {
			// Файл внутри document_root
			return targetPath.substring(rootPath.length());
		}

		// Файл вне document_root — пробуем построить путь с /../
		VirtualFile rootParent = documentRoot.getParent();
		if (rootParent != null) {
			String parentPath = rootParent.getPath();
			if (targetPath.startsWith(parentPath + "/")) {
				String relativePath = targetPath.substring(parentPath.length());
				return "/.." + relativePath;
			}
		}

		return null;
	}

	/**
	 * Вычисляет относительный путь от текущего файла.
	 */
	/**
	 * Вычисляет относительный путь от текущего файла к целевому.
	 * Использует общую утилиту для единообразного поведения.
	 */
	private @Nullable String computeRelativePath(@NotNull VirtualFile currentFile, @NotNull VirtualFile targetFile) {
		return ru.artlebedev.parser3.utils.P3UsePathUtils.computeRelativePathFromFile(currentFile, targetFile);
	}

	/**
	 * Обновляет текст элемента новым путём.
	 */
	private PsiElement updateElementText(@NotNull String newPath) throws IncorrectOperationException {
		PsiElement element = getElement();
		PsiFile containingFile = element.getContainingFile();
		if (containingFile == null) {
			throw new IncorrectOperationException("Cannot find containing file");
		}

		com.intellij.openapi.editor.Document document =
				com.intellij.psi.PsiDocumentManager.getInstance(element.getProject())
						.getDocument(containingFile);

		if (document == null) {
			throw new IncorrectOperationException("Cannot find document");
		}

		// Вычисляем абсолютные позиции в документе
		TextRange rangeInElement = getRangeInElement();
		int elementStart = element.getTextOffset();
		int start = elementStart + rangeInElement.getStartOffset();
		int end = elementStart + rangeInElement.getEndOffset();

		// Заменяем текст напрямую в документе
		document.replaceString(start, end, newPath);

		// Коммитим изменения
		com.intellij.psi.PsiDocumentManager.getInstance(element.getProject())
				.commitDocument(document);

		return element;
	}
}