package ru.artlebedev.parser3.settings;

import com.intellij.openapi.components.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Project-level settings for Parser3 plugin.
 * Stores document_root and main_auto paths.
 */
@Service(Service.Level.PROJECT)
@State(
		name = "Parser3ProjectSettings",
		storages = @Storage("parser3.xml")
)
public final class Parser3ProjectSettings implements PersistentStateComponent<Parser3ProjectSettings.State> {

	/**
	 * Режим автокомплита методов
	 */
	public enum MethodCompletionMode {
		ALL_METHODS,      // Все методы из всех .p файлов проекта
		USE_ONLY          // Только методы из видимых файлов (через use, auto.p, class path)
	}

	private State state = new State();
	private Project project;

	public static Parser3ProjectSettings getInstance(@NotNull Project project) {
		Parser3ProjectSettings settings = project.getService(Parser3ProjectSettings.class);
		settings.project = project;
		return settings;
	}

	@Override
	public @NotNull State getState() {
		return state;
	}

	@Override
	public void loadState(@NotNull State state) {
		this.state = state;
	}

	// Getters with VirtualFile resolution

	public @Nullable VirtualFile getDocumentRoot() {
		if (state.documentRootPath == null || state.documentRootPath.isEmpty()) {
			return null;
		}
		return resolvePath(state.documentRootPath);
	}

	public @Nullable VirtualFile getMainAuto() {
		if (state.mainAutoPath == null || state.mainAutoPath.isEmpty()) {
			return null;
		}
		return resolvePath(state.mainAutoPath);
	}

	/**
	 * Разрешает путь — поддерживает как URL (file://, temp://, jar://, etc.), так и относительные пути от корня проекта
	 */
	private @Nullable VirtualFile resolvePath(@NotNull String path) {
		// Сначала пробуем как URL (любая схема)
		if (path.contains("://")) {
			return VirtualFileManager.getInstance().findFileByUrl(path);
		}

		// Пробуем как абсолютный путь
		VirtualFile file = LocalFileSystem.getInstance().findFileByPath(path);
		if (file != null) {
			return file;
		}

		// Пробуем как относительный путь от корня проекта
		if (project != null) {
			VirtualFile baseDir = project.getBaseDir();
			if (baseDir != null) {
				return baseDir.findFileByRelativePath(path);
			}
		}

		return null;
	}

	// Setters

	public void setDocumentRoot(@Nullable VirtualFile file) {
		state.documentRootPath = file != null ? file.getUrl() : null;
	}

	public void setMainAuto(@Nullable VirtualFile file) {
		state.mainAutoPath = file != null ? file.getUrl() : null;
	}

	// Raw path getters for UI

	public @Nullable String getDocumentRootPath() {
		return state.documentRootPath;
	}

	public @Nullable String getMainAutoPath() {
		return state.mainAutoPath;
	}

	public void setDocumentRootPath(@Nullable String path) {
		state.documentRootPath = path;
	}

	public void setMainAutoPath(@Nullable String path) {
		state.mainAutoPath = path;
	}

	// Method completion mode

	public @NotNull MethodCompletionMode getMethodCompletionMode() {
		if (state.methodCompletionMode == null) {
			return MethodCompletionMode.ALL_METHODS; // По умолчанию - все методы
		}
		return state.methodCompletionMode;
	}

	public void setMethodCompletionMode(@NotNull MethodCompletionMode mode) {
		state.methodCompletionMode = mode;
	}

	public @Nullable String getPseudoHashCompletionConfigJson() {
		return state.pseudoHashCompletionConfigJson;
	}

	public void setPseudoHashCompletionConfigJson(@Nullable String json) {
		state.pseudoHashCompletionConfigJson = json;
	}

	/**
	 * Возвращает Document Root или fallback на корень проекта.
	 * Использовать этот метод вместо getDocumentRoot() везде где нужен document root.
	 */
	public @Nullable VirtualFile getDocumentRootWithFallback() {
		VirtualFile docRoot = getDocumentRoot();
		if (docRoot != null) {
			return docRoot;
		}
		// Fallback на корень проекта
		if (project != null) {
			return project.getBaseDir();
		}
		return null;
	}

	/**
	 * @deprecated Use {@link #getDocumentRootWithFallback()} instead
	 */
	@Deprecated
	public @NotNull VirtualFile getDocumentRootOrProjectBase(@NotNull Project project) {
		VirtualFile docRoot = getDocumentRoot();
		if (docRoot != null) {
			return docRoot;
		}
		VirtualFile baseDir = project.getBaseDir();
		return baseDir != null ? baseDir : getDocumentRoot(); // На всякий случай
	}

	/**
	 * Persistent state holder.
	 */
	public static class State {
		/**
		 * URL of document_root directory (web-root).
		 * Auto.p files are automatically picked up only inside document_root and its subdirectories.
		 */
		public @Nullable String documentRootPath;

		/**
		 * URL of main auto.p file (typically located next to parser3.cgi).
		 * This file is considered included ALWAYS first for any point in the project.
		 */
		public @Nullable String mainAutoPath;

		/**
		 * Режим автокомплита методов.
		 * По умолчанию ALL_METHODS - показывать все методы из всех файлов проекта.
		 */
		public @Nullable MethodCompletionMode methodCompletionMode;

		/**
		 * Скрытый флаг — была ли выполнена автодетекция настроек.
		 * Не редактируется в UI, используется только для проверки.
		 */
		public boolean settingsAutoDetected = false;

		/**
		 * Пользовательский JSON для расширения completion аргументов и значений.
		 */
		public @Nullable String pseudoHashCompletionConfigJson;

		/**
		 * Версия последнего bootstrap-запроса перестройки Parser3-индексов.
		 */
		public @Nullable String indexBootstrapVersion;
	}

	// Скрытый флаг автодетекции

	public boolean isSettingsAutoDetected() {
		return state.settingsAutoDetected;
	}

	public void setSettingsAutoDetected(boolean detected) {
		state.settingsAutoDetected = detected;
	}

	public @Nullable String getIndexBootstrapVersion() {
		return state.indexBootstrapVersion;
	}

	public void setIndexBootstrapVersion(@Nullable String version) {
		state.indexBootstrapVersion = version;
	}
}
