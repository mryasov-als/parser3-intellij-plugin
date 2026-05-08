package ru.artlebedev.parser3.lang;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Объект для хранения информации о документации в LookupElement.
 * Используется для показа документации при Ctrl+Q в списке автокомплита.
 *
 * Пример использования:
 * <pre>
 * LookupElementBuilder.create(new Parser3DocLookupObject("hash::create", url), "hash::create")
 *     .withIcon(...)
 *     .withPresentableText(...)
 * </pre>
 */
public class Parser3DocLookupObject {

	public final @NotNull String lookupString;
	public final @Nullable String url;
	public final @Nullable String description;

	public Parser3DocLookupObject(@NotNull String lookupString, @Nullable String url) {
		this(lookupString, url, null);
	}

	public Parser3DocLookupObject(@NotNull String lookupString, @Nullable String url, @Nullable String description) {
		this.lookupString = lookupString;
		this.url = url;
		this.description = description;
	}

	@Override
	public String toString() {
		return lookupString;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		Parser3DocLookupObject that = (Parser3DocLookupObject) o;
		return lookupString.equals(that.lookupString);
	}

	@Override
	public int hashCode() {
		return lookupString.hashCode();
	}
}