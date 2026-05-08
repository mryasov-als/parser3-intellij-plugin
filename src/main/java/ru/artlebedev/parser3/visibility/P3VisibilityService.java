package ru.artlebedev.parser3.visibility;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import org.jetbrains.annotations.NotNull;
import ru.artlebedev.parser3.Parser3Language;
import ru.artlebedev.parser3.index.P3IndexMaintenance;
import ru.artlebedev.parser3.settings.Parser3ProjectSettings;
import ru.artlebedev.parser3.use.P3UseResolver;
import ru.artlebedev.parser3.utils.Parser3FileUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Основной сервис для определения видимости файлов в Parser3.
 *
 * Для заданного файла F определяет упорядоченный список файлов,
 * из которых видны методы:
 * 1. main_auto (если задан)
 * 2. auto.p chain (от document_root до директории F)
 * 3. use-файлы (из F и из auto.p файлов)
 * 4. сам файл F
 *
 * Результаты кешируются и инвалидируются при изменении файлов.
 */
@Service(Service.Level.PROJECT)
public final class P3VisibilityService {

	private final Project project;
	private final PsiManager psiManager;
	private final CachedValuesManager cachedValuesManager;
	private final P3AutoChainService autoChainService;
	private final P3UseResolver useResolver;

	private static final int MAX_USE_DEPTH = 50; // Защита от циклических зависимостей
	private static final boolean DEBUG_PERF = false;

	public P3VisibilityService(@NotNull Project project) {
		this.project = project;
		this.psiManager = PsiManager.getInstance(project);
		this.cachedValuesManager = CachedValuesManager.getManager(project);
		this.autoChainService = P3AutoChainService.getInstance(project);
		this.useResolver = new P3UseResolver(project);
	}

	public static P3VisibilityService getInstance(@NotNull Project project) {
		return project.getService(P3VisibilityService.class);
	}

	/**
	 * Возвращает упорядоченный список файлов, видимых из заданного файла.
	 * Результат кешируется и зависит от изменений файлов.
	 *
	 * @param file целевой файл
	 * @return упорядоченный список видимых файлов (включая сам файл)
	 */
	public @NotNull List<VirtualFile> getVisibleFiles(@NotNull VirtualFile file) {
		long startTime = DEBUG_PERF ? System.currentTimeMillis() : 0;
		PsiFile psiFile = psiManager.findFile(file);
		if (psiFile == null) {
			// Запасной вариант: вернуть только сам файл
			List<VirtualFile> result = new ArrayList<>();
			result.add(file);
			if (DEBUG_PERF) {
				System.out.println("[P3Visibility.PERF] getVisibleFiles TOTAL: "
						+ (System.currentTimeMillis() - startTime) + "ms"
						+ " file=" + file.getName()
						+ " psiFile=null result=1");
			}
			return result;
		}

		// getCachedValue возвращает результат напрямую
		// Используем PROJECT_ROOT_MODIFICATION_TRACKER - кеш сбрасывается только при изменении структуры проекта,
		// а не при каждом нажатии клавиши в файле
		List<VirtualFile> result = cachedValuesManager.getCachedValue(
				psiFile,
				() -> {
					long computeStart = DEBUG_PERF ? System.currentTimeMillis() : 0;
					List<VirtualFile> visible = computeVisibleFiles(file);
					if (DEBUG_PERF) {
						System.out.println("[P3Visibility.PERF] getVisibleFiles cacheCompute: "
								+ (System.currentTimeMillis() - computeStart) + "ms"
								+ " file=" + file.getName()
								+ " visible=" + visible.size());
					}
					return CachedValueProvider.Result.create(
							visible,
							com.intellij.psi.util.PsiModificationTracker.getInstance(project).forLanguage(Parser3Language.INSTANCE)
					);
				}
		);
		if (DEBUG_PERF) {
			System.out.println("[P3Visibility.PERF] getVisibleFiles TOTAL: "
					+ (System.currentTimeMillis() - startTime) + "ms"
					+ " file=" + file.getName()
					+ " visible=" + result.size());
		}
		return result;
	}

	private @NotNull List<VirtualFile> computeVisibleFiles(@NotNull VirtualFile file) {
		long startTime = DEBUG_PERF ? System.currentTimeMillis() : 0;
		// Используем LinkedHashSet для сохранения порядка и избежания дубликатов
		Set<VirtualFile> visible = new LinkedHashSet<>();
		Set<VirtualFile> visited = new LinkedHashSet<>();

		// 1. Добавить main_auto (если задан)
		Parser3ProjectSettings settings = Parser3ProjectSettings.getInstance(project);
		VirtualFile mainAuto = settings.getMainAuto();
		if (mainAuto != null && mainAuto.isValid()) {
			long mainAutoStart = DEBUG_PERF ? System.currentTimeMillis() : 0;
			visible.add(mainAuto);
			// Собрать use из main_auto
			collectUsesRecursive(mainAuto, visible, visited, 0);
			if (DEBUG_PERF) {
				System.out.println("[P3Visibility.PERF] computeVisibleFiles mainAuto: "
						+ (System.currentTimeMillis() - mainAutoStart) + "ms"
						+ " file=" + file.getName()
						+ " mainAuto=" + mainAuto.getName()
						+ " visible=" + visible.size()
						+ " visited=" + visited.size());
			}
		}

		// 2. Добавить auto.p chain
		long autoChainBuildStart = DEBUG_PERF ? System.currentTimeMillis() : 0;
		List<VirtualFile> autoChain = autoChainService.buildChain(file);
		if (DEBUG_PERF) {
			System.out.println("[P3Visibility.PERF] computeVisibleFiles buildAutoChain: "
					+ (System.currentTimeMillis() - autoChainBuildStart) + "ms"
					+ " file=" + file.getName()
					+ " autoChain=" + autoChain.size());
		}
		long autoChainCollectStart = DEBUG_PERF ? System.currentTimeMillis() : 0;
		for (VirtualFile autoP : autoChain) {
			visible.add(autoP);
			// Собрать use из каждого auto.p
			collectUsesRecursive(autoP, visible, visited, 0);
		}
		if (DEBUG_PERF) {
			System.out.println("[P3Visibility.PERF] computeVisibleFiles collectAutoChainUses: "
					+ (System.currentTimeMillis() - autoChainCollectStart) + "ms"
					+ " file=" + file.getName()
					+ " autoChain=" + autoChain.size()
					+ " visible=" + visible.size()
					+ " visited=" + visited.size());
		}

		// 3. Собрать use из текущего файла
		long currentUseStart = DEBUG_PERF ? System.currentTimeMillis() : 0;
		collectUsesRecursive(file, visible, visited, 0);
		if (DEBUG_PERF) {
			System.out.println("[P3Visibility.PERF] computeVisibleFiles collectCurrentUses: "
					+ (System.currentTimeMillis() - currentUseStart) + "ms"
					+ " file=" + file.getName()
					+ " visible=" + visible.size()
					+ " visited=" + visited.size());
		}

		// 4. Добавить сам текущий файл (в конце)
		visible.add(file);

		List<VirtualFile> result = new ArrayList<>(visible);
		if (DEBUG_PERF) {
			System.out.println("[P3Visibility.PERF] computeVisibleFiles TOTAL: "
					+ (System.currentTimeMillis() - startTime) + "ms"
					+ " file=" + file.getName()
					+ " visible=" + result.size()
					+ " visited=" + visited.size());
		}
		return result;
	}

	/**
	 * Возвращает все Parser3 файлы проекта.
	 * Используется в режиме "Все методы".
	 * БЫСТРО - использует FileTypeIndex вместо рекурсивного сканирования.
	 *
	 * @return список всех Parser3 файлов
	 */
	public @NotNull List<VirtualFile> getAllProjectFiles() {
		long startTime = DEBUG_PERF ? System.currentTimeMillis() : 0;
		List<VirtualFile> result = new ArrayList<>(P3IndexMaintenance.getIndexedParser3Files(project));
		if (DEBUG_PERF) {
			System.out.println("[P3Visibility.PERF] getAllProjectFiles: "
					+ (System.currentTimeMillis() - startTime) + "ms"
					+ " files=" + result.size());
		}
		return result;
	}

	/**
	 * Рекурсивно собирает файлы из use директив.
	 * Использует P3UseFileIndex для быстрого получения USE без парсинга PSI.
	 * Защищает от циклических зависимостей через ограничение глубины и отслеживание visited.
	 */
	private void collectUsesRecursive(
			@NotNull VirtualFile file,
			@NotNull Set<VirtualFile> visible,
			@NotNull Set<VirtualFile> visited,
			int depth
	) {
		long startTime = DEBUG_PERF ? System.currentTimeMillis() : 0;
		if (depth >= MAX_USE_DEPTH) {
			if (DEBUG_PERF) {
				System.out.println("[P3Visibility.PERF] collectUsesRecursive depthLimit file="
						+ file.getName() + " depth=" + depth);
			}
			return; // Слишком глубоко, останавливаем рекурсию
		}

		if (visited.contains(file)) {
			if (DEBUG_PERF) {
				System.out.println("[P3Visibility.PERF] collectUsesRecursive visitedSkip file="
						+ file.getName() + " depth=" + depth);
			}
			return; // Уже посещали, избегаем циклической зависимости
		}

		visited.add(file);

		// Получаем USE пути из индекса в порядке появления в файле.
		long indexStart = DEBUG_PERF ? System.currentTimeMillis() : 0;
		List<ru.artlebedev.parser3.index.P3UseFileIndex.UseInfo> uses =
				ru.artlebedev.parser3.index.P3UseFileIndex.getUseInfosExcludingComments(file, project);
		long indexElapsed = DEBUG_PERF ? System.currentTimeMillis() - indexStart : 0;

		// Разрешить и добавить каждый use
		long resolveElapsed = 0;
		for (ru.artlebedev.parser3.index.P3UseFileIndex.UseInfo useInfo : uses) {
			long resolveStart = DEBUG_PERF ? System.currentTimeMillis() : 0;
			VirtualFile resolved = useResolver.resolve(useInfo.fullPath, file);
			if (DEBUG_PERF) {
				resolveElapsed += System.currentTimeMillis() - resolveStart;
			}
			if (resolved != null && !visible.contains(resolved)) {
				visible.add(resolved);
				// Рекурсивно собрать use из подключенного файла
				collectUsesRecursive(resolved, visible, visited, depth + 1);
			}
		}
		if (DEBUG_PERF) {
			long elapsed = System.currentTimeMillis() - startTime;
			if (elapsed > 5 || !uses.isEmpty()) {
				System.out.println("[P3Visibility.PERF] collectUsesRecursive: "
						+ elapsed + "ms"
						+ " file=" + file.getName()
						+ " depth=" + depth
						+ " uses=" + uses.size()
						+ " indexTime=" + indexElapsed + "ms"
						+ " resolveTime=" + resolveElapsed + "ms"
						+ " visible=" + visible.size()
						+ " visited=" + visited.size());
			}
		}
	}
}
