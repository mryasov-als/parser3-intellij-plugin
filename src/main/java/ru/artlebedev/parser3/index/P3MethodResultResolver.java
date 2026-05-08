package ru.artlebedev.parser3.index;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.artlebedev.parser3.lexer.Parser3LexerUtils;
import ru.artlebedev.parser3.utils.Parser3ClassUtils;

/**
 * Выводит тип результата метода по телу: $result[...] и ^return[...].
 * Для типизации ^return[...] считается полным синонимом $result[...].
 */
public final class P3MethodResultResolver {

	private P3MethodResultResolver() {
	}

	public static @Nullable P3ResolvedValue inferResult(
			@NotNull String text,
			@NotNull Parser3ClassUtils.MethodBoundary methodBoundary
	) {
		return inferResult(text, methodBoundary, null);
	}

	public static @Nullable P3ResolvedValue inferResult(
			@NotNull String text,
			@NotNull Parser3ClassUtils.MethodBoundary methodBoundary,
			@Nullable java.util.function.Supplier<java.util.Map<String, java.util.List<P3VariableFileIndex.VariableTypeInfo>>> parsedVariablesSupplier
	) {
		P3ResolvedValue lastNonEmpty = null;
		int lastNonEmptyOffset = -1;
		P3ResolvedValue richest = null;
		java.util.Map<String, java.util.List<P3VariableFileIndex.VariableTypeInfo>> parsedVariables = null;
		int from = methodBoundary.start;
		int to = Math.min(methodBoundary.end, text.length());

		for (int i = from; i < to; i++) {
			char ch = text.charAt(i);

			if (ch == '$' && matchesWord(text, i + 1, to, "result")) {
				P3ResolvedValue value = parseAssignmentValue(text, i + 7, to);
				if (value != null && hasLocalReferences(value)) {
					if (parsedVariables == null) {
						parsedVariables = getParsedVariables(text, parsedVariablesSupplier);
					}
					value = resolveLocalRefs(value, parsedVariables, methodBoundary, i);
				}
				if (value != null) {
					lastNonEmpty = value;
					lastNonEmptyOffset = i;
					richest = chooseRicherResult(richest, value);
				}
				continue;
			}

			if (ch == '^' && matchesWord(text, i + 1, to, "return")) {
				P3ResolvedValue value = parseAssignmentValue(text, i + 7, to);
				if (value != null && hasLocalReferences(value)) {
					if (parsedVariables == null) {
						parsedVariables = getParsedVariables(text, parsedVariablesSupplier);
					}
					value = resolveLocalRefs(value, parsedVariables, methodBoundary, i);
				}
				if (value != null) {
					lastNonEmpty = value;
					lastNonEmptyOffset = i;
					richest = chooseRicherResult(richest, value);
				}
			}
		}

		if (parsedVariables == null) {
			parsedVariables = getParsedVariables(text, parsedVariablesSupplier);
		}
		LocalResolvedValue resultVariable = findLocalVariableValue(parsedVariables, "result", methodBoundary, to);
		if (resultVariable != null && resultVariable.lastVisibleOffset >= lastNonEmptyOffset) {
			P3ResolvedValue resultValue = hasLocalReferences(resultVariable.value)
					? resolveLocalRefs(resultVariable.value, parsedVariables, methodBoundary, resultVariable.lastVisibleOffset)
					: resultVariable.value;
			return chooseRicherResult(richest, resultValue);
		}

		return richest != null ? richest : lastNonEmpty;
	}

	private static @NotNull P3ResolvedValue chooseRicherResult(
			@Nullable P3ResolvedValue current,
			@NotNull P3ResolvedValue candidate
	) {
		if (current == null) return candidate;
		if (isSamePrimaryStructure(current, candidate)) return candidate;
		if (isWeakResult(candidate) && !isWeakResult(current)) return current;
		int currentScore = richnessScore(current);
		int candidateScore = richnessScore(candidate);
		if (candidateScore >= currentScore) return candidate;
		return current;
	}

	private static boolean isSamePrimaryStructure(
			@NotNull P3ResolvedValue current,
			@NotNull P3ResolvedValue candidate
	) {
		if (hasHashKeys(current) && hasHashKeys(candidate)) return true;
		if (hasColumns(current) && hasColumns(candidate)) return true;
		if (hasHashSourceVars(current) && hasHashSourceVars(candidate)) return true;
		if (current.sourceVarKey != null && candidate.sourceVarKey != null) return true;
		return P3VariableFileIndex.VariableTypeInfo.METHOD_CALL_MARKER.equals(current.className)
				&& P3VariableFileIndex.VariableTypeInfo.METHOD_CALL_MARKER.equals(candidate.className);
	}

	private static boolean isWeakResult(@NotNull P3ResolvedValue value) {
		return !hasHashKeys(value)
				&& !hasColumns(value)
				&& !hasHashSourceVars(value)
				&& value.sourceVarKey == null
				&& value.receiverVarKey == null
				&& (value.methodName == null || value.methodName.isEmpty())
				&& (value.targetClassName == null || value.targetClassName.isEmpty())
				&& !P3VariableFileIndex.VariableTypeInfo.METHOD_CALL_MARKER.equals(value.className)
				&& (value.className.isEmpty() || P3VariableFileIndex.UNKNOWN_TYPE.equals(value.className));
	}

	private static boolean hasHashKeys(@NotNull P3ResolvedValue value) {
		return value.hashKeys != null && !value.hashKeys.isEmpty();
	}

	private static boolean hasColumns(@NotNull P3ResolvedValue value) {
		return value.columns != null && !value.columns.isEmpty();
	}

	private static boolean hasHashSourceVars(@NotNull P3ResolvedValue value) {
		return value.hashSourceVars != null && !value.hashSourceVars.isEmpty();
	}

	private static int richnessScore(@NotNull P3ResolvedValue value) {
		int score = 0;
		if (hasHashKeys(value)) {
			score += 1000 + hashKeyRichness(value.hashKeys);
		}
		if (hasColumns(value)) {
			score += 800 + value.columns.size();
		}
		if (hasHashSourceVars(value)) {
			score += 300 + value.hashSourceVars.size();
		}
		if (value.sourceVarKey != null && !value.sourceVarKey.isEmpty()) {
			score += 200;
		}
		if (P3VariableFileIndex.VariableTypeInfo.METHOD_CALL_MARKER.equals(value.className)) {
			score += 500;
		} else if (!P3VariableFileIndex.UNKNOWN_TYPE.equals(value.className) && !value.className.isEmpty()) {
			score += 100;
		}
		if (value.methodName != null && !value.methodName.isEmpty()) {
			score += 50;
		}
		if (value.targetClassName != null && !value.targetClassName.isEmpty()) {
			score += 20;
		}
		if (value.receiverVarKey != null && !value.receiverVarKey.isEmpty()) {
			score += 20;
		}
		return score;
	}

	private static int hashKeyRichness(@NotNull java.util.Map<String, HashEntryInfo> hashKeys) {
		int score = hashKeys.size();
		for (HashEntryInfo info : hashKeys.values()) {
			if (info.nestedKeys != null && !info.nestedKeys.isEmpty()) {
				score += hashKeyRichness(info.nestedKeys);
			}
			if (info.columns != null && !info.columns.isEmpty()) {
				score += info.columns.size();
			}
			if (!P3VariableFileIndex.UNKNOWN_TYPE.equals(info.className) && !info.className.isEmpty()) {
				score += 1;
			}
		}
		return score;
	}

	private static boolean hasLocalReferences(@NotNull P3ResolvedValue value) {
		return value.sourceVarKey != null
				|| value.receiverVarKey != null
				|| (value.hashSourceVars != null && !value.hashSourceVars.isEmpty())
				|| hasLocalHashEntryReferences(value.hashKeys, new java.util.HashSet<>());
	}

	private static boolean hasLocalHashEntryReferences(
			@Nullable java.util.Map<String, HashEntryInfo> hashKeys,
			@NotNull java.util.Set<Integer> visitedMaps
	) {
		if (hashKeys == null || hashKeys.isEmpty()) return false;
		if (!visitedMaps.add(System.identityHashCode(hashKeys))) return false;
		for (HashEntryInfo info : hashKeys.values()) {
			if (info.sourceVarKey != null
					|| info.receiverVarKey != null
					|| hasLocalHashEntryReferences(info.nestedKeys, visitedMaps)) {
				return true;
			}
		}
		return false;
	}

	private static @NotNull java.util.Map<String, java.util.List<P3VariableFileIndex.VariableTypeInfo>> getParsedVariables(
			@NotNull String text,
			@Nullable java.util.function.Supplier<java.util.Map<String, java.util.List<P3VariableFileIndex.VariableTypeInfo>>> parsedVariablesSupplier
	) {
		if (parsedVariablesSupplier != null) {
			return parsedVariablesSupplier.get();
		}
		return parseVariables(text);
	}

	private static boolean matchesWord(@NotNull String text, int start, int limit, @NotNull String word) {
		int end = start + word.length();
		if (end > limit || end > text.length()) return false;
		if (!text.regionMatches(start, word, 0, word.length())) return false;
		return end >= text.length() || !P3VariableParser.isIdentChar(text.charAt(end));
	}

	private static @Nullable P3ResolvedValue parseAssignmentValue(@NotNull String text, int pos, int limit) {
		while (pos < limit && Character.isWhitespace(text.charAt(pos))) pos++;
		if (pos >= limit) return null;

		char bracket = text.charAt(pos);
		if (bracket != '[' && bracket != '(' && bracket != '{') return null;

		int close = findMatchingBracket(text, pos, limit, bracket);
		if (close < 0) return null;
		if (Parser3ClassUtils.isOffsetInComment(text, pos)) return null;

		if (isEmptyBody(text, pos + 1, close)) {
			return null;
		}

		if (bracket == '{') {
			return P3ResolvedValue.unknown();
		}

		return P3VariableParser.parseResolvedValueContent(text, pos, limit);
	}

	private static boolean isEmptyBody(@NotNull String text, int from, int to) {
		for (int i = from; i < to; i++) {
			if (!Character.isWhitespace(text.charAt(i))) return false;
		}
		return true;
	}

	private static int findMatchingBracket(@NotNull String text, int openPos, int limit, char openCh) {
		char closeCh = openCh == '[' ? ']' : (openCh == '(' ? ')' : '}');
		return Parser3LexerUtils.findMatchingBracket(text, openPos, limit, openCh, closeCh);
	}

	private static @NotNull java.util.Map<String, java.util.List<P3VariableFileIndex.VariableTypeInfo>> parseVariables(
			@NotNull String text
	) {
		java.util.List<Parser3ClassUtils.ClassBoundary> classes = Parser3ClassUtils.findClassBoundaries(text);
		java.util.List<Parser3ClassUtils.MethodBoundary> methods = Parser3ClassUtils.findMethodBoundaries(text, classes);
		return P3VariableParser.parse(text, classes, methods);
	}

	private static @NotNull P3ResolvedValue resolveLocalRefs(
			@NotNull P3ResolvedValue value,
			@NotNull java.util.Map<String, java.util.List<P3VariableFileIndex.VariableTypeInfo>> parsedVariables,
			@NotNull Parser3ClassUtils.MethodBoundary methodBoundary,
			int assignmentOffset
	) {
		P3ResolvedValue current = value;
		if (current.receiverVarKey != null && current.targetClassName == null) {
			P3ResolvedValue receiver = resolveLocalVarValue(
					current.receiverVarKey,
					parsedVariables,
					methodBoundary,
					assignmentOffset,
					new java.util.HashSet<>()
			);
			if (receiver != null && !receiver.className.isEmpty()
					&& !P3VariableFileIndex.VariableTypeInfo.METHOD_CALL_MARKER.equals(receiver.className)) {
				current = P3ResolvedValue.of(
						current.className,
						current.columns,
						current.sourceVarKey,
						current.hashKeys,
						current.hashSourceVars,
						current.methodName,
						receiver.className,
						current.receiverVarKey
				);
			}
		}
		current = resolveLocalHashEntryRefs(
				current,
				parsedVariables,
				methodBoundary,
				assignmentOffset,
				new java.util.HashSet<>()
		);
		if (current.sourceVarKey == null) {
			return resolveLocalHashSourceVars(current, parsedVariables, methodBoundary, assignmentOffset, new java.util.HashSet<>());
		}

		String sourceVarKey = P3VariableEffectiveShape.decodeHashSourceVarKey(current.sourceVarKey);
		int sourceLookupOffset = P3VariableEffectiveShape.decodeHashSourceLookupOffset(current.sourceVarKey, assignmentOffset);
		P3ResolvedValue resolvedSource = resolveLocalVarValue(
				sourceVarKey,
				parsedVariables,
				methodBoundary,
				sourceLookupOffset,
				new java.util.HashSet<>()
		);
		current = resolvedSource != null ? mergeResolvedValues(resolvedSource, current) : current;
		return resolveLocalHashSourceVars(current, parsedVariables, methodBoundary, assignmentOffset, new java.util.HashSet<>());
	}

	private static @NotNull P3ResolvedValue resolveLocalHashEntryRefs(
			@NotNull P3ResolvedValue value,
			@NotNull java.util.Map<String, java.util.List<P3VariableFileIndex.VariableTypeInfo>> parsedVariables,
			@NotNull Parser3ClassUtils.MethodBoundary methodBoundary,
			int assignmentOffset,
			@NotNull java.util.Set<String> visited
	) {
		if (value.hashKeys == null || value.hashKeys.isEmpty()) return value;
		java.util.Map<String, HashEntryInfo> hashKeys =
				resolveLocalHashEntryRefs(value.hashKeys, parsedVariables, methodBoundary, assignmentOffset, visited);
		if (hashKeys == value.hashKeys) return value;
		return P3ResolvedValue.of(
				value.className,
				value.columns,
				value.sourceVarKey,
				hashKeys,
				value.hashSourceVars,
				value.methodName,
				value.targetClassName,
				value.receiverVarKey
		);
	}

	private static @NotNull java.util.Map<String, HashEntryInfo> resolveLocalHashEntryRefs(
			@NotNull java.util.Map<String, HashEntryInfo> hashKeys,
			@NotNull java.util.Map<String, java.util.List<P3VariableFileIndex.VariableTypeInfo>> parsedVariables,
			@NotNull Parser3ClassUtils.MethodBoundary methodBoundary,
			int assignmentOffset,
			@NotNull java.util.Set<String> visited
	) {
		java.util.Map<String, HashEntryInfo> result = null;
		for (java.util.Map.Entry<String, HashEntryInfo> entry : hashKeys.entrySet()) {
			HashEntryInfo oldInfo = entry.getValue();
			HashEntryInfo newInfo = resolveLocalHashEntryRef(
					oldInfo,
					parsedVariables,
					methodBoundary,
					assignmentOffset,
					new java.util.HashSet<>(visited)
			);
			if (newInfo != oldInfo) {
				if (result == null) result = new java.util.LinkedHashMap<>(hashKeys);
				result.put(entry.getKey(), newInfo);
			}
		}
		return result != null ? result : hashKeys;
	}

	private static @NotNull HashEntryInfo resolveLocalHashEntryRef(
			@NotNull HashEntryInfo info,
			@NotNull java.util.Map<String, java.util.List<P3VariableFileIndex.VariableTypeInfo>> parsedVariables,
			@NotNull Parser3ClassUtils.MethodBoundary methodBoundary,
			int assignmentOffset,
			@NotNull java.util.Set<String> visited
	) {
		String visitKey = "hash_entry\n"
				+ info.offset + "\n"
				+ info.file + "\n"
				+ info.sourceVarKey + "\n"
				+ info.methodName + "\n"
				+ info.targetClassName + "\n"
				+ info.receiverVarKey + "\n"
				+ System.identityHashCode(info.nestedKeys);
		if (!visited.add(visitKey)) return info;

		String className = info.className;
		java.util.List<String> columns = info.columns;
		String sourceVarKey = info.sourceVarKey;
		String methodName = info.methodName;
		String targetClassName = info.targetClassName;
		String receiverVarKey = info.receiverVarKey;
		java.util.Map<String, HashEntryInfo> nestedKeys = info.nestedKeys;

		if (sourceVarKey != null && !sourceVarKey.isEmpty()) {
			P3ResolvedValue source = resolveLocalHashEntrySourceRef(
					sourceVarKey,
					parsedVariables,
					methodBoundary,
					assignmentOffset,
					new java.util.HashSet<>(visited)
			);
			if (source != null) {
				className = chooseRicherClassName(className, source.className);
				columns = source.columns != null ? source.columns : columns;
				nestedKeys = P3VariableEffectiveShape.mergeHashEntryMaps(source.hashKeys, nestedKeys);
				sourceVarKey = source.sourceVarKey;
				methodName = source.methodName != null ? source.methodName : methodName;
				targetClassName = source.targetClassName != null ? source.targetClassName : targetClassName;
				receiverVarKey = source.receiverVarKey != null ? source.receiverVarKey : receiverVarKey;
			}
		}

		if (receiverVarKey != null && targetClassName == null) {
			P3ResolvedValue receiver = resolveLocalVarValue(
					receiverVarKey,
					parsedVariables,
					methodBoundary,
					assignmentOffset,
					new java.util.HashSet<>(visited)
			);
			if (receiver != null && !receiver.className.isEmpty()
					&& !P3VariableFileIndex.VariableTypeInfo.METHOD_CALL_MARKER.equals(receiver.className)) {
				targetClassName = receiver.className;
			}
		}

		if (nestedKeys != null && !nestedKeys.isEmpty()) {
			nestedKeys = resolveLocalHashEntryRefs(
					nestedKeys,
					parsedVariables,
					methodBoundary,
					assignmentOffset,
					new java.util.HashSet<>(visited)
				);
		}
		if (java.util.Objects.equals(className, info.className)
				&& java.util.Objects.equals(columns, info.columns)
				&& java.util.Objects.equals(sourceVarKey, info.sourceVarKey)
				&& java.util.Objects.equals(methodName, info.methodName)
				&& java.util.Objects.equals(targetClassName, info.targetClassName)
				&& java.util.Objects.equals(receiverVarKey, info.receiverVarKey)
				&& nestedKeys == info.nestedKeys) return info;
		return new HashEntryInfo(
				className,
				columns,
				nestedKeys,
				info.offset,
				info.file,
				sourceVarKey,
				methodName,
				targetClassName,
				receiverVarKey
		);
	}

	private static @Nullable P3ResolvedValue resolveLocalHashEntrySourceRef(
			@NotNull String rawSourceVarKey,
			@NotNull java.util.Map<String, java.util.List<P3VariableFileIndex.VariableTypeInfo>> parsedVariables,
			@NotNull Parser3ClassUtils.MethodBoundary methodBoundary,
			int assignmentOffset,
			@NotNull java.util.Set<String> visited
	) {
		String sourceVarKey = P3VariableEffectiveShape.decodeHashSourceVarKey(rawSourceVarKey);
		int sourceLookupOffset = Math.min(
				P3VariableEffectiveShape.decodeHashSourceLookupOffset(rawSourceVarKey, assignmentOffset),
				assignmentOffset
		);
		if (!isLocalHashPathReference(sourceVarKey)) {
			return resolveLocalVarValue(sourceVarKey, parsedVariables, methodBoundary, sourceLookupOffset, visited);
		}

		String[] parts = sourceVarKey.split("\\.");
		if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
			return resolveLocalVarValue(sourceVarKey, parsedVariables, methodBoundary, sourceLookupOffset, visited);
		}

		P3ResolvedValue root = resolveLocalVarValue(
				parts[0],
				parsedVariables,
				methodBoundary,
				sourceLookupOffset,
				visited
		);
		if (root == null) return null;
		if (root.hashKeys != null && !root.hashKeys.isEmpty()) {
			HashEntryInfo entry = P3VariableEffectiveShape.resolveHashEntry(root.hashKeys, parts[1]);
			if (entry != null) {
				return P3ResolvedValue.of(
						entry.className,
						entry.columns,
						entry.sourceVarKey,
						entry.nestedKeys,
						null,
						entry.methodName,
						entry.targetClassName,
						entry.receiverVarKey
				);
			}
		}
		if (!root.className.isEmpty()
				&& !P3VariableFileIndex.UNKNOWN_TYPE.equals(root.className)
				&& !P3VariableFileIndex.VariableTypeInfo.METHOD_CALL_MARKER.equals(root.className)) {
			return P3ResolvedValue.of(
					P3VariableFileIndex.VariableTypeInfo.METHOD_CALL_MARKER,
					null,
					null,
					null,
					null,
					parts[1],
					root.className,
					null
			);
		}
		return null;
	}

	private static @Nullable P3ResolvedValue resolveLocalVarValue(
			@NotNull String sourceVarKey,
			@NotNull java.util.Map<String, java.util.List<P3VariableFileIndex.VariableTypeInfo>> parsedVariables,
			@NotNull Parser3ClassUtils.MethodBoundary methodBoundary,
			int assignmentOffset,
			@NotNull java.util.Set<String> visited
	) {
		String decodedSourceVarKey = P3VariableEffectiveShape.decodeHashSourceVarKey(sourceVarKey);
		int sourceLookupOffset = P3VariableEffectiveShape.decodeHashSourceLookupOffset(sourceVarKey, assignmentOffset);
		if (!visited.add(decodedSourceVarKey + "@" + sourceLookupOffset)) return null;

		if (isLocalHashPathReference(decodedSourceVarKey)) {
			return resolveLocalHashPathValue(
					decodedSourceVarKey,
					parsedVariables,
					methodBoundary,
					sourceLookupOffset,
					visited
			);
		}

		LocalResolvedValue local = findLocalVariableValue(
				parsedVariables,
				decodedSourceVarKey,
				methodBoundary,
				sourceLookupOffset
		);
		if (local == null) return null;
		P3ResolvedValue current = local.value;

		if (current.receiverVarKey != null && current.targetClassName == null) {
			P3ResolvedValue receiver = resolveLocalVarValue(
					current.receiverVarKey,
					parsedVariables,
					methodBoundary,
					assignmentOffset,
					visited
			);
			if (receiver != null && !receiver.className.isEmpty()
					&& !P3VariableFileIndex.VariableTypeInfo.METHOD_CALL_MARKER.equals(receiver.className)) {
				current = P3ResolvedValue.of(
						current.className,
						current.columns,
						current.sourceVarKey,
						current.hashKeys,
						current.hashSourceVars,
						current.methodName,
						receiver.className,
						current.receiverVarKey
				);
			}
		}

		if (current.sourceVarKey != null) {
			String nestedSourceVarKey = P3VariableEffectiveShape.decodeHashSourceVarKey(current.sourceVarKey);
			int nestedSourceLookupOffset = P3VariableEffectiveShape.decodeHashSourceLookupOffset(current.sourceVarKey, assignmentOffset);
			P3ResolvedValue nested = resolveLocalVarValue(
					nestedSourceVarKey,
					parsedVariables,
					methodBoundary,
					nestedSourceLookupOffset,
					visited
			);
			if (nested != null) {
				return mergeResolvedValues(nested, current);
			}
		}

		return resolveLocalHashSourceVars(current, parsedVariables, methodBoundary, assignmentOffset, visited);
	}

	private static @NotNull P3ResolvedValue resolveLocalHashSourceVars(
			@NotNull P3ResolvedValue value,
			@NotNull java.util.Map<String, java.util.List<P3VariableFileIndex.VariableTypeInfo>> parsedVariables,
			@NotNull Parser3ClassUtils.MethodBoundary methodBoundary,
			int assignmentOffset,
			@NotNull java.util.Set<String> visited
	) {
		if (value.hashSourceVars == null || value.hashSourceVars.isEmpty()) return value;

		java.util.Map<String, HashEntryInfo> merged = null;
		for (String rawSourceVarKey : value.hashSourceVars) {
			String sourceVarKey = P3VariableEffectiveShape.decodeHashSourceVarKey(rawSourceVarKey);
			if (sourceVarKey.startsWith("hashop\n")) {
				continue;
			}
			int sourceLookupOffset = P3VariableEffectiveShape.decodeHashSourceLookupOffset(rawSourceVarKey, assignmentOffset);
			P3ResolvedValue sourceValue = resolveLocalVarValue(
					sourceVarKey,
					parsedVariables,
					methodBoundary,
					sourceLookupOffset,
					visited
			);
			if (sourceValue != null && sourceValue.hashKeys != null && !sourceValue.hashKeys.isEmpty()) {
				merged = P3VariableEffectiveShape.mergeHashEntryMaps(merged, sourceValue.hashKeys);
			}
		}
		merged = P3VariableEffectiveShape.mergeHashEntryMaps(merged, value.hashKeys);
		if (merged == value.hashKeys) return value;
		return P3ResolvedValue.of(
				value.className,
				value.columns,
				value.sourceVarKey,
				merged,
				null,
				value.methodName,
				value.targetClassName,
				value.receiverVarKey
		);
	}

	private static @Nullable LocalResolvedValue findLocalVariableValue(
			@NotNull java.util.Map<String, java.util.List<P3VariableFileIndex.VariableTypeInfo>> parsedVariables,
			@NotNull String sourceVarKey,
			@NotNull Parser3ClassUtils.MethodBoundary methodBoundary,
			int assignmentOffset
	) {
		java.util.List<P3VariableFileIndex.VariableTypeInfo> infos = parsedVariables.get(sourceVarKey);
		if (infos == null || infos.isEmpty()) return null;

		P3VariableEffectiveShape shape = new P3VariableEffectiveShape(sourceVarKey, false);

		for (P3VariableFileIndex.VariableTypeInfo info : infos) {
			if (info.offset >= assignmentOffset) continue;
			if (!java.util.Objects.equals(info.ownerClass, methodBoundary.ownerClass)) continue;
			if (!java.util.Objects.equals(info.ownerMethod, methodBoundary.name)) continue;
			shape.add(info);
		}
		P3VariableFileIndex.VariableTypeInfo structural = shape.structuralVisible();
		P3ResolvedValue value = shape.toResolvedValue();
		if (structural == null || value == null) return null;
		return new LocalResolvedValue(value, structural.offset, shape.lastVisibleOffset());
	}

	private static boolean isLocalHashPathReference(@NotNull String sourceVarKey) {
		return sourceVarKey.indexOf('.') > 0
				&& !sourceVarKey.startsWith("foreach:")
				&& !sourceVarKey.startsWith("foreach_field:");
	}

	private static @Nullable P3ResolvedValue resolveLocalHashPathValue(
			@NotNull String sourceVarKey,
			@NotNull java.util.Map<String, java.util.List<P3VariableFileIndex.VariableTypeInfo>> parsedVariables,
			@NotNull Parser3ClassUtils.MethodBoundary methodBoundary,
			int assignmentOffset,
			@NotNull java.util.Set<String> visited
	) {
		String[] parts = sourceVarKey.split("\\.");
		if (parts.length < 2 || parts[0].isEmpty()) return null;

		P3ResolvedValue root = resolveLocalVarValue(
				parts[0],
				parsedVariables,
				methodBoundary,
				assignmentOffset,
				visited
		);
		if (root == null || root.hashKeys == null || root.hashKeys.isEmpty()) return null;

		java.util.Map<String, HashEntryInfo> currentKeys = root.hashKeys;
		HashEntryInfo currentEntry = null;
		for (int i = 1; i < parts.length; i++) {
			String part = parts[i];
			if (part.isEmpty()) return null;
			currentEntry = P3VariableEffectiveShape.resolveHashEntry(currentKeys, part);
			if (currentEntry == null) return null;
			if (i < parts.length - 1) {
				currentKeys = currentEntry.nestedKeys;
				if (currentKeys == null || currentKeys.isEmpty()) return null;
			}
		}
		if (currentEntry == null) return null;
		return P3ResolvedValue.of(
				currentEntry.className,
				currentEntry.columns,
				null,
				currentEntry.nestedKeys,
				null,
				null,
				null,
				null
		);
	}

	private static @NotNull P3ResolvedValue mergeResolvedValues(
			@NotNull P3ResolvedValue base,
			@NotNull P3ResolvedValue overlay
	) {
		return P3ResolvedValue.of(
				chooseRicherClassName(base.className, overlay.className),
				overlay.columns != null ? overlay.columns : base.columns,
				null,
				P3VariableEffectiveShape.mergeHashEntryMaps(base.hashKeys, overlay.hashKeys),
				mergeStringLists(base.hashSourceVars, overlay.hashSourceVars),
				overlay.methodName != null ? overlay.methodName : base.methodName,
				overlay.targetClassName != null ? overlay.targetClassName : base.targetClassName,
				overlay.receiverVarKey != null ? overlay.receiverVarKey : base.receiverVarKey
		);
	}

	private static @NotNull String chooseRicherClassName(
			@NotNull String baseClassName,
			@NotNull String overlayClassName
	) {
		if (!P3VariableFileIndex.UNKNOWN_TYPE.equals(overlayClassName) && !overlayClassName.isEmpty()) {
			return overlayClassName;
		}
		if (!P3VariableFileIndex.UNKNOWN_TYPE.equals(baseClassName) && !baseClassName.isEmpty()) {
			return baseClassName;
		}
		return overlayClassName;
	}

	private static @Nullable java.util.List<String> mergeStringLists(
			@Nullable java.util.List<String> base,
			@Nullable java.util.List<String> overlay
	) {
		if (base == null || base.isEmpty()) return overlay;
		if (overlay == null || overlay.isEmpty()) return base;
		java.util.List<String> merged = new java.util.ArrayList<>(base);
		for (String value : overlay) {
			if (!merged.contains(value)) merged.add(value);
		}
		return merged;
	}

	private static final class LocalResolvedValue {
		private final @NotNull P3ResolvedValue value;
		private final int offset;
		private final int lastVisibleOffset;

		private LocalResolvedValue(@NotNull P3ResolvedValue value, int offset, int lastVisibleOffset) {
			this.value = value;
			this.offset = offset;
			this.lastVisibleOffset = lastVisibleOffset;
		}
	}
}
