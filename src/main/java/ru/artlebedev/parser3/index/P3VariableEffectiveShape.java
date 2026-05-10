package ru.artlebedev.parser3.index;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.artlebedev.parser3.lang.Parser3BuiltinMethods;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Собирает эффективную форму переменной из последовательности присваиваний.
 * Полные присваивания сбрасывают структуру, additive-записи дополняют её.
 */
final class P3VariableEffectiveShape {
	private final @NotNull String sourceVarKey;
	private final boolean ignoreForeachScopedSource;

	private @Nullable P3VariableFileIndex.VariableTypeInfo lastVisible;
	private @Nullable P3VariableFileIndex.VariableTypeInfo lastNonAdditiveVisible;
	private @Nullable Map<String, HashEntryInfo> allHashKeys;
	private @Nullable List<String> allHashSourceVars;
	private @Nullable String bestSourceVarKey;
	private @Nullable String bestClassName;
	private boolean bestClassNameFromAdditive;

	P3VariableEffectiveShape(@NotNull String sourceVarKey, boolean ignoreForeachScopedSource) {
		this.sourceVarKey = sourceVarKey;
		this.ignoreForeachScopedSource = ignoreForeachScopedSource;
	}

	void add(@NotNull P3VariableFileIndex.VariableTypeInfo info) {
		if (lastVisible == null || info.offset > lastVisible.offset) {
			lastVisible = info;
		}
		if (!info.isAdditive && (lastNonAdditiveVisible == null || info.offset > lastNonAdditiveVisible.offset)) {
			lastNonAdditiveVisible = info;
		}
		if (!info.isAdditive) {
			boolean isSelfRef = sourceVarKey.equals(info.sourceVarKey)
					|| (info.hashSourceVars != null && info.hashSourceVars.contains(sourceVarKey));
			if (!isSelfRef) {
				allHashKeys = null;
				allHashSourceVars = null;
				bestSourceVarKey = null;
				bestClassName = null;
			}
		}
		if (info.sourceVarKey != null && !info.sourceVarKey.isEmpty()) {
			if (!info.isAdditive || bestSourceVarKey == null) {
				bestSourceVarKey = shouldSnapshotSourceVarKey(info)
						? encodeHashSourceAtOffset(info.sourceVarKey, info.offset)
						: info.sourceVarKey;
			}
		}
		if (!P3VariableFileIndex.UNKNOWN_TYPE.equals(info.className) && !info.className.isEmpty()
				&& (!info.isAdditive || bestClassName == null)) {
			bestClassName = info.className;
			bestClassNameFromAdditive = info.isAdditive;
		}
		allHashKeys = mergeHashEntryMaps(allHashKeys, info.hashKeys);
		if (info.hashSourceVars != null && !info.hashSourceVars.isEmpty()) {
			if (allHashSourceVars == null) allHashSourceVars = new ArrayList<>();
			for (String sv : info.hashSourceVars) {
				String sourceAtOffset = encodeHashSourceAtOffset(sv, info.offset);
				if (!allHashSourceVars.contains(sourceAtOffset)) allHashSourceVars.add(sourceAtOffset);
			}
		}
	}

	boolean hasVisible() {
		return lastVisible != null;
	}

	int lastVisibleOffset() {
		return lastVisible != null ? lastVisible.offset : -1;
	}

	@Nullable P3VariableFileIndex.VariableTypeInfo structuralVisible() {
		if (lastVisible == null) return null;
		return lastVisible.isAdditive && lastNonAdditiveVisible != null ? lastNonAdditiveVisible : lastVisible;
	}

	@NotNull String className() {
		P3VariableFileIndex.VariableTypeInfo structuralVisible = structuralVisible();
		if (structuralVisible == null) return P3VariableFileIndex.UNKNOWN_TYPE;

		String className = structuralVisible.className;
		if (P3VariableFileIndex.UNKNOWN_TYPE.equals(className)
				&& structuralVisible.sourceVarKey != null
				&& bestClassNameFromAdditive
				&& "hash".equals(bestClassName)) {
			return className;
		}
		if (P3VariableFileIndex.UNKNOWN_TYPE.equals(className)
				&& bestClassName != null
				&& canInheritBestStructure(structuralVisible)) {
			className = bestClassName;
		}
		return className;
	}

	@Nullable Map<String, HashEntryInfo> hashKeys() {
		return allHashKeys != null ? allHashKeys : (lastVisible != null ? lastVisible.hashKeys : null);
	}

	@Nullable List<String> columns() {
		P3VariableFileIndex.VariableTypeInfo structuralVisible = structuralVisible();
		if (structuralVisible == null) return null;
		return applyColumnHashMutations(structuralVisible.columns, hashKeys());
	}

	@Nullable List<String> hashSourceVars() {
		return allHashSourceVars != null ? allHashSourceVars : (lastVisible != null ? lastVisible.hashSourceVars : null);
	}

	@Nullable String effectiveSourceVarKey() {
		P3VariableFileIndex.VariableTypeInfo structuralVisible = structuralVisible();
		if (structuralVisible == null) return null;
		return canInheritBestStructure(structuralVisible) && bestSourceVarKey != null
				? bestSourceVarKey : structuralVisible.sourceVarKey;
	}

	@Nullable P3ResolvedValue toResolvedValue() {
		P3VariableFileIndex.VariableTypeInfo structuralVisible = structuralVisible();
		if (structuralVisible == null) return null;
		return P3ResolvedValue.of(
				className(),
				columns(),
				effectiveSourceVarKey(),
				hashKeys(),
				hashSourceVars(),
				structuralVisible.methodName,
				structuralVisible.targetClassName,
				structuralVisible.receiverVarKey
		);
	}

	static boolean isSyntheticReadChainAdditive(@NotNull P3VariableFileIndex.VariableTypeInfo info) {
		return info.isAdditive
				&& info.columns == null
				&& info.sourceVarKey == null
				&& (info.hashSourceVars == null || info.hashSourceVars.isEmpty())
				&& info.methodName == null
				&& info.targetClassName == null
				&& info.receiverVarKey == null
				&& containsOnlySyntheticReadChainKeys(info.hashKeys);
	}

	private static boolean containsOnlySyntheticReadChainKeys(@Nullable Map<String, HashEntryInfo> hashKeys) {
		if (hashKeys == null || hashKeys.isEmpty()) return false;
		for (HashEntryInfo entry : hashKeys.values()) {
			if (!isSyntheticReadChainEntry(entry)) return false;
		}
		return true;
	}

	private static boolean isSyntheticReadChainEntry(@NotNull HashEntryInfo entry) {
		if (entry.offset >= 0
				|| entry.columns != null
				|| entry.sourceVarKey != null
				|| entry.methodName != null
				|| entry.targetClassName != null
				|| entry.receiverVarKey != null) {
			return false;
		}
		if (entry.nestedKeys == null || entry.nestedKeys.isEmpty()) return true;
		return containsOnlySyntheticReadChainKeys(entry.nestedKeys);
	}

	private boolean canInheritBestStructure(@NotNull P3VariableFileIndex.VariableTypeInfo structuralVisible) {
		boolean isForeachScopedSource =
				ignoreForeachScopedSource
						&& structuralVisible.sourceVarKey != null
						&& (structuralVisible.sourceVarKey.startsWith("foreach:")
						|| structuralVisible.sourceVarKey.startsWith("foreach_field:"));
		return (structuralVisible.sourceVarKey != null && !isForeachScopedSource)
				|| structuralVisible.columns != null
				|| structuralVisible.hashKeys != null
				|| structuralVisible.hashSourceVars != null
				|| (allHashKeys != null && !allHashKeys.isEmpty())
				|| (allHashSourceVars != null && !allHashSourceVars.isEmpty());
	}

	static @Nullable Map<String, HashEntryInfo> mergeHashEntryMaps(
			@Nullable Map<String, HashEntryInfo> oldMap,
			@Nullable Map<String, HashEntryInfo> newMap
	) {
		if (oldMap == null || oldMap.isEmpty()) return newMap;
		if (newMap == null || newMap.isEmpty()) return oldMap;

		if (containsOnlyMutationMarkers(oldMap) && containsOnlyMutationMarkers(newMap)) {
			Map<String, HashEntryInfo> mergedMarkers = new LinkedHashMap<>(oldMap);
			mergedMarkers.putAll(newMap);
			return mergedMarkers;
		}

		Map<String, HashEntryInfo> merged = new LinkedHashMap<>(oldMap);
		Map<String, HashEntryInfo> renamedEntries = new LinkedHashMap<>();
		for (Map.Entry<String, HashEntryInfo> entry : newMap.entrySet()) {
			HashEntryInfo newEntry = entry.getValue();
			if (P3VariableParser.isDeletedHashEntry(newEntry)) {
				merged.remove(entry.getKey());
			} else if (P3VariableParser.isRenamedHashEntry(newEntry)) {
				String oldKey = P3VariableParser.getRenamedHashSource(newEntry);
				HashEntryInfo renamedEntry = oldKey != null ? oldMap.get(oldKey) : null;
				if (oldKey != null) {
					merged.remove(oldKey);
				}
				merged.remove(entry.getKey());
				if (renamedEntry != null) {
					renamedEntries.put(entry.getKey(), renamedEntry);
				}
			}
		}
		for (Map.Entry<String, HashEntryInfo> entry : newMap.entrySet()) {
			HashEntryInfo newEntry = entry.getValue();
			if (P3VariableParser.isDeletedHashEntry(newEntry) || P3VariableParser.isRenamedHashEntry(newEntry)) {
				continue;
			}
			HashEntryInfo oldEntry = merged.get(entry.getKey());
			if (oldEntry != null && newEntry != null) {
				merged.put(entry.getKey(), mergeHashEntryInfo(oldEntry, newEntry));
			} else {
				merged.put(entry.getKey(), newEntry);
			}
		}
		merged.putAll(renamedEntries);
		return merged;
	}

	private static boolean containsOnlyMutationMarkers(@NotNull Map<String, HashEntryInfo> map) {
		if (map.isEmpty()) return false;
		for (HashEntryInfo info : map.values()) {
			if (!P3VariableParser.isDeletedHashEntry(info) && !P3VariableParser.isRenamedHashEntry(info)) {
				return false;
			}
		}
		return true;
	}

	static boolean isHashSourceAtOffset(@NotNull String sourceVarKey) {
		return sourceVarKey.startsWith("hashsrc_at\n");
	}

	static @NotNull String encodeHashSourceAtOffset(@NotNull String sourceVarKey, int offset) {
		if (isHashSourceAtOffset(sourceVarKey) || offset < 0) return sourceVarKey;
		return "hashsrc_at\n" + offset + "\n" + sourceVarKey;
	}

	static @NotNull String decodeHashSourceVarKey(@NotNull String sourceVarKey) {
		if (!isHashSourceAtOffset(sourceVarKey)) return sourceVarKey;
		int firstNewLine = sourceVarKey.indexOf('\n');
		if (firstNewLine < 0) return sourceVarKey;
		int secondNewLine = sourceVarKey.indexOf('\n', firstNewLine + 1);
		return secondNewLine >= 0
				? sourceVarKey.substring(secondNewLine + 1)
				: sourceVarKey;
	}

	static int decodeHashSourceLookupOffset(@NotNull String sourceVarKey, int fallbackOffset) {
		if (!isHashSourceAtOffset(sourceVarKey)) return fallbackOffset;
		int firstNewLine = sourceVarKey.indexOf('\n');
		if (firstNewLine < 0) return fallbackOffset;
		int secondNewLine = sourceVarKey.indexOf('\n', firstNewLine + 1);
		if (secondNewLine < 0) return fallbackOffset;
		try {
			return Math.max(0, Integer.parseInt(sourceVarKey.substring(firstNewLine + 1, secondNewLine)) - 1);
		} catch (NumberFormatException ignored) {
			return fallbackOffset;
		}
	}

	private static boolean shouldSnapshotSourceVarKey(@NotNull P3VariableFileIndex.VariableTypeInfo info) {
		if (info.sourceVarKey == null || info.sourceVarKey.isEmpty()) return false;
		if (info.sourceVarKey.startsWith("foreach:") || info.sourceVarKey.startsWith("foreach_field:")) return false;
		if (info.sourceVarKey.startsWith("hashop\n")) return true;
		return !P3VariableFileIndex.UNKNOWN_TYPE.equals(info.className);
	}

	static @Nullable List<String> applyColumnHashMutations(
			@Nullable List<String> columns,
			@Nullable Map<String, HashEntryInfo> hashKeys
	) {
		if (columns == null || columns.isEmpty() || hashKeys == null || hashKeys.isEmpty()) return columns;

		Map<String, String> renameByOldKey = new LinkedHashMap<>();
		Set<String> deletedKeys = new HashSet<>();
		for (Map.Entry<String, HashEntryInfo> entry : hashKeys.entrySet()) {
			HashEntryInfo info = entry.getValue();
			if (P3VariableParser.isDeletedHashEntry(info)) {
				deletedKeys.add(entry.getKey());
			} else if (P3VariableParser.isRenamedHashEntry(info)) {
				String oldKey = P3VariableParser.getRenamedHashSource(info);
				if (oldKey != null && !oldKey.isEmpty()) {
					renameByOldKey.put(oldKey, entry.getKey());
					deletedKeys.add(oldKey);
				}
			}
		}
		if (renameByOldKey.isEmpty() && deletedKeys.isEmpty()) return columns;

		List<String> result = new ArrayList<>(columns.size());
		for (String column : columns) {
			String renamedColumn = renameByOldKey.get(column);
			if (renamedColumn != null) {
				if (!renamedColumn.isEmpty()) result.add(renamedColumn);
			} else if (!deletedKeys.contains(column)) {
				result.add(column);
			}
		}
		return result.isEmpty() ? null : result;
	}

	static @NotNull HashEntryInfo mergeHashEntryInfo(
			@NotNull HashEntryInfo oldInfo,
			@NotNull HashEntryInfo newInfo
	) {
		if (isBuiltinSystemFieldReadChain(oldInfo, newInfo)) {
			return oldInfo;
		}
		if (isMethodResultEntry(oldInfo) && !isMethodResultEntry(newInfo) && newInfo.offset >= 0) {
			return newInfo;
		}
		String mergedClassName = chooseRicherClassName(oldInfo.className, newInfo.className);
		List<String> mergedColumns = newInfo.columns != null ? newInfo.columns : oldInfo.columns;
		Map<String, HashEntryInfo> mergedNested = mergeHashEntryMaps(oldInfo.nestedKeys, newInfo.nestedKeys);
		int mergedOffset = newInfo.offset >= 0 ? newInfo.offset : oldInfo.offset;
		com.intellij.openapi.vfs.VirtualFile mergedFile =
				newInfo.offset >= 0 && newInfo.file != null ? newInfo.file : oldInfo.file;
		boolean useNewMethodMeta = newInfo.methodName != null
				|| newInfo.targetClassName != null
				|| newInfo.receiverVarKey != null;
		String mergedSourceVarKey = newInfo.sourceVarKey != null ? newInfo.sourceVarKey : oldInfo.sourceVarKey;
		return new HashEntryInfo(
				mergedClassName,
				mergedColumns,
				mergedNested,
				mergedOffset,
				mergedFile,
				mergedSourceVarKey,
				useNewMethodMeta ? newInfo.methodName : oldInfo.methodName,
				useNewMethodMeta ? newInfo.targetClassName : oldInfo.targetClassName,
				useNewMethodMeta ? newInfo.receiverVarKey : oldInfo.receiverVarKey
		);
	}

	private static boolean isMethodResultEntry(@NotNull HashEntryInfo info) {
		return P3VariableFileIndex.VariableTypeInfo.METHOD_CALL_MARKER.equals(info.className)
				|| info.methodName != null
				|| info.targetClassName != null
				|| info.receiverVarKey != null;
	}

	static @Nullable HashEntryInfo resolveHashEntry(
			@NotNull Map<String, HashEntryInfo> currentKeys,
			@NotNull String key
	) {
		if ("*".equals(key)) {
			return resolveDynamicHashEntry(currentKeys);
		}
		HashEntryInfo entry = currentKeys.get(key);
		HashEntryInfo wildcard = resolveWildcardEntry(currentKeys);
		if (entry == null) return wildcard;
		if (wildcard == null || P3VariableParser.isDeletedHashEntry(entry)
				|| P3VariableParser.isRenamedHashEntry(entry)) {
			return entry;
		}
		return mergeHashEntryInfo(wildcard, entry);
	}

	/**
	 * При fallback на wildcard '*' мержит nestedKeys из '*' + nestedKeys всех явных ключей.
	 * Пример: $data[$.key1[$.xxx[]] $.key2[$.yyy[]]] + ^data.add[^hash::sql{...}]
	 * → $data.x. → {xxx, yyy} (от key1, key2) + {name, value} (от *)
	 */
	static @Nullable HashEntryInfo resolveWildcardEntry(
			@NotNull Map<String, HashEntryInfo> currentKeys
	) {
		HashEntryInfo wildcard = currentKeys.get("*");
		if (wildcard == null) return null;

		Map<String, HashEntryInfo> merged = new LinkedHashMap<>();
		for (Map.Entry<String, HashEntryInfo> entry : currentKeys.entrySet()) {
			if (!"*".equals(entry.getKey()) && entry.getValue().nestedKeys != null) {
				merged.putAll(entry.getValue().nestedKeys);
			}
		}
		if (wildcard.nestedKeys != null) {
			merged.putAll(wildcard.nestedKeys);
		}

		if (merged.isEmpty()) return wildcard;
		return new HashEntryInfo(
				wildcard.className,
				null,
				merged,
				wildcard.offset,
				wildcard.file,
				wildcard.sourceVarKey,
				wildcard.methodName,
				wildcard.targetClassName,
				wildcard.receiverVarKey
		);
	}

	/**
	 * Динамический сегмент .[$key] нормализуется в "*".
	 * Если явного wildcard нет, берём объединённую форму всех известных значений.
	 */
	static @Nullable HashEntryInfo resolveDynamicHashEntry(
			@NotNull Map<String, HashEntryInfo> currentKeys
	) {
		HashEntryInfo wildcard = resolveWildcardEntry(currentKeys);
		if (wildcard != null) return wildcard;

		HashEntryInfo merged = null;
		for (Map.Entry<String, HashEntryInfo> currentEntry : currentKeys.entrySet()) {
			if ("*".equals(currentEntry.getKey())) continue;
			HashEntryInfo valueInfo = currentEntry.getValue();
			if (valueInfo == null
					|| P3VariableParser.isDeletedHashEntry(valueInfo)
					|| P3VariableParser.isRenamedHashEntry(valueInfo)) {
				continue;
			}
			merged = merged == null ? valueInfo : mergeHashEntryInfo(merged, valueInfo);
		}
		return merged;
	}

	private static boolean isBuiltinSystemFieldReadChain(
			@NotNull HashEntryInfo oldInfo,
			@NotNull HashEntryInfo newInfo
	) {
		if (!Parser3BuiltinMethods.isBuiltinClass(oldInfo.className)) return false;
		if (!"hash".equals(newInfo.className)) return false;
		if (newInfo.nestedKeys == null || newInfo.nestedKeys.isEmpty()) return false;

		for (String key : newInfo.nestedKeys.keySet()) {
			if (findBuiltinInstanceProperty(oldInfo.className, key) == null) {
				return false;
			}
		}
		return true;
	}

	private static @Nullable Parser3BuiltinMethods.BuiltinCallable findBuiltinInstanceProperty(
			@NotNull String className,
			@NotNull String propertyName
	) {
		for (Parser3BuiltinMethods.BuiltinCallable property : Parser3BuiltinMethods.getPropertiesForClass(className)) {
			if (property.name.equals(propertyName)) {
				return property;
			}
		}
		return null;
	}

	private static @NotNull String chooseRicherClassName(
			@NotNull String oldClassName,
			@NotNull String newClassName
	) {
		if (!P3VariableFileIndex.UNKNOWN_TYPE.equals(newClassName) && !newClassName.isEmpty()) return newClassName;
		if (!P3VariableFileIndex.UNKNOWN_TYPE.equals(oldClassName) && !oldClassName.isEmpty()) return oldClassName;
		return newClassName;
	}
}
