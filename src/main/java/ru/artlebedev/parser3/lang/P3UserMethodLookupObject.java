package ru.artlebedev.parser3.lang;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.artlebedev.parser3.model.P3MethodDeclaration;

/**
 * Объект для хранения информации о пользовательском методе в LookupElement.
 * Используется для показа документации при Ctrl+Q в списке автокомплита.
 */
public class P3UserMethodLookupObject {

	public final @NotNull String methodName;
	public final @Nullable P3MethodDeclaration declaration;

	public P3UserMethodLookupObject(@NotNull String methodName, @Nullable P3MethodDeclaration declaration) {
		this.methodName = methodName;
		this.declaration = declaration;
	}

	@Override
	public String toString() {
		return methodName;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		P3UserMethodLookupObject that = (P3UserMethodLookupObject) o;
		return methodName.equals(that.methodName);
	}

	@Override
	public int hashCode() {
		return methodName.hashCode();
	}
}