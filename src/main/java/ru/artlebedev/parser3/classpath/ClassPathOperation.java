package ru.artlebedev.parser3.classpath;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Операция над CLASS_PATH: присваивание (заменяет) или добавление.
 */
public final class ClassPathOperation {

	public enum Type {
		/** $CLASS_PATH[...] — заменяет весь CLASS_PATH */
		ASSIGN,
		/** ^CLASS_PATH.append{...} — добавляет к существующему */
		APPEND
	}

	private final Type type;
	private final List<String> paths;
	private final int offset;  // Позиция в файле для сортировки

	public ClassPathOperation(@NotNull Type type, @NotNull List<String> paths, int offset) {
		this.type = type;
		this.paths = paths;
		this.offset = offset;
	}

	public @NotNull Type getType() {
		return type;
	}

	public @NotNull List<String> getPaths() {
		return paths;
	}

	public int getOffset() {
		return offset;
	}
}