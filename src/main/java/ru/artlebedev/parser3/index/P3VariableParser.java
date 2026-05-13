package ru.artlebedev.parser3.index;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.artlebedev.parser3.settings.Parser3SqlInjectionSupport;
import ru.artlebedev.parser3.utils.Parser3IdentifierUtils;
import ru.artlebedev.parser3.utils.Parser3VariableTailUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Посимвольный парсер переменных Parser3.
 * Единственный источник истины для парсинга присваиваний переменных.
 *
 * Заменяет 6 regex-паттернов в P3VariableFileIndex одним проходом по тексту.
 *
 * Распознаёт:
 * 1. $var[^UserClass::method]      → className = UserClass
 * 2. $var[^builtinClass::method]   → className = builtinClass, + колонки для table
 * 3. $var[^builtinClass:method]    → className = returnType(class, method), + колонки для table
 * 4. $var[^userMethod[]]           → className = METHOD_CALL_MARKER, methodName
 * 5. $var[^UserClass:method[]]     → className = METHOD_CALL_MARKER, methodName, targetClassName
 * 6. $var[$.key[...]]              → className = hash, hashKeys (рекурсивный парсинг)
 * 7. $var[...] (остальное)         → className = UNKNOWN_TYPE
 *
 * Все формы с prefix: $self.var, $MAIN:var
 */
public final class P3VariableParser {

	private static final boolean DEBUG = false;

	private P3VariableParser() {}

	// === Результат парсинга содержимого скобок ===

	/**
	 * Результат парсинга содержимого [...] или (...).
	 * Единая структура — используется и для $var[...], и для $.key[...].
	 */
	static final class ParsedValue {
		final @NotNull String className;
		final @Nullable List<String> columns;       // колонки таблицы
		final @Nullable String sourceVarKey;         // ключ переменной-источника
		final @Nullable Map<String, HashEntryInfo> hashKeys; // ключи хеша
		final @Nullable List<String> hashSourceVars; // переменные-источники для мержа ключей ($data2[$data $.key[...]])
		final @Nullable String methodName;           // имя метода (для METHOD_CALL_MARKER)
		final @Nullable String targetClassName;      // класс метода (для METHOD_CALL_MARKER)

		final @Nullable String receiverVarKey;

		ParsedValue(@NotNull String className) {
			this(className, null, null, null, null, null, null, null);
		}

		ParsedValue(@NotNull String className, @Nullable List<String> columns, @Nullable String sourceVarKey) {
			this(className, columns, sourceVarKey, null, null, null, null, null);
		}

		ParsedValue(@NotNull String className, @Nullable List<String> columns, @Nullable String sourceVarKey,
					@Nullable Map<String, HashEntryInfo> hashKeys, @Nullable List<String> hashSourceVars,
					@Nullable String methodName, @Nullable String targetClassName,
					@Nullable String receiverVarKey) {
			this.className = className;
			this.columns = columns;
			this.sourceVarKey = sourceVarKey;
			this.hashKeys = hashKeys;
			this.hashSourceVars = hashSourceVars;
			this.methodName = methodName;
			this.targetClassName = targetClassName;
			this.receiverVarKey = receiverVarKey;
		}

		/** Хеш-литерал */
		static ParsedValue hash(@NotNull Map<String, HashEntryInfo> hashKeys, @Nullable List<String> sourceVars) {
			return new ParsedValue("hash", null, null, hashKeys, sourceVars, null, null, null);
		}

		/** Вызов метода (ленивый резолв типа) */
		static ParsedValue methodCall(@NotNull String methodName, @Nullable String targetClassName) {
			return new ParsedValue(P3VariableFileIndex.VariableTypeInfo.METHOD_CALL_MARKER, null, null, null, null, methodName, targetClassName, null);
		}

		static ParsedValue objectMethodCall(@NotNull String methodName, @NotNull String receiverVarKey) {
			return new ParsedValue(P3VariableFileIndex.VariableTypeInfo.METHOD_CALL_MARKER, null, null, null, null, methodName, null, receiverVarKey);
		}

		/** Неизвестный тип */
		static ParsedValue unknown() {
			return new ParsedValue(P3VariableFileIndex.UNKNOWN_TYPE);
		}
	}

	private static final class ReadChainHeadInfo {
		final @Nullable String prefix;
		final @NotNull String varName;
		final int afterVarPos;
		final int chainLimit;
		final int terminatorLimit;

		ReadChainHeadInfo(
				@Nullable String prefix,
				@NotNull String varName,
				int afterVarPos,
				int chainLimit,
				int terminatorLimit
		) {
			this.prefix = prefix;
			this.varName = varName;
			this.afterVarPos = afterVarPos;
			this.chainLimit = chainLimit;
			this.terminatorLimit = terminatorLimit;
		}
	}

	private static final class DotChainSegmentInfo {
		final @NotNull String keyName;
		final int keyOffset;
		final int nextPos;

		DotChainSegmentInfo(@NotNull String keyName, int keyOffset, int nextPos) {
			this.keyName = keyName;
			this.keyOffset = keyOffset;
			this.nextPos = nextPos;
		}
	}

	static @NotNull P3ResolvedValue parseResolvedValueContent(
			@NotNull String text,
			int openBracketPos,
			int textLen
	) {
		return toResolvedValue(parseValueContent(text, openBracketPos, textLen));
	}

	static @NotNull P3ResolvedValue toResolvedValue(@NotNull ParsedValue pv) {
		return P3ResolvedValue.of(
				pv.className,
				pv.columns,
				pv.sourceVarKey,
				pv.hashKeys,
				pv.hashSourceVars,
				pv.methodName,
				pv.targetClassName,
				pv.receiverVarKey
		);
	}

	// === Главная функция парсинга ===

	/**
	 * Парсит все присваивания переменных из текста файла.
	 * Один проход по тексту — без regex.
	 *
	 * @return Map: indexKey → List<VariableTypeInfo>
	 */
	public static @NotNull Map<String, List<P3VariableFileIndex.VariableTypeInfo>> parse(
			@NotNull String text,
			@NotNull List<ru.artlebedev.parser3.utils.Parser3ClassUtils.ClassBoundary> classBoundaries,
			@NotNull List<ru.artlebedev.parser3.utils.Parser3ClassUtils.MethodBoundary> methodBoundaries
	) {
		Map<String, List<P3VariableFileIndex.VariableTypeInfo>> result = new HashMap<>();
		int len = text.length();

		for (int i = 0; i < len; i++) {
			char ch = text.charAt(i);

			// Пропускаем комментарии: # в колонке 0
			if (ch == '#' && isColumnZero(text, i)) {
				// Пропускаем до конца строки
				while (i < len && text.charAt(i) != '\n') i++;
				continue;
			}

			// Пропускаем ^rem{...} / ^rem[...] / ^rem(...)
			if (ch == '^' && i + 3 < len && text.charAt(i + 1) == 'r' && text.charAt(i + 2) == 'e' && text.charAt(i + 3) == 'm') {
				int afterRem = i + 4;
				if (afterRem < len) {
					char remBracket = text.charAt(afterRem);
					if (remBracket == '{' || remBracket == '[' || remBracket == '(') {
						int end = findMatchingBracket(text, afterRem, len, remBracket);
						if (end != -1) {
							i = end; // перепрыгиваем на закрывающую скобку
							continue;
						}
					}
				}
			}

			// === ^varName.add[...] — мерж ключей хеша ===
			// Документация: ^hash.add[хеш-выражение] — добавляет все ключи из хеш-выражения
			if (ch == '^' && i + 1 < len && Parser3IdentifierUtils.isIdentifierStart(text.charAt(i + 1))) {
				int caretPos = i;
				int cp = i + 1;

				// Пропускаем prefix: self. или MAIN:
				String caretPrefix = null;
				if (cp + 5 <= len && text.substring(cp, cp + 5).equals("self.")) {
					caretPrefix = "self.";
					cp += 5;
				} else if (cp + 5 <= len && text.substring(cp, cp + 5).equals("MAIN:")) {
					caretPrefix = "MAIN:";
					cp += 5;
				}

				// Парсим имя переменной
				int vnStart = cp;
				if (cp < len && Parser3IdentifierUtils.isIdentifierStart(text.charAt(cp))) {
					cp++;
					while (cp < len && isIdentChar(text.charAt(cp))) cp++;
					String caretVarName = text.substring(vnStart, cp);

					// Ожидаем .add[ / .join[ / .create[ / .copy[ / .rename[ / .sub[ / .delete[ / .remove[ / .set[
					if (cp < len && text.charAt(cp) == '.') {
						int methodStart = cp + 1;
						int methodEnd = methodStart;
						while (methodEnd < len && isIdentChar(text.charAt(methodEnd))) methodEnd++;
						String mutatingMethod = methodEnd > methodStart ? text.substring(methodStart, methodEnd) : "";
						while (methodEnd < len && Character.isWhitespace(text.charAt(methodEnd))) methodEnd++;
						if (isRootHashMutatingMethod(mutatingMethod)
								&& methodEnd < len
								&& (text.charAt(methodEnd) == '[' || text.charAt(methodEnd) == '(')) {
							int addBracketPos = methodEnd;
							String ownerClass = ru.artlebedev.parser3.utils.Parser3ClassUtils.findOwnerClass(caretPos, classBoundaries);
							ru.artlebedev.parser3.utils.Parser3ClassUtils.MethodBoundary ownerMethodBoundary =
									ru.artlebedev.parser3.utils.Parser3ClassUtils.findOwnerMethod(caretPos, methodBoundaries);
							String ownerMethod = ownerMethodBoundary != null ? ownerMethodBoundary.name : null;
							boolean isLocal = P3VariableFileIndex.computeIsLocal(caretPrefix, caretVarName, ownerClass, ownerMethodBoundary, text, classBoundaries);

							// Парсим содержимое [...] — определяем хеш-выражение
							ParsedValue addPv = parseValueContentWithPreviousLocalSources(
									text, addBracketPos, len, result, ownerClass, ownerMethod, caretPos);

							// Собираем sourceVars и/или hashKeys из содержимого
							List<String> sourceVars = new ArrayList<>();
							Map<String, HashEntryInfo> addedKeys = null;

							if ("rename".equals(mutatingMethod)) {
								addedKeys = extractHashRenameMutationKeys(text, addBracketPos, len);
							} else if ("sub".equals(mutatingMethod)) {
								addedKeys = extractHashDeleteMutationKeys(text, addBracketPos, len);
								if (addedKeys == null && addPv.sourceVarKey != null && !addPv.sourceVarKey.isEmpty()) {
									sourceVars.add(buildHashOperationSource("sub_mutation", caretVarName, addPv.sourceVarKey));
								}
							} else if ("delete".equals(mutatingMethod) || "remove".equals(mutatingMethod)) {
								addedKeys = extractSingleDeleteMutationKey(text, addBracketPos, len);
							} else if ("set".equals(mutatingMethod)) {
								addedKeys = extractSingleSetMutationKey(text, addBracketPos, len);
							} else {
								// Если содержимое = хеш-литерал с ключами
								if (addPv.hashKeys != null && !addPv.hashKeys.isEmpty()) {
									addedKeys = addPv.hashKeys;
								}
								if (addPv.hashSourceVars != null) {
									sourceVars.addAll(addPv.hashSourceVars);
								}
								// Если содержимое = $varName — добавляем как sourceVar
								if (addPv.sourceVarKey != null && !addPv.sourceVarKey.isEmpty()) {
									sourceVars.add(addPv.sourceVarKey);
								}
							}

							if (!sourceVars.isEmpty() || (addedKeys != null && !addedKeys.isEmpty())) {
								String indexKey = P3VariableFileIndex.buildIndexKey(caretPrefix, caretVarName);
								P3VariableFileIndex.VariableTypeInfo addInfo = new P3VariableFileIndex.VariableTypeInfo(
										caretPos, "hash", ownerClass, ownerMethod,
										null, null, caretPrefix, isLocal,
										null, null,
										addedKeys != null ? addedKeys : new LinkedHashMap<>(),
										sourceVars.isEmpty() ? null : sourceVars, true);

								if (DEBUG) System.out.println("[P3VarParser] ^" + indexKey + "." + mutatingMethod + " → hash"
										+ (addedKeys != null ? " addedKeys=" + addedKeys.keySet() : "")
										+ (!sourceVars.isEmpty() ? " sourceVars=" + sourceVars : ""));

								result.computeIfAbsent(indexKey, k -> new ArrayList<>()).add(addInfo);
							}
						}
						// Не меняем i — пусть продолжит парсить дальше
					}

					// === ^varName.foreach[key;value]{...} / ^varName.for[key;value]{...} ===
					// Документация: https://www.parser.ru/docs/lang/arrayforeach.htm
					// key = имя переменной ключа (UNKNOWN), value = переменная со значением
					// value получает тип = мерж всех значений hashKeys источника
					if (cp < len && text.charAt(cp) == '.') {
						String methodAfterDot = null;
						int dotPos = cp;
						int mp = cp + 1;
						// Парсим имя метода
						int mStart = mp;
						while (mp < len && isIdentChar(text.charAt(mp))) mp++;
						if (mp > mStart) {
							methodAfterDot = text.substring(mStart, mp);
						}
						// === Общий случай: ^varName.f1.f2...method[...] ===
						// Если цепочка точек >= 2 — все элементы кроме последнего (метода) это поля хеша.
						// ^hash.meets.foreach[;v] → поле "meets" у "hash"
						// ^hash.meets.pos[5]      → поле "meets" у "hash"
						if (mp < len && text.charAt(mp) == '.') {
							// Уже распарсили первый элемент (methodAfterDot), парсим остальные
							List<String> chain = new ArrayList<>();
							List<Integer> chainOffsets = new ArrayList<>();
							if (methodAfterDot != null) {
								chain.add(methodAfterDot);
								chainOffsets.add(mStart);
							}
							int chainCp = mp;
							while (chainCp < len && text.charAt(chainCp) == '.') {
								chainCp++; // после .
								int segStart = chainCp;
								while (chainCp < len && isIdentChar(text.charAt(chainCp))) chainCp++;
								if (chainCp == segStart) break;
								chain.add(text.substring(segStart, chainCp));
								chainOffsets.add(segStart);
							}
							// После цепочки ожидаем скобку — только тогда последний элемент является методом
							while (chainCp < len && Character.isWhitespace(text.charAt(chainCp))) chainCp++;
							if (chain.size() >= 2 && chainCp < len
									&& (text.charAt(chainCp) == '[' || text.charAt(chainCp) == '('
									|| text.charAt(chainCp) == '{')) {
								// chain.get(last) — метод, chain.get(0..last-1) — поля
								int lastIdx = chain.size() - 1;
								String lastMethod = chain.get(lastIdx);
								if (isReceiverOnlyValueMethod(lastMethod)) {
									continue;
								}
								if (isHashMutatingMethod(lastMethod) && chainCp < len && text.charAt(chainCp) == '[') {
									Map<String, HashEntryInfo> mutationKeys = extractNestedHashMutationKeys(
											text, chainCp, len, lastMethod, chain, chainOffsets);
									if (mutationKeys != null && !mutationKeys.isEmpty()) {
										String mutationIndexKey = P3VariableFileIndex.buildIndexKey(caretPrefix, caretVarName);
										String mutationOwnerClass = ru.artlebedev.parser3.utils.Parser3ClassUtils.findOwnerClass(caretPos, classBoundaries);
										ru.artlebedev.parser3.utils.Parser3ClassUtils.MethodBoundary mutationOwnerMethodBoundary =
												ru.artlebedev.parser3.utils.Parser3ClassUtils.findOwnerMethod(caretPos, methodBoundaries);
										String mutationOwnerMethod = mutationOwnerMethodBoundary != null ? mutationOwnerMethodBoundary.name : null;
										boolean mutationIsLocal = P3VariableFileIndex.computeIsLocal(caretPrefix, caretVarName, mutationOwnerClass, mutationOwnerMethodBoundary, text, classBoundaries);

										P3VariableFileIndex.VariableTypeInfo mutationInfo = new P3VariableFileIndex.VariableTypeInfo(
												caretPos, "hash", mutationOwnerClass, mutationOwnerMethod,
												null, null, caretPrefix, mutationIsLocal,
												null, null, mutationKeys, null, true);

										if (DEBUG) System.out.println("[P3VarParser] ^" + mutationIndexKey
												+ ".nestedMutation=" + chain + " → hash keys=" + mutationKeys.keySet());

										result.computeIfAbsent(mutationIndexKey, k -> new ArrayList<>()).add(mutationInfo);
									}
									continue;
								}
								// Если метод foreach/for — у поля будут ключи через foreach_field,
								// ставим wildcard * чтобы hasNestedKeys() вернул true → точка добавится
								boolean isForeachMethod = "foreach".equals(lastMethod) || "for".equals(lastMethod);
								Map<String, HashEntryInfo> leafNestedKeys = null;
								if (isForeachMethod) {
									leafNestedKeys = new java.util.LinkedHashMap<>();
									leafNestedKeys.put("*", new HashEntryInfo(P3VariableFileIndex.UNKNOWN_TYPE, null, null));
								}
								// Строим вложенную структуру снизу вверх (как для $var.f1.f2[...])
								HashEntryInfo leafInfo = new HashEntryInfo(P3VariableFileIndex.UNKNOWN_TYPE, null, leafNestedKeys, chainOffsets.get(lastIdx - 1));
								for (int ci = lastIdx - 1; ci >= 1; ci--) {
									Map<String, HashEntryInfo> wrapper = new LinkedHashMap<>();
									wrapper.put(chain.get(ci), leafInfo);
									leafInfo = new HashEntryInfo("hash", null, wrapper, chainOffsets.get(ci - 1));
								}
								Map<String, HashEntryInfo> addedKeys = new LinkedHashMap<>();
								addedKeys.put(chain.get(0), leafInfo);

								String chainIndexKey = P3VariableFileIndex.buildIndexKey(caretPrefix, caretVarName);
								String chainOwnerClass = ru.artlebedev.parser3.utils.Parser3ClassUtils.findOwnerClass(caretPos, classBoundaries);
								ru.artlebedev.parser3.utils.Parser3ClassUtils.MethodBoundary chainOwnerMethodBoundary =
										ru.artlebedev.parser3.utils.Parser3ClassUtils.findOwnerMethod(caretPos, methodBoundaries);
								String chainOwnerMethod = chainOwnerMethodBoundary != null ? chainOwnerMethodBoundary.name : null;
								boolean chainIsLocal = P3VariableFileIndex.computeIsLocal(caretPrefix, caretVarName, chainOwnerClass, chainOwnerMethodBoundary, text, classBoundaries);

								P3VariableFileIndex.VariableTypeInfo chainInfo = new P3VariableFileIndex.VariableTypeInfo(
										caretPos, "hash", chainOwnerClass, chainOwnerMethod,
										null, null, caretPrefix, chainIsLocal,
										null, null, addedKeys, null, true);

								if (DEBUG) System.out.println("[P3VarParser] ^" + chainIndexKey + ".chain=" + chain
										+ " → hash addedKeys=" + addedKeys.keySet());

								result.computeIfAbsent(chainIndexKey, k -> new ArrayList<>()).add(chainInfo);

								// === Итераторный метод в конце цепочки: ^hash.meets.foreach[;v] ===
								// chain.last == "foreach" → v получает sourceVarKey="foreach_field:hash:meets"
								String lastInChain = chain.get(lastIdx);
								if (isIteratorMethodWithParams(lastInChain) && chainCp < len && text.charAt(chainCp) == '[') {
									int fParamOpen = chainCp;
									int fParamClose = findMatchingBracket(text, fParamOpen, len, '[');
									if (fParamClose > fParamOpen) {
										// Поле = chain[0..lastIdx-1], соединяем через .
										StringBuilder fieldPath = new StringBuilder();
										for (int fi = 0; fi < lastIdx; fi++) {
											if (fi > 0) fieldPath.append('.');
											fieldPath.append(chain.get(fi));
										}
										String fSourceVarKey = "foreach_field:" + chainIndexKey + ":" + fieldPath;
										registerIteratorParams(result, text, classBoundaries, methodBoundaries,
												fParamOpen, fParamClose, caretPos, fSourceVarKey,
												"^" + chainIndexKey + "." + fieldPath + "." + lastInChain);
									}
								}
							}
						}

						if (isIteratorMethodWithParams(methodAfterDot)
								&& mp < len && text.charAt(mp) == '[') {
							// Парсим [key;value]
							int paramOpen = mp;
							int paramClose = findMatchingBracket(text, paramOpen, len, '[');
							if (paramClose > paramOpen) {
								String sourceIndexKey = P3VariableFileIndex.buildIndexKey(caretPrefix, caretVarName);
								registerIteratorParams(result, text, classBoundaries, methodBoundaries,
										paramOpen, paramClose, caretPos, "foreach:" + sourceIndexKey,
										"^" + sourceIndexKey + "." + methodAfterDot);
							}
						}
					}
				}
			}

			// Ищем $ — начало переменной
			if (ch != '$') continue;

			// Проверяем экранирование: ^$
			if (ru.artlebedev.parser3.lexer.Parser3LexerUtils.isEscapedByCaret(text, i)) continue;

			int dollarPos = i;

			// Проверяем комментарий (^rem и #-comment)
			if (ru.artlebedev.parser3.utils.Parser3ClassUtils.isOffsetInComment(text, dollarPos)) continue;

			ReadChainHeadInfo readChainHead = parseReadChainHead(text, dollarPos, len);
			if (readChainHead == null) continue;

			int pos = readChainHead.afterVarPos;
			String prefix = readChainHead.prefix;
			String varName = readChainHead.varName;
			int chainLimit = readChainHead.chainLimit;
			int terminatorLimit = readChainHead.terminatorLimit;

			// === Пропускаем пробелы ===
			while (pos < chainLimit && Character.isWhitespace(text.charAt(pos))) pos++;

			// === Проверяем цепочку точек: $data.key1.key2[...] — добавление ключей в хеш ===
			if (pos < chainLimit && text.charAt(pos) == '.') {
				// Парсим цепочку: .key1.key2...keyN[...]
				List<String> dotChain = new ArrayList<>();
				List<Integer> dotChainOffsets = new ArrayList<>(); // позиции ключей в тексте
				int chainPos = pos;
				while (chainPos < chainLimit && text.charAt(chainPos) == '.') {
					chainPos++; // после .
					DotChainSegmentInfo segmentInfo = parseDotChainSegment(text, chainPos, chainLimit, len);
					if (segmentInfo == null) break;
					chainPos = segmentInfo.nextPos;
					dotChain.add(segmentInfo.keyName);
					dotChainOffsets.add(segmentInfo.keyOffset);
				}
				// После цепочки ожидаем скобку [...]
				if (!dotChain.isEmpty()) {
					// Проверяем стоп-символ ДО пропуска пробелов — именно здесь заканчивается переменная.
					// Точка тоже не является стоп-символом — цепочка продолжается.
					boolean hasStopChar = chainPos >= chainLimit
							|| (!isIdentChar(text.charAt(chainPos)) && text.charAt(chainPos) != '.');
					int lookaheadPos = chainPos;
					// Пробельный lookahead нужен только для проверки терминатора/скобки.
					// Основной курсор сканирования ниже не сдвигаем, иначе можно перепрыгнуть
					// через следующий $ на новой строке и вообще не распарсить следующую переменную.
					while (lookaheadPos < chainLimit && Character.isWhitespace(text.charAt(lookaheadPos))) lookaheadPos++;
					if (lookaheadPos < chainLimit && (text.charAt(lookaheadPos) == '[' || text.charAt(lookaheadPos) == '(' || text.charAt(lookaheadPos) == '{')) {
						// Парсим содержимое скобок — определяем тип значения
						ParsedValue valuePv = parseValueContent(text, lookaheadPos, len);

						String indexKey = P3VariableFileIndex.buildIndexKey(prefix, varName);
						String ownerClass = ru.artlebedev.parser3.utils.Parser3ClassUtils.findOwnerClass(dollarPos, classBoundaries);
						ru.artlebedev.parser3.utils.Parser3ClassUtils.MethodBoundary ownerMethodBoundary =
								ru.artlebedev.parser3.utils.Parser3ClassUtils.findOwnerMethod(dollarPos, methodBoundaries);
						String ownerMethod = ownerMethodBoundary != null ? ownerMethodBoundary.name : null;
						boolean isLocal = P3VariableFileIndex.computeIsLocal(prefix, varName, ownerClass, ownerMethodBoundary, text, classBoundaries);

						// Строим вложенную структуру hashKeys снизу вверх
						// $data.key1.key2.key3[$.inner[]] → data → {key1 → hash{key2 → hash{key3 → hash{inner}}}}
						int lastIdx = dotChain.size() - 1;
						HashEntryInfo leafInfo = parsedValueToHashEntry(valuePv, dotChainOffsets.get(lastIdx));
						leafInfo = enrichHashEntryFromPreviousLocalSource(
								leafInfo, valuePv, result, ownerClass, ownerMethod, dollarPos);
						for (int ci = lastIdx; ci >= 1; ci--) {
							Map<String, HashEntryInfo> wrapper = new LinkedHashMap<>();
							wrapper.put(dotChain.get(ci), leafInfo);
							leafInfo = new HashEntryInfo("hash", null, wrapper, dotChainOffsets.get(ci - 1));
						}

						// Добавляем первый ключ цепочки к переменной
						String firstKey = dotChain.get(0);
						Map<String, HashEntryInfo> addedKeys = new LinkedHashMap<>();
						addedKeys.put(firstKey, leafInfo);

						P3VariableFileIndex.VariableTypeInfo addInfo = new P3VariableFileIndex.VariableTypeInfo(
								dollarPos, "hash", ownerClass, ownerMethod,
								null, null, prefix, isLocal,
								null, null, addedKeys, null, true);

						if (DEBUG) System.out.println("[P3VarParser] " + indexKey + ".chain=" + dotChain
								+ " → hash addedKeys=" + addedKeys.keySet());

						result.computeIfAbsent(indexKey, k -> new ArrayList<>()).add(addInfo);
						i = chainPos; // перемещаем указатель только до конца сегмента, не через lookahead
						continue;
					}
					if (hasStopChar && Parser3VariableTailUtils.hasCompletedReadChainTerminator(text, lookaheadPos, terminatorLimit, dotChain.size())) {
						int lastIdx2 = dotChain.size() - 1;
						HashEntryInfo leafInfo2 = new HashEntryInfo(P3VariableFileIndex.UNKNOWN_TYPE, null, null, -1);
						for (int ci = lastIdx2; ci >= 1; ci--) {
							Map<String, HashEntryInfo> wrapper2 = new LinkedHashMap<>();
							wrapper2.put(dotChain.get(ci), leafInfo2);
							leafInfo2 = new HashEntryInfo("hash", null, wrapper2, -1);
						}
						Map<String, HashEntryInfo> addedKeys2 = new LinkedHashMap<>();
						addedKeys2.put(dotChain.get(0), leafInfo2);
						String indexKey2 = P3VariableFileIndex.buildIndexKey(prefix, varName);
						String ownerClass2 = ru.artlebedev.parser3.utils.Parser3ClassUtils.findOwnerClass(dollarPos, classBoundaries);
						ru.artlebedev.parser3.utils.Parser3ClassUtils.MethodBoundary ownerMethodBoundary2 =
								ru.artlebedev.parser3.utils.Parser3ClassUtils.findOwnerMethod(dollarPos, methodBoundaries);
						String ownerMethod2 = ownerMethodBoundary2 != null ? ownerMethodBoundary2.name : null;
						boolean isLocal2 = P3VariableFileIndex.computeIsLocal(prefix, varName, ownerClass2, ownerMethodBoundary2, text, classBoundaries);
						P3VariableFileIndex.VariableTypeInfo addInfo2 = new P3VariableFileIndex.VariableTypeInfo(
								dollarPos, "hash", ownerClass2, ownerMethod2,
								null, null, prefix, isLocal2,
								null, null, addedKeys2, null, true);

						if (DEBUG) System.out.println("[P3VarParser] " + indexKey2 + ".readChain=" + dotChain
								+ " → hash addedKeys=" + addedKeys2.keySet());

						result.computeIfAbsent(indexKey2, k -> new ArrayList<>()).add(addInfo2);
					}
					i = chainPos;
					continue;
				}
			}

			// === Ожидаем скобку: [ ( { ===
			if (pos >= len) continue;
			char bracket = text.charAt(pos);
			if (bracket != '[' && bracket != '(' && bracket != '{') continue;

			int bracketPos = pos;

			// Нашли присваивание: $prefix?varName[...]
			int offset = dollarPos;

			// === ЕДИНАЯ ФУНКЦИЯ парсинга содержимого скобок ===
			ParsedValue pv = parseValueContent(text, bracketPos, len);

			// Создаём VariableTypeInfo из ParsedValue
			P3VariableFileIndex.VariableTypeInfo info = makeInfoFromParsedValue(
					offset, prefix, varName, pv, classBoundaries, methodBoundaries, text);

			String indexKey = P3VariableFileIndex.buildIndexKey(prefix, varName);

			if (DEBUG) System.out.println("[P3VarParser] " + indexKey + " → " + info.className
					+ (info.methodName != null ? " method=" + info.methodName : "")
					+ (info.targetClassName != null ? " target=" + info.targetClassName : "")
					+ (info.columns != null ? " columns=" + info.columns : "")
					+ (info.sourceVarKey != null ? " source=" + info.sourceVarKey : "")
					+ (info.hashKeys != null ? " hashKeys=" + info.hashKeys.keySet() : ""));

			result.computeIfAbsent(indexKey, k -> new ArrayList<>()).add(info);

			// Не сдвигаем i — следующая итерация начнётся с i+1
		}

		return result;
	}

	private static @NotNull HashEntryInfo enrichHashEntryFromPreviousLocalSource(
			@NotNull HashEntryInfo fallback,
			@NotNull ParsedValue value,
			@NotNull Map<String, List<P3VariableFileIndex.VariableTypeInfo>> parsedVariables,
			@Nullable String ownerClass,
			@Nullable String ownerMethod,
			int assignmentOffset
	) {
		if (value.sourceVarKey == null || value.sourceVarKey.isEmpty()) return fallback;

		P3ResolvedValue resolved = resolvePreviousLocalValue(
				value.sourceVarKey, parsedVariables, ownerClass, ownerMethod, assignmentOffset, new HashSet<>());
		if (resolved == null) return fallback;

		return new HashEntryInfo(
				chooseRicherClassName(fallback.className, resolved.className),
				resolved.columns != null ? resolved.columns : fallback.columns,
				P3VariableEffectiveShape.mergeHashEntryMaps(fallback.nestedKeys, resolved.hashKeys),
				fallback.offset,
				null,
				resolved.sourceVarKey != null ? resolved.sourceVarKey : fallback.sourceVarKey,
				resolved.methodName != null ? resolved.methodName : fallback.methodName,
				resolved.targetClassName != null ? resolved.targetClassName : fallback.targetClassName,
				resolved.receiverVarKey != null ? resolved.receiverVarKey : fallback.receiverVarKey
		);
	}

	private static @Nullable P3ResolvedValue resolvePreviousLocalValue(
			@NotNull String sourceVarKey,
			@NotNull Map<String, List<P3VariableFileIndex.VariableTypeInfo>> parsedVariables,
			@Nullable String ownerClass,
			@Nullable String ownerMethod,
			int assignmentOffset,
			@NotNull Set<String> visited
	) {
		String decodedSourceVarKey = P3VariableEffectiveShape.decodeHashSourceVarKey(sourceVarKey);
		int sourceLookupOffset = P3VariableEffectiveShape.decodeHashSourceLookupOffset(sourceVarKey, assignmentOffset);
		String visitKey = decodedSourceVarKey + "\n" + sourceLookupOffset + "\n" + ownerClass + "\n" + ownerMethod;
		if (!visited.add(visitKey)) return null;

		LocalHashPathParts pathParts = parseLocalHashPathParts(decodedSourceVarKey);
		if (pathParts != null) {
			P3ResolvedValue root = resolvePreviousLocalValue(
					pathParts.rootVarKey, parsedVariables, ownerClass, ownerMethod, sourceLookupOffset, visited);
			return root != null ? resolveHashPathValue(root, pathParts.fieldPath) : null;
		}

		List<P3VariableFileIndex.VariableTypeInfo> infos = parsedVariables.get(decodedSourceVarKey);
		if (infos == null || infos.isEmpty()) return null;

		P3VariableEffectiveShape shape = new P3VariableEffectiveShape(decodedSourceVarKey, false);
		for (P3VariableFileIndex.VariableTypeInfo info : infos) {
			if (info.offset >= sourceLookupOffset) continue;
			if (!Objects.equals(info.ownerClass, ownerClass)) continue;
			if (!Objects.equals(info.ownerMethod, ownerMethod)) continue;
			shape.add(info);
		}

		P3ResolvedValue value = shape.toResolvedValue();
		if (value == null) return null;
		if (value.sourceVarKey != null && !value.sourceVarKey.isEmpty()) {
			P3ResolvedValue source = resolvePreviousLocalValue(
					value.sourceVarKey, parsedVariables, ownerClass, ownerMethod, sourceLookupOffset, visited);
			if (source != null) {
				value = mergeResolvedValues(source, value);
			}
		}
		return resolvePreviousLocalHashSourceVars(value, parsedVariables, ownerClass, ownerMethod, sourceLookupOffset, visited);
	}

	private static @NotNull P3ResolvedValue resolvePreviousLocalHashSourceVars(
			@NotNull P3ResolvedValue value,
			@NotNull Map<String, List<P3VariableFileIndex.VariableTypeInfo>> parsedVariables,
			@Nullable String ownerClass,
			@Nullable String ownerMethod,
			int assignmentOffset,
			@NotNull Set<String> visited
	) {
		if (value.hashSourceVars == null || value.hashSourceVars.isEmpty()) return value;

		Map<String, HashEntryInfo> merged = null;
		for (String hashSourceVar : value.hashSourceVars) {
			String decoded = P3VariableEffectiveShape.decodeHashSourceVarKey(hashSourceVar);
			if (decoded.startsWith("hashop\n")) continue;
			int lookupOffset = P3VariableEffectiveShape.decodeHashSourceLookupOffset(hashSourceVar, assignmentOffset);
			P3ResolvedValue source = resolvePreviousLocalValue(
					hashSourceVar, parsedVariables, ownerClass, ownerMethod, lookupOffset, visited);
			if (source != null && source.hashKeys != null && !source.hashKeys.isEmpty()) {
				merged = P3VariableEffectiveShape.mergeHashEntryMaps(merged, source.hashKeys);
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

	private static @Nullable P3ResolvedValue resolveHashPathValue(
			@NotNull P3ResolvedValue root,
			@NotNull String fieldPath
	) {
		if (root.hashKeys == null || root.hashKeys.isEmpty()) return null;
		String[] parts = fieldPath.split("\\.");
		Map<String, HashEntryInfo> currentKeys = root.hashKeys;
		HashEntryInfo currentEntry = null;
		for (int i = 0; i < parts.length; i++) {
			if (parts[i].isEmpty()) return null;
			currentEntry = P3VariableEffectiveShape.resolveHashEntry(currentKeys, parts[i]);
			if (currentEntry == null) return null;
			if (i < parts.length - 1) {
				currentKeys = currentEntry.nestedKeys;
				if (currentKeys == null || currentKeys.isEmpty()) return null;
			}
		}
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

	private static @Nullable List<String> mergeStringLists(
			@Nullable List<String> base,
			@Nullable List<String> overlay
	) {
		if (base == null || base.isEmpty()) return overlay;
		if (overlay == null || overlay.isEmpty()) return base;
		List<String> result = new ArrayList<>(base);
		for (String value : overlay) {
			if (!result.contains(value)) result.add(value);
		}
		return result;
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

	private static @Nullable LocalHashPathParts parseLocalHashPathParts(@NotNull String sourceVarKey) {
		if (sourceVarKey.startsWith("foreach:") || sourceVarKey.startsWith("foreach_field:")) return null;

		int rootEnd;
		if (sourceVarKey.startsWith("self.")) {
			rootEnd = findDotAfterIdentifier(sourceVarKey, 5);
		} else if (sourceVarKey.startsWith("MAIN:")) {
			rootEnd = findDotAfterIdentifier(sourceVarKey, 5);
		} else {
			rootEnd = findDotAfterIdentifier(sourceVarKey, 0);
		}
		if (rootEnd < 0 || rootEnd >= sourceVarKey.length() - 1) return null;

		return new LocalHashPathParts(sourceVarKey.substring(0, rootEnd), sourceVarKey.substring(rootEnd + 1));
	}

	private static int findDotAfterIdentifier(@NotNull String text, int start) {
		if (start >= text.length()) return -1;
		int pos = start;
		while (pos < text.length() && isIdentChar(text.charAt(pos))) pos++;
		return pos < text.length() && text.charAt(pos) == '.' ? pos : -1;
	}

	private static final class LocalHashPathParts {
		final @NotNull String rootVarKey;
		final @NotNull String fieldPath;

		private LocalHashPathParts(@NotNull String rootVarKey, @NotNull String fieldPath) {
			this.rootVarKey = rootVarKey;
			this.fieldPath = fieldPath;
		}
	}

	private static @Nullable ReadChainHeadInfo parseReadChainHead(@NotNull String text, int dollarPos, int textLen) {
		int pos = dollarPos + 1;
		int chainLimit = textLen;
		int terminatorLimit = textLen;

		if (pos < textLen && text.charAt(pos) == '{') {
			int closeBrace = findMatchingBracket(text, pos, textLen, '{');
			if (closeBrace < 0) return null;
			pos++;
			chainLimit = closeBrace;
			terminatorLimit = closeBrace + 1;
		}

		String prefix = null;
		if (pos + 5 <= chainLimit && text.substring(pos, pos + 5).equals("self.")) {
			prefix = "self.";
			pos += 5;
		} else if (pos + 5 <= chainLimit && text.substring(pos, pos + 5).equals("MAIN:")) {
			prefix = "MAIN:";
			pos += 5;
		}

		int nameStart = pos;
		if (pos >= chainLimit) return null;
		char firstChar = text.charAt(pos);
		if (!Parser3IdentifierUtils.isIdentifierStart(firstChar)) return null;
		pos++;
		while (pos < chainLimit && isIdentChar(text.charAt(pos))) pos++;

		return new ReadChainHeadInfo(
				prefix,
				text.substring(nameStart, pos),
				pos,
				chainLimit,
				terminatorLimit
		);
	}

	private static @Nullable DotChainSegmentInfo parseDotChainSegment(
			@NotNull String text,
			int segmentStart,
			int chainLimit,
			int textLen
	) {
		if (segmentStart >= chainLimit) return null;

		char ch = text.charAt(segmentStart);
		if (ch == '[') {
			int closeBracket = findMatchingBracket(text, segmentStart, textLen, '[');
			if (closeBracket < 0 || closeBracket > chainLimit) return null;
			String literalKey = tryExtractLiteralBracketKey(text, segmentStart + 1, closeBracket);
			if (literalKey != null) {
				return new DotChainSegmentInfo(literalKey, segmentStart + 1, closeBracket + 1);
			}
			if (DEBUG) System.out.println("[P3VarParser] dot-chain dynamic key → wildcard *");
			return new DotChainSegmentInfo("*", segmentStart, closeBracket + 1);
		}

		if (ch == '(' || ch == '{') {
			int dynClose = findMatchingBracket(text, segmentStart, textLen, ch);
			if (dynClose < 0 || dynClose > chainLimit) return null;
			if (DEBUG) System.out.println("[P3VarParser] dot-chain dynamic key → wildcard *");
			return new DotChainSegmentInfo("*", segmentStart, dynClose + 1);
		}

		if (!Parser3IdentifierUtils.isIdentifierStart(ch)) return null;
		int pos = segmentStart + 1;
		while (pos < chainLimit && Parser3IdentifierUtils.isVariableIdentifierChar(text.charAt(pos))) pos++;
		return new DotChainSegmentInfo(text.substring(segmentStart, pos), segmentStart, pos);
	}

	private static @Nullable String tryExtractLiteralBracketKey(@NotNull String text, int from, int to) {
		String key = text.substring(from, to).trim();
		if (key.isEmpty()) return null;

		for (int i = 0; i < key.length(); i++) {
			char ch = key.charAt(i);
			if (ch == '$' || ch == '^'
					|| ch == '[' || ch == ']'
					|| ch == '(' || ch == ')'
					|| ch == '{' || ch == '}'
					|| ch == ';'
					|| ch == '\n' || ch == '\r') {
				return null;
			}
		}

		return key;
	}

	private static boolean isIteratorMethodWithParams(@Nullable String methodName) {
		return "foreach".equals(methodName)
				|| "for".equals(methodName)
				|| "select".equals(methodName)
				|| "sort".equals(methodName);
	}

	private static void registerIteratorParams(
			@NotNull Map<String, List<P3VariableFileIndex.VariableTypeInfo>> result,
			@NotNull String text,
			@NotNull List<ru.artlebedev.parser3.utils.Parser3ClassUtils.ClassBoundary> classBoundaries,
			@NotNull List<ru.artlebedev.parser3.utils.Parser3ClassUtils.MethodBoundary> methodBoundaries,
			int paramOpen,
			int paramClose,
			int ownerOffset,
			@NotNull String valueSourceVarKey,
			@NotNull String debugContext
	) {
		String paramContent = text.substring(paramOpen + 1, paramClose).trim();
		String[] params = paramContent.split(";", -1);
		if (params.length < 2) return;

		String keyParam = params[0].trim();
		String valueParam = params[1].trim();

		int keyOffset = paramOpen + 1;
		while (keyOffset < paramClose && Character.isWhitespace(text.charAt(keyOffset))) keyOffset++;
		int semicolonPos = text.indexOf(';', paramOpen + 1);
		if (semicolonPos < 0 || semicolonPos >= paramClose) return;
		int valueOffset = semicolonPos + 1;
		while (valueOffset < paramClose && Character.isWhitespace(text.charAt(valueOffset))) valueOffset++;

		String ownerClass = ru.artlebedev.parser3.utils.Parser3ClassUtils.findOwnerClass(ownerOffset, classBoundaries);
		ru.artlebedev.parser3.utils.Parser3ClassUtils.MethodBoundary ownerMethodBoundary =
				ru.artlebedev.parser3.utils.Parser3ClassUtils.findOwnerMethod(ownerOffset, methodBoundaries);
		String ownerMethod = ownerMethodBoundary != null ? ownerMethodBoundary.name : null;

		if (!keyParam.isEmpty()) {
			boolean isLocalKey = P3VariableFileIndex.computeIsLocal(null, keyParam, ownerClass, ownerMethodBoundary, text, classBoundaries);
			P3VariableFileIndex.VariableTypeInfo keyInfo = new P3VariableFileIndex.VariableTypeInfo(
					keyOffset, P3VariableFileIndex.UNKNOWN_TYPE, ownerClass, ownerMethod,
					null, null, null, isLocalKey, null, null, null, null);
			result.computeIfAbsent(P3VariableFileIndex.buildIndexKey(null, keyParam), k -> new ArrayList<>()).add(keyInfo);
		}

		if (!valueParam.isEmpty()) {
			boolean isLocalVal = P3VariableFileIndex.computeIsLocal(null, valueParam, ownerClass, ownerMethodBoundary, text, classBoundaries);
			P3VariableFileIndex.VariableTypeInfo valInfo = new P3VariableFileIndex.VariableTypeInfo(
					valueOffset, P3VariableFileIndex.UNKNOWN_TYPE, ownerClass, ownerMethod,
					null, null, null, isLocalVal, null, valueSourceVarKey, null, null);
			String valIndexKey = P3VariableFileIndex.buildIndexKey(null, valueParam);
			result.computeIfAbsent(valIndexKey, k -> new ArrayList<>()).add(valInfo);

			if (DEBUG) System.out.println("[P3VarParser] " + debugContext
					+ " → key=" + keyParam + "(off=" + keyOffset + ")"
					+ " value=" + valIndexKey + "(off=" + valueOffset + ")"
					+ " source=" + valueSourceVarKey);
		}
	}

	// === Единая функция парсинга содержимого скобок ===

	/**
	 * ЕДИНСТВЕННЫЙ ИСТОЧНИК ИСТИНЫ для парсинга содержимого скобок.
	 *
	 * Вызывается для:
	 * - $var[...] — определение типа переменной
	 * - $.key[...] — определение типа значения ключа хеша (рекурсивно)
	 *
	 * Документация Parser3 (стр.41, Конструкции языка):
	 * $имя[$.ключ1[значение] $.ключ2[значение] ... $.ключN[значение]] — хеш-литерал
	 * $имя[^класс::конструктор[...]] — конструктор класса
	 *
	 * @param text полный текст файла
	 * @param openBracketPos позиция открывающей скобки [ или (
	 * @param textLen длина текста
	 * @return результат парсинга
	 */
	static @NotNull ParsedValue parseValueContent(
			@NotNull String text, int openBracketPos, int textLen) {

		char bracket = text.charAt(openBracketPos);
		if (bracket != '[' && bracket != '(' && bracket != '{') {
			return ParsedValue.unknown();
		}

		// Находим парную закрывающую скобку
		int closeBracket = findMatchingBracket(text, openBracketPos, textLen, bracket);
		if (closeBracket < 0) {
			return ParsedValue.unknown();
		}

		int from = openBracketPos + 1;
		int to = closeBracket;

		// Пропускаем пробелы
		int pos = from;
		while (pos < to && Character.isWhitespace(text.charAt(pos))) pos++;

		if (pos >= to) {
			return ParsedValue.unknown();
		}

		// === Проверяем массив-литерал: ; на верхнем уровне ===
		// Документация Parser3 (стр.42): $имя[значение1;значение2;...;значениеN]
		// Точка с запятой на верхнем уровне (вне вложенных скобок) → array
		// Проверяем ДО хеша, т.к. $arr[val;$.key[v]] — это массив, не хеш
		if (containsTopLevelSemicolon(text, from, to)) {
			// Парсим каждый элемент массива — определяем тип
			// Ключи hashKeys = "0", "1", "2"... → переиспользуем инфраструктуру хешей
			Map<String, HashEntryInfo> elementTypes = parseArrayElements(text, from, to, textLen);
			if (DEBUG) System.out.println("[P3VarParser.parseValueContent] array literal"
					+ (elementTypes != null ? " elements=" + elementTypes.keySet() : ""));
					return new ParsedValue("array", null, null, elementTypes, null, null, null, null);
		}

		// === Проверяем хеш-литерал: наличие $.key где-нибудь в содержимом ===
		// Содержимое может начинаться с $varName или ^hash::create[...], а потом $.key[...]
		if (containsHashKey(text, from, to)) {
			HashParseResult hpr = parseHashKeys(text, from, to, textLen);
			if ((hpr.keys != null && !hpr.keys.isEmpty()) || (hpr.sourceVars != null && !hpr.sourceVars.isEmpty())) {
				Map<String, HashEntryInfo> keys = hpr.keys != null ? hpr.keys : new LinkedHashMap<>();
				if (DEBUG) System.out.println("[P3VarParser.parseValueContent] hash literal, keys=" + keys.keySet()
						+ (hpr.sourceVars != null ? " sourceVars=" + hpr.sourceVars : ""));
				return ParsedValue.hash(keys, hpr.sourceVars);
			}
		}

		// === Проверяем вызов: ^ ===
		if (text.charAt(pos) == '^' && bracket == '[') {
			return parseCaretContent(text, pos, to, textLen, findAssignmentOffsetForValue(text, openBracketPos));
		}

		// === Проверяем ссылку на переменную: $varName как единственное содержимое ===
		// $copy[$original] / {$original} → тип copy = тип original (ленивый резолв через sourceVarKey).
		// В круглых скобках это скалярное выражение, оно не должно переносить hash-форму источника.
		if (text.charAt(pos) == '$') {
			String refVar = extractSoleVarReference(text, pos, to);
			if (refVar != null && bracket != '(') {
				if (DEBUG) System.out.println("[P3VarParser.parseValueContent] var reference → sourceVarKey=" + refVar);
				return new ParsedValue(P3VariableFileIndex.UNKNOWN_TYPE, null, refVar);
			}
		}

		if (bracket == '[' && containsTemplateExpression(text, pos, to)) {
			// Шаблон с Parser3-выражением без hash/array/call/reference-формы даёт строковое значение.
			return new ParsedValue("string");
		}

		return ParsedValue.unknown();
	}

	private static @NotNull ParsedValue parseValueContentWithPreviousLocalSources(
			@NotNull String text,
			int openBracketPos,
			int textLen,
			@NotNull Map<String, List<P3VariableFileIndex.VariableTypeInfo>> parsedVariables,
			@Nullable String ownerClass,
			@Nullable String ownerMethod,
			int assignmentOffset
	) {
		char bracket = text.charAt(openBracketPos);
		if (bracket != '[' && bracket != '(' && bracket != '{') {
			return ParsedValue.unknown();
		}

		int closeBracket = findMatchingBracket(text, openBracketPos, textLen, bracket);
		if (closeBracket < 0) {
			return ParsedValue.unknown();
		}

		int from = openBracketPos + 1;
		int to = closeBracket;
		if (!containsTopLevelSemicolon(text, from, to) && containsHashKey(text, from, to)) {
			HashParseResult hpr = parseHashKeys(
					text, from, to, textLen, parsedVariables, ownerClass, ownerMethod, assignmentOffset);
			if ((hpr.keys != null && !hpr.keys.isEmpty()) || (hpr.sourceVars != null && !hpr.sourceVars.isEmpty())) {
				Map<String, HashEntryInfo> keys = hpr.keys != null ? hpr.keys : new LinkedHashMap<>();
				return ParsedValue.hash(keys, hpr.sourceVars);
			}
		}

		return parseValueContent(text, openBracketPos, textLen);
	}

	/**
	 * Парсит содержимое после ^ внутри скобок.
	 * Определяет тип: конструктор, статический метод, вызов метода, вызов метода объекта.
	 *
	 * @param text полный текст
	 * @param caretPos позиция символа ^
	 * @param to конец содержимого (позиция закрывающей скобки)
	 * @param textLen длина текста
	 */
	private static @NotNull ParsedValue parseCaretContent(
			@NotNull String text, int caretPos, int to, int textLen, int sourceSnapshotOffset) {

		int pos = caretPos + 1; // после ^
		// Пропускаем пробелы
		while (pos < to && Character.isWhitespace(text.charAt(pos))) pos++;

		// Парсим имя после ^
		int callNameStart = pos;
		while (pos < to && isIdentChar(text.charAt(pos))) pos++;
		if (pos == callNameStart) {
			return ParsedValue.unknown();
		}
		String callName = text.substring(callNameStart, pos);

		// Пропускаем пробелы
		while (pos < to && Character.isWhitespace(text.charAt(pos))) pos++;

		if (pos >= to) {
			return ParsedValue.unknown();
		}

		if (text.charAt(pos) == ':') {
			// :: или :
			pos++;
			boolean isDoubleColon = (pos < to && text.charAt(pos) == ':');
			if (isDoubleColon) pos++;

			if (isDoubleColon) {
				// ^ClassName:: — конструктор
				return parseConstructor(text, callName, callNameStart, pos, to, textLen);
			} else {
				// ^name:method — статический вызов (одно двоеточие)
				return parseStaticMethod(text, callName, callNameStart, pos, to, textLen);
			}
		} else if (text.charAt(pos) == '[' || text.charAt(pos) == '(' || text.charAt(pos) == '{') {
			// ^callName[ / ^callName( / ^callName{ — вызов метода
			boolean isBuiltin = ru.artlebedev.parser3.lang.Parser3BuiltinMethods.isBuiltinClass(callName);
			boolean isSystemOp = P3VariableFileIndex.isSystemOperator(callName);

			if ("if".equals(callName)) {
				return parseIfResult(text, pos, to, textLen);
			}

			if (!isBuiltin && !isSystemOp) {
				// ^userMethod[...] — тип из документации
				return ParsedValue.methodCall(callName, null);
			}
		} else if (text.charAt(pos) == '.') {
			// ^varName.method[/(/{ — вызов метода объекта
			return parseChainedDotMethodCall(text, callName, pos, to, textLen, sourceSnapshotOffset);
		}

		return ParsedValue.unknown();
	}

	private static int findAssignmentOffsetForValue(@NotNull String text, int openBracketPos) {
		for (int pos = openBracketPos - 1; pos >= 0; pos--) {
			char ch = text.charAt(pos);
			if (ch == '$') return pos;
			if (ch == '\n' || ch == '\r' || ch == ';' || ch == '{' || ch == '}') break;
		}
		return openBracketPos;
	}

	/**
	 * Parser3-оператор ^if может сам быть значением: $a[^if(1){$.k[y]}{$.k[n]}].
	 * Для completion важно сохранить самую богатую hash-структуру из веток.
	 */
	private static @NotNull ParsedValue parseIfResult(
			@NotNull String text, int firstBracketPos, int to, int textLen) {
		int pos = firstBracketPos;
		Map<String, HashEntryInfo> mergedKeys = new LinkedHashMap<>();
		List<String> mergedSourceVars = new ArrayList<>();
		boolean hasHashShape = false;

		while (pos < to) {
			while (pos < to && Character.isWhitespace(text.charAt(pos))) pos++;
			if (pos >= to) break;

			char ch = text.charAt(pos);
			if (ch == '(' || ch == '[') {
				int close = findMatchingBracket(text, pos, textLen, ch);
				if (close <= pos) break;
				pos = close + 1;
				continue;
			}

			if (ch != '{') break;

			ParsedValue branch = parseIfBranchValue(text, pos, textLen);
			if (branch.hashKeys != null || branch.sourceVarKey != null
					|| (branch.hashSourceVars != null && !branch.hashSourceVars.isEmpty())) {
				hasHashShape = true;
				if (branch.hashKeys != null) {
					mergedKeys = P3VariableEffectiveShape.mergeHashEntryMaps(mergedKeys, branch.hashKeys);
				}
				if (branch.sourceVarKey != null && !mergedSourceVars.contains(branch.sourceVarKey)) {
					mergedSourceVars.add(branch.sourceVarKey);
				}
				if (branch.hashSourceVars != null) {
					for (String sourceVar : branch.hashSourceVars) {
						if (!mergedSourceVars.contains(sourceVar)) {
							mergedSourceVars.add(sourceVar);
						}
					}
				}
			}

			int close = findMatchingBracket(text, pos, textLen, '{');
			if (close <= pos) break;
			pos = close + 1;
		}

		if (hasHashShape) {
			return ParsedValue.hash(mergedKeys, mergedSourceVars.isEmpty() ? null : mergedSourceVars);
		}
		return ParsedValue.unknown();
	}

	private static @NotNull ParsedValue parseIfBranchValue(
			@NotNull String text, int openBracePos, int textLen) {
		int closeBrace = findMatchingBracket(text, openBracePos, textLen, '{');
		if (closeBrace <= openBracePos) {
			return ParsedValue.unknown();
		}

		if (containsHashKey(text, openBracePos + 1, closeBrace)) {
			HashParseResult hpr = parseHashKeys(text, openBracePos + 1, closeBrace, textLen);
			if ((hpr.keys != null && !hpr.keys.isEmpty()) || (hpr.sourceVars != null && !hpr.sourceVars.isEmpty())) {
				Map<String, HashEntryInfo> keys = hpr.keys != null ? hpr.keys : new LinkedHashMap<>();
				return ParsedValue.hash(keys, hpr.sourceVars);
			}
		}

		return parseValueContent(text, openBracePos, textLen);
	}

	/**
	 * Парсит конструктор: ^ClassName::create[...] / ^ClassName::sql{...}
	 */
	private static @NotNull ParsedValue parseConstructor(
			@NotNull String text, @NotNull String className, int classNameStart,
			int afterDoubleColon, int to, int textLen) {

		// Пропускаем пробелы
		int pos = afterDoubleColon;
		while (pos < to && Character.isWhitespace(text.charAt(pos))) pos++;

		// Парсим имя метода конструктора (create, sql, etc.)
		int ctorStart = pos;
		while (pos < to && isIdentChar(text.charAt(pos))) pos++;
		String ctorName = text.substring(ctorStart, pos);

		// Для hash::create/sql — проверяем содержимое
		if ("hash".equals(className)) {
			while (pos < to && Character.isWhitespace(text.charAt(pos))) pos++;

			// ^hash::sql{select id, name, value} — первая колонка = ключ, остальные = поля значения
			// Документация: https://www.parser.ru/docs/lang/hashsql.htm
			if ("sql".equals(ctorName) && pos < to && text.charAt(pos) == '{') {
				int closeBrace = findMatchingBracket(text, pos, textLen, '{');
				if (closeBrace > pos) {
					String sqlContent = text.substring(pos + 1, closeBrace);
					List<String> columns = P3VariableFileIndex.parseSqlSelectColumns(sqlContent);
					if (columns != null && columns.size() >= 2) {
						// Первая колонка — ключ хеша, остальные — поля значения (вложенный хеш)
						Map<String, HashEntryInfo> nestedKeys = new LinkedHashMap<>();
						for (int ci = 1; ci < columns.size(); ci++) {
							nestedKeys.put(columns.get(ci), new HashEntryInfo(P3VariableFileIndex.UNKNOWN_TYPE));
						}
						// Wildcard ключ "*" — для любого ключа хеша
						Map<String, HashEntryInfo> hashKeys = new LinkedHashMap<>();
						hashKeys.put("*", new HashEntryInfo("hash", null, nestedKeys));
						if (DEBUG) System.out.println("[P3VarParser.parseConstructor] ^hash::sql → wildcard keys, nested=" + nestedKeys.keySet());
						return ParsedValue.hash(hashKeys, null);
					}
				}
				return new ParsedValue("hash");
			}

			if (pos < to && (text.charAt(pos) == '[' || text.charAt(pos) == '(')) {
				// ^hash::create[...] — рекурсивно парсим содержимое
				ParsedValue inner = parseValueContent(text, pos, textLen);
				if (inner.hashKeys != null || (inner.hashSourceVars != null && !inner.hashSourceVars.isEmpty())) {
					if (DEBUG) System.out.println("[P3VarParser.parseConstructor] ^hash::create → hash"
							+ (inner.hashKeys != null ? " keys=" + inner.hashKeys.keySet() : "")
							+ (inner.hashSourceVars != null ? " sourceVars=" + inner.hashSourceVars : ""));
					Map<String, HashEntryInfo> keys = inner.hashKeys != null ? inner.hashKeys : new LinkedHashMap<>();
					return ParsedValue.hash(keys, inner.hashSourceVars);
				}
				// Fallback: содержимое = $varName (копирование хеша)
				// Документация: ^hash::create[$существующий_хеш] — копия
				int innerOpen = pos;
				int innerClose = findMatchingBracket(text, innerOpen, textLen, text.charAt(innerOpen));
				if (innerClose > innerOpen) {
					List<String> sourceVars = extractVarReferences(text, innerOpen + 1, innerClose);
					if (sourceVars != null && !sourceVars.isEmpty()) {
						if (DEBUG) System.out.println("[P3VarParser.parseConstructor] ^hash::create[$var] sourceVars=" + sourceVars);
						return ParsedValue.hash(new LinkedHashMap<>(), sourceVars);
					}
				}
			}
			// ^hash::create[] — пустой хеш (без ключей)
			return new ParsedValue("hash");
		}

		// Для array::create, array::copy, array::sql
		// Документация: ^array::create[эл1;эл2;...], ^array::copy[$существующий_массив]
		// https://www.parser.ru/docs/lang/arraysql.htm — ^array::sql{select id, name, value}
		if ("array".equals(className)) {
			while (pos < to && Character.isWhitespace(text.charAt(pos))) pos++;

			// ^array::sql{select id, name, value} — все колонки = поля каждого элемента
			if ("sql".equals(ctorName) && pos < to && text.charAt(pos) == '{') {
				int closeBrace = findMatchingBracket(text, pos, textLen, '{');
				if (closeBrace > pos) {
					String sqlContent = text.substring(pos + 1, closeBrace);
					List<String> columns = P3VariableFileIndex.parseSqlSelectColumns(sqlContent);
					if (columns != null && !columns.isEmpty()) {
						// Все колонки — поля каждого элемента массива (вложенный хеш)
						Map<String, HashEntryInfo> nestedKeys = new LinkedHashMap<>();
						for (String col : columns) {
							nestedKeys.put(col, new HashEntryInfo(P3VariableFileIndex.UNKNOWN_TYPE));
						}
						// Wildcard ключ "*" — для любого индекса массива
						Map<String, HashEntryInfo> hashKeys = new LinkedHashMap<>();
						hashKeys.put("*", new HashEntryInfo("hash", null, nestedKeys));
						if (DEBUG) System.out.println("[P3VarParser.parseConstructor] ^array::sql → wildcard keys, nested=" + nestedKeys.keySet());
						return new ParsedValue("array", null, null, hashKeys, null, null, null, null);
					}
				}
				return new ParsedValue("array");
			}

			if (pos < to && (text.charAt(pos) == '[' || text.charAt(pos) == '(')) {
				// Рекурсивно парсим содержимое — может быть массив-литерал или $varName
				ParsedValue inner = parseValueContent(text, pos, textLen);
				if ("array".equals(inner.className)) {
					// ^array::create[val1;val2] — элементы уже распарсены
					return inner;
				}
				if (inner.hashKeys != null || (inner.hashSourceVars != null && !inner.hashSourceVars.isEmpty())) {
					// ^array::copy[ $.1[a] $.2[b] ] — hash-литерал задаёт ключи массива.
					return new ParsedValue("array", null, null,
							inner.hashKeys != null ? inner.hashKeys : new LinkedHashMap<>(),
							inner.hashSourceVars, null, null, null);
				}
				if (inner.sourceVarKey != null) {
					// ^array::copy[$arr] → sourceVarKey для ленивого резолва
					if (DEBUG) System.out.println("[P3VarParser.parseConstructor] ^array::copy → sourceVarKey=" + inner.sourceVarKey);
					return new ParsedValue("array", null, inner.sourceVarKey);
				}
			}
			return new ParsedValue("array");
		}

		// Парсим колонки для table
		List<String> columns = null;
		String sourceVarKey = null;
		if ("table".equals(className)) {
			P3VariableFileIndex.ColumnsResult cr =
					P3VariableFileIndex.extractTableColumnsResult(text, classNameStart);
			columns = cr.columns;
			sourceVarKey = cr.sourceVarKey;
		}

		String builtinReturnType = P3VariableFileIndex.getBuiltinConstructorReturnType(className, ctorName);
		if (builtinReturnType != null) {
			return new ParsedValue(builtinReturnType, columns, sourceVarKey);
		}

		return new ParsedValue(className, columns, sourceVarKey);
	}

	/**
	 * Парсит статический метод: ^ClassName:method[...]
	 */
	private static @NotNull ParsedValue parseStaticMethod(
			@NotNull String text, @NotNull String callName, int callNameStart,
			int afterColon, int to, int textLen) {

		// Пропускаем пробелы
		int pos = afterColon;
		while (pos < to && Character.isWhitespace(text.charAt(pos))) pos++;

		// Парсим имя метода
		int methodStart = pos;
		while (pos < to && (isIdentChar(text.charAt(pos)) || text.charAt(pos) == '-')) pos++;
		String methodName = (pos > methodStart) ? text.substring(methodStart, pos) : null;

		if (methodName == null) {
			return ParsedValue.unknown();
		}

		boolean isBuiltin = ru.artlebedev.parser3.lang.Parser3BuiltinMethods.isBuiltinClass(callName);

		if (isBuiltin) {
			// ^builtinClass:method — статический метод встроенного класса
			String returnType = P3VariableFileIndex.getStaticMethodReturnType(callName, methodName);
			if (returnType != null) {
				List<String> columns = null;
				String sourceVarKey = null;
				if ("table".equals(returnType)) {
					P3VariableFileIndex.ColumnsResult cr =
							P3VariableFileIndex.extractTableColumnsResult(text, callNameStart);
					columns = cr.columns;
					sourceVarKey = cr.sourceVarKey;
				}
				return new ParsedValue(returnType, columns, sourceVarKey);
			}
			// returnType == null — не знаем тип
		} else {
			// ^UserClass:method — статический вызов пользовательского класса.
			// Имя класса в Parser3 может быть и в нижнем регистре, поэтому не
			// ограничиваемся эвристикой "с заглавной буквы".
			return ParsedValue.methodCall(methodName, callName);
		}

		return ParsedValue.unknown();
	}

	/**
	 * Парсит вызов метода объекта: ^varName.method[...]
	 * Обрабатывает select/sort (сохранение типа table) и split (новая table).
	 */
	private static @NotNull ParsedValue parseDotMethodCall(
			@NotNull String text, @NotNull String varName,
			int dotPos, int to, int textLen, int sourceSnapshotOffset) {

		int pos = dotPos + 1; // после .
		int dotMethodStart = pos;
		while (pos < to && isIdentChar(text.charAt(pos))) pos++;
		if (pos == dotMethodStart) {
			return ParsedValue.unknown();
		}

		String dotMethod = text.substring(dotMethodStart, pos);

		// Пропускаем пробелы
		while (pos < to && Character.isWhitespace(text.charAt(pos))) pos++;
		if (pos >= to || (text.charAt(pos) != '[' && text.charAt(pos) != '(' && text.charAt(pos) != '{')) {
			return ParsedValue.unknown();
		}

		if ("hash".equals(dotMethod) && (text.charAt(pos) == '[' || text.charAt(pos) == '(')) {
			String valueMode = extractTableHashValueMode(text, pos, textLen);
			Map<String, HashEntryInfo> hashKeys = new LinkedHashMap<>();
			return new ParsedValue("hash", null, null, hashKeys,
					java.util.Collections.singletonList(buildTableOperationSource("hash", varName, valueMode)),
					null, null, null);
		} else if ("array".equals(dotMethod) && (text.charAt(pos) == '[' || text.charAt(pos) == '(')) {
			String valueMode = extractTableArrayValueMode(text, pos, textLen);
			Map<String, HashEntryInfo> hashKeys = new LinkedHashMap<>();
			return new ParsedValue("array", null, null, hashKeys,
					java.util.Collections.singletonList(buildTableOperationSource("array", varName, valueMode)),
					null, null, null);
		} else if ("select".equals(dotMethod) || "sort".equals(dotMethod)) {
			if (DEBUG) System.out.println("[P3VarParser] ^" + varName + "." + dotMethod + " → source-preserving, sourceVarKey=" + varName);
			return new ParsedValue(P3VariableFileIndex.UNKNOWN_TYPE, null,
					P3VariableEffectiveShape.encodeHashSourceAtOffset(varName, sourceSnapshotOffset));
		} else if ("keys".equals(dotMethod) || "_keys".equals(dotMethod)) {
			String columnName = extractFirstTextParameter(text, pos, textLen, "key");
			if (DEBUG) System.out.println("[P3VarParser] ^" + varName + "." + dotMethod + " → table, columns=" + columnName);
			return new ParsedValue("table", java.util.Collections.singletonList(columnName), null);
		} else if ("union".equals(dotMethod) || "intersection".equals(dotMethod)) {
			String argVar = extractFirstVarParameter(text, pos, textLen);
			if (argVar != null) {
				if (DEBUG) System.out.println("[P3VarParser] ^" + varName + "." + dotMethod + " → hashop arg=" + argVar);
				return new ParsedValue("hash", null, buildHashOperationSource(dotMethod, varName, argVar));
			}
			return new ParsedValue("hash", null, buildHashOperationSource("copy", varName, null));
		} else if ("at".equals(dotMethod)) {
			String returnMode = extractHashAtReturnMode(text, pos, textLen);
			if ("key".equals(returnMode)) {
				if (DEBUG) System.out.println("[P3VarParser] ^" + varName + ".at → string key");
				return new ParsedValue("string");
			}
			if ("hash".equals(returnMode)) {
				if (DEBUG) System.out.println("[P3VarParser] ^" + varName + ".at → hash, sourceVarKey=" + varName);
				return new ParsedValue("hash", null,
						P3VariableEffectiveShape.encodeHashSourceAtOffset(varName, sourceSnapshotOffset));
			}
			if (DEBUG) System.out.println("[P3VarParser] ^" + varName + ".at → value, sourceVarKey=" + varName + ".*");
			return new ParsedValue(P3VariableFileIndex.UNKNOWN_TYPE, null,
					P3VariableEffectiveShape.encodeHashSourceAtOffset(varName + ".*", sourceSnapshotOffset));
		} else if ("split".equals(dotMethod)) {
			// ^str.split[разделитель;опции;имя_столбца] → table (или array при опции "a")
			// Опции: h = первый элемент — заголовок (колонки неизвестны), a = вернуть array
			String options = null;
			String columnName = "piece";
			boolean columnsKnown = true;
			if (text.charAt(pos) == '[') {
				int closePos = findMatchingBracket(text, pos, textLen, '[');
				if (closePos > pos) {
					String content = text.substring(pos + 1, closePos);
					String[] splitParams = splitSemicolonsTopLevel(content);
					if (splitParams.length >= 2) {
						options = splitParams[1].trim();
					}
					if (splitParams.length >= 3) {
						String customName = splitParams[2].trim();
						if (!customName.isEmpty()) {
							String resolvedName = resolveSimpleTextParameter(text, customName, sourceSnapshotOffset);
							if (resolvedName != null && !resolvedName.isEmpty()) {
								columnName = resolvedName;
							} else {
								columnsKnown = false;
							}
						}
					}
				}
			}
			// Опция "a" → array
			if (options != null && options.contains("a")) {
				if (DEBUG) System.out.println("[P3VarParser] ^" + varName + ".split → array (option a)");
				return new ParsedValue("array");
			}
			// Опция "h" → колонки неизвестны (заголовок из данных)
			if (options != null && options.contains("h")) {
				if (DEBUG) System.out.println("[P3VarParser] ^" + varName + ".split → table, no columns (option h)");
				return new ParsedValue("table", null, null);
			}
			if (!columnsKnown) {
				if (DEBUG) System.out.println("[P3VarParser] ^" + varName + ".split → table, dynamic column");
				return new ParsedValue("table", null, null);
			}
			List<String> columns = java.util.Collections.singletonList(columnName);
			if (DEBUG) System.out.println("[P3VarParser] ^" + varName + ".split → table, columns=" + columns);
			return new ParsedValue("table", columns, null);
		} else if ("columns".equals(dotMethod)) {
			// ^list.columns[] → table с колонкой "column"
			// ^list.columns[xxx] → table с колонкой "xxx"
			String colName = "column";
			if (text.charAt(pos) == '[') {
				int closePos = findMatchingBracket(text, pos, textLen, '[');
				if (closePos > pos) {
					String content = text.substring(pos + 1, closePos).trim();
					if (!content.isEmpty()) {
						colName = content;
					}
				}
			}
			List<String> cols = java.util.Collections.singletonList(colName);
			if (DEBUG) System.out.println("[P3VarParser] ^" + varName + ".columns → table, columns=" + cols);
			return new ParsedValue("table", cols, null);
		}

		// === Пользовательские шаблоны SQL: ^oSql.hash{SQL}, ^oSql.array{SQL}, ^oSql.table{SQL} ===
		// Обрабатываем их только если префикс явно разрешён в настройках SQL injection.
		if (text.charAt(pos) == '{' && isConfiguredUserSqlInjection(varName, dotMethod)) {
			int closeBrace = findMatchingBracket(text, pos, textLen, '{');
			if (closeBrace > pos) {
				String sqlContent = text.substring(pos + 1, closeBrace);
				List<String> sqlColumns = P3VariableFileIndex.parseSqlSelectColumns(sqlContent);

				if (dotMethod.contains("hash")) {
					// ^oSql.hash{SELECT id, name, value} — как ^hash::sql{}
					if (sqlColumns != null && sqlColumns.size() >= 2) {
						Map<String, HashEntryInfo> nestedKeys = new LinkedHashMap<>();
						for (int ci = 1; ci < sqlColumns.size(); ci++) {
							nestedKeys.put(sqlColumns.get(ci), new HashEntryInfo(P3VariableFileIndex.UNKNOWN_TYPE));
						}
						Map<String, HashEntryInfo> hashKeys = new LinkedHashMap<>();
						hashKeys.put("*", new HashEntryInfo("hash", null, nestedKeys));
						if (DEBUG) System.out.println("[P3VarParser] ^" + varName + "." + dotMethod + " → hash wildcard, nested=" + nestedKeys.keySet());
						return ParsedValue.hash(hashKeys, null);
					}
					return new ParsedValue("hash");
				} else if (dotMethod.contains("array")) {
					// ^oSql.array{SELECT id, name, value} — как ^array::sql{}
					if (sqlColumns != null && !sqlColumns.isEmpty()) {
						Map<String, HashEntryInfo> nestedKeys = new LinkedHashMap<>();
						for (String col : sqlColumns) {
							nestedKeys.put(col, new HashEntryInfo(P3VariableFileIndex.UNKNOWN_TYPE));
						}
						Map<String, HashEntryInfo> hashKeys = new LinkedHashMap<>();
						hashKeys.put("*", new HashEntryInfo("hash", null, nestedKeys));
						if (DEBUG) System.out.println("[P3VarParser] ^" + varName + "." + dotMethod + " → array wildcard, nested=" + nestedKeys.keySet());
						return new ParsedValue("array", null, null, hashKeys, null, null, null, null);
					}
					return new ParsedValue("array");
				} else if (dotMethod.contains("table")) {
					// ^oSql.table{SELECT name, value} — как ^table::sql{}
					if (DEBUG) System.out.println("[P3VarParser] ^" + varName + "." + dotMethod + " → table, columns=" + sqlColumns);
					return new ParsedValue("table", sqlColumns, null);
				}
			}
		}

		return ParsedValue.objectMethodCall(dotMethod, varName);
	}

	private static @NotNull String extractHashAtReturnMode(@NotNull String text, int openPos, int textLen) {
		if (openPos < 0 || openPos >= textLen) return "value";
		char open = text.charAt(openPos);
		if (open != '[' && open != '(') return "value";

		int closePos = findMatchingBracket(text, openPos, textLen, open);
		if (closePos <= openPos) return "value";

		String content = text.substring(openPos + 1, closePos);
		String[] params = splitSemicolonsTopLevel(content);
		if (params.length < 2) return "value";

		String mode = params[1].trim().toLowerCase(java.util.Locale.ROOT);
		if ("key".equals(mode) || "hash".equals(mode)) {
			return mode;
		}
		return "value";
	}

	private static @NotNull String buildHashOperationSource(
			@NotNull String operation,
			@NotNull String selfVar,
			@Nullable String argVar
	) {
		return "hashop\n" + operation + "\n" + selfVar + "\n" + (argVar != null ? argVar : "");
	}

	private static @NotNull String buildTableOperationSource(
			@NotNull String operation,
			@NotNull String tableVar,
			@NotNull String valueMode
	) {
		return "tableop\n" + operation + "\n" + tableVar + "\n" + valueMode;
	}

	private static @NotNull String extractTableHashValueMode(
			@NotNull String text,
			int firstOpenPos,
			int textLen
	) {
		int firstClose = findMatchingBracket(text, firstOpenPos, textLen, text.charAt(firstOpenPos));
		if (firstClose <= firstOpenPos) return "row";
		int pos = firstClose + 1;
		while (pos < textLen && Character.isWhitespace(text.charAt(pos))) pos++;
		if (pos < textLen && (text.charAt(pos) == '[' || text.charAt(pos) == '(')) {
			String valueColumn = extractFirstTextParameter(text, pos, textLen, "");
			if (!valueColumn.isEmpty() && valueColumn.indexOf('$') < 0 && valueColumn.indexOf('^') < 0) {
				return "scalar";
			}
		}
		return "row";
	}

	private static @NotNull String extractTableArrayValueMode(
			@NotNull String text,
			int openPos,
			int textLen
	) {
		String valueColumn = extractFirstTextParameter(text, openPos, textLen, "");
		if (!valueColumn.isEmpty() && valueColumn.indexOf('$') < 0 && valueColumn.indexOf('^') < 0) {
			return "scalar";
		}
		return "row";
	}

	private static @Nullable String extractFirstVarParameter(@NotNull String text, int openPos, int textLen) {
		if (openPos < 0 || openPos >= textLen) return null;
		char open = text.charAt(openPos);
		if (open != '[' && open != '(' && open != '{') return null;
		int closePos = findMatchingBracket(text, openPos, textLen, open);
		if (closePos <= openPos) return null;

		String content = text.substring(openPos + 1, closePos);
		String[] params = splitSemicolonsTopLevel(content);
		if (params.length == 0) return null;

		String first = params[0].trim();
		if (!first.startsWith("$") || first.length() < 2) return null;
		String varRef = first.substring(1);
		for (int i = 0; i < varRef.length(); i++) {
			char ch = varRef.charAt(i);
			if (!Character.isLetterOrDigit(ch) && ch != '_' && ch != '.' && ch != ':') {
				return null;
			}
		}
		return varRef;
	}

	private static @NotNull String extractFirstTextParameter(
			@NotNull String text,
			int openPos,
			int textLen,
			@NotNull String defaultValue
	) {
		if (openPos < 0 || openPos >= textLen) return defaultValue;
		char open = text.charAt(openPos);
		if (open != '[' && open != '(' && open != '{') return defaultValue;
		int closePos = findMatchingBracket(text, openPos, textLen, open);
		if (closePos <= openPos) return defaultValue;

		String content = text.substring(openPos + 1, closePos);
		String[] params = splitSemicolonsTopLevel(content);
		if (params.length == 0) return defaultValue;
		String first = params[0].trim();
		return first.isEmpty() ? defaultValue : first;
	}

	private static @Nullable String resolveSimpleTextParameter(
			@NotNull String text,
			@NotNull String raw,
			int beforeOffset
	) {
		String trimmed = raw.trim();
		if (trimmed.isEmpty()) return null;
		if (!trimmed.startsWith("$")) {
			return isSimpleColumnName(trimmed) ? trimmed : null;
		}

		String varName = extractSoleVarReference(trimmed, 0, trimmed.length());
		if (varName == null || varName.contains(".") || varName.contains(":")) {
			return null;
		}
		return resolveSimpleStringAssignmentBefore(text, varName, beforeOffset);
	}

	private static @Nullable String resolveSimpleStringAssignmentBefore(
			@NotNull String text,
			@NotNull String varName,
			int beforeOffset
	) {
		String needle = "$" + varName + "[";
		int searchFrom = Math.min(Math.max(beforeOffset, 0), text.length());
		while (searchFrom > 0) {
			int start = text.lastIndexOf(needle, searchFrom - 1);
			if (start < 0) return null;
			int openPos = start + 1 + varName.length();
			int closePos = findMatchingBracket(text, openPos, searchFrom, '[');
			if (closePos > openPos) {
				String value = text.substring(openPos + 1, closePos).trim();
				return isSimpleColumnName(value) ? value : null;
			}
			searchFrom = start;
		}
		return null;
	}

	private static boolean isSimpleColumnName(@NotNull String value) {
		if (value.isEmpty()) return false;
		if (!Parser3IdentifierUtils.isIdentifierStart(value.charAt(0))) return false;
		for (int i = 1; i < value.length(); i++) {
			if (!isIdentChar(value.charAt(i))) return false;
		}
		return true;
	}

	private static @Nullable Map<String, HashEntryInfo> extractHashRenameMutationKeys(
			@NotNull String text,
			int openPos,
			int textLen
	) {
		if (openPos < 0 || openPos >= textLen || text.charAt(openPos) != '[') return null;
		int closePos = findMatchingBracket(text, openPos, textLen, '[');
		if (closePos <= openPos) return null;
		String content = text.substring(openPos + 1, closePos);

		Map<String, HashEntryInfo> result = new LinkedHashMap<>();
		if (containsHashKey(text, openPos + 1, closePos)) {
			HashParseResult hpr = parseHashKeys(text, openPos + 1, closePos, textLen);
			if (hpr.keys != null) {
				java.util.Set<String> producedTargets = new java.util.HashSet<>();
				for (Map.Entry<String, HashEntryInfo> entry : hpr.keys.entrySet()) {
					String oldKey = entry.getKey();
					if (producedTargets.contains(oldKey)) {
						continue;
					}
					String newKey = extractLiteralValueFromHashEntry(content, oldKey);
					if (newKey != null && newKey.isEmpty()) {
						result.put(oldKey, deletedHashEntry());
					} else if (newKey != null) {
						result.put(newKey, renamedHashEntry(oldKey));
						result.put(oldKey, deletedHashEntry());
						producedTargets.add(newKey);
					}
				}
			}
		} else {
			String[] params = splitSemicolonsTopLevel(content);
			if (params.length >= 2) {
				String oldKey = params[0].trim();
				String newKey = params[1].trim();
				if (!oldKey.isEmpty() && newKey.isEmpty()) {
					result.put(oldKey, deletedHashEntry());
				} else if (!oldKey.isEmpty()) {
					result.put(newKey, renamedHashEntry(oldKey));
					result.put(oldKey, deletedHashEntry());
				}
			}
		}

		return result.isEmpty() ? null : result;
	}

	private static boolean isHashMutatingMethod(@NotNull String methodName) {
		return "add".equals(methodName) || "rename".equals(methodName) || "sub".equals(methodName);
	}

	private static boolean isReceiverOnlyValueMethod(@NotNull String methodName) {
		return "trim".equals(methodName);
	}

	private static boolean containsTemplateExpression(@NotNull String text, int from, int to) {
		for (int i = from; i < to; i++) {
			char ch = text.charAt(i);
			if (ch == '^' || ch == '$') return true;
		}
		return false;
	}

	private static boolean isRootHashMutatingMethod(@NotNull String methodName) {
		return isHashMutatingMethod(methodName)
				|| "join".equals(methodName)
				|| "create".equals(methodName)
				|| "copy".equals(methodName)
				|| "delete".equals(methodName)
				|| "remove".equals(methodName)
				|| "set".equals(methodName);
	}

	private static @Nullable Map<String, HashEntryInfo> extractSingleDeleteMutationKey(
			@NotNull String text,
			int openPos,
			int textLen
	) {
		String key = extractFirstTextParameter(text, openPos, textLen, "");
		if (key.isEmpty() || key.indexOf('$') >= 0 || key.indexOf('^') >= 0 || key.indexOf(';') >= 0) return null;

		Map<String, HashEntryInfo> result = new LinkedHashMap<>();
		result.put(key, deletedHashEntry());
		return result;
	}

	private static @Nullable Map<String, HashEntryInfo> extractSingleSetMutationKey(
			@NotNull String text,
			int openPos,
			int textLen
	) {
		String key = extractFirstTextParameter(text, openPos, textLen, "");
		if (key.isEmpty() || key.indexOf('$') >= 0 || key.indexOf('^') >= 0 || key.indexOf(';') >= 0) return null;

		Map<String, HashEntryInfo> result = new LinkedHashMap<>();
		result.put(key, new HashEntryInfo(P3VariableFileIndex.UNKNOWN_TYPE));
		return result;
	}

	private static @Nullable Map<String, HashEntryInfo> extractNestedHashMutationKeys(
			@NotNull String text,
			int openPos,
			int textLen,
			@NotNull String methodName,
			@NotNull List<String> chain,
			@NotNull List<Integer> chainOffsets
	) {
		int lastIdx = chain.size() - 1;
		if (lastIdx < 1) return null;

		Map<String, HashEntryInfo> leafKeys;
		if ("rename".equals(methodName)) {
			leafKeys = extractHashRenameMutationKeys(text, openPos, textLen);
		} else if ("sub".equals(methodName)) {
			leafKeys = extractHashDeleteMutationKeys(text, openPos, textLen);
		} else {
			ParsedValue addPv = parseValueContent(text, openPos, textLen);
			leafKeys = addPv.hashKeys;
		}
		if (leafKeys == null || leafKeys.isEmpty()) return null;

		HashEntryInfo leafInfo = new HashEntryInfo("hash", null, leafKeys, chainOffsets.get(lastIdx - 1));
		for (int ci = lastIdx - 1; ci >= 1; ci--) {
			Map<String, HashEntryInfo> wrapper = new LinkedHashMap<>();
			wrapper.put(chain.get(ci), leafInfo);
			leafInfo = new HashEntryInfo("hash", null, wrapper, chainOffsets.get(ci - 1));
		}

		Map<String, HashEntryInfo> result = new LinkedHashMap<>();
		result.put(chain.get(0), leafInfo);
		return result;
	}

	private static @Nullable Map<String, HashEntryInfo> extractHashDeleteMutationKeys(
			@NotNull String text,
			int openPos,
			int textLen
	) {
		ParsedValue pv = parseValueContent(text, openPos, textLen);
		if (pv.hashKeys == null || pv.hashKeys.isEmpty()) return null;

		Map<String, HashEntryInfo> result = new LinkedHashMap<>();
		for (String key : pv.hashKeys.keySet()) {
			result.put(key, deletedHashEntry());
		}
		return result;
	}

	private static @Nullable String extractLiteralValueFromHashEntry(@NotNull String content, @NotNull String key) {
		String needle = "$." + key;
		int idx = content.indexOf(needle);
		if (idx < 0) return null;
		int pos = idx + needle.length();
		while (pos < content.length() && Character.isWhitespace(content.charAt(pos))) pos++;
		if (pos >= content.length()) return null;
		char open = content.charAt(pos);
		if (open != '[' && open != '(' && open != '{') return null;
		int close = findMatchingBracket(content, pos, content.length(), open);
		if (close <= pos) return null;
		String value = content.substring(pos + 1, close).trim();
		if (value.indexOf('$') >= 0 || value.indexOf('^') >= 0 || value.indexOf(';') >= 0) return null;
		return value;
	}

	static boolean isDeletedHashEntry(@Nullable HashEntryInfo entry) {
		return entry != null && "__deleted__".equals(entry.className);
	}

	static boolean isRenamedHashEntry(@Nullable HashEntryInfo entry) {
		return entry != null && entry.className.startsWith("__rename__:");
	}

	static @Nullable String getRenamedHashSource(@NotNull HashEntryInfo entry) {
		return isRenamedHashEntry(entry) ? entry.className.substring("__rename__:".length()) : null;
	}

	private static @NotNull HashEntryInfo deletedHashEntry() {
		return new HashEntryInfo("__deleted__");
	}

	private static @NotNull HashEntryInfo renamedHashEntry(@NotNull String oldKey) {
		return new HashEntryInfo("__rename__:" + oldKey);
	}

	private static boolean isConfiguredUserSqlInjection(@NotNull String varName, @NotNull String dotMethod) {
		return Parser3SqlInjectionSupport.matchesConfiguredPrefix("^" + varName + "." + dotMethod);
	}

	/**
	 * Результат парсинга хеш-литерала: ключи + переменные-источники.
	 */
	private static @NotNull ParsedValue parseChainedDotMethodCall(
			@NotNull String text,
			@NotNull String rootVarName,
			int firstDotPos,
			int to,
			int textLen,
			int sourceSnapshotOffset
	) {
		String sourceVarName = rootVarName;
		int methodDotPos = firstDotPos;
		int pos = firstDotPos;

		while (pos < to && text.charAt(pos) == '.') {
			int segStart = pos + 1;
			int segEnd = segStart;
			while (segEnd < to && isIdentChar(text.charAt(segEnd))) segEnd++;
			if (segEnd == segStart) return ParsedValue.unknown();

			int afterSegment = segEnd;
			while (afterSegment < to && Character.isWhitespace(text.charAt(afterSegment))) afterSegment++;

			if (afterSegment < to && text.charAt(afterSegment) == '.') {
				sourceVarName = sourceVarName + "." + text.substring(segStart, segEnd);
				methodDotPos = afterSegment;
				pos = afterSegment;
				continue;
			}

			if (afterSegment < to && (text.charAt(afterSegment) == '[' || text.charAt(afterSegment) == '(' || text.charAt(afterSegment) == '{')) {
				return parseDotMethodCall(text, sourceVarName, methodDotPos, to, textLen, sourceSnapshotOffset);
			}

			return ParsedValue.unknown();
		}

		return ParsedValue.unknown();
	}

	private static final class HashParseResult {
		final @Nullable Map<String, HashEntryInfo> keys;
		final @Nullable List<String> sourceVars;
		HashParseResult(@Nullable Map<String, HashEntryInfo> keys, @Nullable List<String> sourceVars) {
			this.keys = keys;
			this.sourceVars = sourceVars;
		}
	}

	/**
	 * Проверяет что содержимое скобок — единственная ссылка на переменную: $varName.
	 * Возвращает имя переменной (с учётом prefix self./MAIN:), или null если не подходит.
	 * Не матчит: $. (хеш-ключ), несколько переменных, текст с пробелами между.
	 */
	private static @Nullable String extractSoleVarReference(@NotNull String text, int from, int to) {
		int pos = from;
		// Пропускаем пробелы
		while (pos < to && Character.isWhitespace(text.charAt(pos))) pos++;
		if (pos >= to || text.charAt(pos) != '$') return null;
		pos++; // после $
		if (pos >= to) return null;

		// Пропускаем prefix: self. или MAIN:
		String prefix = "";
		if (pos + 5 <= to && text.substring(pos, pos + 5).equals("self.")) {
			prefix = "self.";
			pos += 5;
		} else if (pos + 5 <= to && text.substring(pos, pos + 5).equals("MAIN:")) {
			prefix = "MAIN:";
			pos += 5;
		}

		// $.key — это хеш, не ссылка
		if (pos < to && text.charAt(pos) == '.') return null;

		// Парсим имя (и опциональную цепочку точек: $data.key1, $data.[key], $data.[$key])
		if (pos >= to || !Parser3IdentifierUtils.isIdentifierStart(text.charAt(pos))) return null;
		int nameStart = pos;
		while (pos < to && isIdentChar(text.charAt(pos))) pos++;
		StringBuilder varName = new StringBuilder(text.substring(nameStart, pos));

		// Разрешаем цепочку точек: $data.key1, $arr.0, $data.[literal], $data.[$dynamic]
		while (pos < to && text.charAt(pos) == '.') {
			pos++; // пропускаем точку
			DotChainSegmentInfo segment = parseDotChainSegment(text, pos, to, text.length());
			if (segment == null) return null;
			varName.append('.').append(segment.keyName);
			pos = segment.nextPos;
		}

		// После имени — только пробелы до конца
		while (pos < to && Character.isWhitespace(text.charAt(pos))) pos++;
		if (pos != to) return null; // есть ещё что-то — не чистая ссылка

		return prefix + varName;
	}

	/**
	 * Извлекает ссылки на переменные ($varName) из диапазона текста.
	 * Пропускает $.key (хеш-ключи) — ищем только $varName без точки.
	 */
	private static @Nullable List<String> extractVarReferences(@NotNull String text, int from, int to) {
		List<String> refs = null;
		int pos = from;
		while (pos < to) {
			while (pos < to && Character.isWhitespace(text.charAt(pos))) pos++;
			if (pos >= to) break;
			if (text.charAt(pos) == '$' && pos + 1 < to && text.charAt(pos + 1) != '.' && isIdentChar(text.charAt(pos + 1))) {
				pos++; // после $
				int nameStart = pos;
				while (pos < to && isIdentChar(text.charAt(pos))) pos++;
				if (pos > nameStart) {
					if (refs == null) refs = new ArrayList<>();
					refs.add(text.substring(nameStart, pos));
				}
			} else {
				pos++;
			}
		}
		return refs;
	}

	/**
	 * Быстрая проверка: содержит ли диапазон текста хеш-ключ ($.key[).
	 * Нужна чтобы не вызывать parseHashKeys для обычных присваиваний.
	 */
	private static boolean containsHashKey(@NotNull String text, int from, int to) {
		int depth = 0;
		for (int i = from; i < to - 1; i++) {
			char c = text.charAt(i);
			if (c == '[' || c == '(' || c == '{') {
				depth++;
			} else if (c == ']' || c == ')' || c == '}') {
				depth--;
			} else if (c == '$' && depth == 0 && text.charAt(i + 1) == '.') {
				return true;
			}
		}
		return false;
	}

	/**
	 * Проверяет наличие ; на верхнем уровне (вне вложенных скобок).
	 * Документация Parser3: $имя[значение1;значение2;...;значениеN] — массив.
	 */
	private static boolean containsTopLevelSemicolon(@NotNull String text, int from, int to) {
		int depth = 0;
		for (int i = from; i < to; i++) {
			char c = text.charAt(i);
			if (c == '[' || c == '(' || c == '{') {
				depth++;
			} else if (c == ']' || c == ')' || c == '}') {
				depth--;
			} else if (c == ';' && depth == 0) {
				return true;
			}
		}
		return false;
	}

	// === Парсинг массив-литералов ===

	/**
	 * Парсит элементы массив-литерала, определяя тип каждого элемента.
	 * Документация Parser3 (стр.42): $имя[значение1;значение2;...;значениеN]
	 *
	 * Разбивает по ; на верхнем уровне (вне вложенных скобок).
	 * Для каждого элемента определяет тип через анализ содержимого.
	 * Возвращает Map с ключами "0", "1", "2"... — переиспользуем инфраструктуру hashKeys.
	 *
	 * @return Map индекс → HashEntryInfo, или null если нет типизированных элементов
	 */
	private static @Nullable Map<String, HashEntryInfo> parseArrayElements(
			@NotNull String text, int from, int to, int textLen) {
		Map<String, HashEntryInfo> elements = new LinkedHashMap<>();
		int elementIndex = 0;
		int elementStart = from;
		int depth = 0;

		for (int i = from; i <= to; i++) {
			if (i < to) {
				char c = text.charAt(i);
				if (c == '[' || c == '(' || c == '{') {
					depth++;
				} else if (c == ']' || c == ')' || c == '}') {
					depth--;
				} else if (c == ';' && depth == 0) {
					// Конец элемента — анализируем
					HashEntryInfo info = analyzeArrayElement(text, elementStart, i, textLen);
					// Запоминаем offset начала элемента для навигации
					if (info.offset < 0) info = info.withOffset(elementStart);
					elements.put(String.valueOf(elementIndex), info);
					elementIndex++;
					elementStart = i + 1;
					continue;
				}
			} else {
				// Последний элемент (после последней ;)
				HashEntryInfo info = analyzeArrayElement(text, elementStart, to, textLen);
				if (info.offset < 0) info = info.withOffset(elementStart);
				elements.put(String.valueOf(elementIndex), info);
			}
		}

		return elements.isEmpty() ? null : elements;
	}

	/**
	 * Анализирует один элемент массива и определяет его тип.
	 * Переиспользует parseValueContent через создание "виртуальных скобок".
	 */
	private static @NotNull HashEntryInfo analyzeArrayElement(
			@NotNull String text, int from, int to, int textLen) {
		// Пропускаем пробелы
		int pos = from;
		while (pos < to && Character.isWhitespace(text.charAt(pos))) pos++;
		if (pos >= to) {
			return new HashEntryInfo(P3VariableFileIndex.UNKNOWN_TYPE);
		}

		// Проверяем хеш-литерал: $.key[...]
		if (text.charAt(pos) == '$' && pos + 1 < to && text.charAt(pos + 1) == '.') {
			HashParseResult hpr = parseHashKeys(text, from, to, textLen);
			if (hpr.keys != null && !hpr.keys.isEmpty()) {
				return new HashEntryInfo("hash", null, hpr.keys);
			}
		}

		// Проверяем вызов: ^class::constructor или ^method
		if (text.charAt(pos) == '^') {
			ParsedValue pv = parseCaretContent(text, pos, to, textLen, from);
			return parsedValueToHashEntry(pv);
		}

		return new HashEntryInfo(P3VariableFileIndex.UNKNOWN_TYPE);
	}

	// === Парсинг хеш-литералов ===

	/**
	 * Парсит ключи хеш-литерала.
	 * Вход: содержимое между [ и ] хеша, т.е. "$.key1[val1] $.key2[val2]"
	 * РЕКУРСИВНО вызывает parseValueContent() для значений ключей.
	 *
	 * Документация Parser3 (стр.41, Конструкции языка, Хеш):
	 * $имя[$.ключ1[значение] $.ключ2[значение] ... $.ключN[значение]]
	 * Хеш позволяет создавать многомерные структуры (hash of hash).
	 *
	 * @param text полный текст файла
	 * @param from начало содержимого (после открывающей [)
	 * @param to конец содержимого (перед закрывающей ])
	 * @param textLen полная длина текста
	 * @return Map ключ → HashEntryInfo, или null если не удалось распарсить
	 */
	private static @NotNull HashParseResult parseHashKeys(
			@NotNull String text, int from, int to, int textLen) {
		return parseHashKeys(text, from, to, textLen, null, null, null, -1);
	}

	private static @NotNull HashParseResult parseHashKeys(
			@NotNull String text,
			int from,
			int to,
			int textLen,
			@Nullable Map<String, List<P3VariableFileIndex.VariableTypeInfo>> parsedVariables,
			@Nullable String ownerClass,
			@Nullable String ownerMethod,
			int assignmentOffset
	) {
		Map<String, HashEntryInfo> keys = new LinkedHashMap<>();
		List<String> sourceVars = null;
		int pos = from;

		while (pos < to) {
			// Пропускаем пробелы, комментарии (# до конца строки) и ^rem{...}
			while (pos < to) {
				while (pos < to && Character.isWhitespace(text.charAt(pos))) pos++;
				if (pos < to && text.charAt(pos) == '#') {
					// Комментарий — пропускаем до конца строки
					while (pos < to && text.charAt(pos) != '\n') pos++;
				} else if (pos + 4 <= to && text.charAt(pos) == '^'
						&& text.charAt(pos + 1) == 'r' && text.charAt(pos + 2) == 'e' && text.charAt(pos + 3) == 'm') {
					// ^rem{...} / ^rem[...] / ^rem(...)
					int afterRem = pos + 4;
					if (afterRem < to) {
						char remBracket = text.charAt(afterRem);
						if (remBracket == '{' || remBracket == '[' || remBracket == '(') {
							int end = findMatchingBracket(text, afterRem, textLen, remBracket);
							if (end != -1) {
								pos = end + 1;
								continue;
							}
						}
					}
					break;
				} else {
					break;
				}
			}
			if (pos >= to) break;

			if (text.charAt(pos) == '$' && pos + 1 < to) {
				if (text.charAt(pos + 1) == '.') {
					// $.key[ — ключ хеша
					pos += 2; // после $.

					// Парсим имя ключа
					DotChainSegmentInfo keySegment = parseDotChainSegment(text, pos, to, textLen);
					if (keySegment == null) {
						pos++;
						continue;
					}
					String keyName = keySegment.keyName;
					int keyStart = keySegment.keyOffset;
					pos = keySegment.nextPos;

					// Ожидаем [ или (
					while (pos < to && Character.isWhitespace(text.charAt(pos))) pos++;
					if (pos >= to) {
						keys.put(keyName, new HashEntryInfo(P3VariableFileIndex.UNKNOWN_TYPE, null, null, keyStart));
						break;
					}

					char keyBracket = text.charAt(pos);
					if (keyBracket == '[' || keyBracket == '(' || keyBracket == '{') {
						// ЕДИНАЯ ФУНКЦИЯ — парсим содержимое скобок
						ParsedValue pv = parsedVariables != null
								? parseValueContentWithPreviousLocalSources(
										text, pos, textLen, parsedVariables, ownerClass, ownerMethod, assignmentOffset)
								: parseValueContent(text, pos, textLen);
						HashEntryInfo entryInfo = parsedValueToHashEntry(pv, keyStart);
						if (parsedVariables != null) {
							entryInfo = enrichHashEntryFromPreviousLocalSource(
									entryInfo, pv, parsedVariables, ownerClass, ownerMethod, assignmentOffset);
						}

						if (DEBUG) System.out.println("[P3VarParser.parseHashKeys] key=" + keyName + " → " + entryInfo);
						keys.put(keyName, entryInfo);

						int closePos = findMatchingBracket(text, pos, textLen, keyBracket);
						if (closePos < 0) break;
						pos = closePos + 1;
					} else {
						break;
					}
				} else if (isIdentChar(text.charAt(pos + 1))) {
					// $varName — ссылка на другой хеш (наследование ключей)
					// Документация: ^hash::create[$существующий_хеш] — копирование
					pos++; // после $
					int nameStart = pos;
					while (pos < to && isIdentChar(text.charAt(pos))) pos++;
					String refVarName = text.substring(nameStart, pos);
					if (sourceVars == null) sourceVars = new ArrayList<>();
					sourceVars.add(refVarName);
					if (DEBUG) System.out.println("[P3VarParser.parseHashKeys] sourceVar=" + refVarName);
				} else {
					pos++;
				}
			} else if (text.charAt(pos) == '^') {
				// ^hash::create[$var] — извлекаем $var как sourceVar
				// Другие вызовы — просто пропускаем
				int caretPos = pos;
				pos++; // после ^
				int nameStart = pos;
				while (pos < to && isIdentChar(text.charAt(pos))) pos++;
				String caretName = (pos > nameStart) ? text.substring(nameStart, pos) : "";

				// Пропускаем ::methodName или :methodName
				while (pos < to && (text.charAt(pos) == ':' || isIdentChar(text.charAt(pos)))) pos++;
				while (pos < to && Character.isWhitespace(text.charAt(pos))) pos++;

				if (pos < to && (text.charAt(pos) == '[' || text.charAt(pos) == '(' || text.charAt(pos) == '{')) {
					char callBracket = text.charAt(pos);
					int closePos = findMatchingBracket(text, pos, textLen, callBracket);
					if (closePos > 0) {
						// Если это ^hash::create[...] — ищем $varName внутри
						if ("hash".equals(caretName) && callBracket == '[') {
							int inner = pos + 1;
							while (inner < closePos && Character.isWhitespace(text.charAt(inner))) inner++;
							// Ищем $varName (не $.) внутри
							while (inner < closePos) {
								if (text.charAt(inner) == '$' && inner + 1 < closePos
										&& text.charAt(inner + 1) != '.' && isIdentChar(text.charAt(inner + 1))) {
									inner++; // после $
									int refStart = inner;
									while (inner < closePos && isIdentChar(text.charAt(inner))) inner++;
									String refName = text.substring(refStart, inner);
									if (sourceVars == null) sourceVars = new ArrayList<>();
									sourceVars.add(refName);
									if (DEBUG) System.out.println("[P3VarParser.parseHashKeys] ^hash::create sourceVar=" + refName);
								} else {
									inner++;
								}
							}
						}
						pos = closePos + 1;
					} else {
						pos++;
					}
				}
			} else {
				pos++;
			}
		}

		return new HashParseResult(keys.isEmpty() ? null : keys, sourceVars);
	}

	/**
	 * Преобразует ParsedValue → HashEntryInfo.
	 * Единая точка конвертации результата парсинга в запись хеша.
	 */
	private static @NotNull HashEntryInfo parsedValueToHashEntry(@NotNull ParsedValue pv) {
		return new HashEntryInfo(
				pv.className,
				pv.columns,
				pv.hashKeys,
				-1,
				pv.sourceVarKey,
				pv.methodName,
				pv.targetClassName,
				pv.receiverVarKey
		);
	}

	/**
	 * Преобразует ParsedValue → HashEntryInfo с известной позицией ключа.
	 */
	private static @NotNull HashEntryInfo parsedValueToHashEntry(@NotNull ParsedValue pv, int offset) {
		return new HashEntryInfo(
				pv.className,
				pv.columns,
				pv.hashKeys,
				offset,
				pv.sourceVarKey != null ? P3VariableEffectiveShape.encodeHashSourceAtOffset(pv.sourceVarKey, offset) : null,
				pv.methodName,
				pv.targetClassName,
				pv.receiverVarKey
		);
	}

	// === Создание VariableTypeInfo ===

	/**
	 * Создаёт VariableTypeInfo из ParsedValue.
	 * Единая точка создания — используется для всех типов переменных.
	 */
	private static P3VariableFileIndex.VariableTypeInfo makeInfoFromParsedValue(
			int offset,
			@Nullable String prefix,
			@NotNull String varName,
			@NotNull ParsedValue pv,
			@NotNull List<ru.artlebedev.parser3.utils.Parser3ClassUtils.ClassBoundary> classBoundaries,
			@NotNull List<ru.artlebedev.parser3.utils.Parser3ClassUtils.MethodBoundary> methodBoundaries,
			@NotNull String text
	) {
		String ownerClass = ru.artlebedev.parser3.utils.Parser3ClassUtils.findOwnerClass(offset, classBoundaries);
		ru.artlebedev.parser3.utils.Parser3ClassUtils.MethodBoundary ownerMethodBoundary =
				ru.artlebedev.parser3.utils.Parser3ClassUtils.findOwnerMethod(offset, methodBoundaries);
		String ownerMethod = ownerMethodBoundary != null ? ownerMethodBoundary.name : null;

		boolean isLocal = P3VariableFileIndex.computeIsLocal(prefix, varName, ownerClass, ownerMethodBoundary, text, classBoundaries);
		String targetClassName = pv.targetClassName;
		if ((targetClassName == null || targetClassName.isEmpty())
				&& "self".equals(pv.receiverVarKey)
				&& ownerClass != null && !ownerClass.isEmpty()) {
			targetClassName = ownerClass;
		}

		return new P3VariableFileIndex.VariableTypeInfo(
				offset, pv.className, ownerClass, ownerMethod,
				pv.methodName, targetClassName, prefix, isLocal,
				pv.columns, pv.sourceVarKey, pv.hashKeys, pv.hashSourceVars, false, pv.receiverVarKey);
	}

	// === Вспомогательные методы ===

	/**
	 * Проверяет что позиция — в колонке 0 (первый символ строки).
	 */
	private static boolean isColumnZero(@NotNull String text, int pos) {
		return pos == 0 || (pos > 0 && text.charAt(pos - 1) == '\n');
	}

	/**
	 * Символ может быть частью идентификатора.
	 */
	static boolean isIdentChar(char ch) {
		return Character.isLetterOrDigit(ch) || ch == '_';
	}

	/**
	 * Разбивает строку по `;` на верхнем уровне (не внутри скобок).
	 * Используется для парсинга параметров split и других методов.
	 */
	private static @NotNull String[] splitSemicolonsTopLevel(@NotNull String content) {
		java.util.List<String> parts = new java.util.ArrayList<>();
		int depth = 0;
		int start = 0;

		for (int i = 0; i < content.length(); i++) {
			char c = content.charAt(i);
			if (c == '[' || c == '(' || c == '{') {
				depth++;
			} else if (c == ']' || c == ')' || c == '}') {
				depth--;
			} else if (c == ';' && depth == 0) {
				parts.add(content.substring(start, i));
				start = i + 1;
			}
		}
		parts.add(content.substring(start));
		return parts.toArray(new String[0]);
	}

	/**
	 * Находит парную закрывающую скобку с учётом вложенности и экранирования.
	 */
	static int findMatchingBracket(@NotNull String text, int openPos, int limit, char openCh) {
		char closeCh;
		switch (openCh) {
			case '{': closeCh = '}'; break;
			case '[': closeCh = ']'; break;
			case '(': closeCh = ')'; break;
			default: return -1;
		}
		return findMatchingBracketForVariableParser(text, openPos, limit, openCh, closeCh);
	}

	private static int findMatchingBracketForVariableParser(
			@NotNull String text,
			int openPos,
			int limit,
			char openCh,
			char closeCh
	) {
		int length = text.length();
		if (openPos < 0 || openPos >= length) return -1;

		int depth = 0;
		boolean inSingleQuote = false;
		boolean inDoubleQuote = false;
		for (int i = openPos; i < limit && i < length; i++) {
			char c = text.charAt(i);
			if (ru.artlebedev.parser3.lexer.Parser3LexerUtils.isEscapedByCaret(text, i)) continue;

			if (c == '\'' && !inDoubleQuote) {
				inSingleQuote = !inSingleQuote;
				continue;
			}
			if (c == '"' && !inSingleQuote) {
				// В параметрах Parser3 встречается $.encloser["]: это значение кавычки,
				// а не начало строки до конца присваивания.
				if (i + 1 < limit && i + 1 < length && text.charAt(i + 1) == closeCh) {
					continue;
				}
				inDoubleQuote = !inDoubleQuote;
				continue;
			}
			if (inSingleQuote || inDoubleQuote) continue;

			if (c == openCh) {
				depth++;
			} else if (c == closeCh) {
				depth--;
				if (depth == 0) return i;
			}
		}
		return -1;
	}
}
