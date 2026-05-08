package ru.artlebedev.parser3.index;

import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.*;

/**
 * Информация об одном ключе хеша.
 * Хранит тип значения, колонки (для table) и вложенные ключи (для hash of hash).
 *
 * Используется в VariableTypeInfo.hashKeys для поддержки автокомплита по ключам хеша:
 * $data[$.key[$.subkey[^table::create{name	value}]]]
 * $data.key.subkey. → колонки таблицы "name", "value"
 */
public final class HashEntryInfo {

	/** Тип значения по ключу (hash, table, UNKNOWN_TYPE, и т.д.) */
	public final @NotNull String className;

	/** Колонки таблицы — если className = "table" */
	public final @Nullable List<String> columns;

	/** Вложенные ключи — если className = "hash" */
	public final @Nullable Map<String, HashEntryInfo> nestedKeys;

	/** Позиция ключа в тексте файла (-1 если неизвестна) */
	public final int offset;

	/** Файл, которому принадлежит offset. Заполняется во время чтения индекса. */
	public final @Nullable VirtualFile file;

	/** Имя метода для ленивого резолва значения ключа, если className = METHOD_CALL_MARKER. */
	public final @Nullable String methodName;

	/** Класс статического метода для ленивого резолва значения ключа. */
	public final @Nullable String targetClassName;

	/** Получатель object-method вызова для ленивого резолва значения ключа. */
	public final @Nullable String receiverVarKey;

	/** Ключ переменной-источника для ленивого резолва значения ключа. */
	public final @Nullable String sourceVarKey;

	public HashEntryInfo(@NotNull String className) {
		this(className, null, null, -1);
	}

	public HashEntryInfo(@NotNull String className, @Nullable List<String> columns) {
		this(className, columns, null, -1);
	}

	public HashEntryInfo(@NotNull String className, @Nullable List<String> columns,
						 @Nullable Map<String, HashEntryInfo> nestedKeys) {
		this(className, columns, nestedKeys, -1);
	}

	public HashEntryInfo(@NotNull String className, @Nullable List<String> columns,
						 @Nullable Map<String, HashEntryInfo> nestedKeys, int offset) {
		this(className, columns, nestedKeys, offset, null);
	}

	HashEntryInfo(@NotNull String className, @Nullable List<String> columns,
				  @Nullable Map<String, HashEntryInfo> nestedKeys, int offset,
				  @Nullable VirtualFile file) {
		this(className, columns, nestedKeys, offset, file, null, null, null, null);
	}

	HashEntryInfo(@NotNull String className, @Nullable List<String> columns,
				  @Nullable Map<String, HashEntryInfo> nestedKeys, int offset,
				  @Nullable String methodName, @Nullable String targetClassName,
				  @Nullable String receiverVarKey) {
		this(className, columns, nestedKeys, offset, null, null, methodName, targetClassName, receiverVarKey);
	}

	HashEntryInfo(@NotNull String className, @Nullable List<String> columns,
				  @Nullable Map<String, HashEntryInfo> nestedKeys, int offset,
				  @Nullable String sourceVarKey, @Nullable String methodName,
				  @Nullable String targetClassName, @Nullable String receiverVarKey) {
		this(className, columns, nestedKeys, offset, null, sourceVarKey, methodName, targetClassName, receiverVarKey);
	}

	HashEntryInfo(@NotNull String className, @Nullable List<String> columns,
				  @Nullable Map<String, HashEntryInfo> nestedKeys, int offset,
				  @Nullable VirtualFile file, @Nullable String methodName,
				  @Nullable String targetClassName, @Nullable String receiverVarKey) {
		this(className, columns, nestedKeys, offset, file, null, methodName, targetClassName, receiverVarKey);
	}

	HashEntryInfo(@NotNull String className, @Nullable List<String> columns,
				  @Nullable Map<String, HashEntryInfo> nestedKeys, int offset,
				  @Nullable VirtualFile file, @Nullable String sourceVarKey,
				  @Nullable String methodName, @Nullable String targetClassName,
				  @Nullable String receiverVarKey) {
		this.className = className;
		this.columns = (columns != null && columns.isEmpty()) ? null : columns;
		this.nestedKeys = (nestedKeys != null && nestedKeys.isEmpty()) ? null : nestedKeys;
		this.offset = offset;
		this.file = file;
		this.sourceVarKey = sourceVarKey;
		this.methodName = methodName;
		this.targetClassName = targetClassName;
		this.receiverVarKey = receiverVarKey;
	}

	/**
	 * Проверяет, есть ли вложенные ключи
	 */
	public boolean hasNestedKeys() {
		return nestedKeys != null && !nestedKeys.isEmpty();
	}

	// === Сериализация ===

	/**
	 * Записывает HashEntryInfo в поток (рекурсивно для вложенных)
	 */
	public static void write(@NotNull DataOutput out, @NotNull HashEntryInfo entry) throws IOException {
		out.writeUTF(entry.className);
		out.writeInt(entry.offset);
		out.writeUTF(entry.methodName != null ? entry.methodName : "");
		out.writeUTF(entry.targetClassName != null ? entry.targetClassName : "");
		out.writeUTF(entry.receiverVarKey != null ? entry.receiverVarKey : "");
		out.writeUTF(entry.sourceVarKey != null ? entry.sourceVarKey : "");

		// Колонки таблицы
		if (entry.columns != null && !entry.columns.isEmpty()) {
			out.writeInt(entry.columns.size());
			for (String col : entry.columns) {
				out.writeUTF(col);
			}
		} else {
			out.writeInt(0);
		}

		// Вложенные ключи (рекурсивно)
		if (entry.nestedKeys != null && !entry.nestedKeys.isEmpty()) {
			out.writeInt(entry.nestedKeys.size());
			for (Map.Entry<String, HashEntryInfo> e : entry.nestedKeys.entrySet()) {
				out.writeUTF(e.getKey());
				write(out, e.getValue());
			}
		} else {
			out.writeInt(0);
		}
	}

	/**
	 * Читает HashEntryInfo из потока (рекурсивно для вложенных)
	 */
	public static @NotNull HashEntryInfo read(@NotNull DataInput in) throws IOException {
		String className = in.readUTF();
		int offset = in.readInt();
		String methodName = in.readUTF();
		String targetClassName = in.readUTF();
		String receiverVarKey = in.readUTF();
		String sourceVarKey = in.readUTF();

		// Колонки
		int colCount = in.readInt();
		List<String> columns = null;
		if (colCount > 0) {
			columns = new ArrayList<>(colCount);
			for (int i = 0; i < colCount; i++) {
				columns.add(in.readUTF());
			}
		}

		// Вложенные ключи
		int nestedCount = in.readInt();
		Map<String, HashEntryInfo> nestedKeys = null;
		if (nestedCount > 0) {
			nestedKeys = new LinkedHashMap<>(nestedCount);
			for (int i = 0; i < nestedCount; i++) {
				String key = in.readUTF();
				HashEntryInfo nested = read(in);
				nestedKeys.put(key, nested);
			}
		}

		return new HashEntryInfo(
				className,
				columns,
				nestedKeys,
				offset,
				null,
				sourceVarKey.isEmpty() ? null : sourceVarKey,
				methodName.isEmpty() ? null : methodName,
				targetClassName.isEmpty() ? null : targetClassName,
				receiverVarKey.isEmpty() ? null : receiverVarKey
		);
	}

	/**
	 * Записывает Map<String, HashEntryInfo> в поток
	 */
	public static void writeMap(@NotNull DataOutput out, @Nullable Map<String, HashEntryInfo> map) throws IOException {
		if (map != null && !map.isEmpty()) {
			out.writeInt(map.size());
			for (Map.Entry<String, HashEntryInfo> e : map.entrySet()) {
				out.writeUTF(e.getKey());
				write(out, e.getValue());
			}
		} else {
			out.writeInt(0);
		}
	}

	/**
	 * Читает Map<String, HashEntryInfo> из потока
	 */
	public static @Nullable Map<String, HashEntryInfo> readMap(@NotNull DataInput in) throws IOException {
		int count = in.readInt();
		if (count == 0) return null;
		Map<String, HashEntryInfo> map = new LinkedHashMap<>(count);
		for (int i = 0; i < count; i++) {
			String key = in.readUTF();
			HashEntryInfo entry = read(in);
			map.put(key, entry);
		}
		return map;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		HashEntryInfo that = (HashEntryInfo) o;
		return offset == that.offset &&
				className.equals(that.className) &&
				Objects.equals(columns, that.columns) &&
				Objects.equals(nestedKeys, that.nestedKeys) &&
				Objects.equals(sourceVarKey, that.sourceVarKey) &&
				Objects.equals(methodName, that.methodName) &&
				Objects.equals(targetClassName, that.targetClassName) &&
				Objects.equals(receiverVarKey, that.receiverVarKey);
	}

	@Override
	public int hashCode() {
		return Objects.hash(className, columns, nestedKeys, offset, sourceVarKey, methodName, targetClassName, receiverVarKey);
	}

	/**
	 * Возвращает копию с указанным offset (для элементов массива).
	 */
	public HashEntryInfo withOffset(int newOffset) {
		return new HashEntryInfo(className, columns, nestedKeys, newOffset, file, sourceVarKey, methodName, targetClassName, receiverVarKey);
	}

	/**
	 * Возвращает копию с обогащёнными nestedKeys (для алиасов).
	 */
	public HashEntryInfo withNestedKeys(@NotNull java.util.Map<String, HashEntryInfo> newNestedKeys) {
		return new HashEntryInfo(className, columns, newNestedKeys, offset, file, sourceVarKey, methodName, targetClassName, receiverVarKey);
	}

	/**
	 * Возвращает копию, где offset привязан к конкретному файлу.
	 */
	public HashEntryInfo withFile(@NotNull VirtualFile newFile) {
		Map<String, HashEntryInfo> nestedWithFile = null;
		if (nestedKeys != null && !nestedKeys.isEmpty()) {
			nestedWithFile = new LinkedHashMap<>(nestedKeys.size());
			for (Map.Entry<String, HashEntryInfo> entry : nestedKeys.entrySet()) {
				nestedWithFile.put(entry.getKey(), entry.getValue().withFile(newFile));
			}
		}
		return new HashEntryInfo(className, columns, nestedWithFile, offset, newFile, sourceVarKey, methodName, targetClassName, receiverVarKey);
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder("HashEntryInfo{type=").append(className);
		if (offset >= 0) sb.append(", offset=").append(offset);
		if (file != null) sb.append(", file=").append(file.getName());
		if (columns != null) sb.append(", cols=").append(columns);
		if (nestedKeys != null) sb.append(", keys=").append(nestedKeys.keySet());
		if (sourceVarKey != null) sb.append(", source=").append(sourceVarKey);
		if (methodName != null) sb.append(", method=").append(methodName);
		if (targetClassName != null) sb.append(", target=").append(targetClassName);
		if (receiverVarKey != null) sb.append(", receiver=").append(receiverVarKey);
		return sb.append('}').toString();
	}
}
