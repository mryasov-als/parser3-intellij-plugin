package ru.artlebedev.parser3.polysymbols;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.polySymbols.query.PolySymbolQueryScopeContributor;
import com.intellij.polySymbols.query.PolySymbolQueryScopeProviderRegistrar;
import com.intellij.polySymbols.query.PolySymbolScope;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.artlebedev.parser3.injector.Parser3DebugLog;
import ru.artlebedev.parser3.psi.Parser3PsiFile;

import java.util.Collections;
import java.util.List;

/**
 * Contributor который предоставляет CSS классы из Parser3 файлов.
 *
 * Регистрируемся для:
 * - XmlAttributeValue — чтобы class="className" в Parser3 HTML видели определения
 * - Любой PSI location в файле — fallback
 *
 * Это позволяет IntelliJ связывать class="item" с .item в том же файле.
 */
public class Parser3CssClassScopeContributor implements PolySymbolQueryScopeContributor {

	static {
		Parser3DebugLog.log("PolySymbols", "Parser3CssClassScopeContributor ЗАГРУЖЕН");
	}

	@Override
	public void registerProviders(@NotNull PolySymbolQueryScopeProviderRegistrar registrar) {
		Parser3DebugLog.log("PolySymbols", "registerProviders() вызван");

		// Регистрируемся для XmlAttributeValue — значение class="..." в HTML
		// Это нужно чтобы из class="item" найти определение .item
		registrar
				.forPsiLocation(XmlAttributeValue.class)
				.contributeScopeProvider(this::getScopeForLocation);

		// Регистрируемся для любого места в файле — fallback
		registrar
				.forAnyPsiLocationInFile()
				.contributeScopeProvider(this::getScopeForLocation);

		Parser3DebugLog.log("PolySymbols", "registerProviders() завершён");
	}

	/**
	 * Возвращает scope с CSS классами из Parser3 файла для указанного элемента.
	 *
	 * ВАЖНО: возвращаем scope ТОЛЬКО если элемент находится в реальном Parser3 файле,
	 * а не в injected HTML/CSS. Иначе мы перезапишем стандартный CSS scope.
	 */
	@NotNull
	private List<PolySymbolScope> getScopeForLocation(@NotNull PsiElement location) {
		PsiFile file = location.getContainingFile();
		if (file == null) {
			return Collections.emptyList();
		}

		String locClass = location.getClass().getSimpleName();
		String fileName = file.getName();
		String fileClass = file.getClass().getSimpleName();

		// Проверяем: это injected файл (HtmlFileImpl, CssFileImpl) или реальный Parser3?
		// Для injected файлов НЕ возвращаем scope — пусть работает стандартный механизм
		if (!(file instanceof Parser3PsiFile)) {
			// Это injected файл — проверяем, есть ли Parser3 как host
			PsiFile hostFile = findParser3File(location);
			if (hostFile == null) {
				// Нет Parser3 host — это обычный HTML/CSS файл, не вмешиваемся
				return Collections.emptyList();
			}

			// Есть Parser3 host, но мы в injected контексте
			// Возвращаем scope только если это запрос для css/classes
			// НЕТ — лучше вообще не возвращать для injected,
			// пусть стандартный механизм HTML плагина работает
			Parser3DebugLog.log("PolySymbols", "getScopeForLocation: SKIP injected loc=" + locClass +
					" file=" + fileName + " fileClass=" + fileClass);
			return Collections.emptyList();
		}

		// Это реальный Parser3 файл — возвращаем наш scope
		Parser3DebugLog.log("PolySymbols", "getScopeForLocation: Parser3 loc=" + locClass +
				" file=" + fileName);
		return Collections.singletonList(new Parser3CssClassScope(file));
	}

	/**
	 * Находит Parser3 файл для указанного элемента.
	 * Работает как для элементов напрямую в Parser3 файле,
	 * так и для элементов внутри injected HTML/CSS.
	 */
	@Nullable
	private PsiFile findParser3File(@NotNull PsiElement element) {
		PsiFile file = element.getContainingFile();
		if (file == null) {
			return null;
		}

		// Прямой Parser3 файл
		if (file instanceof Parser3PsiFile) {
			return file;
		}

		// Original файл (для injected)
		PsiFile originalFile = file.getOriginalFile();
		if (originalFile instanceof Parser3PsiFile) {
			return originalFile;
		}

		// Context файл (для injected через MultiHostInjector)
		PsiElement context = file.getContext();
		if (context != null) {
			PsiFile contextFile = context.getContainingFile();
			if (contextFile instanceof Parser3PsiFile) {
				return contextFile;
			}
		}

		return null;
	}
}