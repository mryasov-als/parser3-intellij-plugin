package ru.artlebedev.parser3.resolve;

import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.openapi.application.QueryExecutorBase;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.search.FileTypeIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.util.Processor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.artlebedev.parser3.injector.Parser3DebugLog;
import ru.artlebedev.parser3.psi.Parser3PsiFile;

import java.util.Collection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ищет использования CSS классов (class="...") при клике на определение (.className) в Parser3 файлах.
 *
 * Когда пользователь кликает на .x-css-class в <style> блоке Parser3 файла,
 * этот searcher находит все class="x-css-class" в Parser3 и HTML файлах проекта.
 *
 * ОПТИМИЗАЦИЯ: Используем текстовый поиск (PsiSearchHelper) вместо перебора PSI всех файлов.
 * Ищем строку className в файлах, потом проверяем только найденные.
 */
public class Parser3CssClassReferencesSearcher extends QueryExecutorBase<PsiReference, ReferencesSearch.SearchParameters> {

	// Паттерн для извлечения имени CSS класса из селектора
	private static final Pattern CSS_CLASS_SELECTOR = Pattern.compile("^\\.([a-zA-Z_][a-zA-Z0-9_-]*)");

	public Parser3CssClassReferencesSearcher() {
		super(true); // require read action
	}

	@Override
	public void processQuery(
			@NotNull ReferencesSearch.SearchParameters queryParameters,
			@NotNull Processor<? super PsiReference> consumer
	) {
		PsiElement target = queryParameters.getElementToSearch();

		// Проверяем что это элемент внутри CSS (в Parser3 файле)
		String className = extractCssClassName(target);
		if (className == null) {
			return;
		}

		// Проверяем что это из Parser3 файла
		PsiFile parser3File = findParser3HostFile(target);
		if (parser3File == null) {
			return;
		}

		Project project = parser3File.getProject();
		Parser3DebugLog.log("CssRefSearch", "Ищем usages для ." + className + " в проекте");

		// Используем текстовый поиск — ищем файлы содержащие className
		com.intellij.psi.search.PsiSearchHelper searchHelper =
				com.intellij.psi.search.PsiSearchHelper.getInstance(project);

		GlobalSearchScope scope = queryParameters.getEffectiveSearchScope() instanceof GlobalSearchScope
				? (GlobalSearchScope) queryParameters.getEffectiveSearchScope()
				: GlobalSearchScope.projectScope(project);

		// Ищем текст className в проекте
		searchHelper.processElementsWithWord(
				(element, offsetInElement) -> {
					// Проверяем что это class="...className..."
					if (element instanceof XmlAttributeValue) {
						XmlAttributeValue attrValue = (XmlAttributeValue) element;
						PsiElement parent = attrValue.getParent();
						if (parent instanceof XmlAttribute) {
							XmlAttribute attr = (XmlAttribute) parent;
							if ("class".equalsIgnoreCase(attr.getName())) {
								String value = attrValue.getValue();
								if (value != null && containsClass(value, className)) {
									PsiReference ref = createUsageReference(attrValue, className);
									if (ref != null) {
										Parser3DebugLog.log("CssRefSearch",
												"  Найдено: class=\"" + value + "\" в " +
														element.getContainingFile().getName());
										return consumer.process(ref);
									}
								}
							}
						}
					}
					return true;
				},
				scope,
				className,
				com.intellij.psi.search.UsageSearchContext.IN_STRINGS,
				true // case sensitive
		);
	}

	/**
	 * Извлекает имя CSS класса из элемента (если это CSS селектор).
	 */
	@Nullable
	private String extractCssClassName(@NotNull PsiElement element) {
		String text = element.getText();
		if (text == null) return null;

		Matcher matcher = CSS_CLASS_SELECTOR.matcher(text);
		if (matcher.find()) {
			return matcher.group(1);
		}

		// Может быть часть селектора — проверяем родителя
		PsiElement parent = element.getParent();
		if (parent != null) {
			text = parent.getText();
			if (text != null) {
				matcher = CSS_CLASS_SELECTOR.matcher(text);
				if (matcher.find()) {
					return matcher.group(1);
				}
			}
		}

		return null;
	}

	/**
	 * Находит Parser3 файл-хост для элемента (включая injected).
	 */
	@Nullable
	private PsiFile findParser3HostFile(@NotNull PsiElement element) {
		PsiFile file = element.getContainingFile();
		if (file == null) return null;

		if (file instanceof Parser3PsiFile) return file;

		// Injected — ищем host
		Project project = element.getProject();
		InjectedLanguageManager injManager = InjectedLanguageManager.getInstance(project);

		PsiFile hostFile = injManager.getTopLevelFile(element);
		if (hostFile instanceof Parser3PsiFile) return hostFile;

		// Пробуем через context
		PsiElement context = file.getContext();
		if (context != null) {
			PsiFile contextFile = context.getContainingFile();
			if (contextFile instanceof Parser3PsiFile) return contextFile;
		}

		return null;
	}

	/**
	 * Проверяет содержит ли значение атрибута class указанный класс.
	 */
	private boolean containsClass(@NotNull String classValue, @NotNull String className) {
		String[] classes = classValue.split("\\s+");
		for (String cls : classes) {
			if (cls.equals(className)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Создаёт reference для найденного использования.
	 */
	@Nullable
	private PsiReference createUsageReference(@NotNull XmlAttributeValue element, @NotNull String className) {
		String value = element.getValue();
		if (value == null) return null;

		int classStart = findClassPosition(value, className);
		if (classStart < 0) return null;

		int valueOffset = 1; // открывающая кавычка
		TextRange range = TextRange.create(
				valueOffset + classStart,
				valueOffset + classStart + className.length()
		);

		return new CssClassUsageReference(element, range, className);
	}

	/**
	 * Находит позицию класса в строке значения.
	 */
	private int findClassPosition(@NotNull String value, @NotNull String className) {
		String[] classes = value.split("\\s+");
		int pos = 0;
		for (String cls : classes) {
			if (cls.equals(className)) {
				return value.indexOf(className, pos);
			}
			pos += cls.length() + 1;
		}
		return -1;
	}

	/**
	 * Reference для использования CSS класса.
	 */
	private static class CssClassUsageReference extends PsiReferenceBase<XmlAttributeValue> {
		private final String className;

		CssClassUsageReference(@NotNull XmlAttributeValue element, @NotNull TextRange range, @NotNull String className) {
			super(element, range, true);
			this.className = className;
		}

		@Override
		public @Nullable PsiElement resolve() {
			return null;
		}

		@Override
		public Object @NotNull [] getVariants() {
			return EMPTY_ARRAY;
		}
	}
}