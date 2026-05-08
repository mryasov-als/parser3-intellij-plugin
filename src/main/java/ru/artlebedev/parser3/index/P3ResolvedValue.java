package ru.artlebedev.parser3.index;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Богатое описание результата выражения/метода.
 * Используется для переменных, $result и результатов вызова методов.
 */
public final class P3ResolvedValue {

	public final @NotNull String className;
	public final @Nullable List<String> columns;
	public final @Nullable String sourceVarKey;
	public final @Nullable Map<String, HashEntryInfo> hashKeys;
	public final @Nullable List<String> hashSourceVars;
	public final @Nullable String methodName;
	public final @Nullable String targetClassName;
	public final @Nullable String receiverVarKey;

	public P3ResolvedValue(
			@NotNull String className,
			@Nullable List<String> columns,
			@Nullable String sourceVarKey,
			@Nullable Map<String, HashEntryInfo> hashKeys,
			@Nullable List<String> hashSourceVars,
			@Nullable String methodName,
			@Nullable String targetClassName,
			@Nullable String receiverVarKey
	) {
		this.className = className;
		this.columns = columns;
		this.sourceVarKey = sourceVarKey;
		this.hashKeys = (hashKeys != null && hashKeys.isEmpty()) ? null : hashKeys;
		this.hashSourceVars = (hashSourceVars != null && hashSourceVars.isEmpty()) ? null : hashSourceVars;
		this.methodName = methodName;
		this.targetClassName = targetClassName;
		this.receiverVarKey = receiverVarKey;
	}

	public static @NotNull P3ResolvedValue of(
			@NotNull String className,
			@Nullable List<String> columns,
			@Nullable String sourceVarKey,
			@Nullable Map<String, HashEntryInfo> hashKeys,
			@Nullable List<String> hashSourceVars,
			@Nullable String methodName,
			@Nullable String targetClassName
	) {
		return new P3ResolvedValue(className, columns, sourceVarKey, hashKeys, hashSourceVars, methodName, targetClassName, null);
	}

	public static @NotNull P3ResolvedValue of(
			@NotNull String className,
			@Nullable List<String> columns,
			@Nullable String sourceVarKey,
			@Nullable Map<String, HashEntryInfo> hashKeys,
			@Nullable List<String> hashSourceVars,
			@Nullable String methodName,
			@Nullable String targetClassName,
			@Nullable String receiverVarKey
	) {
		return new P3ResolvedValue(className, columns, sourceVarKey, hashKeys, hashSourceVars, methodName, targetClassName, receiverVarKey);
	}

	public static @NotNull P3ResolvedValue unknown() {
		return new P3ResolvedValue(P3VariableFileIndex.UNKNOWN_TYPE, null, null, null, null, null, null, null);
	}

	public boolean isUnknown() {
		return P3VariableFileIndex.UNKNOWN_TYPE.equals(className)
				&& columns == null
				&& sourceVarKey == null
				&& hashKeys == null
				&& hashSourceVars == null
				&& methodName == null
				&& targetClassName == null
				&& receiverVarKey == null;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		P3ResolvedValue that = (P3ResolvedValue) o;
		return Objects.equals(className, that.className)
				&& Objects.equals(columns, that.columns)
				&& Objects.equals(sourceVarKey, that.sourceVarKey)
				&& Objects.equals(hashKeys, that.hashKeys)
				&& Objects.equals(hashSourceVars, that.hashSourceVars)
				&& Objects.equals(methodName, that.methodName)
				&& Objects.equals(targetClassName, that.targetClassName)
				&& Objects.equals(receiverVarKey, that.receiverVarKey);
	}

	@Override
	public int hashCode() {
		return Objects.hash(className, columns, sourceVarKey, hashKeys, hashSourceVars, methodName, targetClassName, receiverVarKey);
	}
}
