package ru.artlebedev.parser3.index;

import com.intellij.util.indexing.DataIndexer;
import com.intellij.util.indexing.FileBasedIndex;
import com.intellij.util.indexing.FileBasedIndexExtension;
import com.intellij.util.indexing.FileContent;
import com.intellij.util.indexing.ID;
import com.intellij.util.io.DataExternalizer;
import com.intellij.util.io.EnumeratorStringDescriptor;
import com.intellij.util.io.KeyDescriptor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.artlebedev.parser3.utils.Parser3FileUtils;
import ru.artlebedev.parser3.model.P3MethodCallType;
import ru.artlebedev.parser3.model.P3MethodDoc;
import ru.artlebedev.parser3.model.P3MethodParameter;
import ru.artlebedev.parser3.utils.Parser3ClassUtils;
import ru.artlebedev.parser3.utils.Parser3IdentifierUtils;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FileBasedIndex для объявлений методов в Parser3.
 */
public final class P3MethodFileIndex extends FileBasedIndexExtension<String, List<P3MethodFileIndex.MethodInfo>> {

	public static final ID<String, List<MethodInfo>> NAME = ID.create("parser3.methods");

	private static final Pattern METHOD_PATTERN = Pattern.compile(
			"(?:^|(?<=\\{))\\^?@(?:(static|dynamic):)?(" + Parser3IdentifierUtils.METHOD_NAME_REGEX + ")(?:\\s*\\[([^\\]]*?)\\]|\\s*$|\\s+)",
			Pattern.MULTILINE
	);

	private static final Pattern OPTIONS_PATTERN = Pattern.compile(
			"@OPTIONS\\s*[\\r\\n]+([^@]*?)(?=@|$)",
			Pattern.MULTILINE | Pattern.DOTALL
	);

	private static final java.util.Set<String> RESERVED_DIRECTIVES = java.util.Set.of(
			"CLASS", "BASE", "OPTIONS", "USE", "static", "dynamic", "locals", "partial"
	);

	public static final class MethodInfo {
		public final int offset;
		public final @Nullable String ownerClass;
		public final @NotNull List<String> parameterNames;
		public final @Nullable String docText;
		public final @NotNull List<DocParam> docParams;
		public final @Nullable DocParam docResult;
		public final @Nullable P3ResolvedValue inferredResult;
		public final boolean isGetter;
		public final @NotNull P3MethodCallType callType;

		public MethodInfo(int offset, @Nullable String ownerClass) {
			this(offset, ownerClass, List.of(), null, List.of(), null, null, false, P3MethodCallType.ANY);
		}

		public MethodInfo(
				int offset,
				@Nullable String ownerClass,
				@NotNull List<String> parameterNames,
				@Nullable String docText,
				@NotNull List<DocParam> docParams,
				@Nullable DocParam docResult
		) {
			this(offset, ownerClass, parameterNames, docText, docParams, docResult, null, false, P3MethodCallType.ANY);
		}

		public MethodInfo(
				int offset,
				@Nullable String ownerClass,
				@NotNull List<String> parameterNames,
				@Nullable String docText,
				@NotNull List<DocParam> docParams,
				@Nullable DocParam docResult,
				@Nullable P3ResolvedValue inferredResult,
				boolean isGetter,
				@NotNull P3MethodCallType callType
		) {
			this.offset = offset;
			this.ownerClass = ownerClass;
			this.parameterNames = parameterNames;
			this.docText = docText;
			this.docParams = docParams;
			this.docResult = docResult;
			this.inferredResult = inferredResult;
			this.isGetter = isGetter;
			this.callType = callType;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			MethodInfo that = (MethodInfo) o;
			return offset == that.offset
					&& isGetter == that.isGetter
					&& Objects.equals(ownerClass, that.ownerClass)
					&& Objects.equals(parameterNames, that.parameterNames)
					&& Objects.equals(docText, that.docText)
					&& Objects.equals(docParams, that.docParams)
					&& Objects.equals(docResult, that.docResult)
					&& Objects.equals(inferredResult, that.inferredResult)
					&& callType == that.callType;
		}

		@Override
		public int hashCode() {
			return Objects.hash(offset, ownerClass, parameterNames, docText, docParams, docResult, inferredResult, isGetter, callType);
		}
	}

	public static final class DocParam {
		public final @NotNull String name;
		public final @Nullable String type;
		public final @Nullable String description;

		public DocParam(@NotNull String name, @Nullable String type, @Nullable String description) {
			this.name = name;
			this.type = type;
			this.description = description;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			DocParam docParam = (DocParam) o;
			return Objects.equals(name, docParam.name)
					&& Objects.equals(type, docParam.type)
					&& Objects.equals(description, docParam.description);
		}

		@Override
		public int hashCode() {
			return Objects.hash(name, type, description);
		}
	}

	private static final com.intellij.openapi.diagnostic.Logger LOG =
			com.intellij.openapi.diagnostic.Logger.getInstance(P3MethodFileIndex.class);

	@Override
	public @NotNull ID<String, List<MethodInfo>> getName() {
		return NAME;
	}

	@Override
	public @NotNull DataIndexer<String, List<MethodInfo>, FileContent> getIndexer() {
		return inputData -> {
			Map<String, List<MethodInfo>> result = new HashMap<>();

			try {
				String text = inputData.getContentAsText().toString();
				if (P3VariableFileIndex.isBinaryContent(text)) return result;

				List<Parser3ClassUtils.ClassBoundary> classes = Parser3ClassUtils.findClassBoundaries(text);
				List<Parser3ClassUtils.MethodBoundary> methods = Parser3ClassUtils.findMethodBoundaries(text, classes);
				java.util.concurrent.atomic.AtomicReference<Map<String, List<P3VariableFileIndex.VariableTypeInfo>>> parsedVariablesRef =
						new java.util.concurrent.atomic.AtomicReference<>();
				java.util.function.Supplier<Map<String, List<P3VariableFileIndex.VariableTypeInfo>>> parsedVariablesSupplier = () -> {
					Map<String, List<P3VariableFileIndex.VariableTypeInfo>> parsedVariables = parsedVariablesRef.get();
					if (parsedVariables != null) {
						return parsedVariables;
					}

					Map<String, List<P3VariableFileIndex.VariableTypeInfo>> parsed = P3VariableFileIndex.parseVariablesFromText(text);
					parsedVariablesRef.compareAndSet(null, parsed);
					return parsedVariablesRef.get();
				};

				Matcher matcher = METHOD_PATTERN.matcher(text);
				while (matcher.find()) {
					P3MethodCallType explicitCallType = P3MethodCallType.fromDirective(matcher.group(1));
					String methodName = matcher.group(2);
					String boundaryMethodName = methodName;

					if (RESERVED_DIRECTIVES.contains(methodName)) continue;
					if (methodName.equals("GET") || methodName.equals("SET") || methodName.startsWith("SET_")) continue;
					if (methodName.equals("GET_DEFAULT")) continue;

					boolean isGetter = false;
					if (methodName.startsWith("GET_")) {
						methodName = methodName.substring(4);
						isGetter = true;
					}

					int offset = text.charAt(matcher.start()) == '^' ? matcher.start() + 1 : matcher.start();
					String paramsStr = matcher.group(3);
					List<String> parameterNames = parseParameterNames(paramsStr);
					String ownerClass = Parser3ClassUtils.findOwnerClass(offset, classes);
					P3MethodCallType callType = explicitCallType != P3MethodCallType.ANY
							? explicitCallType
							: findDefaultCallType(text, offset, ownerClass, classes);
					P3MethodDoc doc = P3MethodDoc.parseFromText(text, offset);
					Parser3ClassUtils.MethodBoundary boundary =
							findMethodBoundary(boundaryMethodName, offset, ownerClass, methods);
					P3ResolvedValue inferredResult = boundary != null
							? P3MethodResultResolver.inferResult(text, boundary, parsedVariablesSupplier)
							: null;

					MethodInfo methodInfo = createMethodInfo(
							offset,
							ownerClass,
							parameterNames,
							doc,
							inferredResult,
							isGetter,
							callType
					);

					result.computeIfAbsent(methodName, k -> new ArrayList<>()).add(methodInfo);
				}
			} catch (Exception e) {
				P3IndexExceptionUtil.rethrowIfControlFlow(e);
				LOG.error("[Parser3Index] Error indexing methods in file: " + inputData.getFileName(), e);
			}

			return result;
		};
	}

	private static @Nullable Parser3ClassUtils.MethodBoundary findMethodBoundary(
			@NotNull String methodName,
			int offset,
			@Nullable String ownerClass,
			@NotNull List<Parser3ClassUtils.MethodBoundary> methods
	) {
		for (Parser3ClassUtils.MethodBoundary boundary : methods) {
			if (boundary.start != offset) continue;
			if (!methodName.equals(boundary.name)) continue;
			if (!Objects.equals(ownerClass, boundary.ownerClass)) continue;
			return boundary;
		}
		return null;
	}

	private static @NotNull List<String> parseParameterNames(@Nullable String paramsStr) {
		return Parser3ClassUtils.parseParameterNames(paramsStr);
	}

	private static @NotNull MethodInfo createMethodInfo(
			int offset,
			@Nullable String ownerClass,
			@NotNull List<String> parameterNames,
			@Nullable P3MethodDoc doc,
			@Nullable P3ResolvedValue inferredResult,
			boolean isGetter,
			@NotNull P3MethodCallType callType
	) {
		if (doc == null) {
			return new MethodInfo(offset, ownerClass, parameterNames, null, List.of(), null, inferredResult, isGetter, callType);
		}

		List<DocParam> docParams = new ArrayList<>();
		for (P3MethodParameter param : doc.getParameters()) {
			docParams.add(new DocParam(param.getName(), param.getType(), param.getDescription()));
		}

		DocParam docResult = null;
		if (doc.getResult() != null) {
			P3MethodParameter result = doc.getResult();
			docResult = new DocParam(result.getName(), result.getType(), result.getDescription());
		}

		return new MethodInfo(offset, ownerClass, parameterNames, doc.getRawText(), docParams, docResult, inferredResult, isGetter, callType);
	}

	private static @NotNull P3MethodCallType findDefaultCallType(
			@NotNull String text,
			int offset,
			@Nullable String ownerClass,
			@NotNull List<Parser3ClassUtils.ClassBoundary> classes
	) {
		int start = 0;
		int end = text.length();
		if (ownerClass == null) {
			if (!classes.isEmpty()) {
				end = Math.min(end, classes.get(0).start);
			}
		} else {
			for (Parser3ClassUtils.ClassBoundary cls : classes) {
				if (cls.name.equals(ownerClass) && offset >= cls.start && offset < cls.end) {
					start = cls.start;
					end = Math.min(text.length(), cls.end);
					break;
				}
			}
		}

		String scopeText = text.substring(start, end);
		Matcher matcher = OPTIONS_PATTERN.matcher(scopeText);
		while (matcher.find()) {
			String optionsText = matcher.group(1).trim();
			for (String option : optionsText.split("[\\s\\r\\n]+")) {
				String trimmed = option.trim();
				if ("static".equals(trimmed)) return P3MethodCallType.STATIC;
				if ("dynamic".equals(trimmed)) return P3MethodCallType.DYNAMIC;
			}
		}
		return P3MethodCallType.ANY;
	}

	@Override
	public @NotNull KeyDescriptor<String> getKeyDescriptor() {
		return EnumeratorStringDescriptor.INSTANCE;
	}

	@Override
	public @NotNull DataExternalizer<List<MethodInfo>> getValueExternalizer() {
		return new DataExternalizer<>() {
			@Override
			public void save(@NotNull DataOutput out, List<MethodInfo> value) throws IOException {
				out.writeInt(value.size());
				for (MethodInfo info : value) {
					out.writeInt(info.offset);
					writeNullableString(out, info.ownerClass);

					out.writeInt(info.parameterNames.size());
					for (String param : info.parameterNames) {
						out.writeUTF(param);
					}

					writeNullableString(out, info.docText);

					out.writeInt(info.docParams.size());
					for (DocParam param : info.docParams) {
						out.writeUTF(param.name);
						writeNullableString(out, param.type);
						writeNullableString(out, param.description);
					}

					out.writeBoolean(info.docResult != null);
					if (info.docResult != null) {
						out.writeUTF(info.docResult.name);
						writeNullableString(out, info.docResult.type);
						writeNullableString(out, info.docResult.description);
					}

					out.writeBoolean(info.inferredResult != null);
					if (info.inferredResult != null) {
						writeResolvedValue(out, info.inferredResult);
					}

					out.writeBoolean(info.isGetter);
					out.writeUTF(info.callType.name());
				}
			}

			@Override
			public List<MethodInfo> read(@NotNull DataInput in) throws IOException {
				int size = in.readInt();
				List<MethodInfo> result = new ArrayList<>(size);
				for (int i = 0; i < size; i++) {
					int offset = in.readInt();
					String ownerClass = readNullableString(in);

					int paramCount = in.readInt();
					List<String> parameterNames = new ArrayList<>(paramCount);
					for (int j = 0; j < paramCount; j++) {
						parameterNames.add(in.readUTF());
					}

					String docText = readNullableString(in);

					int docParamCount = in.readInt();
					List<DocParam> docParams = new ArrayList<>(docParamCount);
					for (int j = 0; j < docParamCount; j++) {
						String name = in.readUTF();
						String type = readNullableString(in);
						String description = readNullableString(in);
						docParams.add(new DocParam(name, type, description));
					}

					DocParam docResult = null;
					if (in.readBoolean()) {
						docResult = new DocParam(in.readUTF(), readNullableString(in), readNullableString(in));
					}

					P3ResolvedValue inferredResult = null;
					if (in.readBoolean()) {
						inferredResult = readResolvedValue(in);
					}

					boolean isGetter = in.readBoolean();
					P3MethodCallType callType = P3MethodCallType.valueOf(in.readUTF());
					result.add(new MethodInfo(offset, ownerClass, parameterNames, docText, docParams, docResult, inferredResult, isGetter, callType));
				}
				return result;
			}
		};
	}

	private static void writeResolvedValue(@NotNull DataOutput out, @NotNull P3ResolvedValue value) throws IOException {
		out.writeUTF(value.className);
		writeNullableString(out, value.sourceVarKey);
		writeNullableString(out, value.methodName);
		writeNullableString(out, value.targetClassName);
		writeNullableString(out, value.receiverVarKey);

		if (value.columns != null) {
			out.writeInt(value.columns.size());
			for (String column : value.columns) {
				out.writeUTF(column);
			}
		} else {
			out.writeInt(0);
		}

		HashEntryInfo.writeMap(out, value.hashKeys);

		if (value.hashSourceVars != null) {
			out.writeInt(value.hashSourceVars.size());
			for (String sourceVar : value.hashSourceVars) {
				out.writeUTF(sourceVar);
			}
		} else {
			out.writeInt(0);
		}
	}

	private static @NotNull P3ResolvedValue readResolvedValue(@NotNull DataInput in) throws IOException {
		String className = in.readUTF();
		String sourceVarKey = readNullableString(in);
		String methodName = readNullableString(in);
		String targetClassName = readNullableString(in);
		String receiverVarKey = readNullableString(in);

		int columnsCount = in.readInt();
		List<String> columns = null;
		if (columnsCount > 0) {
			columns = new ArrayList<>(columnsCount);
			for (int i = 0; i < columnsCount; i++) {
				columns.add(in.readUTF());
			}
		}

		Map<String, HashEntryInfo> hashKeys = HashEntryInfo.readMap(in);

		int sourceVarsCount = in.readInt();
		List<String> hashSourceVars = null;
		if (sourceVarsCount > 0) {
			hashSourceVars = new ArrayList<>(sourceVarsCount);
			for (int i = 0; i < sourceVarsCount; i++) {
				hashSourceVars.add(in.readUTF());
			}
		}

		return P3ResolvedValue.of(className, columns, sourceVarKey, hashKeys, hashSourceVars, methodName, targetClassName, receiverVarKey);
	}

	private static void writeNullableString(@NotNull DataOutput out, @Nullable String value) throws IOException {
		out.writeBoolean(value != null);
		if (value != null) {
			out.writeUTF(value);
		}
	}

	private static @Nullable String readNullableString(@NotNull DataInput in) throws IOException {
		return in.readBoolean() ? in.readUTF() : null;
	}

	@Override
	public int getVersion() {
		return 27; // v27: принудительный bootstrap перестройки после установки плагина
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
