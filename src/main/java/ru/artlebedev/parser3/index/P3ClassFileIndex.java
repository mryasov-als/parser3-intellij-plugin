package ru.artlebedev.parser3.index;

import com.intellij.util.indexing.*;
import com.intellij.util.io.DataExternalizer;
import com.intellij.util.io.EnumeratorStringDescriptor;
import com.intellij.util.io.KeyDescriptor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.artlebedev.parser3.utils.Parser3FileUtils;
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
 * FileBasedIndex для объявлений классов в Parser3.
 *
 * Индексирует: имя класса -> список ClassInfo (offset, базовый класс, опции)
 *
 * Работает на уровне текста файла (без PSI), поэтому очень быстрый.
 * Обновляется автоматически при изменении файлов.
 */
public final class P3ClassFileIndex extends FileBasedIndexExtension<String, List<P3ClassFileIndex.ClassInfo>> {

	public static final ID<String, List<ClassInfo>> NAME = ID.create("parser3.classes");

	// @CLASS с именем на следующей строке
	private static final Pattern CLASS_PATTERN = Pattern.compile(
			"@CLASS\\s*[\\r\\n]+\\s*(" + Parser3IdentifierUtils.NAME_REGEX + ")",
			Pattern.MULTILINE
	);

	// @BASE с именем на следующей строке
	private static final Pattern BASE_PATTERN = Pattern.compile(
			"@BASE\\s*[\\r\\n]+\\s*(" + Parser3IdentifierUtils.NAME_REGEX + ")",
			Pattern.MULTILINE
	);

	// @OPTIONS с опциями
	private static final Pattern OPTIONS_PATTERN = Pattern.compile(
			"@OPTIONS\\s*[\\r\\n]+([^@]*?)(?=@|$)",
			Pattern.MULTILINE | Pattern.DOTALL
	);

	/**
	 * Информация о классе
	 */
	public static final class ClassInfo {
		public final int startOffset;
		public final int endOffset;
		public final @Nullable String baseClassName;
		public final @NotNull List<String> options;

		public ClassInfo(int startOffset, int endOffset, @Nullable String baseClassName, @NotNull List<String> options) {
			this.startOffset = startOffset;
			this.endOffset = endOffset;
			this.baseClassName = baseClassName;
			this.options = new ArrayList<>(options);
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			ClassInfo classInfo = (ClassInfo) o;
			return startOffset == classInfo.startOffset &&
					endOffset == classInfo.endOffset &&
					java.util.Objects.equals(baseClassName, classInfo.baseClassName) &&
					java.util.Objects.equals(options, classInfo.options);
		}

		@Override
		public int hashCode() {
			return java.util.Objects.hash(startOffset, endOffset, baseClassName, options);
		}
	}

	@Override
	public @NotNull ID<String, List<ClassInfo>> getName() {
		return NAME;
	}

	private static final com.intellij.openapi.diagnostic.Logger LOG =
			com.intellij.openapi.diagnostic.Logger.getInstance(P3ClassFileIndex.class);

	@Override
	public @NotNull DataIndexer<String, List<ClassInfo>, FileContent> getIndexer() {
		return inputData -> {
			Map<String, List<ClassInfo>> result = new HashMap<>();

			try {
				String text = inputData.getContentAsText().toString();

				// Бинарный файл — не индексируем
				if (P3VariableFileIndex.isBinaryContent(text)) return result;

				// Находим все @CLASS
				List<ClassRaw> rawClasses = new ArrayList<>();
				Matcher classMatcher = CLASS_PATTERN.matcher(text);
				while (classMatcher.find()) {
					String className = classMatcher.group(1);
					int start = classMatcher.start();
					rawClasses.add(new ClassRaw(className, start));
				}

				// Если нет классов — добавляем неявный MAIN для всего файла
				if (rawClasses.isEmpty()) {
					ClassInfo mainInfo = new ClassInfo(0, text.length(), null, new ArrayList<>());
					result.computeIfAbsent("MAIN", k -> new ArrayList<>()).add(mainInfo);
					return result;
				}

				// Если первый @CLASS начинается не с начала файла — создаём MAIN для кода до него
				// Это важно для случая когда методы объявлены ДО первого @CLASS
				int firstClassStart = rawClasses.get(0).start;
				if (firstClassStart > 0) {
					// Проверяем что до @CLASS есть что-то кроме пробелов и комментариев
					String beforeClass = text.substring(0, firstClassStart);
					// Ищем объявления методов (@methodName) или @USE директивы
					if (beforeClass.contains("@") && !beforeClass.trim().isEmpty()) {
						ClassInfo mainInfo = new ClassInfo(0, firstClassStart, null, new ArrayList<>());
						result.computeIfAbsent("MAIN", k -> new ArrayList<>()).add(mainInfo);
					}
				}

				// Устанавливаем границы и парсим директивы для каждого класса
				for (int i = 0; i < rawClasses.size(); i++) {
					ClassRaw raw = rawClasses.get(i);
					int endOffset = (i + 1 < rawClasses.size())
							? rawClasses.get(i + 1).start
							: text.length();

					// Ищем @BASE в пределах класса
					String baseClassName = findBaseClass(text, raw.start, endOffset);

					// Ищем @OPTIONS в пределах класса
					List<String> options = findOptions(text, raw.start, endOffset);

					ClassInfo info = new ClassInfo(raw.start, endOffset, baseClassName, options);
					result.computeIfAbsent(raw.name, k -> new ArrayList<>()).add(info);
				}
			} catch (Exception e) {
				P3IndexExceptionUtil.rethrowIfControlFlow(e);
				LOG.error("[Parser3Index] Error indexing classes in file: " + inputData.getFileName(), e);
			}

			return result;
		};
	}

	/**
	 * Ищет @BASE в пределах класса
	 */
	private static @Nullable String findBaseClass(String text, int start, int end) {
		String classText = text.substring(start, end);
		Matcher matcher = BASE_PATTERN.matcher(classText);
		if (matcher.find()) {
			return matcher.group(1);
		}
		return null;
	}

	/**
	 * Ищет @OPTIONS в пределах класса
	 */
	private static @NotNull List<String> findOptions(String text, int start, int end) {
		List<String> options = new ArrayList<>();
		String classText = text.substring(start, end);

		Matcher matcher = OPTIONS_PATTERN.matcher(classText);
		if (matcher.find()) {
			String optionsText = matcher.group(1).trim();
			// Разбиваем по пробелам и переносам строк
			String[] parts = optionsText.split("[\\s\\r\\n]+");
			for (String part : parts) {
				String trimmed = part.trim();
				if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
					options.add(trimmed);
				}
			}
		}

		return options;
	}

	private static class ClassRaw {
		final String name;
		final int start;

		ClassRaw(String name, int start) {
			this.name = name;
			this.start = start;
		}
	}

	@Override
	public @NotNull KeyDescriptor<String> getKeyDescriptor() {
		return EnumeratorStringDescriptor.INSTANCE;
	}

	@Override
	public @NotNull DataExternalizer<List<ClassInfo>> getValueExternalizer() {
		return new DataExternalizer<>() {
			@Override
			public void save(@NotNull DataOutput out, List<ClassInfo> value) throws IOException {
				out.writeInt(value.size());
				for (ClassInfo info : value) {
					out.writeInt(info.startOffset);
					out.writeInt(info.endOffset);
					out.writeBoolean(info.baseClassName != null);
					if (info.baseClassName != null) {
						out.writeUTF(info.baseClassName);
					}
					out.writeInt(info.options.size());
					for (String opt : info.options) {
						out.writeUTF(opt);
					}
				}
			}

			@Override
			public List<ClassInfo> read(@NotNull DataInput in) throws IOException {
				int size = in.readInt();
				List<ClassInfo> result = new ArrayList<>(size);
				for (int i = 0; i < size; i++) {
					int startOffset = in.readInt();
					int endOffset = in.readInt();
					boolean hasBase = in.readBoolean();
					String baseClassName = hasBase ? in.readUTF() : null;
					int optCount = in.readInt();
					List<String> options = new ArrayList<>(optCount);
					for (int j = 0; j < optCount; j++) {
						options.add(in.readUTF());
					}
					result.add(new ClassInfo(startOffset, endOffset, baseClassName, options));
				}
				return result;
			}
		};
	}

	@Override
	public int getVersion() {
		return 10; // v10: принудительный bootstrap перестройки после установки плагина
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
