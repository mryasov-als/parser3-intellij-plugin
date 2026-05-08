package ru.artlebedev.parser3.references;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.FileTypeIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.refactoring.listeners.RefactoringElementListener;
import com.intellij.refactoring.listeners.RefactoringElementListenerProvider;
import com.intellij.refactoring.listeners.UndoRefactoringElementListener;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.artlebedev.parser3.Parser3FileType;
import ru.artlebedev.parser3.psi.P3UseExtractor;
import ru.artlebedev.parser3.settings.Parser3ProjectSettings;
import ru.artlebedev.parser3.use.P3UseResolver;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Listens for Move/Rename refactoring of Parser3 files.
 * Shows warnings when references cannot be updated automatically.
 */
public final class P3RefactoringListenerProvider implements RefactoringElementListenerProvider {

	private static final Logger LOG = Logger.getInstance(P3RefactoringListenerProvider.class);

	// Старое имя переименовываемой директории (для передачи в handleElementRename)
	private static volatile String renamingDirectoryOldName = null;

	// Информация об изменённых USE путях
	private static final CopyOnWriteArrayList<UsePathChange> recentChanges = new CopyOnWriteArrayList<>();

	// === Для тестирования ===
	// Последнее уведомление об успешном обновлении путей
	private static volatile String lastSuccessNotification = null;
	// Последний список broken refs (для диалога с предупреждениями)
	private static volatile List<ClassPathReferenceInfo> lastBrokenRefs = null;
	// Количество показанных уведомлений (для тестов)
	private static volatile int successNotificationCount = 0;
	private static volatile int warningDialogCount = 0;

	/**
	 * Возвращает содержимое последнего уведомления об успешном обновлении (для тестов).
	 */
	public static String getLastSuccessNotificationForTest() {
		return lastSuccessNotification;
	}

	/**
	 * Возвращает последний список broken refs (для тестов).
	 */
	public static List<ClassPathReferenceInfo> getLastBrokenRefsForTest() {
		return lastBrokenRefs;
	}

	/**
	 * Возвращает количество показанных уведомлений об успехе (для тестов).
	 */
	public static int getSuccessNotificationCountForTest() {
		return successNotificationCount;
	}

	/**
	 * Возвращает количество показанных диалогов с предупреждениями (для тестов).
	 */
	public static int getWarningDialogCountForTest() {
		return warningDialogCount;
	}

	/**
	 * Сбрасывает все счётчики и данные для тестов.
	 */
	public static void resetTestState() {
		lastSuccessNotification = null;
		lastBrokenRefs = null;
		successNotificationCount = 0;
		warningDialogCount = 0;
		recentChanges.clear();
	}

	/**
	 * Вычисляет относительный путь файла от document_root или корня проекта.
	 */
	private static String getRelativePath(@NotNull Project project, @NotNull String absolutePath) {
		Parser3ProjectSettings settings = Parser3ProjectSettings.getInstance(project);
		VirtualFile documentRoot = settings.getDocumentRootWithFallback();

		String basePath = null;
		if (documentRoot != null) {
			basePath = documentRoot.getPath();
		}
		if (basePath == null && project.getBasePath() != null) {
			basePath = project.getBasePath();
		}

		if (basePath != null && absolutePath.startsWith(basePath)) {
			String relativePath = absolutePath.substring(basePath.length());
			if (relativePath.startsWith("/")) {
				relativePath = relativePath.substring(1);
			}
			return relativePath.isEmpty() ? absolutePath : relativePath;
		}

		return absolutePath;
	}

	/**
	 * Информация об изменении USE пути.
	 */
	public static class UsePathChange {
		public final String filePath;
		public final String fileName;
		public final String oldUsePath;
		public final String newUsePath;
		public final int offset;
		public final int lineNumber;

		public UsePathChange(String filePath, String fileName, String oldUsePath, String newUsePath, int offset, int lineNumber) {
			this.filePath = filePath;
			this.fileName = fileName;
			this.oldUsePath = oldUsePath;
			this.newUsePath = newUsePath;
			this.offset = offset;
			this.lineNumber = lineNumber;
		}

		@Override
		public String toString() {
			return fileName + ": " + oldUsePath + " → " + newUsePath;
		}
	}

	/**
	 * Регистрирует изменение USE пути.
	 * Вызывается из P3UseFileReference.handleElementRename()
	 */
	public static void registerUsePathChange(String filePath, String fileName, String oldUsePath, String newUsePath, int offset, int lineNumber) {
		recentChanges.add(new UsePathChange(filePath, fileName, oldUsePath, newUsePath, offset, lineNumber));
	}

	/**
	 * Удаляет изменение USE пути (при rollback).
	 */
	public static void removeUsePathChange(String filePath, int lineNumber) {
		recentChanges.removeIf(change ->
				change.filePath.equals(filePath) &&
						isOnSameLine(change.offset, lineNumber, filePath)
		);
	}

	/**
	 * Проверяет находится ли offset на указанной строке.
	 */
	private static boolean isOnSameLine(int offset, int lineNumber, String filePath) {
		// Простая проверка — если offset близок к lineNumber * средняя длина строки
		// Более точная проверка требует доступа к файлу
		// Пока используем приблизительную оценку
		VirtualFile file = LocalFileSystem.getInstance().findFileByPath(filePath);
		if (file == null) {
			return false;
		}
		try {
			String content = new String(file.contentsToByteArray(), file.getCharset());
			int currentLine = 1;
			for (int i = 0; i < offset && i < content.length(); i++) {
				if (content.charAt(i) == '\n') {
					currentLine++;
				}
			}
			return currentLine == lineNumber;
		} catch (Exception e) {
			return false;
		}
	}

	/**
	 * Показывает уведомление об изменённых USE путях.
	 */
	public static void showChangesNotification(@NotNull Project project) {
		if (recentChanges.isEmpty()) {
			return;
		}

		List<UsePathChange> changes = new ArrayList<>(recentChanges);
		recentChanges.clear();

		// Считаем количество уникальных файлов
		java.util.Set<String> uniqueFiles = new java.util.HashSet<>();
		for (UsePathChange change : changes) {
			uniqueFiles.add(change.filePath);
		}
		int pathCount = changes.size();
		int fileCount = uniqueFiles.size();

		String message = formatChangesMessage(pathCount, fileCount);

		// Сохраняем для тестов
		lastSuccessNotification = message;
		successNotificationCount++;

		Notification notification = NotificationGroupManager.getInstance()
				.getNotificationGroup("Parser3 Refactoring")
				.createNotification("Parser3: Пути обновлены", message, NotificationType.INFORMATION);

		notification.addAction(new NotificationAction("Показать изменения") {
			@Override
			public void actionPerformed(@NotNull AnActionEvent e, @NotNull Notification notification) {
				showChangesDialog(project, changes);
				notification.expire();
			}
		});

		notification.notify(project);
	}

	/**
	 * Показывает уведомление о потенциальных проблемах (когда "search for references" был выключен).
	 * По клику на уведомление открывается диалог со списком файлов.
	 */
	private static void showPotentialIssuesNotification(@NotNull Project project, @NotNull List<ClassPathReferenceInfo> refs) {
		if (refs.isEmpty()) {
			return;
		}

		// Считаем уникальные файлы
		java.util.Set<String> uniqueFiles = new java.util.HashSet<>();
		for (ClassPathReferenceInfo ref : refs) {
			uniqueFiles.add(ref.sourceFilePath);
		}
		int refCount = refs.size();
		int fileCount = uniqueFiles.size();

		String message = formatPotentialIssuesMessage(refCount, fileCount);

		Notification notification = NotificationGroupManager.getInstance()
				.getNotificationGroup("Parser3 Refactoring")
				.createNotification("Parser3: Проверьте ссылки", message, NotificationType.WARNING);

		notification.addAction(new NotificationAction("Показать список") {
			@Override
			public void actionPerformed(@NotNull AnActionEvent e, @NotNull Notification notification) {
				showBrokenReferencesDialogStatic(project, refs);
				notification.expire();
			}
		});

		notification.notify(project);
	}

	/**
	 * Форматирует сообщение о потенциальных проблемах.
	 */
	private static String formatPotentialIssuesMessage(int refCount, int fileCount) {
		String refWord = formatReferenceWord(refCount);
		String fileWord = formatFileWord(fileCount);
		String mayWord = refCount == 1 ? "могла" : "могли";
		return refCount + " " + refWord + " в " + fileCount + " " + fileWord + " " + mayWord + " перестать работать";
	}

	private static String formatReferenceWord(int count) {
		int lastTwo = count % 100;
		int lastOne = count % 10;

		if (lastTwo >= 11 && lastTwo <= 19) {
			return "ссылок";
		}
		if (lastOne == 1) {
			return "ссылка";
		}
		if (lastOne >= 2 && lastOne <= 4) {
			return "ссылки";
		}
		return "ссылок";
	}

	/**
	 * Статический метод для показа диалога с broken refs (вызывается из notification action).
	 */
	private static void showBrokenReferencesDialogStatic(@NotNull Project project, @NotNull List<ClassPathReferenceInfo> refs) {
		// Сохраняем для тестов
		lastBrokenRefs = new ArrayList<>(refs);
		warningDialogCount++;

		com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(() -> {
			BrokenReferencesDialog dialog = new BrokenReferencesDialog(project, refs);
			dialog.show();
		});
	}

	/**
	 * Форматирует сообщение о количестве изменений с правильными окончаниями.
	 */
	private static String formatChangesMessage(int pathCount, int fileCount) {
		String updatedWord = formatUpdatedWord(pathCount);
		String pathWord = formatPathWord(pathCount);
		String fileWord = formatFileWord(fileCount);
		return updatedWord + " " + pathCount + " " + pathWord + " в " + fileCount + " " + fileWord;
	}

	private static String formatUpdatedWord(int count) {
		int lastTwo = count % 100;
		int lastOne = count % 10;

		if (lastTwo >= 11 && lastTwo <= 19) {
			return "Обновлено";
		}
		if (lastOne == 1) {
			return "Обновлён";
		}
		return "Обновлено";
	}

	private static String formatPathWord(int count) {
		int lastTwo = count % 100;
		int lastOne = count % 10;

		if (lastTwo >= 11 && lastTwo <= 19) {
			return "путей";
		}
		if (lastOne == 1) {
			return "путь";
		}
		if (lastOne >= 2 && lastOne <= 4) {
			return "пути";
		}
		return "путей";
	}

	private static String formatFileWord(int count) {
		int lastTwo = count % 100;
		int lastOne = count % 10;

		if (lastTwo >= 11 && lastTwo <= 19) {
			return "файлах";
		}
		if (lastOne == 1) {
			return "файле";
		}
		if (lastOne >= 2 && lastOne <= 4) {
			return "файлах";
		}
		return "файлах";
	}

	/**
	 * Показывает диалог со списком изменений.
	 */
	private static void showChangesDialog(@NotNull Project project, @NotNull List<UsePathChange> changes) {
		// Сортируем: сначала по файлу, потом по offset (порядок строк в файле)
		changes.sort((a, b) -> {
			int fileCompare = a.filePath.compareTo(b.filePath);
			if (fileCompare != 0) {
				return fileCompare;
			}
			return Integer.compare(a.offset, b.offset);
		});

		DialogWrapper dialog = new DialogWrapper(project, false) {
			{
				setTitle("Parser3: Пути обновлены");
				setOKButtonText("Закрыть");
				init();
			}

			@Override
			protected @Nullable JComponent createCenterPanel() {
				JPanel panel = new JPanel(new BorderLayout(0, 10));

				JLabel label = new JLabel(
						"<html>Следующие пути были автоматически обновлены.<br>" +
								"Дважды кликните для перехода к файлу:</html>"
				);
				panel.add(label, BorderLayout.NORTH);

				DefaultListModel<UsePathChange> model = new DefaultListModel<>();
				for (UsePathChange change : changes) {
					model.addElement(change);
				}

				JBList<UsePathChange> list = new JBList<>(model);
				list.setCellRenderer(new DefaultListCellRenderer() {
					@Override
					public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
						super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
						if (value instanceof UsePathChange) {
							UsePathChange change = (UsePathChange) value;
							String relativePath = getRelativePath(project, change.filePath);
							setText("<html><a href='#'>" + relativePath + ":" + change.lineNumber + "</a>: " +
									change.oldUsePath + " → " + change.newUsePath + "</html>");
						}
						return this;
					}
				});

				list.addMouseListener(new MouseAdapter() {
					@Override
					public void mouseClicked(MouseEvent e) {
						if (e.getClickCount() == 2) {
							UsePathChange selected = list.getSelectedValue();
							if (selected != null) {
								VirtualFile file = LocalFileSystem.getInstance().findFileByPath(selected.filePath);
								if (file != null) {
									new OpenFileDescriptor(project, file, selected.offset).navigate(true);
								}
							}
						}
					}
				});

				JBScrollPane scrollPane = new JBScrollPane(list);
				scrollPane.setPreferredSize(new Dimension(500, 300));
				panel.add(scrollPane, BorderLayout.CENTER);

				return panel;
			}

			@Override
			protected Action @NotNull [] createActions() {
				return new Action[]{getOKAction()};
			}
		};
		dialog.show();
	}

	/**
	 * Очищает список изменений.
	 */
	public static void clearRecentChanges() {
		recentChanges.clear();
	}

	/**
	 * Получает старое имя переименовываемой директории.
	 * Вызывается из P3UseFileReference.handleElementRename()
	 */
	public static @Nullable String getRenamingDirectoryOldName() {
		return renamingDirectoryOldName;
	}

	/**
	 * Очищает сохранённое имя директории.
	 */
	public static void clearRenamingDirectoryOldName() {
		renamingDirectoryOldName = null;
	}

	@Override
	public @Nullable RefactoringElementListener getListener(@NotNull PsiElement element) {

		if (element instanceof PsiFile) {
			return getFileListener((PsiFile) element);
		}

		if (element instanceof com.intellij.psi.PsiDirectory) {
			return getDirectoryListener((com.intellij.psi.PsiDirectory) element);
		}

		return null;
	}

	private @Nullable RefactoringElementListener getFileListener(@NotNull PsiFile psiFile) {

		if (psiFile.getFileType() != Parser3FileType.INSTANCE) {
			return null;
		}

		VirtualFile vFile = psiFile.getVirtualFile();
		if (vFile == null) {
			return null;
		}

		// Сохраняем путь к файлу ДО перемещения
		String originalFilePath = vFile.getPath();

		// Очищаем старое имя директории - это переименование файла, не директории
		renamingDirectoryOldName = null;

		// Очищаем предыдущие снимки
		preRefactoringSnapshots.clear();

		// Собираем все CLASS_PATH references на этот файл до рефакторинга
		List<ClassPathReferenceInfo> classPathRefs = collectClassPathReferencesForFile(psiFile);

		// Собираем снимки ВСЕХ путей которые резолвятся на этот файл
		collectUsePathSnapshots(psiFile.getProject(), vFile);

		// Всегда возвращаем listener чтобы показать уведомление об изменениях
		return new P3RefactoringListener(psiFile.getProject(), classPathRefs, originalFilePath);
	}

	private @Nullable RefactoringElementListener getDirectoryListener(@NotNull com.intellij.psi.PsiDirectory psiDir) {
		VirtualFile vDir = psiDir.getVirtualFile();
		Project project = psiDir.getProject();

		// Сохраняем старое имя директории для использования в handleElementRename
		renamingDirectoryOldName = vDir.getName();

		// Сохраняем путь к директории ДО переименования
		String originalDirPath = vDir.getPath();

		// Очищаем предыдущие снимки
		preRefactoringSnapshots.clear();

		// Собираем CLASS_PATH references на все .p файлы в директории (рекурсивно)
		List<ClassPathReferenceInfo> classPathRefs = collectClassPathReferencesForDirectory(project, vDir);

		// Собираем снимки ВСЕХ путей которые резолвятся на файлы в этой директории
		collectUsePathSnapshotsForDirectory(project, vDir);

		if (classPathRefs.isEmpty() && preRefactoringSnapshots.isEmpty()) {
			// Даже если нет references, всё равно возвращаем listener
			// чтобы сработал elementRenamed для показа уведомления
			return new P3RefactoringListener(project, new ArrayList<>(), originalDirPath, true);
		}

		return new P3RefactoringListener(project, classPathRefs, originalDirPath, true);
	}

	/**
	 * Собирает снимки всех USE путей которые резолвятся на указанный файл.
	 * Использует P3UsageService как единый источник истины.
	 */
	private void collectUsePathSnapshots(@NotNull Project project, @NotNull VirtualFile targetFile) {

		String targetHash = computeContentHash(targetFile);
		if (targetHash == null) {
			return;
		}

		// Используем P3UsageService — единый источник истины
		P3UsageService usageService = P3UsageService.getInstance(project);
		List<P3UsageService.FileUsage> usages = usageService.findUsages(targetFile);


		for (P3UsageService.FileUsage usage : usages) {
			preRefactoringSnapshots.add(new UsePathSnapshot(
					usage.sourceFilePath,
					usage.sourceFile.getName(),
					usage.usePath,
					usage.lineNumber,
					targetHash,
					targetFile.getPath(),
					usage.directiveType
			));
		}

	}

	/**
	 * Собирает снимки всех USE путей которые резолвятся на файлы в указанной директории.
	 */
	private void collectUsePathSnapshotsForDirectory(@NotNull Project project, @NotNull VirtualFile targetDir) {

		// Используем P3UsageService для поиска файлов в директории
		P3UsageService usageService = P3UsageService.getInstance(project);
		List<VirtualFile> filesInDir = usageService.findFilesInDirectory(targetDir);

		if (filesInDir.isEmpty()) {
			return;
		}


		// Вычисляем хеши контента для всех файлов
		java.util.Map<String, String> fileHashes = new java.util.HashMap<>();
		for (VirtualFile file : filesInDir) {
			String hash = computeContentHash(file);
			if (hash != null) {
				fileHashes.put(file.getPath(), hash);
			}
		}

		// Для каждого файла в директории ищем использования через P3UsageService
		for (VirtualFile targetFile : filesInDir) {
			List<P3UsageService.FileUsage> usages = usageService.findUsages(targetFile);
			String hash = fileHashes.get(targetFile.getPath());

			for (P3UsageService.FileUsage usage : usages) {
				if (hash != null) {
					preRefactoringSnapshots.add(new UsePathSnapshot(
							usage.sourceFilePath,
							usage.sourceFile.getName(),
							usage.usePath,
							usage.lineNumber,
							hash,
							targetFile.getPath(),
							usage.directiveType
					));
				}
			}
		}

	}

	private List<ClassPathReferenceInfo> collectClassPathReferencesForFile(@NotNull PsiFile targetFile) {
		List<ClassPathReferenceInfo> result = new ArrayList<>();
		Project project = targetFile.getProject();
		VirtualFile targetVFile = targetFile.getVirtualFile();

		if (targetVFile == null) {
			return result;
		}

		// Используем P3UsageService — единый источник истины
		P3UsageService usageService = P3UsageService.getInstance(project);
		List<P3UsageService.FileUsage> usages = usageService.findUsages(targetVFile);

		for (P3UsageService.FileUsage usage : usages) {
			String path = usage.usePath;

			// Проверяем что это CLASS_PATH путь (без / в начале и без ../)
			if (path.startsWith("/") || path.startsWith("../") || path.startsWith("./")) {
				continue;
			}

			result.add(new ClassPathReferenceInfo(
					usage.sourceFilePath,
					usage.sourceFile.getName(),
					path,
					usage.lineNumber,
					usage.directiveType
			));
		}

		return result;
	}

	private List<ClassPathReferenceInfo> collectClassPathReferencesForDirectory(@NotNull Project project, @NotNull VirtualFile targetDir) {
		List<ClassPathReferenceInfo> result = new ArrayList<>();

		// Используем P3UsageService для поиска файлов в директории
		P3UsageService usageService = P3UsageService.getInstance(project);
		List<VirtualFile> filesInDir = usageService.findFilesInDirectory(targetDir);

		if (filesInDir.isEmpty()) {
			return result;
		}

		// Для каждого файла в директории ищем CLASS_PATH использования
		for (VirtualFile targetFile : filesInDir) {
			List<P3UsageService.FileUsage> usages = usageService.findUsages(targetFile);

			for (P3UsageService.FileUsage usage : usages) {
				String path = usage.usePath;

				// Проверяем что это CLASS_PATH путь
				if (path.startsWith("/") || path.startsWith("../") || path.startsWith("./")) {
					continue;
				}

				result.add(new ClassPathReferenceInfo(
						usage.sourceFilePath,
						usage.sourceFile.getName(),
						path,
						usage.lineNumber,
						usage.directiveType
				));
			}
		}

		return result;
	}

	/**
	 * @deprecated Используйте P3UsageService.findFilesInDirectory() вместо этого метода.
	 */
	@Deprecated
	private void collectParser3FilesRecursively(@NotNull VirtualFile dir, @NotNull List<VirtualFile> result) {
		for (VirtualFile child : dir.getChildren()) {
			if (child.isDirectory()) {
				collectParser3FilesRecursively(child, result);
			} else {
				// Проверяем через FileType, а не по расширению
				if (child.getFileType() instanceof Parser3FileType) {
					result.add(child);
				}
			}
		}
	}

	private static class ClassPathReferenceInfo {
		final String sourceFilePath;
		final String sourceFileName;
		final String usePath;
		final int lineNumber;
		final RollbackReason reason;
		final ru.artlebedev.parser3.utils.P3UsePathUtils.UseDirectiveType directiveType;

		ClassPathReferenceInfo(String sourceFilePath, String sourceFileName, String usePath, int lineNumber,
							   RollbackReason reason, ru.artlebedev.parser3.utils.P3UsePathUtils.UseDirectiveType directiveType) {
			this.sourceFilePath = sourceFilePath;
			this.sourceFileName = sourceFileName;
			this.usePath = usePath;
			this.lineNumber = lineNumber;
			this.reason = reason;
			this.directiveType = directiveType;
		}

		ClassPathReferenceInfo(String sourceFilePath, String sourceFileName, String usePath, int lineNumber,
							   ru.artlebedev.parser3.utils.P3UsePathUtils.UseDirectiveType directiveType) {
			this(sourceFilePath, sourceFileName, usePath, lineNumber, null, directiveType);
		}

		/**
		 * Конструктор для обратной совместимости — использует USE_METHOD по умолчанию.
		 */
		ClassPathReferenceInfo(String sourceFilePath, String sourceFileName, String usePath, int lineNumber, RollbackReason reason) {
			this(sourceFilePath, sourceFileName, usePath, lineNumber, reason,
					ru.artlebedev.parser3.utils.P3UsePathUtils.UseDirectiveType.USE_METHOD);
		}

		ClassPathReferenceInfo(String sourceFilePath, String sourceFileName, String usePath, int lineNumber) {
			this(sourceFilePath, sourceFileName, usePath, lineNumber, null,
					ru.artlebedev.parser3.utils.P3UsePathUtils.UseDirectiveType.USE_METHOD);
		}

		@Override
		public String toString() {
			String reasonText = reason != null ? " — " + reason.description : "";
			String useDisplay = directiveType != null
					? ru.artlebedev.parser3.utils.P3UsePathUtils.formatUsePath(usePath, directiveType)
					: "^use[" + usePath + "]";  // По умолчанию ^use[] формат
			return sourceFileName + ":" + lineNumber + ": " + useDisplay + reasonText;
		}
	}

	/**
	 * Причины отката изменения пути.
	 */
	private enum RollbackReason {
		PATH_NOT_RESOLVED("путь не резолвится"),
		CONTENT_MISMATCH("контент файла отличается"),
		CANNOT_COMPUTE_PATH("не удалось вычислить новый путь"),
		NOT_UPDATED("требует ручного обновления");

		final String description;

		RollbackReason(String description) {
			this.description = description;
		}
	}

	/**
	 * Информация о USE пути до рефакторинга для проверки корректности изменений.
	 */
	private static class UsePathSnapshot {
		final String sourceFilePath;      // Файл содержащий use-директиву
		final String sourceFileName;
		final String usePath;             // Путь в use-директиве
		final int lineNumber;             // Номер строки (1-based)
		final String targetContentHash;   // Хеш контента целевого файла
		final String targetFilePath;      // Путь к целевому файлу (для отладки)
		final ru.artlebedev.parser3.utils.P3UsePathUtils.UseDirectiveType directiveType;

		UsePathSnapshot(String sourceFilePath, String sourceFileName, String usePath,
						int lineNumber, String targetContentHash, String targetFilePath,
						ru.artlebedev.parser3.utils.P3UsePathUtils.UseDirectiveType directiveType) {
			this.sourceFilePath = sourceFilePath;
			this.sourceFileName = sourceFileName;
			this.usePath = usePath;
			this.lineNumber = lineNumber;
			this.targetContentHash = targetContentHash;
			this.targetFilePath = targetFilePath;
			this.directiveType = directiveType;
		}
	}

	// Снимки путей до рефакторинга
	private static final CopyOnWriteArrayList<UsePathSnapshot> preRefactoringSnapshots = new CopyOnWriteArrayList<>();

	/**
	 * Вычисляет хеш контента файла.
	 */
	private static @Nullable String computeContentHash(@NotNull VirtualFile file) {
		try {
			byte[] content = file.contentsToByteArray();
			java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
			byte[] hash = md.digest(content);
			StringBuilder sb = new StringBuilder();
			for (byte b : hash) {
				sb.append(String.format("%02x", b));
			}
			return sb.toString();
		} catch (Exception e) {
			return null;
		}
	}

	private static class P3RefactoringListener implements RefactoringElementListener, UndoRefactoringElementListener {

		private final Project project;
		private final List<ClassPathReferenceInfo> classPathRefs;
		private final String originalFilePath; // Путь к файлу/директории ДО перемещения
		private String newFilePath; // Путь к файлу/директории ПОСЛЕ перемещения
		private final boolean isDirectoryRefactoring; // true если переименовывается директория

		// Собираем ВСЕ сломанные ссылки для показа в ОДНОМ диалоге
		private final List<ClassPathReferenceInfo> allBrokenRefs = new ArrayList<>();

		// Флаг что elementMoved/elementRenamed был вызван
		private volatile boolean elementCallbackCalled = false;

		P3RefactoringListener(Project project, List<ClassPathReferenceInfo> classPathRefs) {
			this.project = project;
			this.classPathRefs = classPathRefs;
			this.originalFilePath = null;
			this.isDirectoryRefactoring = false;
			scheduleDelayedCheck();
		}

		P3RefactoringListener(Project project, List<ClassPathReferenceInfo> classPathRefs, String originalFilePath) {
			this.project = project;
			this.classPathRefs = classPathRefs;
			this.originalFilePath = originalFilePath;
			this.isDirectoryRefactoring = false;
			scheduleDelayedCheck();
		}

		P3RefactoringListener(Project project, List<ClassPathReferenceInfo> classPathRefs, String originalFilePath, boolean isDirectory) {
			this.project = project;
			this.classPathRefs = classPathRefs;
			this.originalFilePath = originalFilePath;
			this.isDirectoryRefactoring = isDirectory;
			scheduleDelayedCheck();
		}

		/**
		 * Запланировать отложенную проверку.
		 * Если elementMoved/elementRenamed не был вызван (выключен "search for references"),
		 * то проверим состояние и покажем предупреждение.
		 */
		private void scheduleDelayedCheck() {

			com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(() -> {

				// Даём время на завершение рефакторинга
				com.intellij.util.Alarm alarm = new com.intellij.util.Alarm(com.intellij.util.Alarm.ThreadToUse.SWING_THREAD);
				alarm.addRequest(() -> {

					if (!elementCallbackCalled && !classPathRefs.isEmpty()) {
						checkBrokenRefsDelayed();
					}
				}, 500); // 500ms задержка
			});
		}

		/**
		 * Проверка сломанных ссылок когда elementMoved/elementRenamed не был вызван.
		 */
		private void checkBrokenRefsDelayed() {
			allBrokenRefs.clear();
			collectBrokenRefsFromClassPathRefs();

			if (!allBrokenRefs.isEmpty()) {
				// Показываем уведомление вместо модального диалога
				// (чекбокс "search for references" был выключен)
				List<ClassPathReferenceInfo> refs = new ArrayList<>(allBrokenRefs);
				showPotentialIssuesNotification(project, refs);
			}
		}

		@Override
		public void elementMoved(@NotNull PsiElement newElement) {
			elementCallbackCalled = true; // Помечаем что callback был вызван

			// Очищаем список broken refs
			allBrokenRefs.clear();

			if (newElement instanceof PsiFile) {
				PsiFile movedFile = (PsiFile) newElement;
				VirtualFile vFile = movedFile.getVirtualFile();
				if (vFile != null) {
					newFilePath = vFile.getPath();
				}

				// Обновляем ссылки ВНУТРИ перемещённого файла
				updateReferencesInsideMovedFile(movedFile);
			}

			// Обновляем внешние ссылки которые IDEA не обновила
			verifyAndUpdateUnchangedPaths();

			// Собираем broken refs из checkAndWarn (не показываем диалог)
			collectBrokenRefsFromClassPathRefs();

			// Собираем broken refs из verifyAndRollbackIncorrectChanges (не показываем диалог)
			verifyAndRollbackIncorrectChangesCollectOnly();

			// Показываем ОДИН диалог со ВСЕМИ проблемами
			if (!allBrokenRefs.isEmpty()) {
				showBrokenReferencesDialog(allBrokenRefs);
			}

			// После завершения показываем уведомление об изменённых путях
			com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(() -> {
				showChangesNotification(project);
			});
		}

		@Override
		public void elementRenamed(@NotNull PsiElement newElement) {
			elementCallbackCalled = true; // Помечаем что callback был вызван

			// Сохраняем новый путь к файлу/директории
			if (newElement instanceof PsiFile) {
				VirtualFile vf = ((PsiFile) newElement).getVirtualFile();
				if (vf != null) {
					newFilePath = vf.getPath();
				}
			} else if (newElement instanceof com.intellij.psi.PsiDirectory) {
				VirtualFile vf = ((com.intellij.psi.PsiDirectory) newElement).getVirtualFile();
				if (vf != null) {
					newFilePath = vf.getPath();
				}
			}

			// При переименовании директории НЕ обновляем CLASS_PATH пути автоматически
			if (isDirectoryRefactoring) {
				// Не вызываем verifyAndUpdateUnchangedPaths — CLASS_PATH пути не должны меняться
			} else {
				// Проверяем и обновляем пути которые IDEA не обновила
				verifyAndUpdateUnchangedPaths();
			}

			// Очищаем список broken refs
			allBrokenRefs.clear();

			// Собираем broken refs (путь мог не обновиться)
			collectBrokenRefsFromClassPathRefs();

			// Показываем ОДИН диалог со ВСЕМИ проблемами
			if (!allBrokenRefs.isEmpty()) {
				showBrokenReferencesDialog(allBrokenRefs);
			}

			// После завершения показываем уведомление об изменённых путях
			com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(() -> {
				showChangesNotification(project);
			});
		}

		@Override
		public void undoElementMovedOrRenamed(@NotNull PsiElement newElement, @NotNull String oldQualifiedName) {
		}

		/**
		 * Обновляет относительные ссылки внутри перемещённого файла.
		 * Когда файл перемещается в другую директорию, относительные пути в нём становятся неверными.
		 */
		private void updateReferencesInsideMovedFile(@NotNull PsiFile movedFile) {

			if (originalFilePath == null) {
				return;
			}

			VirtualFile newVFile = movedFile.getVirtualFile();
			if (newVFile == null) {
				return;
			}

			VirtualFile newDir = newVFile.getParent();
			if (newDir == null) {
				return;
			}

			// Вычисляем старую директорию
			String originalDir = originalFilePath.substring(0, originalFilePath.lastIndexOf('/'));
			String newDirPath = newDir.getPath();

			// Если директория не изменилась — ничего делать не нужно
			if (originalDir.equals(newDirPath)) {
				return;
			}

			P3UseResolver resolver = P3UseResolver.getInstance(project);

			// Извлекаем все use-пути из файла
			Collection<P3UseExtractor.UseInfo> uses = P3UseExtractor.extractUses(movedFile);

			// Собираем изменения для применения
			List<UsePathUpdate> updates = new ArrayList<>();

			for (P3UseExtractor.UseInfo useInfo : uses) {
				String path = useInfo.getPath();

				// Пропускаем абсолютные пути — они не зависят от местоположения файла
				if (path.startsWith("/")) {
					continue;
				}

				// Резолвим путь относительно СТАРОГО местоположения
				VirtualFile oldResolved = resolveRelativeToOldLocation(path, originalDir);
				if (oldResolved == null || !oldResolved.exists()) {
					continue;
				}

				// Сохраняем хеш целевого файла для проверки
				String targetHash = computeContentHash(oldResolved);

				// Проверяем резолвится ли путь из НОВОГО местоположения на тот же файл
				VirtualFile newResolved = resolver.resolve(path, newVFile);
				if (newResolved != null && newResolved.equals(oldResolved)) {
					// Путь всё ещё работает — не трогаем
					continue;
				}

				// Вычисляем новый относительный путь
				String newPath = computeRelativePath(newDir, oldResolved);
				if (newPath != null && !newPath.equals(path)) {
					updates.add(new UsePathUpdate(useInfo.getLineNumber(), path, newPath, targetHash, oldResolved.getPath()));
				}
			}

			// Применяем изменения и проверяем корректность
			if (!updates.isEmpty()) {
				applyAndVerifyUpdatesInsideFile(movedFile, updates);
			}
		}

		/**
		 * Проверяет и обновляет пути которые НЕ были обновлены IDEA.
		 * Вызывается после того как IDEA обработала references (если "search for references" включён).
		 * Если путь не изменился — значит IDEA его не обновила, и мы делаем это сами.
		 */
		private void verifyAndUpdateUnchangedPaths() {

			if (originalFilePath == null || newFilePath == null) {
				return;
			}


			PsiManager psiManager = PsiManager.getInstance(project);
			com.intellij.openapi.vfs.VirtualFileManager vfm = com.intellij.openapi.vfs.VirtualFileManager.getInstance();

			// Проходим по всем сохранённым снимкам
			for (UsePathSnapshot snapshot : preRefactoringSnapshots) {

				// Пробуем сначала temp:// (для тестов), потом LocalFileSystem
				VirtualFile sourceVFile = vfm.findFileByUrl("temp://" + snapshot.sourceFilePath);
				if (sourceVFile == null) {
					sourceVFile = LocalFileSystem.getInstance().findFileByPath(snapshot.sourceFilePath);
				}
				if (sourceVFile == null) {
					continue;
				}

				PsiFile sourcePsiFile = psiManager.findFile(sourceVFile);
				if (sourcePsiFile == null) {
					continue;
				}

				// Проверяем текущий путь в файле
				String currentPath = findPathAtPosition(sourcePsiFile, snapshot.lineNumber, snapshot.usePath);

				// Если путь УЖЕ изменился — IDEA его обновила, пропускаем
				if (currentPath != null && !currentPath.equals(snapshot.usePath)) {
					continue;
				}

				// Если currentPath == null — P3UseExtractor не нашёл путь (комментарий).
				// Проверяем: если reference уже обновил этот путь (зарегистрировал в recentChanges) — пропускаем.
				if (currentPath == null) {
					boolean alreadyUpdated = false;
					for (UsePathChange change : recentChanges) {
						if (change.filePath.equals(snapshot.sourceFilePath) &&
								change.oldUsePath.equals(snapshot.usePath)) {
							alreadyUpdated = true;
							break;
						}
					}
					if (alreadyUpdated) {
						continue;
					}
				}

				// Проверяем: если путь резолвился через CLASS_PATH, не трогаем его
				// (кроме случая когда переименовывается сам файл)
				P3UseResolver resolver = P3UseResolver.getInstance(project);

				// Проверяем — это переименование файла или директории?
				String oldFileName = originalFilePath.substring(originalFilePath.lastIndexOf('/') + 1);
				boolean isFileRename = snapshot.usePath.endsWith(oldFileName) || snapshot.usePath.equals(oldFileName);

				if (!isFileRename) {
					// Это переименование директории
					// Проверяем резолвится ли путь относительно файла (без CLASS_PATH)
					VirtualFile sourceDir = sourceVFile.getParent();
					if (sourceDir != null) {
						VirtualFile resolvedRelative = sourceDir.findFileByRelativePath(snapshot.usePath);
						if (resolvedRelative == null) {
							// Путь НЕ резолвится относительно файла — значит через CLASS_PATH
							// НЕ трогаем такие пути при переименовании директории
							continue;
						}
					}
				}

				// Путь НЕ изменился — вычисляем новый путь
				// Определяем тип операции: move (директория изменилась) или rename (только имя)
				String oldDir = originalFilePath.substring(0, originalFilePath.lastIndexOf('/'));
				String newDir = newFilePath.substring(0, newFilePath.lastIndexOf('/'));
				boolean isMove = !oldDir.equals(newDir);


				String newUsePath = null;

				if (isMove) {
					// MOVE: вычисляем новый относительный путь от sourceFile к новому расположению целевого файла
					VirtualFile newTargetFile = vfm.findFileByUrl("temp://" + newFilePath);
					if (newTargetFile == null) {
						newTargetFile = LocalFileSystem.getInstance().findFileByPath(newFilePath);
					}

					if (newTargetFile != null) {
						VirtualFile sourceDir = sourceVFile.getParent();
						if (sourceDir != null) {
							newUsePath = ru.artlebedev.parser3.utils.P3UsePathUtils.computeRelativePath(sourceDir, newTargetFile);
						}
					}
				} else {
					// RENAME: просто заменяем старое имя файла на новое в пути
					// oldFileName уже вычислен выше
					String newFileName = newFilePath.substring(newFilePath.lastIndexOf('/') + 1);

					if (snapshot.usePath.endsWith(oldFileName)) {
						// Путь заканчивается на старое имя файла — заменяем
						newUsePath = snapshot.usePath.substring(0, snapshot.usePath.length() - oldFileName.length()) + newFileName;
					} else {
						// Пробуем computeNewUsePath для более сложных случаев
						newUsePath = ru.artlebedev.parser3.utils.P3UsePathUtils.computeNewUsePath(
								snapshot.usePath, originalFilePath, newFilePath);
					}
				}

				if (newUsePath == null || newUsePath.equals(snapshot.usePath)) {
					continue;
				}

				// Применяем изменение
				boolean success = applyPathUpdate(sourcePsiFile, snapshot.lineNumber, snapshot.usePath, newUsePath);

				if (success) {
					// Вычисляем offset из lineNumber
					int offset = 0;
					com.intellij.openapi.editor.Document doc =
							com.intellij.psi.PsiDocumentManager.getInstance(project).getDocument(sourcePsiFile);
					if (doc != null && snapshot.lineNumber > 0 && snapshot.lineNumber <= doc.getLineCount()) {
						offset = doc.getLineStartOffset(snapshot.lineNumber - 1); // lineNumber 1-based
					}

					registerUsePathChange(snapshot.sourceFilePath, snapshot.sourceFileName,
							snapshot.usePath, newUsePath, offset, snapshot.lineNumber);
				} else {
				}
			}
		}

		/**
		 * Применяет обновление пути в файле.
		 */
		private boolean applyPathUpdate(@NotNull PsiFile file, int lineNumber,
										@NotNull String oldPath, @NotNull String newPath) {
			com.intellij.openapi.editor.Document document =
					com.intellij.psi.PsiDocumentManager.getInstance(project).getDocument(file);
			if (document == null) {
				return false;
			}

			String text = document.getText();

			// Ищем старый путь в документе
			int index = text.indexOf(oldPath);
			if (index < 0) {
				return false;
			}

			// Заменяем
			com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project, () -> {
				document.replaceString(index, index + oldPath.length(), newPath);
				com.intellij.psi.PsiDocumentManager.getInstance(project).commitDocument(document);
			});

			return true;
		}

		/**
		 * Резолвит путь относительно старого местоположения файла.
		 */
		private @Nullable VirtualFile resolveRelativeToOldLocation(@NotNull String path, @NotNull String oldDirPath) {
			// Используем VFS файла для навигации, а не LocalFileSystem
			// Это важно для тестов, где файлы в TempFileSystem

			// Если есть newVFile, получаем старую директорию через навигацию вверх
			// newVFile = /src/inner/helper.p, нужно получить /src
			// originalDir = /src (строка)

			VirtualFile oldDir = null;

			// Пробуем через VirtualFileManager (универсальный способ)
			com.intellij.openapi.vfs.VirtualFileManager vfm = com.intellij.openapi.vfs.VirtualFileManager.getInstance();

			// Пробуем найти директорию по разным URL-схемам
			// 1. temp:// для тестов
			oldDir = vfm.findFileByUrl("temp://" + oldDirPath);

			// 2. file:// для реальных файлов
			if (oldDir == null) {
				oldDir = LocalFileSystem.getInstance().findFileByPath(oldDirPath);
			}


			if (oldDir == null) {
				return null;
			}
			VirtualFile result = oldDir.findFileByRelativePath(path);
			return result;
		}

		/**
		 * Вычисляет относительный путь от директории к файлу.
		 */
		/**
		 * Вычисляет относительный путь от директории к файлу.
		 * Использует общую утилиту для единообразного поведения.
		 */
		private @Nullable String computeRelativePath(@NotNull VirtualFile fromDir, @NotNull VirtualFile toFile) {
			return ru.artlebedev.parser3.utils.P3UsePathUtils.computeRelativePath(fromDir, toFile);
		}

		/**
		 * Применяет обновления путей внутри файла и проверяет их корректность.
		 * Если новый путь не резолвится или резолвится на файл с другим контентом — откатывает.
		 */
		private void applyAndVerifyUpdatesInsideFile(@NotNull PsiFile psiFile, @NotNull List<UsePathUpdate> updates) {
			List<ClassPathReferenceInfo> rolledBackRefs = new ArrayList<>();

			com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project, () -> {
				com.intellij.openapi.editor.Document document =
						com.intellij.psi.PsiDocumentManager.getInstance(project).getDocument(psiFile);
				if (document == null) {
					return;
				}

				// Сортируем по убыванию номера строки чтобы не сбивать offset'ы
				updates.sort((a, b) -> Integer.compare(b.lineNumber, a.lineNumber));

				VirtualFile vFile = psiFile.getVirtualFile();
				String filePath = vFile != null ? vFile.getPath() : "";
				String fileName = vFile != null ? vFile.getName() : "";

				P3UseResolver resolver = P3UseResolver.getInstance(project);

				for (UsePathUpdate update : updates) {
					int lineIndex = update.lineNumber - 1;
					if (lineIndex < 0 || lineIndex >= document.getLineCount()) {
						continue;
					}

					int lineStart = document.getLineStartOffset(lineIndex);
					int lineEnd = document.getLineEndOffset(lineIndex);
					String lineText = document.getText().substring(lineStart, lineEnd);

					// Ищем путь в строке (поддержка и ^use[] и @USE)
					ru.artlebedev.parser3.utils.P3UsePathUtils.PathMatch match =
							ru.artlebedev.parser3.utils.P3UsePathUtils.findPathInLine(lineText, update.oldPath);

					if (match != null) {
						int pathStart = lineStart + match.pathStart;
						int pathEnd = lineStart + match.pathEnd;

						// Применяем изменение
						document.replaceString(pathStart, pathEnd, update.newPath);

						// Коммитим изменения чтобы PSI обновился
						com.intellij.psi.PsiDocumentManager.getInstance(project).commitDocument(document);

						// Проверяем корректность нового пути
						VirtualFile resolved = resolver.resolve(update.newPath, vFile);
						boolean needsRollback = false;
						RollbackReason rollbackReason = null;

						if (resolved == null) {
							needsRollback = true;
							rollbackReason = RollbackReason.PATH_NOT_RESOLVED;
						} else if (update.targetHash != null) {
							String newHash = computeContentHash(resolved);
							if (newHash == null || !newHash.equals(update.targetHash)) {
								needsRollback = true;
								rollbackReason = RollbackReason.CONTENT_MISMATCH;
							}
						}

						if (needsRollback) {
							// Откатываем — нужно пересчитать позиции после предыдущего изменения
							int newLineEnd = document.getLineEndOffset(lineIndex);
							String newLineText = document.getText().substring(lineStart, newLineEnd);

							ru.artlebedev.parser3.utils.P3UsePathUtils.PathMatch newMatch =
									ru.artlebedev.parser3.utils.P3UsePathUtils.findPathInLine(newLineText, update.newPath);

							if (newMatch != null) {
								int newPathStart = lineStart + newMatch.pathStart;
								int newPathEnd = lineStart + newMatch.pathEnd;
								document.replaceString(newPathStart, newPathEnd, update.oldPath);
							}

							// Определяем тип директивы по оригинальному match
							ru.artlebedev.parser3.utils.P3UsePathUtils.UseDirectiveType directiveType =
									match != null ? match.directiveType : ru.artlebedev.parser3.utils.P3UsePathUtils.UseDirectiveType.USE_METHOD;

							rolledBackRefs.add(new ClassPathReferenceInfo(
									filePath,
									fileName,
									update.oldPath,
									update.lineNumber,
									rollbackReason,
									directiveType
							));
						} else {
							// Регистрируем изменение для уведомления
							registerUsePathChange(filePath, fileName, update.oldPath, update.newPath, pathStart, update.lineNumber);
						}
					}
				}
			});

			// Добавляем в общий список broken refs
			allBrokenRefs.addAll(rolledBackRefs);
			// НЕ показываем диалог здесь — всё покажется в elementMoved
		}

		private static class UsePathUpdate {
			final int lineNumber;
			final String oldPath;
			final String newPath;
			final String targetHash;
			final String targetFilePath;

			UsePathUpdate(int lineNumber, String oldPath, String newPath, String targetHash, String targetFilePath) {
				this.lineNumber = lineNumber;
				this.oldPath = oldPath;
				this.newPath = newPath;
				this.targetHash = targetHash;
				this.targetFilePath = targetFilePath;
			}
		}

		/**
		 * Проверяет корректность изменений путей и откатывает некорректные.
		 * Добавляет информацию о проблемах в allBrokenRefs, НЕ показывает диалог.
		 */
		private void verifyAndRollbackIncorrectChangesCollectOnly() {
			if (preRefactoringSnapshots.isEmpty()) {
				return;
			}

			P3UseResolver resolver = P3UseResolver.getInstance(project);
			PsiManager psiManager = PsiManager.getInstance(project);

			com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project, () -> {
				for (UsePathSnapshot snapshot : preRefactoringSnapshots) {
					VirtualFile sourceFile = LocalFileSystem.getInstance().findFileByPath(snapshot.sourceFilePath);
					if (sourceFile == null) {
						continue;
					}

					PsiFile psiFile = psiManager.findFile(sourceFile);
					if (psiFile == null) {
						continue;
					}

					// Находим текущий путь на этой позиции
					String currentPath = findPathAtPosition(psiFile, snapshot.lineNumber, snapshot.usePath);
					if (currentPath == null) {
						continue;
					}

					// Если путь не изменился - пропускаем
					if (currentPath.equals(snapshot.usePath)) {
						continue;
					}

					// Резолвим новый путь
					VirtualFile resolved = resolver.resolve(currentPath, sourceFile);
					if (resolved == null) {
						// Путь не резолвится — откатываем
						if (rollbackPath(psiFile, snapshot.lineNumber, currentPath, snapshot.usePath)) {
							// Добавляем в общий список с причиной
							allBrokenRefs.add(new ClassPathReferenceInfo(
									snapshot.sourceFilePath,
									snapshot.sourceFileName,
									snapshot.usePath,
									snapshot.lineNumber,
									RollbackReason.PATH_NOT_RESOLVED,
									snapshot.directiveType
							));
							// Удаляем из уведомлений об изменениях
							removeUsePathChange(snapshot.sourceFilePath, snapshot.lineNumber);
						}
						continue;
					}

					// Проверяем хеш контента
					String newHash = computeContentHash(resolved);
					if (newHash == null || !newHash.equals(snapshot.targetContentHash)) {
						// Контент отличается — откатываем
						if (rollbackPath(psiFile, snapshot.lineNumber, currentPath, snapshot.usePath)) {
							// Добавляем в общий список с причиной
							allBrokenRefs.add(new ClassPathReferenceInfo(
									snapshot.sourceFilePath,
									snapshot.sourceFileName,
									snapshot.usePath,
									snapshot.lineNumber,
									RollbackReason.CONTENT_MISMATCH,
									snapshot.directiveType
							));
							// Удаляем из уведомлений об изменениях
							removeUsePathChange(snapshot.sourceFilePath, snapshot.lineNumber);
						}
					}
				}
			});

			// Очищаем снимки
			preRefactoringSnapshots.clear();
			// НЕ показываем диалог здесь — все broken refs собраны в allBrokenRefs
		}

		/**
		 * Откатывает путь на старое значение.
		 * @return true если откат успешен
		 */
		private boolean rollbackPath(@NotNull PsiFile psiFile, int lineNumber, @NotNull String currentPath, @NotNull String oldPath) {
			com.intellij.openapi.editor.Document document =
					com.intellij.psi.PsiDocumentManager.getInstance(project).getDocument(psiFile);
			if (document == null) {
				return false;
			}

			// Получаем начало и конец строки по номеру (0-based в Document)
			int lineIndex = lineNumber - 1;
			if (lineIndex < 0 || lineIndex >= document.getLineCount()) {
				return false;
			}

			int lineStart = document.getLineStartOffset(lineIndex);
			int lineEnd = document.getLineEndOffset(lineIndex);
			String lineText = document.getText().substring(lineStart, lineEnd);

			// Ищем путь в строке (поддержка и ^use[] и @USE)
			ru.artlebedev.parser3.utils.P3UsePathUtils.PathMatch match =
					ru.artlebedev.parser3.utils.P3UsePathUtils.findPathInLine(lineText, currentPath);

			if (match != null) {
				int pathStart = lineStart + match.pathStart;
				int pathEnd = lineStart + match.pathEnd;

				document.replaceString(pathStart, pathEnd, oldPath);
				return true;
			}

			return false;
		}

		/**
		 * Собирает информацию о сломанных CLASS_PATH ссылках.
		 * Добавляет в allBrokenRefs, НЕ показывает диалог.
		 */
		private void collectBrokenRefsFromClassPathRefs() {

			P3UseResolver resolver = P3UseResolver.getInstance(project);
			PsiManager psiManager = PsiManager.getInstance(project);

			// Проверяем каждый сохранённый reference
			for (ClassPathReferenceInfo ref : classPathRefs) {

				VirtualFile sourceFile = LocalFileSystem.getInstance().findFileByPath(ref.sourceFilePath);
				if (sourceFile == null) {
					continue;
				}

				PsiFile psiFile = psiManager.findFile(sourceFile);
				if (psiFile == null) {
					continue;
				}

				// Ищем актуальный путь на той же позиции (или рядом)
				String currentPath = findPathAtPosition(psiFile, ref.lineNumber, ref.usePath);

				if (currentPath == null) {
					// Путь на этой позиции не найден — возможно он был обновлён
					// Не показываем предупреждение
					continue;
				}

				// Если путь не изменился, проверяем резолвится ли он
				if (currentPath.equals(ref.usePath)) {
					VirtualFile resolved = resolver.resolve(currentPath, sourceFile);

					if (resolved == null) {
						// Путь не изменился и не резолвится — IDEA не обновляла (выключен "search for references")
						allBrokenRefs.add(new ClassPathReferenceInfo(
								ref.sourceFilePath,
								ref.sourceFileName,
								currentPath,
								ref.lineNumber,
								RollbackReason.NOT_UPDATED,
								ref.directiveType
						));
					}
				} else {
				}
				// Если путь изменился — значит он был успешно обновлён
			}
			// НЕ показываем диалог здесь — все broken refs собраны в allBrokenRefs
		}

		/**
		 * Ищет CLASS_PATH путь на указанной позиции или рядом.
		 * Возвращает актуальный путь или null если не найден.
		 */
		private @Nullable String findPathAtPosition(@NotNull PsiFile psiFile, int lineNumber, @NotNull String originalPath) {
			// Извлекаем все use-пути из файла
			Collection<P3UseExtractor.UseInfo> uses = P3UseExtractor.extractUses(psiFile);

			// Ищем путь на той же строке
			for (P3UseExtractor.UseInfo useInfo : uses) {
				if (useInfo.getLineNumber() == lineNumber) {
					return useInfo.getPath();
				}
			}

			// Fallback: ищем точное совпадение пути (на случай если путь не изменился)
			for (P3UseExtractor.UseInfo useInfo : uses) {
				if (useInfo.getPath().equals(originalPath)) {
					return originalPath;
				}
			}

			return null;
		}

		private void showBrokenReferencesDialog(List<ClassPathReferenceInfo> brokenRefs) {
			// Сохраняем для тестов
			lastBrokenRefs = new ArrayList<>(brokenRefs);
			warningDialogCount++;

			// Проверяем — все ли ссылки NOT_UPDATED (чекбокс "search for references" был выключен)
			boolean allNotUpdated = brokenRefs.stream()
					.allMatch(ref -> ref.reason == RollbackReason.NOT_UPDATED);

			if (allNotUpdated) {
				// Чекбокс был выключен — показываем notification вместо модального диалога
				List<ClassPathReferenceInfo> refs = new ArrayList<>(brokenRefs);
				showPotentialIssuesNotification(project, refs);
			} else {
				// Есть реальные ошибки — показываем модальный диалог сразу
				SwingUtilities.invokeLater(() -> {
					BrokenReferencesDialog dialog = new BrokenReferencesDialog(project, brokenRefs);
					dialog.show();
				});
			}
		}
	}

	/**
	 * Диалог со списком сломанных references.
	 * Клик по элементу открывает файл и переходит к строке.
	 */
	private static class BrokenReferencesDialog extends DialogWrapper {

		private final Project project;
		private final List<ClassPathReferenceInfo> brokenRefs;

		BrokenReferencesDialog(Project project, List<ClassPathReferenceInfo> brokenRefs) {
			super(project, false);
			this.project = project;
			this.brokenRefs = brokenRefs;

			// Определяем заголовок и текст в зависимости от типа проблем
			boolean allNotUpdated = brokenRefs.stream()
					.allMatch(ref -> ref.reason == RollbackReason.NOT_UPDATED);

			if (allNotUpdated) {
				setTitle("Parser3: Проверьте подключения файлов");
			} else {
				setTitle("Parser3: Ссылки не обновлены");
			}
			setOKButtonText("Закрыть");
			init();
		}

		@Override
		protected @Nullable JComponent createCenterPanel() {
			JPanel panel = new JPanel(new BorderLayout(0, 10));

			// Определяем текст в зависимости от типа проблем
			boolean allNotUpdated = brokenRefs.stream()
					.allMatch(ref -> ref.reason == RollbackReason.NOT_UPDATED);

			String labelText;
			if (allNotUpdated) {
				labelText = "<html>Следующие файлы могли перестать подключаться после перемещения.<br>" +
						"Проверьте и при необходимости обновите пути вручную.<br>" +
						"Дважды кликните для перехода к файлу:</html>";
			} else {
				labelText = "<html>Следующие ссылки не могут быть обновлены автоматически.<br>" +
						"Дважды кликните для перехода к файлу:</html>";
			}

			JLabel label = new JLabel(labelText);
			panel.add(label, BorderLayout.NORTH);

			DefaultListModel<ClassPathReferenceInfo> listModel = new DefaultListModel<>();
			for (ClassPathReferenceInfo ref : brokenRefs) {
				listModel.addElement(ref);
			}

			JBList<ClassPathReferenceInfo> list = new JBList<>(listModel);
			list.setCellRenderer(new DefaultListCellRenderer() {
				@Override
				public Component getListCellRendererComponent(JList<?> list, Object value, int index,
															  boolean isSelected, boolean cellHasFocus) {
					super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
					if (value instanceof ClassPathReferenceInfo) {
						ClassPathReferenceInfo ref = (ClassPathReferenceInfo) value;
						String relativePath = getRelativePath(project, ref.sourceFilePath);
						String reasonText = ref.reason != null ? " — " + ref.reason.description : "";
						// Используем правильный формат в зависимости от типа директивы
						String useDisplay = ref.directiveType != null
								? ru.artlebedev.parser3.utils.P3UsePathUtils.formatUsePath(ref.usePath, ref.directiveType)
								: "^use[" + ref.usePath + "]";
						setText("<html><a href='#'>" + relativePath + ":" + ref.lineNumber +
								"</a>: " + useDisplay + reasonText + "</html>");
					}
					return this;
				}
			});

			list.addMouseListener(new MouseAdapter() {
				@Override
				public void mouseClicked(MouseEvent e) {
					if (e.getClickCount() == 2) {
						ClassPathReferenceInfo ref = list.getSelectedValue();
						if (ref != null) {
							navigateToReference(ref);
						}
					}
				}
			});

			JBScrollPane scrollPane = new JBScrollPane(list);
			scrollPane.setPreferredSize(new Dimension(450, 200));
			panel.add(scrollPane, BorderLayout.CENTER);

			return panel;
		}

		private void navigateToReference(ClassPathReferenceInfo ref) {
			VirtualFile file = LocalFileSystem.getInstance().findFileByPath(ref.sourceFilePath);
			if (file == null) {
				return;
			}

			// Открываем файл на нужной строке
			// OpenFileDescriptor принимает 0-based line number
			new OpenFileDescriptor(project, file, ref.lineNumber - 1, 0).navigate(true);
		}

		@Override
		protected Action @NotNull [] createActions() {
			return new Action[]{getOKAction()};
		}
	}
}