package ru.artlebedev.parser3.visibility;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectRootModificationTracker;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiManager;
import org.jetbrains.annotations.NotNull;
import ru.artlebedev.parser3.Parser3Language;
import ru.artlebedev.parser3.index.P3IndexMaintenance;
import ru.artlebedev.parser3.settings.Parser3ProjectSettings;
import ru.artlebedev.parser3.use.P3UseResolver;
import ru.artlebedev.parser3.utils.Parser3FileUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
	private final P3AutoChainService autoChainService;
	private final P3UseResolver useResolver;
	private volatile long allProjectFilesModCount = -1;
	private volatile @NotNull List<VirtualFile> allProjectFilesCache = Collections.emptyList();
	private final @NotNull Map<VirtualFile, VisibleFilesCacheEntry> visibleFilesCache = new ConcurrentHashMap<>();

	private static final int MAX_USE_DEPTH = 50; // Защита от циклических зависимостей
	private static final boolean DEBUG_PERF = false;

	public P3VisibilityService(@NotNull Project project) {
		this.project = project;
		this.psiManager = PsiManager.getInstance(project);
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
		if (!file.isValid() || psiManager.findFile(file) == null) {
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

		VisibleFilesCacheEntry cached = visibleFilesCache.get(file);
		VisibleFilesCacheKey cacheKey = buildVisibleFilesCacheKey(file, cached != null ? cached.files : null);
		if (cached != null && cached.key.equals(cacheKey)) {
			if (DEBUG_PERF) {
				System.out.println("[P3Visibility.PERF] getVisibleFiles TOTAL: "
						+ (System.currentTimeMillis() - startTime) + "ms"
						+ " file=" + file.getName()
						+ " visible=" + cached.files.size()
						+ " cacheHit=true");
			}
			return cached.files;
		}

		long computeStart = DEBUG_PERF ? System.currentTimeMillis() : 0;
		List<VirtualFile> visible = Collections.unmodifiableList(computeVisibleFiles(file));
		visibleFilesCache.put(file, new VisibleFilesCacheEntry(buildVisibleFilesCacheKey(file, visible), visible));
		if (DEBUG_PERF) {
			System.out.println("[P3Visibility.PERF] getVisibleFiles cacheCompute: "
					+ (System.currentTimeMillis() - computeStart) + "ms"
					+ " file=" + file.getName()
					+ " visible=" + visible.size());
		}
		if (DEBUG_PERF) {
			System.out.println("[P3Visibility.PERF] getVisibleFiles TOTAL: "
					+ (System.currentTimeMillis() - startTime) + "ms"
					+ " file=" + file.getName()
					+ " visible=" + visible.size()
					+ " cacheHit=false");
		}
		return visible;
	}

	private @NotNull VisibleFilesCacheKey buildVisibleFilesCacheKey(
			@NotNull VirtualFile file,
			@org.jetbrains.annotations.Nullable List<VirtualFile> cachedVisibleFiles
	) {
		Parser3ProjectSettings settings = Parser3ProjectSettings.getInstance(project);
		long rootStamp = ProjectRootModificationTracker.getInstance(project).getModificationCount();
		return new VisibleFilesCacheKey(
				rootStamp,
				computeCurrentUseFingerprint(file),
				computeVisibleDependencyStamp(file, cachedVisibleFiles),
				settings.getDocumentRootPath(),
				settings.getMainAutoPath()
		);
	}

	private long computeCurrentUseFingerprint(@NotNull VirtualFile file) {
		String text = readFileTextSmart(file);
		if (text.indexOf("@USE") < 0 && text.indexOf("^use") < 0) return 0L;

		long hash = 1125899906842597L;
		int count = 0;
		for (int pos = 0; pos < text.length(); pos++) {
			boolean atUse = text.startsWith("@USE", pos);
			boolean caretUse = text.startsWith("^use", pos);
			if (!atUse && !caretUse) continue;

			int lineStart = pos;
			while (lineStart > 0 && text.charAt(lineStart - 1) != '\n' && text.charAt(lineStart - 1) != '\r') {
				lineStart--;
			}
			int lineEnd = pos;
			while (lineEnd < text.length() && text.charAt(lineEnd) != '\n' && text.charAt(lineEnd) != '\r') {
				lineEnd++;
			}
			hash = 31 * hash + text.substring(lineStart, lineEnd).trim().hashCode();
			count++;
			pos = lineEnd;
		}
		return 31 * hash + count;
	}

	private long computeVisibleDependencyStamp(
			@NotNull VirtualFile currentFile,
			@org.jetbrains.annotations.Nullable List<VirtualFile> cachedVisibleFiles
	) {
		if (cachedVisibleFiles == null || cachedVisibleFiles.isEmpty()) return 0L;
		long hash = 1469598103934665603L;
		for (VirtualFile visibleFile : cachedVisibleFiles) {
			if (currentFile.equals(visibleFile)) continue;
			hash = 1099511628211L * hash + visibleFile.getPath().hashCode();
			hash = 1099511628211L * hash + visibleFile.getModificationStamp();
		}
		return hash;
	}

	private static @NotNull String readFileTextSmart(@NotNull VirtualFile file) {
		Document document = FileDocumentManager.getInstance().getDocument(file);
		if (document != null) {
			return document.getText();
		}
		try {
			return new String(file.contentsToByteArray(), StandardCharsets.UTF_8);
		} catch (Exception ignored) {
			return "";
		}
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

	private static final class VisibleFilesCacheEntry {
		final @NotNull VisibleFilesCacheKey key;
		final @NotNull List<VirtualFile> files;

		private VisibleFilesCacheEntry(
				@NotNull VisibleFilesCacheKey key,
				@NotNull List<VirtualFile> files
		) {
			this.key = key;
			this.files = files;
		}
	}

	private static final class VisibleFilesCacheKey {
		final long rootStamp;
		final long currentUseFingerprint;
		final long dependencyStamp;
		final @NotNull String documentRootPath;
		final @NotNull String mainAutoPath;

		private VisibleFilesCacheKey(
				long rootStamp,
				long currentUseFingerprint,
				long dependencyStamp,
				@org.jetbrains.annotations.Nullable String documentRootPath,
				@org.jetbrains.annotations.Nullable String mainAutoPath
		) {
			this.rootStamp = rootStamp;
			this.currentUseFingerprint = currentUseFingerprint;
			this.dependencyStamp = dependencyStamp;
			this.documentRootPath = documentRootPath != null ? documentRootPath : "";
			this.mainAutoPath = mainAutoPath != null ? mainAutoPath : "";
		}

		@Override
		public boolean equals(Object other) {
			if (this == other) return true;
			if (!(other instanceof VisibleFilesCacheKey)) return false;
			VisibleFilesCacheKey key = (VisibleFilesCacheKey) other;
			return rootStamp == key.rootStamp
					&& currentUseFingerprint == key.currentUseFingerprint
					&& dependencyStamp == key.dependencyStamp
					&& documentRootPath.equals(key.documentRootPath)
					&& mainAutoPath.equals(key.mainAutoPath);
		}

		@Override
		public int hashCode() {
			int result = Long.hashCode(rootStamp);
			result = 31 * result + Long.hashCode(currentUseFingerprint);
			result = 31 * result + Long.hashCode(dependencyStamp);
			result = 31 * result + documentRootPath.hashCode();
			result = 31 * result + mainAutoPath.hashCode();
			return result;
		}
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
		long modCount = com.intellij.psi.util.PsiModificationTracker.getInstance(project)
				.forLanguage(Parser3Language.INSTANCE)
				.getModificationCount();
		List<VirtualFile> cached = allProjectFilesCache;
		boolean cacheHit = allProjectFilesModCount == modCount;
		List<VirtualFile> result;
		if (cacheHit) {
			result = new ArrayList<>(cached);
		} else {
			synchronized (this) {
				cached = allProjectFilesCache;
				cacheHit = allProjectFilesModCount == modCount;
				if (cacheHit) {
					result = new ArrayList<>(cached);
				} else {
					result = new ArrayList<>(P3IndexMaintenance.getIndexedParser3Files(project));
					allProjectFilesCache = Collections.unmodifiableList(new ArrayList<>(result));
					allProjectFilesModCount = modCount;
				}
			}
		}
		if (DEBUG_PERF) {
			System.out.println("[P3Visibility.PERF] getAllProjectFiles: "
					+ (System.currentTimeMillis() - startTime) + "ms"
					+ " files=" + result.size()
					+ " cacheHit=" + cacheHit);
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
