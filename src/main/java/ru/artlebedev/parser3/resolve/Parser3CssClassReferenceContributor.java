package ru.artlebedev.parser3.resolve;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.*;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.util.ProcessingContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.artlebedev.parser3.index.P3CssClassIndex;
import ru.artlebedev.parser3.injector.Parser3DebugLog;
import ru.artlebedev.parser3.psi.Parser3PsiFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Добавляет references к CSS классам в injected HTML внутри Parser3 файлов.
 *
 * Когда пользователь кликает на class="item", этот contributor добавляет
 * references к .item определениям используя индекс P3CssClassFileIndex
 * (для .p файлов) и текстовый поиск (для .css/.scss/.less файлов).
 *
 * ОПТИМИЗАЦИЯ: не загружает PSI файлов при фоновом resolve.
 * PSI загружается только при навигации (Ctrl+Click).
 */
public class Parser3CssClassReferenceContributor extends PsiReferenceContributor {

	@Override
	public void registerReferenceProviders(@NotNull PsiReferenceRegistrar registrar) {
		Parser3DebugLog.log("CssRef", "Parser3CssClassReferenceContributor зарегистрирован");

		// Регистрируемся для XmlAttributeValue (значения атрибутов в HTML)
		registrar.registerReferenceProvider(
				PlatformPatterns.psiElement(XmlAttributeValue.class),
				new PsiReferenceProvider() {
					@Override
					public PsiReference @NotNull [] getReferencesByElement(
							@NotNull PsiElement element,
							@NotNull ProcessingContext context
					) {
						if (!(element instanceof XmlAttributeValue)) {
							return PsiReference.EMPTY_ARRAY;
						}

						XmlAttributeValue attrValue = (XmlAttributeValue) element;

						// Проверяем что это атрибут class
						PsiElement parent = attrValue.getParent();
						if (!(parent instanceof XmlAttribute)) {
							return PsiReference.EMPTY_ARRAY;
						}

						XmlAttribute attr = (XmlAttribute) parent;
						if (!"class".equalsIgnoreCase(attr.getName())) {
							return PsiReference.EMPTY_ARRAY;
						}

						// Проверяем что мы внутри Parser3 файла (через injection host)
						if (!isInsideParser3File(element)) {
							return PsiReference.EMPTY_ARRAY;
						}

						// Получаем значение атрибута (может содержать несколько классов)
						String value = attrValue.getValue();
						if (value == null || value.isEmpty()) {
							return PsiReference.EMPTY_ARRAY;
						}

						Parser3DebugLog.log("CssRef", "Обрабатываем class=\"" + value + "\"");

						// Разбиваем на отдельные классы и создаём references с правильным TextRange
						List<PsiReference> refs = new ArrayList<>();

						// Позиция начала value внутри атрибута (после открывающей кавычки)
						int valueStartInElement = 1;

						// Ищем каждый класс и его позицию
						int searchFrom = 0;
						while (searchFrom < value.length()) {
							// Пропускаем пробелы
							while (searchFrom < value.length() && Character.isWhitespace(value.charAt(searchFrom))) {
								searchFrom++;
							}
							if (searchFrom >= value.length()) break;

							// Находим конец класса
							int classStart = searchFrom;
							while (searchFrom < value.length() && !Character.isWhitespace(value.charAt(searchFrom))) {
								searchFrom++;
							}
							int classEnd = searchFrom;

							String className = value.substring(classStart, classEnd);
							if (!className.isEmpty()) {
								// TextRange относительно XmlAttributeValue элемента
								com.intellij.openapi.util.TextRange range = new com.intellij.openapi.util.TextRange(
										valueStartInElement + classStart,
										valueStartInElement + classEnd
								);
								refs.add(new Parser3CssClassReference(attrValue, className, range));
								Parser3DebugLog.log("CssRef", "  Создан reference для: " + className + " range=" + range);
							}
						}

						return refs.toArray(PsiReference.EMPTY_ARRAY);
					}
				}
		);
	}

	/**
	 * Проверяет, находится ли элемент внутри Parser3 файла (через injection).
	 */
	private static boolean isInsideParser3File(@NotNull PsiElement element) {
		PsiFile file = element.getContainingFile();
		if (file == null) {
			return false;
		}

		// Прямой Parser3 файл
		if (file instanceof Parser3PsiFile) {
			return true;
		}

		// Проверяем injection host
		PsiElement context = file.getContext();
		if (context != null) {
			PsiFile hostFile = context.getContainingFile();
			if (hostFile instanceof Parser3PsiFile) {
				return true;
			}
		}

		return false;
	}

	/**
	 * Reference к CSS классу — использует индекс для resolve.
	 * PSI файлов НЕ загружается при фоновом resolve — только при навигации.
	 */
	private static class Parser3CssClassReference extends PsiReferenceBase<XmlAttributeValue>
			implements PsiPolyVariantReference {

		private final String className;

		Parser3CssClassReference(@NotNull XmlAttributeValue element, @NotNull String className,
								 @NotNull com.intellij.openapi.util.TextRange rangeInElement) {
			super(element, rangeInElement);
			this.className = className;
		}

		@Override
		public @Nullable PsiElement resolve() {
			ResolveResult[] results = multiResolve(false);
			return results.length == 1 ? results[0].getElement() : null;
		}

		@Override
		public ResolveResult @NotNull [] multiResolve(boolean incompleteCode) {
			Parser3DebugLog.log("CssRef", "multiResolve для: " + className);

			PsiFile file = myElement.getContainingFile();
			if (file == null) {
				return ResolveResult.EMPTY_ARRAY;
			}

			Project project = file.getProject();
			P3CssClassIndex cssIndex = P3CssClassIndex.getInstance(project);

			// Получаем все определения из индекса — без загрузки PSI
			List<P3CssClassIndex.CssClassLocation> locations = cssIndex.findDefinitions(className);

			if (locations.isEmpty()) {
				return ResolveResult.EMPTY_ARRAY;
			}

			// Конвертируем в ResolveResult — PSI загружается лениво при getElement()
			List<ResolveResult> results = new ArrayList<>();
			for (P3CssClassIndex.CssClassLocation loc : locations) {
				results.add(new LazyResolveResult(project, loc, className));
			}

			Parser3DebugLog.log("CssRef", "  Всего найдено: " + results.size());
			return results.toArray(ResolveResult.EMPTY_ARRAY);
		}

		@Override
		public Object @NotNull [] getVariants() {
			// Автокомплит CSS классов
			PsiFile file = myElement.getContainingFile();
			if (file == null) {
				return EMPTY_ARRAY;
			}

			Project project = file.getProject();
			P3CssClassIndex cssIndex = P3CssClassIndex.getInstance(project);

			Map<String, String> allClasses = cssIndex.getAllClassNames();

			List<com.intellij.codeInsight.lookup.LookupElement> variants = new ArrayList<>();
			for (Map.Entry<String, String> entry : allClasses.entrySet()) {
				variants.add(com.intellij.codeInsight.lookup.LookupElementBuilder.create(entry.getKey())
						.withTypeText(entry.getValue())
						.withIcon(com.intellij.icons.AllIcons.Xml.Css_class));
			}

			return variants.toArray();
		}
	}

	/**
	 * Ленивый ResolveResult — PSI загружается только при вызове getElement().
	 * При фоновом resolve (подсветка, аннотации) getElement() может вызываться,
	 * но мы минимизируем работу — один findFile + findElementAt вместо
	 * перебора всех файлов проекта.
	 */
	private static class LazyResolveResult implements ResolveResult {
		private final Project project;
		private final P3CssClassIndex.CssClassLocation location;
		private final String className;
		private PsiElement cachedElement;
		private boolean resolved;

		LazyResolveResult(@NotNull Project project,
						  @NotNull P3CssClassIndex.CssClassLocation location,
						  @NotNull String className) {
			this.project = project;
			this.location = location;
			this.className = className;
		}

		@Override
		public @Nullable PsiElement getElement() {
			if (!resolved) {
				resolved = true;
				P3CssClassIndex cssIndex = P3CssClassIndex.getInstance(project);
				PsiElement element = cssIndex.resolveToElement(location);
				if (element != null) {
					// Оборачиваем для красивого отображения в popup навигации
					String fileName = location.file.getName();
					PsiFile psiFile = element.getContainingFile();
					String text = psiFile != null ? psiFile.getText() : "";
					int line = com.intellij.openapi.util.text.StringUtil.offsetToLineNumber(text, location.offset) + 1;
					cachedElement = new Parser3CssTargetElement(element, fileName, line, className);
				}
			}
			return cachedElement;
		}

		@Override
		public boolean isValidResult() {
			return location.file.isValid();
		}
	}

	/**
	 * Обёртка PsiElement с правильным отображением в popup.
	 */
	private static class Parser3CssTargetElement extends com.intellij.psi.impl.FakePsiElement {
		private final PsiElement original;
		private final String fileName;
		private final int line;
		private final String className;

		Parser3CssTargetElement(PsiElement original, String fileName, int line, String className) {
			this.original = original;
			this.fileName = fileName;
			this.line = line;
			this.className = className;
		}

		@Override
		public PsiElement getParent() {
			return original.getParent();
		}

		@Override
		public PsiFile getContainingFile() {
			return original.getContainingFile();
		}

		@Override
		public com.intellij.openapi.util.TextRange getTextRange() {
			return original.getTextRange();
		}

		@Override
		public int getTextOffset() {
			return original.getTextOffset();
		}

		@Override
		public String getText() {
			return "." + className;
		}

		@Override
		public boolean isValid() {
			return original.isValid();
		}

		@Override
		public com.intellij.navigation.ItemPresentation getPresentation() {
			return new com.intellij.navigation.ItemPresentation() {
				@Override
				public String getPresentableText() {
					return "." + className;
				}

				@Override
				public String getLocationString() {
					return "(" + fileName + ":" + line + ")";
				}

				@Override
				public javax.swing.Icon getIcon(boolean unused) {
					return null;
				}
			};
		}

		@Override
		public void navigate(boolean requestFocus) {
			if (original instanceof com.intellij.pom.Navigatable) {
				((com.intellij.pom.Navigatable) original).navigate(requestFocus);
			}
		}

		@Override
		public boolean canNavigate() {
			return original instanceof com.intellij.pom.Navigatable &&
					((com.intellij.pom.Navigatable) original).canNavigate();
		}

		@Override
		public boolean canNavigateToSource() {
			return canNavigate();
		}
	}
}