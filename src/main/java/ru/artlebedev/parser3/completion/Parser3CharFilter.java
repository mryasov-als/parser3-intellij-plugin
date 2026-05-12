package ru.artlebedev.parser3.completion;

import com.intellij.codeInsight.lookup.CharFilter;
import com.intellij.codeInsight.lookup.Lookup;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.Nullable;
import ru.artlebedev.parser3.utils.Parser3PsiUtils;

/**
 * CharFilter для Parser3 — говорит IntelliJ как обрабатывать символы при открытом popup.
 * Вставка выбранного пункта разрешена только явным подтверждением, а не обычным набором кода.
 */
public class Parser3CharFilter extends CharFilter {

	@Override
	public @Nullable Result acceptChar(char c, int prefixLength, Lookup lookup) {
		PsiFile file = lookup.getPsiFile();
		if (file == null || !Parser3PsiUtils.isParser3File(file)) {
			return null; // Не наш файл — пусть другие CharFilter решают
		}

		// Идентификаторы и "::" должны продолжать фильтровать popup при обычном наборе.
		if (c == ':' || P3CompletionUtils.isVarIdentChar(c)) {
			return Result.ADD_TO_PREFIX;
		}

		// Любой разделитель, скобка, точка, пробел и т.п. — это ручной ввод кода, а не подтверждение lookup.
		return Result.HIDE_LOOKUP;
	}
}
