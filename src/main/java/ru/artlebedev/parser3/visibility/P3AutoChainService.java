package ru.artlebedev.parser3.visibility;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.artlebedev.parser3.settings.Parser3ProjectSettings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Сервис для построения цепочки auto.p для заданного файла.
 *
 * Цепочка auto.p — это последовательность файлов auto.p от document_root
 * до директории с целевым файлом.
 *
 * Пример:
 *   document_root = /var/www
 *   file = /var/www/section/subsection/page.p
 *   chain = [/var/www/auto.p, /var/www/section/auto.p, /var/www/section/subsection/auto.p]
 */
@Service(Service.Level.PROJECT)
public final class P3AutoChainService {

	private static final String AUTO_P = "auto.p";
	private static final int MAX_DEPTH = 100; // Защита от бесконечных циклов

	private final Project project;

	public P3AutoChainService(@NotNull Project project) {
		this.project = project;
	}

	public static P3AutoChainService getInstance(@NotNull Project project) {
		return project.getService(P3AutoChainService.class);
	}

	/**
	 * Строит цепочку auto.p для заданного файла.
	 *
	 * @param file целевой файл
	 * @return список auto.p от document_root до директории файла; если document_root не задан
	 *         или файл находится вне него, используется директория самого файла
	 */
	public @NotNull List<VirtualFile> buildChain(@NotNull VirtualFile file) {
		Parser3ProjectSettings settings = Parser3ProjectSettings.getInstance(project);
		VirtualFile documentRoot = settings.getDocumentRootWithFallback();

		if (documentRoot == null || !documentRoot.isDirectory() || !isAncestor(documentRoot, file)) {
			documentRoot = file.isDirectory() ? file : file.getParent();
		}
		if (documentRoot == null || !documentRoot.isDirectory()) {
			return Collections.emptyList();
		}

		List<VirtualFile> chain = new ArrayList<>();
		VirtualFile current = file.isDirectory() ? file : file.getParent();
		int depth = 0;

		// Идём вверх от файла до document_root
		while (current != null && depth < MAX_DEPTH) {
			// Проверяем auto.p в текущей директории
			VirtualFile autoP = current.findChild(AUTO_P);
			if (autoP != null && autoP.isValid() && !autoP.isDirectory()) {
				chain.add(0, autoP); // Добавляем в начало, чтобы сохранить порядок
			}

			// Останавливаемся на document_root
			if (current.equals(documentRoot)) {
				break;
			}

			current = current.getParent();
			depth++;
		}

		return chain;
	}

	/**
	 * Проверяет, что ancestor является родителем file или совпадает с ним.
	 */
	private boolean isAncestor(@NotNull VirtualFile ancestor, @NotNull VirtualFile file) {
		VirtualFile current = file;
		int depth = 0;

		while (current != null && depth < MAX_DEPTH) {
			if (current.equals(ancestor)) {
				return true;
			}
			current = current.getParent();
			depth++;
		}

		return false;
	}
}
