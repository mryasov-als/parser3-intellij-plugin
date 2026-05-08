package ru.artlebedev.parser3.completion;

import com.intellij.codeInsight.lookup.CharFilter;
import com.intellij.codeInsight.lookup.Lookup;
import com.intellij.psi.PsiFile;
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

		return null; // Для остальных символов — стандартное поведение
	}
}