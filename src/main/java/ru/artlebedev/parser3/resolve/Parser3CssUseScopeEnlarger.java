package ru.artlebedev.parser3.resolve;

import com.intellij.psi.PsiElement;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.SearchScope;
import com.intellij.psi.search.UseScopeEnlarger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Расширяет scope поиска CSS классов, чтобы включить Parser3 файлы.
 *
 * Это нужно чтобы при поиске usages CSS класса (например .item)
 * поиск включал Parser3 файлы, где могут быть:
 * - определения этого класса в <style> блоках
 * - использования в class="item" атрибутах
 */
public class Parser3CssUseScopeEnlarger extends UseScopeEnlarger {

	@Nullable
	@Override
	public SearchScope getAdditionalUseScope(@NotNull PsiElement element) {
		String elemClass = element.getClass().getSimpleName();

		// Для CSS элементов добавляем ВСЕ файлы проекта в scope
		// (включая Parser3 файлы которые содержат <style> блоки)
		if (elemClass.contains("Css")) {
			return GlobalSearchScope.allScope(element.getProject());
		}

		return null;
	}
}