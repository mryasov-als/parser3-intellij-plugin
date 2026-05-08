package ru.artlebedev.parser3.index;

import com.intellij.util.indexing.*;
import com.intellij.util.io.DataExternalizer;
import com.intellij.util.io.EnumeratorStringDescriptor;
import com.intellij.util.io.KeyDescriptor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.artlebedev.parser3.utils.Parser3FileUtils;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FileBasedIndex для типов переменных в Parser3.
 *
 * Индексирует присваивания вида:
 * - $var[^ClassName::constructor[]] — пользовательские классы (с большой буквы)
 * - $var[^table::load[...]] — встроенные классы (с маленькой буквы)
 * - $var[^json:parse[...]] — статические методы встроенных классов
 *
 * Ключ: имя переменной (без $)
 * Значение: список VariableTypeInfo (offset, className)
 */
public final class P3VariableFileIndex extends FileBasedIndexExtension<String, List<P3VariableFileIndex.VariableTypeInfo>> {

	public static final ID<String, List<VariableTypeInfo>> NAME = ID.create("parser3.variables");

	// Паттерн 1: Конструктор пользовательского класса $var[^ClassName::method]
	// Имя класса с большой буквы
	// Группы: 1 - префикс (self./MAIN: или пусто), 2 - имя переменной, 3 - имя класса
	private static final Pattern USER_CLASS_CONSTRUCTOR_PATTERN = Pattern.compile(
			"\\$(self\\.|MAIN:)?([\\p{L}_][\\p{L}0-9_]*)\\s*\\[\\s*\\^\\s*([A-Z][\\p{L}0-9_]*)\\s*::",
			Pattern.MULTILINE
	);

	// Паттерн 2: Конструктор встроенного класса $var[^builtinClass::method]
	// Имя класса с маленькой буквы (table, hash, date, file, image, xdoc, array, regex)
	// Группы: 1 - префикс (self./MAIN: или пусто), 2 - имя переменной, 3 - имя класса
	private static final Pattern BUILTIN_CLASS_CONSTRUCTOR_PATTERN = Pattern.compile(
			"\\$(self\\.|MAIN:)?([\\p{L}_][\\p{L}0-9_]*)\\s*\\[\\s*\\^\\s*([a-z][a-z0-9_]*)\\s*::",
			Pattern.MULTILINE
	);

	// Паттерн 3: Статический метод встроенного класса $var[^builtinClass:method]
	// Возвращает объект того же класса (например ^json:parse возвращает hash)
	// Группы: 1 - префикс (self./MAIN: или пусто), 2 - имя переменной, 3 - имя класса, 4 - имя метода
	private static final Pattern BUILTIN_STATIC_METHOD_PATTERN = Pattern.compile(
			"\\$(self\\.|MAIN:)?([\\p{L}_][\\p{L}0-9_]*)\\s*\\[\\s*\\^\\s*([a-z][a-z0-9_]*)\\s*:\\s*([a-z][a-z0-9_-]*)\\s*[\\[({]",
			Pattern.MULTILINE
	);

	// Паттерн 4: Вызов пользовательского метода $var[^methodName[...]]
	// НЕ матчит: ^Class::method (конструктор), ^class:method (статический)
	// Тип определяется из документации метода ($result)
	// Группы: 1 - префикс (self./MAIN: или пусто), 2 - имя переменной, 3 - имя метода
	// Негативный lookahead (?!:) исключает случаи с : после имени метода
	private static final Pattern USER_METHOD_CALL_PATTERN = Pattern.compile(
			"\\$(self\\.|MAIN:)?([\\p{L}_][\\p{L}0-9_]*)\\s*\\[\\s*\\^\\s*([a-z_][\\p{L}0-9_]*)(?!\\s*:)\\s*[\\[({]",
			Pattern.MULTILINE
	);

	// Паттерн 5: Статический вызов метода пользовательского класса $var[^ClassName:method[...]]
	// Имя класса с большой буквы, одно двоеточие
	// Тип определяется из документации метода ($result)
	// Группы: 1 - префикс (self./MAIN: или пусто), 2 - имя переменной, 3 - имя класса, 4 - имя метода
	private static final Pattern USER_CLASS_STATIC_METHOD_PATTERN = Pattern.compile(
			"\\$(self\\.|MAIN:)?([\\p{L}_][\\p{L}0-9_]*)\\s*\\[\\s*\\^\\s*([A-Z][\\p{L}0-9_]*)\\s*:\\s*([\\p{L}_][\\p{L}0-9_]*)\\s*[\\[({]",
			Pattern.MULTILINE
	);

	// Паттерн 6: Любое присваивание переменной $var[...], $var(...), $var{...}
	// Ловит ВСЕ переменные, тип = "" (неизвестен)
	// Группы: 1 - префикс (self./MAIN: или null), 2 - имя переменной
	private static final Pattern ANY_VAR_ASSIGNMENT_PATTERN = Pattern.compile(
			"\\$(self\\.|MAIN:)?([\\p{L}_][\\p{L}0-9_]*)\\s*[\\[({]",
			Pattern.MULTILINE
	);

	// Кеш разбора SQL-колонок нужен именно для индексации:
	// одни и те же SQL-фрагменты могут многократно проходить через inferResult() и парсер переменных.
	private static final int SQL_COLUMNS_CACHE_SIZE = 128;
	private static final List<String> SQL_COLUMNS_NULL = List.of();
	private static final Map<String, List<String>> SQL_COLUMNS_CACHE = Collections.synchronizedMap(
			new LinkedHashMap<>(SQL_COLUMNS_CACHE_SIZE, 0.75f, true) {
				@Override
				protected boolean removeEldestEntry(Map.Entry<String, List<String>> eldest) {
					return size() > SQL_COLUMNS_CACHE_SIZE;
				}
			}
	);

	/**
	 * Маркер для переменных без определённого типа.
	 * Используется при индексации всех присваиваний — тип неизвестен.
	 */
	public static final String UNKNOWN_TYPE = "";

	/**
	 * Информация о типе переменной
	 */
	public static final class VariableTypeInfo {
		public final int offset;                      // Смещение в файле
		public final @NotNull String className;       // Имя класса (тип переменной) или METHOD_CALL_MARKER
		public final @Nullable String ownerClass;     // Класс-владелец (null = MAIN)
		public final @Nullable String ownerMethod;    // Метод-владелец (null = вне метода)
		public final @Nullable String methodName;     // Имя вызываемого метода (для определения типа из $result)
		public final @Nullable String targetClassName; // Имя класса для статического вызова ^ClassName:method
		public final @Nullable String varPrefix;      // Префикс переменной: "self." или "MAIN:" или null
		public final boolean isLocal;                 // true если переменная локальна в своём методе (@OPTIONS locals, [locals], [varName])
		public final @Nullable List<String> columns;  // Колонки таблицы (для table::create{col1\tcol2})
		public final @Nullable String sourceVarKey;   // Ключ переменной-источника (для table::create[$var] — копирование)
		public final @Nullable java.util.Map<String, HashEntryInfo> hashKeys; // Ключи хеша (для hash-литералов)
		public final @Nullable java.util.List<String> hashSourceVars; // Переменные-источники ключей ($data2[$data $.key[...]])
		public final boolean isAdditive; // true for $data.key[...], ^data.add[...]

		/**
		 * Маркер для типа, определяемого из вызова метода.
		 * Реальный тип будет определён в runtime из документации метода.
		 */
		public static final String METHOD_CALL_MARKER = "@METHOD_CALL@";
		public final @Nullable String receiverVarKey;

		public VariableTypeInfo(int offset, @NotNull String className, @Nullable String ownerClass, @Nullable String ownerMethod) {
			this(offset, className, ownerClass, ownerMethod, null, null, null, false, null);
		}

		public VariableTypeInfo(int offset, @NotNull String className, @Nullable String ownerClass, @Nullable String ownerMethod, @Nullable String methodName) {
			this(offset, className, ownerClass, ownerMethod, methodName, null, null, false, null);
		}

		public VariableTypeInfo(int offset, @NotNull String className, @Nullable String ownerClass, @Nullable String ownerMethod, @Nullable String methodName, @Nullable String targetClassName) {
			this(offset, className, ownerClass, ownerMethod, methodName, targetClassName, null, false, null);
		}

		public VariableTypeInfo(int offset, @NotNull String className, @Nullable String ownerClass, @Nullable String ownerMethod,
								@Nullable String methodName, @Nullable String targetClassName, @Nullable String varPrefix) {
			this(offset, className, ownerClass, ownerMethod, methodName, targetClassName, varPrefix, false, null);
		}

		public VariableTypeInfo(int offset, @NotNull String className, @Nullable String ownerClass, @Nullable String ownerMethod,
								@Nullable String methodName, @Nullable String targetClassName, @Nullable String varPrefix, boolean isLocal) {
			this(offset, className, ownerClass, ownerMethod, methodName, targetClassName, varPrefix, isLocal, null);
		}

		public VariableTypeInfo(int offset, @NotNull String className, @Nullable String ownerClass, @Nullable String ownerMethod,
								@Nullable String methodName, @Nullable String targetClassName, @Nullable String varPrefix, boolean isLocal,
								@Nullable List<String> columns) {
			this(offset, className, ownerClass, ownerMethod, methodName, targetClassName, varPrefix, isLocal, columns, null);
		}

		public VariableTypeInfo(int offset, @NotNull String className, @Nullable String ownerClass, @Nullable String ownerMethod,
								@Nullable String methodName, @Nullable String targetClassName, @Nullable String varPrefix, boolean isLocal,
								@Nullable List<String> columns, @Nullable String sourceVarKey) {
			this(offset, className, ownerClass, ownerMethod, methodName, targetClassName, varPrefix, isLocal, columns, sourceVarKey, null);
		}

		public VariableTypeInfo(int offset, @NotNull String className, @Nullable String ownerClass, @Nullable String ownerMethod,
								@Nullable String methodName, @Nullable String targetClassName, @Nullable String varPrefix, boolean isLocal,
								@Nullable List<String> columns, @Nullable String sourceVarKey,
								@Nullable java.util.Map<String, HashEntryInfo> hashKeys) {
			this(offset, className, ownerClass, ownerMethod, methodName, targetClassName, varPrefix, isLocal, columns, sourceVarKey, hashKeys, null);
		}

		public VariableTypeInfo(int offset, @NotNull String className, @Nullable String ownerClass, @Nullable String ownerMethod,
								@Nullable String methodName, @Nullable String targetClassName, @Nullable String varPrefix, boolean isLocal,
								@Nullable List<String> columns, @Nullable String sourceVarKey,
								@Nullable java.util.Map<String, HashEntryInfo> hashKeys,
								@Nullable java.util.List<String> hashSourceVars) {
			this(offset, className, ownerClass, ownerMethod, methodName, targetClassName, varPrefix, isLocal, columns, sourceVarKey, hashKeys, hashSourceVars, false);
		}

		public VariableTypeInfo(int offset, @NotNull String className, @Nullable String ownerClass, @Nullable String ownerMethod,
								@Nullable String methodName, @Nullable String targetClassName, @Nullable String varPrefix, boolean isLocal,
								@Nullable List<String> columns, @Nullable String sourceVarKey,
								@Nullable java.util.Map<String, HashEntryInfo> hashKeys,
								@Nullable java.util.List<String> hashSourceVars, boolean isAdditive) {
			this(offset, className, ownerClass, ownerMethod, methodName, targetClassName, varPrefix, isLocal,
					columns, sourceVarKey, hashKeys, hashSourceVars, isAdditive, null);
		}

		public VariableTypeInfo(int offset, @NotNull String className, @Nullable String ownerClass, @Nullable String ownerMethod,
								@Nullable String methodName, @Nullable String targetClassName, @Nullable String varPrefix, boolean isLocal,
								@Nullable List<String> columns, @Nullable String sourceVarKey,
								@Nullable java.util.Map<String, HashEntryInfo> hashKeys,
								@Nullable java.util.List<String> hashSourceVars, boolean isAdditive,
								@Nullable String receiverVarKey) {
			this.offset = offset;
			this.className = className;
			this.ownerClass = ownerClass;
			this.ownerMethod = ownerMethod;
			this.methodName = methodName;
			this.targetClassName = targetClassName;
			this.receiverVarKey = receiverVarKey;
			this.varPrefix = varPrefix;
			this.isLocal = isLocal;
			this.columns = columns;
			this.sourceVarKey = sourceVarKey;
			this.hashKeys = (hashKeys != null && hashKeys.isEmpty()) ? null : hashKeys;
			this.hashSourceVars = (hashSourceVars != null && hashSourceVars.isEmpty()) ? null : hashSourceVars;
			this.isAdditive = isAdditive;
		}

		/**
		 * Проверяет, нужно ли определять тип из документации метода
		 */
		public boolean isMethodCallType() {
			return METHOD_CALL_MARKER.equals(className) && methodName != null;
		}

		/**
		 * Проверяет, это $MAIN:var
		 */
		public boolean isMainVar() {
			return "MAIN:".equals(varPrefix);
		}

		/**
		 * Проверяет, это $self.var
		 */
		public boolean isSelfVar() {
			return "self.".equals(varPrefix);
		}

		/**
		 * Проверяет, это простая $var (без префикса)
		 */
		public boolean isSimpleVar() {
			return varPrefix == null;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			VariableTypeInfo that = (VariableTypeInfo) o;
			return offset == that.offset &&
					isLocal == that.isLocal &&
					className.equals(that.className) &&
					java.util.Objects.equals(ownerClass, that.ownerClass) &&
					java.util.Objects.equals(ownerMethod, that.ownerMethod) &&
					java.util.Objects.equals(methodName, that.methodName) &&
					java.util.Objects.equals(targetClassName, that.targetClassName) &&
					java.util.Objects.equals(receiverVarKey, that.receiverVarKey) &&
					java.util.Objects.equals(varPrefix, that.varPrefix) &&
					java.util.Objects.equals(columns, that.columns) &&
					java.util.Objects.equals(sourceVarKey, that.sourceVarKey) &&
					java.util.Objects.equals(hashKeys, that.hashKeys) &&
					java.util.Objects.equals(hashSourceVars, that.hashSourceVars);
		}

		@Override
		public int hashCode() {
			return java.util.Objects.hash(offset, className, ownerClass, ownerMethod, methodName, targetClassName, receiverVarKey, varPrefix, isLocal, columns, sourceVarKey, hashKeys, hashSourceVars);
		}

		@Override
		public String toString() {
			return "VariableTypeInfo{offset=" + offset + ", className='" + className +
					"', ownerClass='" + ownerClass + "', ownerMethod='" + ownerMethod +
					"', methodName='" + methodName + "', targetClassName='" + targetClassName +
					"', receiverVarKey='" + receiverVarKey +
					"', varPrefix='" + varPrefix + "', isLocal=" + isLocal +
					"', columns=" + columns + ", sourceVarKey='" + sourceVarKey +
					"', hashKeys=" + (hashKeys != null ? hashKeys.keySet() : "null") +
					", hashSourceVars=" + hashSourceVars + "}";
		}
	}

	@NotNull
	@Override
	public ID<String, List<VariableTypeInfo>> getName() {
		return NAME;
	}

	@NotNull
	@Override
	public DataIndexer<String, List<VariableTypeInfo>, FileContent> getIndexer() {
		return inputData -> {
			String text = inputData.getContentAsText().toString();
			// Бинарный файл — не индексируем
			if (isBinaryContent(text)) return java.util.Collections.emptyMap();
			return parseVariablesFromText(text);
		};
	}

	/**
	 * Проверяет, содержит ли текст бинарные данные (нулевые байты).
	 */
	static boolean isBinaryContent(@NotNull String text) {
		int checkLen = Math.min(text.length(), 8192);
		for (int i = 0; i < checkLen; i++) {
			if (text.charAt(i) == '\0') return true;
		}
		return false;
	}

	/**
	 * Результат парсинга колонок: либо список колонок, либо ссылка на переменную-источник.
	 */
	static final class ColumnsResult {
		final @Nullable List<String> columns;
		final @Nullable String sourceVarKey;

		ColumnsResult(@Nullable List<String> columns, @Nullable String sourceVarKey) {
			this.columns = columns;
			this.sourceVarKey = sourceVarKey;
		}

		static ColumnsResult ofColumns(@Nullable List<String> columns) {
			return new ColumnsResult(columns, null);
		}

		static ColumnsResult ofSource(@NotNull String sourceVarKey) {
			return new ColumnsResult(null, sourceVarKey);
		}

		static final ColumnsResult EMPTY = new ColumnsResult(null, null);
	}

	/**
	 * Извлекает колонки таблицы из выражения присваивания.
	 * Единая точка входа — определяет тип (create/sql) и делегирует парсинг.
	 */
	static @NotNull ColumnsResult extractColumnsResult(@NotNull String text, int constructorOffset, @NotNull String className) {
		if ("table".equals(className)) {
			return extractTableColumnsResult(text, constructorOffset);
		}
		return ColumnsResult.EMPTY;
	}

	/**
	 * Извлекает колонки таблицы из ^table::create{}, ^table::sql{}, ^table::create[$var].
	 */
	static @NotNull ColumnsResult extractTableColumnsResult(@NotNull String text, int constructorOffset) {
		int textLen = text.length();

		// Ищем :: или : после offset
		int pos = constructorOffset;
		while (pos < textLen && text.charAt(pos) != ':') pos++;
		if (pos >= textLen) return ColumnsResult.EMPTY;

		pos++;
		if (pos < textLen && text.charAt(pos) == ':') pos++;

		// Пропускаем пробелы
		while (pos < textLen && Character.isWhitespace(text.charAt(pos))) pos++;

		// Читаем имя метода
		int methodStart = pos;
		while (pos < textLen && (Character.isLetterOrDigit(text.charAt(pos)) || text.charAt(pos) == '_')) pos++;
		if (pos == methodStart) return ColumnsResult.EMPTY;
		String methodName = text.substring(methodStart, pos);

		// Пропускаем пробелы
		while (pos < textLen && Character.isWhitespace(text.charAt(pos))) pos++;

		if ("create".equals(methodName)) {
			if (pos < textLen && text.charAt(pos) == '[') {
				// ^table::create[...] — копирование или [nameless]{...}
				int bracketEnd = ru.artlebedev.parser3.lexer.Parser3LexerUtils.findMatchingSquareBracket(text, pos, textLen);
				if (bracketEnd == -1) return ColumnsResult.EMPTY;

				String bracketContent = text.substring(pos + 1, bracketEnd).trim();

				// Если после ] есть { — это [nameless]{...}
				int afterBracket = bracketEnd + 1;
				while (afterBracket < textLen && Character.isWhitespace(text.charAt(afterBracket))) afterBracket++;
				if (afterBracket < textLen && text.charAt(afterBracket) == '{') {
					return ColumnsResult.EMPTY;
				}

				// Иначе ^table::create[$var] или ^table::create[$var;options]
				String sourceKey = extractSourceVarKey(bracketContent);
				if (sourceKey != null) {
					return ColumnsResult.ofSource(sourceKey);
				}
				return ColumnsResult.EMPTY;
			}

			if (pos < textLen && text.charAt(pos) == '{') {
				int braceEnd = ru.artlebedev.parser3.lexer.Parser3LexerUtils.findMatchingBrace(text, pos, textLen);
				if (braceEnd == -1) braceEnd = textLen;
				String content = text.substring(pos + 1, braceEnd);
				String separator = extractTableCreateSeparator(text, braceEnd + 1, textLen);
				String encloser = extractTableCreateEncloser(text, braceEnd + 1, textLen);
				return ColumnsResult.ofColumns(parseTableCreateColumns(content, separator, encloser));
			}
		} else if ("sql".equals(methodName)) {
			if (pos < textLen && text.charAt(pos) == '{') {
				int braceEnd = ru.artlebedev.parser3.lexer.Parser3LexerUtils.findMatchingBrace(text, pos, textLen);
				if (braceEnd == -1) braceEnd = textLen;
				String content = text.substring(pos + 1, braceEnd);
				return ColumnsResult.ofColumns(parseSqlSelectColumns(content));
			}
		}

		return ColumnsResult.EMPTY;
	}

	/**
	 * Извлекает ключ переменной-источника из содержимого [...].
	 * Поддерживает: $var, $self.var, $MAIN:var, $var;options.
	 */
	private static @Nullable String extractSourceVarKey(@NotNull String content) {
		// Берём первый параметр (до ; если есть)
		String firstParam = content;
		int depth = 0;
		for (int i = 0; i < content.length(); i++) {
			char ch = content.charAt(i);
			if (ch == '(' || ch == '[' || ch == '{') depth++;
			else if (ch == ')' || ch == ']' || ch == '}') { if (depth > 0) depth--; }
			else if (ch == ';' && depth == 0) { firstParam = content.substring(0, i); break; }
		}
		firstParam = firstParam.trim();

		if (!firstParam.startsWith("$") || firstParam.length() < 2) return null;
		String varRef = firstParam.substring(1);

		// Валидация: буквы, цифры, _, точка, двоеточие
		for (int i = 0; i < varRef.length(); i++) {
			char ch = varRef.charAt(i);
			if (!Character.isLetterOrDigit(ch) && ch != '_' && ch != '.' && ch != ':') {
				return null;
			}
		}
		return varRef;
	}


	/**
	 * Парсит колонки из ^table::create{col1\tcol2\ndata...}.
	 * Первая строка — имена колонок через TAB.
	 */
	private static @Nullable List<String> parseTableCreateColumns(@NotNull String content) {
		return parseTableCreateColumns(content, "\t", null);
	}

	private static @Nullable List<String> parseTableCreateColumns(@NotNull String content, @NotNull String separator) {
		return parseTableCreateColumns(content, separator, null);
	}

	private static @Nullable List<String> parseTableCreateColumns(
			@NotNull String content,
			@NotNull String separator,
			@Nullable String encloser
	) {
		// Извлекаем первую строку до \n или конца
		int lineEnd = 0;
		while (lineEnd < content.length()) {
			char ch = content.charAt(lineEnd);
			if (ch == '\n' || ch == '\r') break;
			lineEnd++;
		}

		if (lineEnd == 0) return null;

		String firstLine = content.substring(0, lineEnd).trim();
		if (firstLine.isEmpty()) return null;

		List<String> parts = splitTableHeader(firstLine, separator, encloser);
		if (parts.isEmpty()) return null;

		List<String> columns = new ArrayList<>();
		for (String part : parts) {
			String col = stripTableEncloser(part.trim(), encloser);
			if (!col.isEmpty()) {
				columns.add(col);
			}
		}

		return columns.isEmpty() ? null : columns;
	}

	private static @NotNull String extractTableCreateSeparator(@NotNull String text, int pos, int textLen) {
		String separator = extractTableCreateOption(text, pos, textLen, "$.separator[");
		return separator == null || separator.isEmpty() ? "\t" : separator;
	}

	private static @Nullable String extractTableCreateEncloser(@NotNull String text, int pos, int textLen) {
		String value = extractTableCreateOption(text, pos, textLen, "$.encloser[");
		return value == null || value.isEmpty() ? null : value;
	}

	private static @Nullable String extractTableCreateOption(
			@NotNull String text,
			int pos,
			int textLen,
			@NotNull String marker
	) {
		while (pos < textLen && Character.isWhitespace(text.charAt(pos))) pos++;
		if (pos >= textLen || text.charAt(pos) != '[') return null;

		int optionsEnd = P3VariableParser.findMatchingBracket(text, pos, textLen, '[');
		if (optionsEnd <= pos) return null;

		String options = text.substring(pos + 1, optionsEnd);
		int valueStart = options.indexOf(marker);
		if (valueStart < 0) return null;

		valueStart += marker.length();
		int valueEnd = options.indexOf(']', valueStart);
		if (valueEnd < valueStart) return null;

		return options.substring(valueStart, valueEnd);
	}

	private static @NotNull List<String> splitTableHeader(
			@NotNull String firstLine,
			@NotNull String separator,
			@Nullable String encloser
	) {
		if (separator.isEmpty()) return java.util.Collections.singletonList(firstLine);

		List<String> parts = new ArrayList<>();
		int start = 0;
		boolean insideEncloser = false;
		char encloserChar = encloser != null && encloser.length() == 1 ? encloser.charAt(0) : 0;

		for (int i = 0; i < firstLine.length(); i++) {
			char ch = firstLine.charAt(i);
			if (encloserChar != 0 && ch == encloserChar) {
				insideEncloser = !insideEncloser;
			}
			if (!insideEncloser && firstLine.startsWith(separator, i)) {
				parts.add(firstLine.substring(start, i));
				i += separator.length() - 1;
				start = i + 1;
			}
		}
		parts.add(firstLine.substring(start));
		return parts;
	}

	private static @NotNull String stripTableEncloser(@NotNull String value, @Nullable String encloser) {
		if (encloser == null || encloser.isEmpty()) return value;
		if (value.startsWith(encloser) && value.endsWith(encloser) && value.length() >= encloser.length() * 2) {
			return value.substring(encloser.length(), value.length() - encloser.length());
		}
		return value;
	}

	/**
	 * Парсит имена колонок из SQL SELECT запроса.
	 * Поддерживает:
	 * - SELECT name, uri FROM ... → [name, uri]
	 * - SELECT t.name AS menu_name → [menu_name]
	 * - SELECT 'text' AS 'some column name' → [some column name]
	 * - SELECT (SELECT count(*) FROM t) AS cnt → [cnt]
	 * - SELECT lower(x) → [lower(x)] (выражение как есть, без AS)
	 * - SELECT count(*) → [count(*)]
	 * - SELECT * FROM ... → null
	 * - Весь SQL в скобках (SELECT ... UNION ...) → парсит внутри
	 * - Пропускает Parser3 конструкции ($var, ^method[])
	 */
	public static @Nullable List<String> parseSqlSelectColumns(@NotNull String sqlContent) {
		List<String> cachedColumns = SQL_COLUMNS_CACHE.get(sqlContent);
		if (cachedColumns != null) {
			return cachedColumns == SQL_COLUMNS_NULL ? null : cachedColumns;
		}

		String cleaned = sqlContent.trim();
		if (containsParser3InSql(sqlContent)) {
			cleaned = ru.artlebedev.parser3.injector.Parser3TextCleaner.cleanSqlContent(
					sqlContent,
					ru.artlebedev.parser3.injector.Parser3TextCleaner.SqlCleanMode.FAST_INDEXING
			).trim();
		}

		// Если весь SQL обёрнут в скобки: (SELECT ... UNION ...) — разворачиваем
		if (cleaned.startsWith("(")) {
			int matchEnd = findMatchingParen(cleaned, 0);
			if (matchEnd != -1) {
				cleaned = cleaned.substring(1, matchEnd).trim();
			}
		}

		// Ищем SELECT (без учёта регистра)
		String upper = cleaned.toUpperCase();
		int selectIdx = findTopLevelKeyword(upper, "SELECT", 0);
		if (selectIdx == -1) return null;

		int afterSelect = selectIdx + 6; // "SELECT".length()

		// Пропускаем DISTINCT / ALL если есть
		String afterSelStr = upper.substring(afterSelect).trim();
		if (afterSelStr.startsWith("DISTINCT") && (afterSelect + (upper.substring(afterSelect).indexOf("DISTINCT")) + 8 < upper.length())) {
			int distIdx = upper.indexOf("DISTINCT", afterSelect);
			if (distIdx != -1) afterSelect = distIdx + 8;
		} else if (afterSelStr.startsWith("ALL") && afterSelStr.length() > 3 && !Character.isLetterOrDigit(afterSelStr.charAt(3))) {
			int allIdx = upper.indexOf("ALL", afterSelect);
			if (allIdx != -1) afterSelect = allIdx + 3;
		}

		// Ищем FROM/WHERE/GROUP/ORDER/HAVING/LIMIT/UNION на верхнем уровне
		int endIdx = findSelectEnd(upper, afterSelect);
		if (endIdx == -1) endIdx = cleaned.length();

		String selectPart = cleaned.substring(afterSelect, endIdx).trim();
		if (selectPart.isEmpty() || selectPart.equals("*")) return null;

		// Разбиваем по запятым верхнего уровня (не внутри скобок и строк)
		List<String> items = splitByTopLevelCommas(selectPart);

		List<String> columns = new ArrayList<>();
		for (String item : items) {
			String col = extractColumnName(item.trim());
			if (col != null && !col.isEmpty()) {
				columns.add(col);
			}
		}

		List<String> result = columns.isEmpty() ? null : List.copyOf(columns);
		SQL_COLUMNS_CACHE.put(sqlContent, result != null ? result : SQL_COLUMNS_NULL);
		return result;
	}

	private static boolean containsParser3InSql(@NotNull String sqlContent) {
		for (int i = 0; i < sqlContent.length(); i++) {
			char ch = sqlContent.charAt(i);
			if ((ch == '^' || ch == '$') && !ru.artlebedev.parser3.lexer.Parser3LexerUtils.isEscapedByCaret(sqlContent, i)) {
				return true;
			}
		}
		return false;
	}



	/**
	 * Ищет парную закрывающую ) для ( на указанной позиции.
	 * Учитывает вложенные скобки и строки в кавычках.
	 */
	private static int findMatchingParen(@NotNull String text, int openPos) {
		int depth = 0;
		boolean inSingleQuote = false;
		boolean inDoubleQuote = false;
		for (int i = openPos; i < text.length(); i++) {
			char ch = text.charAt(i);
			if (inSingleQuote) {
				if (ch == '\'' && (i + 1 >= text.length() || text.charAt(i + 1) != '\'')) inSingleQuote = false;
				else if (ch == '\'' && i + 1 < text.length() && text.charAt(i + 1) == '\'') i++; // escaped ''
				continue;
			}
			if (inDoubleQuote) {
				if (ch == '"') inDoubleQuote = false;
				continue;
			}
			if (ch == '\'') { inSingleQuote = true; continue; }
			if (ch == '"') { inDoubleQuote = true; continue; }
			if (ch == '(') depth++;
			else if (ch == ')') {
				depth--;
				if (depth == 0) return i;
			}
		}
		return -1;
	}

	/**
	 * Ищет ключевое слово SQL на верхнем уровне (не внутри скобок).
	 * Ключевое слово должно быть отделено пробелами/переносами строк.
	 */
	private static int findTopLevelKeyword(@NotNull String upperSql, @NotNull String keyword, int fromIndex) {
		int depth = 0;
		int len = upperSql.length();
		int kwLen = keyword.length();
		boolean inSingleQuote = false;
		boolean inDoubleQuote = false;
		boolean inBacktick = false;

		for (int i = fromIndex; i <= len - kwLen; i++) {
			char ch = upperSql.charAt(i);
			if (inSingleQuote) {
				if (ch == '\'' && (i + 1 >= len || upperSql.charAt(i + 1) != '\'')) inSingleQuote = false;
				else if (ch == '\'' && i + 1 < len && upperSql.charAt(i + 1) == '\'') i++;
				continue;
			}
			if (inDoubleQuote) {
				if (ch == '"') inDoubleQuote = false;
				continue;
			}
			if (inBacktick) {
				if (ch == '`') inBacktick = false;
				continue;
			}
			if (ch == '\'') {
				inSingleQuote = true;
				continue;
			}
			if (ch == '"') {
				inDoubleQuote = true;
				continue;
			}
			if (ch == '`') {
				inBacktick = true;
				continue;
			}
			if (ch == '(') depth++;
			else if (ch == ')') { if (depth > 0) depth--; }
			else if (depth == 0 && upperSql.startsWith(keyword, i)) {
				// Проверяем что слово отделено (не часть другого идентификатора)
				boolean startOk = (i == 0 || (!Character.isLetterOrDigit(upperSql.charAt(i - 1)) && upperSql.charAt(i - 1) != '_'));
				boolean endOk = (i + kwLen >= len || (!Character.isLetterOrDigit(upperSql.charAt(i + kwLen)) && upperSql.charAt(i + kwLen) != '_'));
				if (startOk && endOk) {
					return i;
				}
			}
		}
		return -1;
	}

	/**
	 * Ищет конец SELECT части (FROM/WHERE/GROUP/ORDER/HAVING/LIMIT/UNION/;).
	 */
	private static int findSelectEnd(@NotNull String upperSql, int fromIndex) {
		String[] keywords = {"FROM", "WHERE", "GROUP", "ORDER", "HAVING", "LIMIT", "UNION", "EXCEPT", "INTERSECT"};
		int earliest = -1;
		for (String kw : keywords) {
			int pos = findTopLevelKeyword(upperSql, kw, fromIndex);
			if (pos != -1 && (earliest == -1 || pos < earliest)) {
				earliest = pos;
			}
		}
		return earliest;
	}

	/**
	 * Разбивает строку по запятым верхнего уровня (не внутри скобок и строк).
	 */
	private static @NotNull List<String> splitByTopLevelCommas(@NotNull String str) {
		List<String> result = new ArrayList<>();
		int depth = 0;
		int start = 0;
		boolean inSingleQuote = false;
		boolean inDoubleQuote = false;
		boolean inBacktick = false;

		for (int i = 0; i < str.length(); i++) {
			char ch = str.charAt(i);
			if (inSingleQuote) {
				if (ch == '\'' && (i + 1 >= str.length() || str.charAt(i + 1) != '\'')) inSingleQuote = false;
				else if (ch == '\'' && i + 1 < str.length() && str.charAt(i + 1) == '\'') i++;
				continue;
			}
			if (inDoubleQuote) {
				if (ch == '"') inDoubleQuote = false;
				continue;
			}
			if (inBacktick) {
				if (ch == '`') inBacktick = false;
				continue;
			}
			if (ch == '\'') { inSingleQuote = true; continue; }
			if (ch == '"') { inDoubleQuote = true; continue; }
			if (ch == '`') { inBacktick = true; continue; }
			if (ch == '(') depth++;
			else if (ch == ')') { if (depth > 0) depth--; }
			else if (ch == ',' && depth == 0) {
				result.add(str.substring(start, i));
				start = i + 1;
			}
		}
		result.add(str.substring(start));
		return result;
	}


	/**
	 * Извлекает имя колонки из одного элемента SELECT.
	 * Правила:
	 * - С AS: "expr AS alias" → alias (кавычки убираются)
	 * - Простой идентификатор: "name" → "name"
	 * - Через точку: "t.name" → "name"
	 * - Выражение без AS: "lower(x)" → "lower(x)", "count(*)" → "count(*)"
	 * - Подзапрос без AS: "(select ...)" → "(select ...)"
	 * - "*" → null
	 */
	private static @Nullable String extractColumnName(@NotNull String item) {
		if (item.isEmpty() || item.equals("*")) return null;

		// Ищем AS на верхнем уровне (без учёта регистра)
		String upper = item.toUpperCase();
		int asIdx = -1;
		int depth = 0;
		boolean inSQ = false, inDQ = false, inBQ = false;
		for (int i = 0; i <= upper.length() - 2; i++) {
			char ch = item.charAt(i);
			if (inSQ) {
				if (ch == '\'' && (i + 1 >= item.length() || item.charAt(i + 1) != '\'')) inSQ = false;
				else if (ch == '\'' && i + 1 < item.length() && item.charAt(i + 1) == '\'') i++;
				continue;
			}
			if (inDQ) { if (ch == '"') inDQ = false; continue; }
			if (inBQ) { if (ch == '`') inBQ = false; continue; }
			if (ch == '\'') { inSQ = true; continue; }
			if (ch == '"') { inDQ = true; continue; }
			if (ch == '`') { inBQ = true; continue; }
			if (ch == '(') depth++;
			else if (ch == ')') { if (depth > 0) depth--; }
			else if (depth == 0 && upper.charAt(i) == 'A' && upper.charAt(i + 1) == 'S') {
				boolean startOk = (i == 0 || !Character.isLetterOrDigit(upper.charAt(i - 1)) && upper.charAt(i - 1) != '_');
				boolean endOk = (i + 2 >= upper.length() || !Character.isLetterOrDigit(upper.charAt(i + 2)) && upper.charAt(i + 2) != '_');
				if (startOk && endOk) {
					asIdx = i;
				}
			}
		}

		if (asIdx != -1) {
			String alias = item.substring(asIdx + 2).trim();
			// Убираем кавычки: 'some name' → some name, "col" → col, `col` → col
			if ((alias.startsWith("'") && alias.endsWith("'")) ||
					(alias.startsWith("\"") && alias.endsWith("\"")) ||
					(alias.startsWith("`") && alias.endsWith("`"))) {
				alias = alias.substring(1, alias.length() - 1);
			}
			return alias.isEmpty() ? null : alias;
		}

		// Нет AS — анализируем выражение
		String trimmed = item.trim();

		// Backtick-quoted идентификатор: `s1 s2` или table.`s1 s2` → s1 s2
		if (trimmed.contains("`")) {
			int lastDot = trimmed.lastIndexOf('.');
			String part = lastDot >= 0 ? trimmed.substring(lastDot + 1).trim() : trimmed;
			if (part.startsWith("`") && part.endsWith("`") && part.length() > 2) {
				return part.substring(1, part.length() - 1);
			}
		}

		// Если это простой идентификатор (возможно с точкой: t.name)
		if (isSimpleSqlIdentifier(trimmed)) {
			// Берём часть после последней точки
			int dotIdx = trimmed.lastIndexOf('.');
			String name = dotIdx >= 0 ? trimmed.substring(dotIdx + 1) : trimmed;
			// Проверяем что это не SQL ключевое слово
			if (isSqlKeyword(name.toUpperCase())) return null;
			return name;
		}

		// Выражение (функция, подзапрос) без AS — сохраняем как есть
		// Нормализуем пробелы: несколько пробелов/табов/переносов → один пробел
		String normalized = trimmed.replaceAll("\\s+", " ").trim();
		return normalized.isEmpty() ? null : normalized;
	}

	/**
	 * Проверяет, является ли строка простым SQL идентификатором (возможно с точкой).
	 * Примеры: "name", "t.name", "schema.table.col" → true
	 * "lower(x)", "count(*)", "(select ...)" → false
	 */
	private static boolean isSimpleSqlIdentifier(@NotNull String str) {
		for (int i = 0; i < str.length(); i++) {
			char ch = str.charAt(i);
			if (!Character.isLetterOrDigit(ch) && ch != '_' && ch != '.') {
				return false;
			}
		}
		return !str.isEmpty() && !Character.isDigit(str.charAt(0));
	}


	/**
	 * Проверяет, является ли слово SQL ключевым словом.
	 */
	private static boolean isSqlKeyword(@NotNull String word) {
		switch (word) {
			case "SELECT": case "FROM": case "WHERE": case "AND": case "OR":
			case "NOT": case "IN": case "EXISTS": case "BETWEEN": case "LIKE":
			case "IS": case "NULL": case "TRUE": case "FALSE": case "CASE":
			case "WHEN": case "THEN": case "ELSE": case "END": case "AS":
			case "ON": case "JOIN": case "LEFT": case "RIGHT": case "INNER":
			case "OUTER": case "CROSS": case "DISTINCT": case "ALL":
			case "GROUP": case "ORDER": case "BY": case "HAVING":
			case "LIMIT": case "OFFSET": case "UNION": case "EXCEPT":
			case "INTERSECT": case "INTO": case "VALUES": case "SET":
			case "UPDATE": case "DELETE": case "INSERT":
				return true;
			default:
				return false;
		}
	}

	/**
	 * Индексирует присваивания по указанному паттерну.
	 *
	 * @param text текст файла
	 * @param pattern паттерн для поиска
	 * @param result результат индексации
	 * @param classBoundaries границы классов
	 * @param methodBoundaries границы методов
	 * @param checkBuiltin проверять ли что класс встроенный
	 */
	private static void indexPattern(
			String text,
			Pattern pattern,
			Map<String, List<VariableTypeInfo>> result,
			List<ru.artlebedev.parser3.utils.Parser3ClassUtils.ClassBoundary> classBoundaries,
			List<ru.artlebedev.parser3.utils.Parser3ClassUtils.MethodBoundary> methodBoundaries,
			boolean checkBuiltin
	) {
		Matcher matcher = pattern.matcher(text);
		while (matcher.find()) {
			String prefix = matcher.group(1);   // "self." или "MAIN:" или null
			String varName = matcher.group(2);
			String className = matcher.group(3);
			int offset = matcher.start();

			// Пропускаем комментарии
			if (ru.artlebedev.parser3.utils.Parser3ClassUtils.isOffsetInComment(text, offset)) continue;

			// Для встроенных классов проверяем что класс действительно встроенный
			if (checkBuiltin && !ru.artlebedev.parser3.lang.Parser3BuiltinMethods.isBuiltinClass(className)) continue;

			// Определяем класс-владелец и метод-владелец
			String ownerClass = ru.artlebedev.parser3.utils.Parser3ClassUtils.findOwnerClass(offset, classBoundaries);
			ru.artlebedev.parser3.utils.Parser3ClassUtils.MethodBoundary ownerMethodBoundary =
					ru.artlebedev.parser3.utils.Parser3ClassUtils.findOwnerMethod(offset, methodBoundaries);
			String ownerMethod = ownerMethodBoundary != null ? ownerMethodBoundary.name : null;

			// Определяем локальность переменной
			boolean isLocal = computeIsLocal(prefix, varName, ownerClass, ownerMethodBoundary, text, classBoundaries);

			// Ключ индекса: префикс + имя переменной
			String indexKey = buildIndexKey(prefix, varName);

			// Парсим колонки (table::create, table::sql, table::create[$var])
			List<String> columns = null;
			String sourceVarKey = null;
			if ("table".equals(className)) {
				// Передаём позицию имени класса (group 3), а не $ (matcher.start())
				// Иначе для $MAIN:list[^table::] extractColumnsResult найдёт : из MAIN:
				ColumnsResult cr = extractColumnsResult(text, matcher.start(3), className);
				columns = cr.columns;
				sourceVarKey = cr.sourceVarKey;
			}

			VariableTypeInfo info = new VariableTypeInfo(offset, className, ownerClass, ownerMethod, null, null, prefix, isLocal, columns, sourceVarKey);

			result.computeIfAbsent(indexKey, k -> new ArrayList<>())
					.add(info);
		}
	}

	/**
	 * Строит ключ индекса из префикса и имени переменной.
	 * "self." + "var" -> "self.var"
	 * "MAIN:" + "var" -> "MAIN:var"
	 * null + "var" -> "var"
	 */
	static @NotNull String buildIndexKey(@Nullable String prefix, @NotNull String varName) {
		if (prefix == null || prefix.isEmpty()) {
			return varName;
		}
		return prefix + varName;
	}

	/**
	 * Определяет, является ли переменная локальной в своём методе.
	 * $self.var и $MAIN:var — всегда глобальные (не локальные).
	 * $var локальна если: @OPTIONS locals для контекста, или [locals] у метода, или [varName] у метода.
	 */
	static boolean computeIsLocal(
			@Nullable String prefix,
			@NotNull String varName,
			@Nullable String ownerClass,
			@Nullable ru.artlebedev.parser3.utils.Parser3ClassUtils.MethodBoundary ownerMethod,
			@NotNull String text,
			@NotNull List<ru.artlebedev.parser3.utils.Parser3ClassUtils.ClassBoundary> classBoundaries
	) {
		// $self.var и $MAIN:var — всегда глобальные
		if (prefix != null && !prefix.isEmpty()) return false;
		// Вне метода — глобальная
		if (ownerMethod == null) return false;
		// $result — всегда локальна в пределах метода (возвращаемое значение метода)
		if ("result".equals(varName)) return true;
		// Проверяем @OPTIONS locals для контекста (класса или MAIN)
		int methodStart = ownerMethod.start;
		if (ownerClass == null) {
			if (ru.artlebedev.parser3.utils.Parser3ClassUtils.hasMainLocalsBefore(text, classBoundaries, methodStart)) return true;
		} else {
			for (ru.artlebedev.parser3.utils.Parser3ClassUtils.ClassBoundary cb : classBoundaries) {
				if (cb.name.equals(ownerClass)) {
					if (ru.artlebedev.parser3.utils.Parser3ClassUtils.hasClassLocalsBefore(text, cb, methodStart)) return true;
					break;
				}
			}
		}
		// Проверяем [locals] или [varName] у метода
		return ownerMethod.isVariableLocal(varName);
	}

	/**
	 * Индексирует статические методы встроенных классов.
	 * Например: $data[^json:parse[...]] — тип hash (json:parse возвращает hash)
	 */
	private static void indexStaticMethods(
			String text,
			Map<String, List<VariableTypeInfo>> result,
			List<ru.artlebedev.parser3.utils.Parser3ClassUtils.ClassBoundary> classBoundaries,
			List<ru.artlebedev.parser3.utils.Parser3ClassUtils.MethodBoundary> methodBoundaries
	) {
		Matcher matcher = BUILTIN_STATIC_METHOD_PATTERN.matcher(text);
		while (matcher.find()) {
			String prefix = matcher.group(1);   // "self." или "MAIN:" или null
			String varName = matcher.group(2);
			String className = matcher.group(3);
			String methodName = matcher.group(4);
			int offset = matcher.start();

			// Пропускаем комментарии
			if (ru.artlebedev.parser3.utils.Parser3ClassUtils.isOffsetInComment(text, offset)) continue;

			// Проверяем что класс встроенный
			if (!ru.artlebedev.parser3.lang.Parser3BuiltinMethods.isBuiltinClass(className)) {
				continue;
			}

			// Определяем тип возвращаемого значения статического метода
			String returnType = getStaticMethodReturnType(className, methodName);
			if (returnType == null) {
				continue;
			}

			// Определяем класс-владелец и метод-владелец
			String ownerClass = ru.artlebedev.parser3.utils.Parser3ClassUtils.findOwnerClass(offset, classBoundaries);
			ru.artlebedev.parser3.utils.Parser3ClassUtils.MethodBoundary ownerMethodBoundary =
					ru.artlebedev.parser3.utils.Parser3ClassUtils.findOwnerMethod(offset, methodBoundaries);
			String ownerMethod = ownerMethodBoundary != null ? ownerMethodBoundary.name : null;

			boolean isLocal = computeIsLocal(prefix, varName, ownerClass, ownerMethodBoundary, text, classBoundaries);

			// Ключ индекса: префикс + имя переменной
			String indexKey = buildIndexKey(prefix, varName);

			// Парсим колонки (table:sql → table с колонками из SQL)
			List<String> columns = null;
			String sourceVarKey = null;
			if ("table".equals(returnType)) {
				// Передаём позицию имени класса (group 3), а не $ (matcher.start())
				ColumnsResult cr = extractColumnsResult(text, matcher.start(3), "table");
				columns = cr.columns;
				sourceVarKey = cr.sourceVarKey;
			}

			VariableTypeInfo info = new VariableTypeInfo(offset, returnType, ownerClass, ownerMethod, null, null, prefix, isLocal, columns, sourceVarKey);

			result.computeIfAbsent(indexKey, k -> new ArrayList<>())
					.add(info);
		}
	}

	/**
	 * Определяет тип возвращаемого значения статического метода встроенного класса.
	 * Возвращает null если тип неизвестен или может быть разным.
	 */
	static @Nullable String getStaticMethodReturnType(String className, String methodName) {
		// Специальные случаи — статические методы с известным типом возврата
		switch (className.toLowerCase(java.util.Locale.ROOT)) {
			case "curl":
				// ^curl:load[] возвращает HTTP-файл
				if ("load".equals(methodName)) return "file-http";
				// ^curl:info[] возвращает hash
				if ("info".equals(methodName)) return "hash";
				break;

			case "file":
				// ^file:list[] возвращает table
				if ("list".equals(methodName)) return "table";
				// ^file:stat[] возвращает hash
				if ("stat".equals(methodName)) return "hash";
				// ^file:find[] возвращает file
				if ("find".equals(methodName)) return "string";
				break;

			case "string":
				// ^string:sql{} возвращает string
				if ("sql".equals(methodName)) return "string";
				if ("base64".equals(methodName)) return "string";
				break;

			case "table":
				// ^table:sql{} возвращает table
				if ("sql".equals(methodName)) return "table";
				break;

			case "array":
				if ("sql".equals(methodName)) return "array";
				break;

			case "hash":
				if ("sql".equals(methodName)) return "hash";
				break;

			case "int":
				if ("sql".equals(methodName)) return "int";
				break;

			case "double":
				if ("sql".equals(methodName)) return "double";
				break;

			case "date":
				// ^date:calendar[] возвращает table
				if ("calendar".equals(methodName)) return "table";
				if ("last-day".equals(methodName)) return "int";
				break;

			case "reflection":
				// ^reflection:fields[] возвращает hash
				if ("fields".equals(methodName)) return "hash";
				// ^reflection:methods[] возвращает hash
				if ("methods".equals(methodName)) return "hash";
				// ^reflection:classes[] возвращает hash
				if ("classes".equals(methodName)) return "hash";
				break;

			case "mail":
				// ^mail:received[] возвращает hash
				if ("received".equals(methodName)) return "hash";
				break;

			case "image":
				if ("measure".equals(methodName)) return "image";
				break;

			case "inet":
				if ("name2ip".equals(methodName)) return "string";
				if ("ip2name".equals(methodName)) return "string";
				break;

			// json:parse НЕ определяем — результат может быть hash, array или что-то другое
		}

		return null;
	}

	/**
	 * Определяет тип встроенного конструктора ^class::method.
	 * Возвращает null, если нужен обычный тип класса.
	 */
	static @Nullable String getBuiltinConstructorReturnType(String className, String constructorName) {
		switch (className.toLowerCase(java.util.Locale.ROOT)) {
			case "file":
				switch (constructorName) {
					case "cgi":
					case "exec":
						return "file-exec";
					case "stat":
						return "file-local";
					case "sql":
						return "file-sql";
					default:
						return null;
				}
			default:
				return null;
		}
	}

	/**
	 * Индексирует вызовы пользовательских методов.
	 * Тип определяется из документации метода ($result) в runtime.
	 *
	 * Пример: $data[^res_hash[]] — тип будет из # $result(hash) в документации @res_hash
	 */
	private static void indexUserMethodCalls(
			String text,
			Map<String, List<VariableTypeInfo>> result,
			List<ru.artlebedev.parser3.utils.Parser3ClassUtils.ClassBoundary> classBoundaries,
			List<ru.artlebedev.parser3.utils.Parser3ClassUtils.MethodBoundary> methodBoundaries
	) {
		Matcher matcher = USER_METHOD_CALL_PATTERN.matcher(text);
		while (matcher.find()) {
			String prefix = matcher.group(1);   // "self." или "MAIN:" или null
			String varName = matcher.group(2);
			String methodName = matcher.group(3);
			int offset = matcher.start();

			// Пропускаем комментарии
			if (ru.artlebedev.parser3.utils.Parser3ClassUtils.isOffsetInComment(text, offset)) continue;

			// Пропускаем встроенные классы (они обрабатываются в других паттернах)
			if (ru.artlebedev.parser3.lang.Parser3BuiltinMethods.isBuiltinClass(methodName)) {
				continue;
			}

			// Пропускаем системные операторы
			if (isSystemOperator(methodName)) {
				continue;
			}

			// Определяем класс-владелец и метод-владелец
			String ownerClass = ru.artlebedev.parser3.utils.Parser3ClassUtils.findOwnerClass(offset, classBoundaries);
			ru.artlebedev.parser3.utils.Parser3ClassUtils.MethodBoundary ownerMethodBoundary =
					ru.artlebedev.parser3.utils.Parser3ClassUtils.findOwnerMethod(offset, methodBoundaries);
			String ownerMethod = ownerMethodBoundary != null ? ownerMethodBoundary.name : null;

			boolean isLocal = computeIsLocal(prefix, varName, ownerClass, ownerMethodBoundary, text, classBoundaries);

			// Ключ индекса: префикс + имя переменной
			String indexKey = buildIndexKey(prefix, varName);

			// Создаём запись с маркером METHOD_CALL
			VariableTypeInfo info = new VariableTypeInfo(
					offset,
					VariableTypeInfo.METHOD_CALL_MARKER,
					ownerClass,
					ownerMethod,
					methodName,
					null,
					prefix,
					isLocal
			);

			result.computeIfAbsent(indexKey, k -> new ArrayList<>())
					.add(info);
		}
	}

	/**
	 * Проверяет, является ли имя системным оператором Parser3
	 */
	static boolean isSystemOperator(String name) {
		switch (name) {
			case "if":
			case "switch":
			case "case":
			case "for":
			case "while":
			case "break":
			case "continue":
			case "try":
			case "throw":
			case "rem":
			case "return":
			case "use":
			case "process":
			case "connect":
			case "cache":
			case "sleep":
			case "taint":
			case "untaint":
			case "eval":
				return true;
			default:
				return false;
		}
	}

	/**
	 * Индексирует статические вызовы методов пользовательских классов.
	 * Тип определяется из документации метода ($result) в runtime.
	 *
	 * Пример: $var[^User:method[]] — тип будет из # $result(hash) в документации @method в классе User
	 */
	private static void indexUserClassStaticMethods(
			String text,
			Map<String, List<VariableTypeInfo>> result,
			List<ru.artlebedev.parser3.utils.Parser3ClassUtils.ClassBoundary> classBoundaries,
			List<ru.artlebedev.parser3.utils.Parser3ClassUtils.MethodBoundary> methodBoundaries
	) {
		Matcher matcher = USER_CLASS_STATIC_METHOD_PATTERN.matcher(text);
		while (matcher.find()) {
			String prefix = matcher.group(1);   // "self." или "MAIN:" или null
			String varName = matcher.group(2);
			String targetClassName = matcher.group(3);
			String methodName = matcher.group(4);
			int offset = matcher.start();

			// Пропускаем комментарии
			if (ru.artlebedev.parser3.utils.Parser3ClassUtils.isOffsetInComment(text, offset)) continue;

			// Определяем класс-владелец и метод-владелец
			String ownerClass = ru.artlebedev.parser3.utils.Parser3ClassUtils.findOwnerClass(offset, classBoundaries);
			ru.artlebedev.parser3.utils.Parser3ClassUtils.MethodBoundary ownerMethodBoundary =
					ru.artlebedev.parser3.utils.Parser3ClassUtils.findOwnerMethod(offset, methodBoundaries);
			String ownerMethod = ownerMethodBoundary != null ? ownerMethodBoundary.name : null;

			boolean isLocal = computeIsLocal(prefix, varName, ownerClass, ownerMethodBoundary, text, classBoundaries);

			// Ключ индекса: префикс + имя переменной
			String indexKey = buildIndexKey(prefix, varName);

			// Создаём запись с маркером METHOD_CALL и targetClassName
			VariableTypeInfo info = new VariableTypeInfo(
					offset,
					VariableTypeInfo.METHOD_CALL_MARKER,
					ownerClass,
					ownerMethod,
					methodName,
					targetClassName,
					prefix,
					isLocal
			);

			result.computeIfAbsent(indexKey, k -> new ArrayList<>())
					.add(info);
		}
	}

	/**
	 * Индексирует ВСЕ присваивания переменных (без определения типа).
	 * Добавляет запись с UNKNOWN_TYPE только если для данного ключа+offset ещё нет записи
	 * (типизированные паттерны 1-5 имеют приоритет).
	 */
	private static void indexAllVarAssignments(
			String text,
			Map<String, List<VariableTypeInfo>> result,
			List<ru.artlebedev.parser3.utils.Parser3ClassUtils.ClassBoundary> classBoundaries,
			List<ru.artlebedev.parser3.utils.Parser3ClassUtils.MethodBoundary> methodBoundaries
	) {
		// Собираем множество offset'ов уже проиндексированных записей
		Set<Integer> existingOffsets = new HashSet<>();
		for (List<VariableTypeInfo> infos : result.values()) {
			for (VariableTypeInfo info : infos) {
				existingOffsets.add(info.offset);
			}
		}

		Matcher matcher = ANY_VAR_ASSIGNMENT_PATTERN.matcher(text);
		while (matcher.find()) {
			int offset = matcher.start();

			// Уже проиндексировано типизированным паттерном — пропускаем
			if (existingOffsets.contains(offset)) continue;

			String prefix = matcher.group(1);
			String varName = matcher.group(2);

			// Пропускаем комментарии
			if (ru.artlebedev.parser3.utils.Parser3ClassUtils.isOffsetInComment(text, offset)) continue;

			// Определяем класс-владелец и метод-владелец
			String ownerClass = ru.artlebedev.parser3.utils.Parser3ClassUtils.findOwnerClass(offset, classBoundaries);
			ru.artlebedev.parser3.utils.Parser3ClassUtils.MethodBoundary ownerMethodBoundary =
					ru.artlebedev.parser3.utils.Parser3ClassUtils.findOwnerMethod(offset, methodBoundaries);
			String ownerMethod = ownerMethodBoundary != null ? ownerMethodBoundary.name : null;

			boolean isLocal = computeIsLocal(prefix, varName, ownerClass, ownerMethodBoundary, text, classBoundaries);

			String indexKey = buildIndexKey(prefix, varName);

			VariableTypeInfo info = new VariableTypeInfo(offset, UNKNOWN_TYPE, ownerClass, ownerMethod, null, null, prefix, isLocal);

			result.computeIfAbsent(indexKey, k -> new ArrayList<>())
					.add(info);
		}
	}

	@NotNull
	@Override
	public KeyDescriptor<String> getKeyDescriptor() {
		return EnumeratorStringDescriptor.INSTANCE;
	}

	@NotNull
	@Override
	public DataExternalizer<List<VariableTypeInfo>> getValueExternalizer() {
		return new DataExternalizer<>() {
			@Override
			public void save(@NotNull DataOutput out, List<VariableTypeInfo> value) throws IOException {
				out.writeInt(value.size());
				for (VariableTypeInfo info : value) {
					out.writeInt(info.offset);
					out.writeUTF(info.className);
					out.writeUTF(info.ownerClass != null ? info.ownerClass : "");
					out.writeUTF(info.ownerMethod != null ? info.ownerMethod : "");
					out.writeUTF(info.methodName != null ? info.methodName : "");
					out.writeUTF(info.targetClassName != null ? info.targetClassName : "");
					out.writeUTF(info.receiverVarKey != null ? info.receiverVarKey : "");
					out.writeUTF(info.varPrefix != null ? info.varPrefix : "");
					out.writeBoolean(info.isLocal);
					out.writeBoolean(info.isAdditive);
					// Колонки таблицы
					if (info.columns != null && !info.columns.isEmpty()) {
						out.writeInt(info.columns.size());
						for (String col : info.columns) {
							out.writeUTF(col);
						}
					} else {
						out.writeInt(0);
					}
					// Ключ переменной-источника (для table::create[$var])
					out.writeUTF(info.sourceVarKey != null ? info.sourceVarKey : "");
					// Ключи хеша (для hash-литералов)
					HashEntryInfo.writeMap(out, info.hashKeys);
					// Переменные-источники ключей хеша ($data2[$data $.key[...]])
					if (info.hashSourceVars != null) {
						out.writeInt(info.hashSourceVars.size());
						for (String sv : info.hashSourceVars) {
							out.writeUTF(sv);
						}
					} else {
						out.writeInt(0);
					}
				}
			}

			@Override
			public List<VariableTypeInfo> read(@NotNull DataInput in) throws IOException {
				int size = in.readInt();
				List<VariableTypeInfo> result = new ArrayList<>(size);
				for (int i = 0; i < size; i++) {
					int offset = in.readInt();
					String className = in.readUTF();
					String ownerClass = in.readUTF();
					String ownerMethod = in.readUTF();
					String methodName = in.readUTF();
					String targetClassName = in.readUTF();
					String receiverVarKey = in.readUTF();
					String varPrefix = in.readUTF();
					boolean isLocal = in.readBoolean();
					boolean isAdditive = in.readBoolean();
					// Колонки таблицы
					int colCount = in.readInt();
					List<String> columns = null;
					if (colCount > 0) {
						columns = new ArrayList<>(colCount);
						for (int c = 0; c < colCount; c++) {
							columns.add(in.readUTF());
						}
					}
					// Ключ переменной-источника
					String sourceVarKey = in.readUTF();
					// Ключи хеша (для hash-литералов)
					java.util.Map<String, HashEntryInfo> hashKeys = HashEntryInfo.readMap(in);
					// Переменные-источники ключей хеша
					int srcCount = in.readInt();
					java.util.List<String> hashSourceVars = null;
					if (srcCount > 0) {
						hashSourceVars = new java.util.ArrayList<>(srcCount);
						for (int sv = 0; sv < srcCount; sv++) {
							hashSourceVars.add(in.readUTF());
						}
					}
					result.add(new VariableTypeInfo(
							offset,
							className,
							ownerClass.isEmpty() ? null : ownerClass,
							ownerMethod.isEmpty() ? null : ownerMethod,
							methodName.isEmpty() ? null : methodName,
							targetClassName.isEmpty() ? null : targetClassName,
							varPrefix.isEmpty() ? null : varPrefix,
							isLocal,
							columns,
							sourceVarKey.isEmpty() ? null : sourceVarKey,
							hashKeys,
							hashSourceVars,
							isAdditive,
							receiverVarKey.isEmpty() ? null : receiverVarKey
					));
				}
				return result;
			}
		};
	}

	/**
	 * Парсит переменные из текста файла напрямую (без индекса).
	 * Используется для текущего файла при автокомплите/навигации,
	 * чтобы работать с несохранёнными изменениями.
	 *
	 * Логика ИДЕНТИЧНА getIndexer() — единый источник истины.
	 */
	public static Map<String, List<VariableTypeInfo>> parseVariablesFromText(@NotNull String text) {
		Map<String, List<VariableTypeInfo>> result = new HashMap<>();

		try {
			List<ru.artlebedev.parser3.utils.Parser3ClassUtils.ClassBoundary> classBoundaries =
					ru.artlebedev.parser3.utils.Parser3ClassUtils.findClassBoundaries(text);
			List<ru.artlebedev.parser3.utils.Parser3ClassUtils.MethodBoundary> methodBoundaries =
					ru.artlebedev.parser3.utils.Parser3ClassUtils.findMethodBoundaries(text, classBoundaries);

			// Единый посимвольный парсер — заменяет все 6 regex паттернов
			result = P3VariableParser.parse(text, classBoundaries, methodBoundaries);
		} catch (Exception e) {
			System.out.println("[P3VariableFileIndex] Error parsing variables from text");
			e.printStackTrace();
		}

		return result;
	}

	@Override
	public int getVersion() {
		return 44; // v44: принудительный bootstrap перестройки после установки плагина
	}

	@Override
	public FileBasedIndex.@NotNull InputFilter getInputFilter() {
		return Parser3FileUtils::isParser3File;
	}

	@Override
	public boolean dependsOnFileContent() {
		return true;
	}
}
