package ru.artlebedev.parser3.index;

import ru.artlebedev.parser3.utils.Parser3FileUtils;

import com.intellij.util.indexing.*;
import com.intellij.util.io.DataExternalizer;
import com.intellij.util.io.EnumeratorStringDescriptor;
import com.intellij.util.io.KeyDescriptor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.artlebedev.parser3.utils.Parser3IdentifierUtils;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FileBasedIndex для вызовов методов в Parser3.
 *
 * Индексирует: имя метода -> список вызовов (offset, тип вызова, имя класса)
 *
 * Типы вызовов:
 * - ^method[] — простой вызов
 * - ^self.method[] — вызов через self
 * - ^MAIN:method[] — вызов из MAIN
 * - ^BASE:method[] — вызов из базового класса
 * - ^ClassName:method[] или ^ClassName::method[] — вызов метода класса
 */
public final class P3MethodCallFileIndex extends FileBasedIndexExtension<String, List<P3MethodCallFileIndex.MethodCallInfo>> {

	public static final ID<String, List<MethodCallInfo>> NAME = ID.create("parser3.methodCalls");

	// Паттерн для вызовов методов
	// Группы: 1=prefix (MAIN:|BASE:|ClassName:|ClassName::), 4=self., 5=methodName, 6=скобка
	// Поддерживает все виды скобок: [] () {}
	private static final Pattern METHOD_CALL_PATTERN = Pattern.compile(
			"\\^" +
					"(?:" +
					"(MAIN:|BASE::|BASE:|(" + Parser3IdentifierUtils.NAME_REGEX + ")::|(" + Parser3IdentifierUtils.NAME_REGEX + "):)" + // группа 1: префикс с классом
					"|" +
					"(self\\.)" + // группа 4: self.
					")?" +
					"(" + Parser3IdentifierUtils.METHOD_NAME_REGEX + ")" + // группа 5: имя метода
					"\\s*([\\[\\(\\{])"  // группа 6: открывающая скобка
	);

	// Паттерн для вызовов методов объекта:
	// ^var.method[], ^self.var.method[], ^MAIN:var.method[]
	private static final Pattern OBJECT_METHOD_CALL_PATTERN = Pattern.compile(
			"\\^" +
					"(?:" +
					"(MAIN:)(" + Parser3IdentifierUtils.NAME_REGEX + ")" +
					"|" +
					"(self\\.)(" + Parser3IdentifierUtils.NAME_REGEX + ")" +
					"|" +
					"(" + Parser3IdentifierUtils.NAME_REGEX + ")" +
					")" +
					"\\." +
					"(" + Parser3IdentifierUtils.METHOD_NAME_REGEX + ")" +
					"\\s*([\\[\\(\\{])"
	);

	// Паттерн для @CLASS — определяем границы классов
	private static final Pattern CLASS_PATTERN = Pattern.compile(
			"@CLASS\\s*[\\r\\n]+\\s*(" + Parser3IdentifierUtils.NAME_REGEX + ")",
			Pattern.MULTILINE
	);

	// Встроенные методы Parser3 — не индексируем
	private static final java.util.Set<String> BUILTIN_METHODS = java.util.Set.of(
			"if", "for", "while", "switch", "case", "default", "try", "catch", "throw",
			"connect", "use", "process", "untaint", "taint", "apply_taint",
			"rem", "eval", "break", "continue", "sleep", "caller"
	);

	/**
	 * Тип вызова метода
	 */
	public enum CallType {
		SIMPLE,         // ^method[]
		SELF,           // ^self.method[]
		MAIN,           // ^MAIN:method[]
		BASE,           // ^BASE:method[] или ^BASE::method[]
		OBJECT,         // ^var.method[] / ^self.var.method[] / ^MAIN:var.method[]
		CLASS_STATIC,   // ^ClassName:method[]
		CLASS_CONSTRUCTOR // ^ClassName::method[]
	}

	/**
	 * Тип скобок
	 */
	public enum BracketType {
		SQUARE,   // []
		ROUND,    // ()
		CURLY;    // {}

		public String getOpen() {
			return switch (this) {
				case SQUARE -> "[";
				case ROUND -> "(";
				case CURLY -> "{";
			};
		}

		public String getClose() {
			return switch (this) {
				case SQUARE -> "]";
				case ROUND -> ")";
				case CURLY -> "}";
			};
		}

		public static BracketType fromChar(char c) {
			return switch (c) {
				case '[' -> SQUARE;
				case '(' -> ROUND;
				case '{' -> CURLY;
				default -> SQUARE;
			};
		}
	}

	/**
	 * Информация о вызове метода
	 */
	public static final class MethodCallInfo {
		public final int offset;
		public final @NotNull CallType callType;
		public final @Nullable String targetClassName;  // для CLASS_STATIC/CLASS_CONSTRUCTOR
		public final @Nullable String callerClassName;  // класс, из которого сделан вызов (null = MAIN)
		public final @Nullable String receiverVarKey;   // для OBJECT-вызовов
		public final @NotNull BracketType bracketType;  // тип скобок
		public final boolean isInComment;               // вызов находится в комментарии

		public MethodCallInfo(int offset, @NotNull CallType callType,
							  @Nullable String targetClassName, @Nullable String callerClassName,
							  @Nullable String receiverVarKey,
							  @NotNull BracketType bracketType, boolean isInComment) {
			this.offset = offset;
			this.callType = callType;
			this.targetClassName = targetClassName;
			this.callerClassName = callerClassName;
			this.receiverVarKey = receiverVarKey;
			this.bracketType = bracketType;
			this.isInComment = isInComment;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			MethodCallInfo that = (MethodCallInfo) o;
			return offset == that.offset &&
					callType == that.callType &&
					bracketType == that.bracketType &&
					isInComment == that.isInComment &&
					java.util.Objects.equals(targetClassName, that.targetClassName) &&
					java.util.Objects.equals(callerClassName, that.callerClassName) &&
					java.util.Objects.equals(receiverVarKey, that.receiverVarKey);
		}

		@Override
		public int hashCode() {
			return java.util.Objects.hash(offset, callType, targetClassName, callerClassName, receiverVarKey, bracketType, isInComment);
		}
	}

	private static final com.intellij.openapi.diagnostic.Logger LOG =
			com.intellij.openapi.diagnostic.Logger.getInstance(P3MethodCallFileIndex.class);

	@Override
	public @NotNull ID<String, List<MethodCallInfo>> getName() {
		return NAME;
	}

	@Override
	public @NotNull DataIndexer<String, List<MethodCallInfo>, FileContent> getIndexer() {
		return inputData -> {
			Map<String, List<MethodCallInfo>> result = new HashMap<>();

			try {
				String text = inputData.getContentAsText().toString();

				// Бинарный файл — не индексируем
				if (P3VariableFileIndex.isBinaryContent(text)) return result;

				// Находим границы классов
				List<ClassBoundary> classes = findClassBoundaries(text);

				// Ищем все обычные вызовы методов
				Matcher matcher = METHOD_CALL_PATTERN.matcher(text);
				while (matcher.find()) {
					String prefix = matcher.group(1);      // MAIN: или BASE: или ClassName: или ClassName::
					String selfPrefix = matcher.group(4);  // self.
					String methodName = matcher.group(5);  // имя метода
					String bracket = matcher.group(6);     // открывающая скобка

					// Пропускаем встроенные методы
					if (BUILTIN_METHODS.contains(methodName)) {
						continue;
					}

					int offset = matcher.start();

					// Проверяем находится ли вызов в комментарии
					boolean isInComment = ru.artlebedev.parser3.utils.Parser3ClassUtils.isOffsetInComment(text, offset);

					// Определяем в каком классе находится вызов
					String callerClassName = findOwnerClass(offset, classes);

					// Определяем тип вызова
					CallType callType;
					String targetClassName = null;

					if (selfPrefix != null) {
						callType = CallType.SELF;
					} else if (prefix != null) {
						if (prefix.equals("MAIN:")) {
							callType = CallType.MAIN;
						} else if (prefix.equals("BASE:") || prefix.equals("BASE::")) {
							callType = CallType.BASE;
						} else if (prefix.endsWith("::")) {
							callType = CallType.CLASS_CONSTRUCTOR;
							targetClassName = prefix.substring(0, prefix.length() - 2);
						} else if (prefix.endsWith(":")) {
							callType = CallType.CLASS_STATIC;
							targetClassName = prefix.substring(0, prefix.length() - 1);
						} else {
							callType = CallType.SIMPLE;
						}
					} else {
						callType = CallType.SIMPLE;
					}

					// Определяем тип скобки
					BracketType bracketType = BracketType.fromChar(bracket.charAt(0));

					addMethodCallInfo(result, methodName, new MethodCallInfo(
							offset, callType, targetClassName, callerClassName, null, bracketType, isInComment
					));
				}

				// Ищем вызовы методов объекта
				Matcher objectMatcher = OBJECT_METHOD_CALL_PATTERN.matcher(text);
				while (objectMatcher.find()) {
					String receiverVarKey;
					if (objectMatcher.group(1) != null) {
						receiverVarKey = "MAIN:" + objectMatcher.group(2);
					} else if (objectMatcher.group(3) != null) {
						receiverVarKey = "self." + objectMatcher.group(4);
					} else {
						receiverVarKey = objectMatcher.group(5);
					}

					String methodName = objectMatcher.group(6);
					String bracket = objectMatcher.group(7);

					if (BUILTIN_METHODS.contains(methodName)) {
						continue;
					}

					int offset = objectMatcher.start();
					boolean isInComment = ru.artlebedev.parser3.utils.Parser3ClassUtils.isOffsetInComment(text, offset);
					String callerClassName = findOwnerClass(offset, classes);
					BracketType bracketType = BracketType.fromChar(bracket.charAt(0));

					addMethodCallInfo(result, methodName, new MethodCallInfo(
							offset,
							CallType.OBJECT,
							null,
							callerClassName,
							receiverVarKey,
							bracketType,
							isInComment
					));
				}
			} catch (Exception e) {
				P3IndexExceptionUtil.rethrowIfControlFlow(e);
				LOG.error("[Parser3Index] Error indexing method calls in file: " + inputData.getFileName(), e);
			}

			return result;
		};
	}

	/**
	 * Находит границы классов в тексте
	 */
	private static List<ClassBoundary> findClassBoundaries(String text) {
		List<ClassBoundary> result = new ArrayList<>();

		Matcher matcher = CLASS_PATTERN.matcher(text);
		while (matcher.find()) {
			String className = matcher.group(1);
			int start = matcher.start();
			result.add(new ClassBoundary(className, start));
		}

		// Устанавливаем end для каждого класса
		for (int i = 0; i < result.size(); i++) {
			ClassBoundary current = result.get(i);
			if (i + 1 < result.size()) {
				current.end = result.get(i + 1).start;
			} else {
				current.end = text.length();
			}
		}

		return result;
	}

	/**
	 * Определяет в каком классе находится offset
	 */
	private static @Nullable String findOwnerClass(int offset, List<ClassBoundary> classes) {
		for (ClassBoundary cls : classes) {
			if (offset >= cls.start && offset < cls.end) {
				return cls.name;
			}
		}
		// Если не в пользовательском классе — это MAIN
		return null;
	}

	private static class ClassBoundary {
		final String name;
		final int start;
		int end;

		ClassBoundary(String name, int start) {
			this.name = name;
			this.start = start;
		}
	}

	private static void addMethodCallInfo(
			@NotNull Map<String, List<MethodCallInfo>> result,
			@NotNull String methodName,
			@NotNull MethodCallInfo callInfo
	) {
		result.computeIfAbsent(methodName, k -> new ArrayList<>()).add(callInfo);
	}

	@Override
	public @NotNull KeyDescriptor<String> getKeyDescriptor() {
		return EnumeratorStringDescriptor.INSTANCE;
	}

	@Override
	public @NotNull DataExternalizer<List<MethodCallInfo>> getValueExternalizer() {
		return new DataExternalizer<>() {
			@Override
			public void save(@NotNull DataOutput out, List<MethodCallInfo> value) throws IOException {
				out.writeInt(value.size());
				for (MethodCallInfo info : value) {
					out.writeInt(info.offset);
					out.writeInt(info.callType.ordinal());
					out.writeInt(info.bracketType.ordinal());
					out.writeBoolean(info.isInComment);

					out.writeBoolean(info.targetClassName != null);
					if (info.targetClassName != null) {
						out.writeUTF(info.targetClassName);
					}

					out.writeBoolean(info.callerClassName != null);
					if (info.callerClassName != null) {
						out.writeUTF(info.callerClassName);
					}

					out.writeBoolean(info.receiverVarKey != null);
					if (info.receiverVarKey != null) {
						out.writeUTF(info.receiverVarKey);
					}
				}
			}

			@Override
			public List<MethodCallInfo> read(@NotNull DataInput in) throws IOException {
				int size = in.readInt();
				List<MethodCallInfo> result = new ArrayList<>(size);

				for (int i = 0; i < size; i++) {
					int offset = in.readInt();
					CallType callType = CallType.values()[in.readInt()];
					BracketType bracketType = BracketType.values()[in.readInt()];
					boolean isInComment = in.readBoolean();

					boolean hasTarget = in.readBoolean();
					String targetClassName = hasTarget ? in.readUTF() : null;

					boolean hasCaller = in.readBoolean();
					String callerClassName = hasCaller ? in.readUTF() : null;

					boolean hasReceiverVarKey = in.readBoolean();
					String receiverVarKey = hasReceiverVarKey ? in.readUTF() : null;

					result.add(new MethodCallInfo(offset, callType, targetClassName, callerClassName, receiverVarKey, bracketType, isInComment));
				}

				return result;
			}
		};
	}

	@Override
	public int getVersion() {
		return 9; // v9: принудительный bootstrap перестройки после установки плагина
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
