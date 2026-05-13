package ru.artlebedev.parser3;

import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VfsUtil;
import org.jetbrains.annotations.NotNull;
import ru.artlebedev.parser3.settings.Parser3ProjectSettings;
import ru.artlebedev.parser3.visibility.P3VisibilityService;
import ru.artlebedev.parser3.visibility.P3ScopeContext;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Нагрузочные regression-тесты completion на синтетическом обезличенном проекте.
 */
public class x17_PerformanceCompletionTest extends Parser3TestCase {

	private static final int PROJECT_CLASS_COUNT = 1000;
	private static final long HOT_COMPLETION_LIMIT_MS = 250;

	public void testObjectDotCompletionInAutouseProjectWithThousandFilesIsFast() {
		setMethodCompletionMode(Parser3ProjectSettings.MethodCompletionMode.USE_ONLY);
		createHeavyAutouseProject();

		VirtualFile page = createParser3FileInDir("pages/page.p",
				"@main[]\n" +
						"# обезличенный нагрузочный кейс: autouse + много файлов + hash-chain completion\n" +
						"$person[\n" +
						"\t$.profile[\n" +
						"\t\t$.members[\n" +
						"\t\t\t$.name[test_user]\n" +
						"\t\t\t$.items[\n" +
						"\t\t\t\t$.title[test_item]\n" +
						"\t\t\t]\n" +
						"\t\t]\n" +
						"\t]\n" +
						"]\n" +
						"$target[$person.profile.members]\n" +
						"$target.<caret>\n");

		com.intellij.psi.PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
		DumbService.getInstance(getProject()).waitForSmartMode();

		List<String> warmup = complete(page);
		assertTrue("Нагрузочный completion должен видеть hash key name: " + warmup, warmup.contains("name"));
		assertTrue("Нагрузочный completion должен видеть hash key items.: " + warmup, warmup.contains("items."));

		long worstMs = 0;
		for (int i = 0; i < 3; i++) {
			long start = System.nanoTime();
			List<String> completions = complete(page);
			long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
			worstMs = Math.max(worstMs, elapsedMs);
			assertTrue("Completion должен сохранять hash key name: " + completions, completions.contains("name"));
			assertTrue("Completion должен сохранять hash key items.: " + completions, completions.contains("items."));
		}

		assertTrue("Горячий object-dot completion на " + PROJECT_CLASS_COUNT
						+ " Parser3-файлах должен укладываться в " + HOT_COMPLETION_LIMIT_MS
						+ "ms, худший прогретый прогон=" + worstMs + "ms",
				worstMs <= HOT_COMPLETION_LIMIT_MS);
	}

	public void testMainHashCompletionIgnoresDisconnectedClassFiles() throws Exception {
		setMethodCompletionMode(Parser3ProjectSettings.MethodCompletionMode.USE_ONLY);
		createDisconnectedClassProject();

		String pageText =
				"@main[]\n" +
						"# обезличенный кейс: MAIN видит только несколько файлов, классы лежат отдельно\n" +
						"$person[\n" +
						"\t$.name[test_user]\n" +
						"\t$.items[\n" +
						"\t\t$.title[test_item]\n" +
						"\t]\n" +
						"]\n" +
						"$person.<caret>\n";
		VirtualFile page = createParser3FileInDir("pages/disconnected_classes_page.p", pageText);

		com.intellij.psi.PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
		DumbService.getInstance(getProject()).waitForSmartMode();

		P3ScopeContext scopeContext = new P3ScopeContext(getProject(), page, pageText.indexOf("<caret>"));
		scopeContext.getVariableSearchFiles();
		assertNull("Контекст переменных не должен заранее строить classSearchFiles",
				readLazyClassSearchFiles(scopeContext));

		List<String> warmup = complete(page);
		assertTrue("Completion должен видеть локальный hash key name: " + warmup, warmup.contains("name"));
		assertTrue("Completion должен видеть локальный hash key items.: " + warmup, warmup.contains("items."));

		long worstMs = 0;
		for (int i = 0; i < 3; i++) {
			long start = System.nanoTime();
			List<String> completions = complete(page);
			long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
			worstMs = Math.max(worstMs, elapsedMs);
			assertTrue("Completion должен сохранять hash key name: " + completions, completions.contains("name"));
			assertTrue("Completion должен сохранять hash key items.: " + completions, completions.contains("items."));
		}

		assertTrue("Hash completion не должен зависеть от " + PROJECT_CLASS_COUNT
						+ " неподключённых классов, худший прогретый прогон=" + worstMs + "ms",
				worstMs <= HOT_COMPLETION_LIMIT_MS);
	}

	public void testVisibleFilesCacheSurvivesPlainTypingWithoutUseChanges() {
		setMethodCompletionMode(Parser3ProjectSettings.MethodCompletionMode.USE_ONLY);
		createParser3FileInDir("lib/common.p",
				"@helper[]\n" +
						"$helperValue[test_helper]\n");
		VirtualFile page = createParser3FileInDir("pages/plain_typing_page.p",
				"@main[]\n" +
						"^use[../lib/common.p]\n" +
						"$news_uri[^request:uri.split[?;lh]]\n" +
						"$news_uri[^news_uri.0.trim[both;/]]\n" +
						"$start_uri[^news_uri.split[/;lh]]\n" +
						"$start_uri[$start_uri.0]\n" +
						"$sta<caret>");

		com.intellij.psi.PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
		DumbService.getInstance(getProject()).waitForSmartMode();

		P3VisibilityService visibilityService = P3VisibilityService.getInstance(getProject());
		List<VirtualFile> before = visibilityService.getVisibleFiles(page);

		ApplicationManager.getApplication().runWriteAction(() -> {
			try {
				VfsUtil.saveText(page,
						"@main[]\n" +
								"^use[../lib/common.p]\n" +
								"$news_uri[^request:uri.split[?;lh]]\n" +
								"$news_uri[^news_uri.0.trim[both;/]]\n" +
								"$start_uri[^news_uri.split[/;lh]]\n" +
								"$start_uri[$start_uri.0]\n" +
								"$star<caret>");
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		});
		com.intellij.psi.PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
		DumbService.getInstance(getProject()).waitForSmartMode();

		List<VirtualFile> after = visibilityService.getVisibleFiles(page);
		assertSame("Обычный набор переменной не должен сбрасывать кеш видимых файлов, если use-граф не менялся",
				before, after);
		assertTrue("Видимость должна сохранить подключённый файл: " + after,
				after.stream().anyMatch(file -> "common.p".equals(file.getName())));
	}

	private void createHeavyAutouseProject() {
		createParser3FileInDir("auto.p",
				"@autouse[name]\n" +
						"^use[classes/$name.p]\n");

		for (int i = 0; i < PROJECT_CLASS_COUNT; i++) {
			String className = String.format("TestClass%04d", i);
			createParser3FileInDir("classes/" + className + ".p",
					"@CLASS\n" +
							className + "\n" +
							"\n" +
							"@create[]\n" +
							"$self.item[\n" +
							"\t$.id[test_id]\n" +
							"\t$.created_at[2026-01-01]\n" +
							"]\n" +
							"\n" +
							"@GET_name[]\n" +
							"$result[test_name]\n");
		}
	}

	private void createDisconnectedClassProject() {
		createParser3FileInDir("auto.p",
				"@autouse[]\n" +
						"$autoValue[test_auto]\n");
		createParser3FileInDir("lib/helpers.p",
				"@helper[]\n" +
						"$helperValue[test_helper]\n");
		createParser3FileInDir("pages/auto.p",
				"@local_auto[]\n" +
						"$localValue[test_local]\n");

		for (int i = 0; i < PROJECT_CLASS_COUNT; i++) {
			String className = String.format("UnusedClass%04d", i);
			createParser3FileInDir("unused_classes/" + className + ".p",
					"@CLASS\n" +
							className + "\n" +
							"\n" +
							"@create[]\n" +
							"$self.item[\n" +
							"\t$.id[test_id]\n" +
							"\t$.updated_at[2026-01-01]\n" +
							"]\n");
		}
	}

	private Object readLazyClassSearchFiles(@NotNull P3ScopeContext scopeContext) throws Exception {
		java.lang.reflect.Field field = P3ScopeContext.class.getDeclaredField("classSearchFiles");
		field.setAccessible(true);
		return field.get(scopeContext);
	}

	private @NotNull List<String> complete(@NotNull VirtualFile file) {
		myFixture.configureFromExistingVirtualFile(file);
		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		if (elements == null) {
			return List.of();
		}
		return Arrays.stream(elements)
				.map(LookupElement::getLookupString)
				.collect(Collectors.toList());
	}
}
