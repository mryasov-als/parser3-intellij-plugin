package ru.artlebedev.parser3.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Параметр метода Parser3.
 *
 * Примеры:
 * - $email — параметр без типа
 * - $name(string) — параметр с типом
 * - $result — специальный параметр (возвращаемое значение)
 */
public final class P3MethodParameter {

	private final @NotNull String name;
	private final @Nullable String type;
	private final @Nullable String description;
	private final boolean isResult; // true если это $result

	public P3MethodParameter(@NotNull String name, @Nullable String type, @Nullable String description, boolean isResult) {
		this.name = name;
		this.type = type;
		this.description = description;
		this.isResult = isResult;
	}

	public @NotNull String getName() {
		return name;
	}

	public @Nullable String getType() {
		return type;
	}

	public @Nullable String getDescription() {
		return description;
	}

	public boolean isResult() {
		return isResult;
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		sb.append("$").append(name);
		if (type != null) {
			sb.append("(").append(type).append(")");
		}
		if (description != null) {
			sb.append(" — ").append(description);
		}
		return sb.toString();
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		P3MethodParameter that = (P3MethodParameter) o;
		return name.equals(that.name);
	}

	@Override
	public int hashCode() {
		return name.hashCode();
	}
}