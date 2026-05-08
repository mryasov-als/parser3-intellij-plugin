package ru.artlebedev.parser3.settings;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.notification.NotificationAction;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import org.jetbrains.annotations.NotNull;
import ru.artlebedev.parser3.index.P3IndexMaintenance;

/**
 * Запускается при открытии проекта.
 * Автоматически определяет настройки Parser3 если они не заданы.
 * Также проверяет целостность индексов и переиндексирует при необходимости.
 *
 * Используем StartupActivity.DumbAware вместо ProjectActivity,
 * так как ProjectActivity — это Kotlin suspend fun и плохо работает из Java.
 */
public final class P3SettingsAutoDetectionActivity implements StartupActivity.DumbAware {

	@Override
	public void runActivity(@NotNull Project project) {

		// Ждём окончания индексации перед проверками
		DumbService.getInstance(project).runWhenSmart(() -> {

			// Проверяем целостность индексов
			P3IndexMaintenance.bootstrapIndexesIfNeeded(project);

			// Проверяем флаг - была ли уже детекция настроек
			if (wasDetectionPerformed(project)) {
				return;
			}

			P3ProjectSettingsDetector detector = P3ProjectSettingsDetector.getInstance(project);
			if (detector == null) {
				return;
			}

			// Показываем уведомление только если в проекте есть хоть один auto.p
			// Оборачиваем в ReadAction для безопасной работы с VFS
			boolean hasAutoP = ReadAction.compute(() -> detector.hasAutoPFiles());

			if (!hasAutoP) {
				markDetectionPerformed(project);
				return;
			}

			// Детекция настроек тоже должна быть в ReadAction
			P3ProjectSettingsDetector.DetectionResult result = ReadAction.compute(() -> detector.detectSettings());

			// Сохраняем найденные настройки
			if (result.documentRoot != null || result.mainAutoP != null) {
				saveDetectedSettings(project, result);
			}

			// Показываем уведомление
			showDetectionNotification(project, result);

			// Отмечаем что детекция была выполнена
			markDetectionPerformed(project);
		});
	}

	/**
	 * Проверяет был ли уже выполнен автопоиск настроек.
	 */
	private boolean wasDetectionPerformed(@NotNull Project project) {
		return Parser3ProjectSettings.getInstance(project).isSettingsAutoDetected();
	}

	/**
	 * Отмечает что детекция была выполнена
	 */
	private void markDetectionPerformed(@NotNull Project project) {
		Parser3ProjectSettings.getInstance(project).setSettingsAutoDetected(true);
	}

	/**
	 * Сохраняет найденные настройки в Parser3ProjectSettings
	 */
	private void saveDetectedSettings(@NotNull Project project, @NotNull P3ProjectSettingsDetector.DetectionResult result) {
		Parser3ProjectSettings settings = Parser3ProjectSettings.getInstance(project);

		if (result.documentRoot != null) {
			settings.setDocumentRootPath(result.documentRoot);
		}

		if (result.mainAutoP != null) {
			settings.setMainAutoPath(result.mainAutoP);
		}
	}

	private void showDetectionNotification(@NotNull Project project, @NotNull P3ProjectSettingsDetector.DetectionResult result) {
		StringBuilder message = new StringBuilder();

		if (result.documentRoot != null) {
			message.append("Document root: ").append(result.documentRoot);
		} else {
			message.append("Document root: не найден");
		}

		message.append("<br>");

		if (result.mainAutoP != null) {
			message.append("Основной auto.p: ").append(result.mainAutoP);
		} else {
			message.append("Основной auto.p: не найден");
		}

		NotificationType type = (result.documentRoot != null || result.mainAutoP != null)
				? NotificationType.INFORMATION
				: NotificationType.WARNING;

		Notification notification = NotificationGroupManager.getInstance()
				.getNotificationGroup("Parser3.Settings")
				.createNotification(
						"Настройки Parser3",
						message.toString(),
						type
				);

		// Добавляем действие "Настройки"
		notification.addAction(NotificationAction.createSimple("Настройки", () -> {
			ShowSettingsUtil.getInstance().showSettingsDialog(
					project,
					Parser3SettingsConfigurable.class
			);
		}));

		notification.notify(project);
	}
}
