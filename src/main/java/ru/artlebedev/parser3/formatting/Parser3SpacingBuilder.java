package ru.artlebedev.parser3.formatting;

import com.intellij.formatting.SpacingBuilder;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import org.jetbrains.annotations.NotNull;
import ru.artlebedev.parser3.Parser3Language;
import ru.artlebedev.parser3.Parser3TokenTypes;

public final class Parser3SpacingBuilder {

	private Parser3SpacingBuilder() {
	}

	public static @NotNull SpacingBuilder create(@NotNull CodeStyleSettings settings) {
		return new SpacingBuilder(settings, Parser3Language.INSTANCE)
				// Скобки и блоки без пробелов перед ними
				.before(Parser3TokenTypes.LPAREN).spaces(0)
				.before(Parser3TokenTypes.LBRACE).spaces(0)
				.before(Parser3TokenTypes.LBRACKET).spaces(0)
				// После открывающих скобок пробел не навязываем
				.after(Parser3TokenTypes.LPAREN).spaces(0)
				.after(Parser3TokenTypes.LBRACE).spaces(0)
				.after(Parser3TokenTypes.LBRACKET).spaces(0)
				// По умолчанию остальные пробелы оставляем как есть (builder вернёт null)
				;
	}
}