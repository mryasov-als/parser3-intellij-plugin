package ru.artlebedev.parser3.completion;

import com.intellij.codeInsight.lookup.CharFilter;
import com.intellij.codeInsight.lookup.Lookup;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.impl.LookupImpl;
import com.intellij.openapi.editor.Editor;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.artlebedev.parser3.utils.Parser3PsiUtils;

/**
 * CharFilter для Parser3 — говорит IntelliJ как обрабатывать символы при открытом popup.
 * Главное — не закрывать popup при вводе ':'
 */
public class Parser3CharFilter extends CharFilter {

	@Override
	public @Nullable Result acceptChar(char c, int prefixLength, Lookup lookup) {
		PsiFile file = lookup.getPsiFile();
		if (file == null || !Parser3PsiUtils.isParser3File(file)) {
			return null; // Не наш файл — пусть другие CharFilter решают
		}

		// Для ':' — добавляем к prefix, не закрываем popup
		if (c == ':') {
			return Result.ADD_TO_PREFIX;
		}

		if (c == '.') {
			if (isDollarVariableBeforeTypedDot(lookup)) {
				String typedName = extractTypedVariableNameBeforeDot(lookup);
				String exactTypedLookup = typedName.isEmpty() ? "" : typedName + ".";
				LookupElement currentItem = lookup.getCurrentItem();
				if (currentItem != null
						&& currentItem.getLookupString().endsWith(".")
						&& !currentItem.getLookupString().equals(exactTypedLookup)) {
					return Result.SELECT_ITEM_AND_FINISH_LOOKUP;
				}
				LookupElement matchingDottedItem = findMatchingDottedItemBeforeDot(lookup);
				if (matchingDottedItem != null
						&& !matchingDottedItem.getLookupString().equals(exactTypedLookup)
						&& lookup instanceof LookupImpl) {
					((LookupImpl) lookup).setCurrentItem(matchingDottedItem);
					return Result.SELECT_ITEM_AND_FINISH_LOOKUP;
				}
				return Result.HIDE_LOOKUP;
			}
			LookupElement currentItem = lookup.getCurrentItem();
			if (currentItem != null && currentItem.getLookupString().endsWith(".")) {
				return Result.SELECT_ITEM_AND_FINISH_LOOKUP;
			}
		}

		return null; // Для остальных символов — стандартное поведение
	}

	private static boolean isDollarVariableBeforeTypedDot(@NotNull Lookup lookup) {
		Editor editor = lookup.getEditor();
		int caretOffset = editor.getCaretModel().getOffset();
		CharSequence text = editor.getDocument().getCharsSequence();
		return P3CompletionUtils.shouldAutoPopupVariableDot(text, caretOffset, '.');
	}

	private static @Nullable LookupElement findMatchingDottedItemBeforeDot(@NotNull Lookup lookup) {
		String typedName = extractTypedVariableNameBeforeDot(lookup);
		if (typedName.isEmpty()) {
			return null;
		}

		String exactLookup = typedName + ".";
		for (LookupElement item : lookup.getItems()) {
			String lookupString = item.getLookupString();
			if (lookupString.equals(exactLookup)) {
				return item;
			}
		}
		return null;
	}

	private static @NotNull String extractTypedVariableNameBeforeDot(@NotNull Lookup lookup) {
		Editor editor = lookup.getEditor();
		int caretOffset = editor.getCaretModel().getOffset();
		CharSequence text = editor.getDocument().getCharsSequence();
		if (!P3CompletionUtils.shouldAutoPopupVariableDot(text, caretOffset, '.')) {
			return "";
		}

		int end = Math.max(0, Math.min(caretOffset, text.length()));
		int start = end;
		while (start > 0 && P3CompletionUtils.isVarIdentChar(text.charAt(start - 1))) {
			start--;
		}
		if (start == end) {
			return "";
		}
		if (start == 0 || text.charAt(start - 1) != '$') {
			return "";
		}
		return text.subSequence(start, end).toString();
	}
}
