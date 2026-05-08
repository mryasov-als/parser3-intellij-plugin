package ru.artlebedev.parser3.index;

import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.indexing.*;
import com.intellij.util.io.DataExternalizer;
import com.intellij.util.io.EnumeratorStringDescriptor;
import com.intellij.util.io.KeyDescriptor;
import org.jetbrains.annotations.NotNull;
import ru.artlebedev.parser3.utils.Parser3FileUtils;
import ru.artlebedev.parser3.utils.Parser3ClassUtils;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class P3CssClassFileIndex extends FileBasedIndexExtension<String, List<P3CssClassFileIndex.CssClassInfo>> {

	public static final ID<String, List<CssClassInfo>> NAME = ID.create("parser3.cssClasses");

	private static final Pattern CSS_CLASS_PATTERN = Pattern.compile("\\.([a-zA-Z_][a-zA-Z0-9_-]*)");

	private static final boolean DEBUG = false;

	@Override
	public @NotNull ID<String, List<CssClassInfo>> getName() {
		return NAME;
	}

	@Override
	public int getVersion() {
		return 3; // v3: принудительный bootstrap перестройки после установки плагина
	}

	@Override
	public @NotNull DataIndexer<String, List<CssClassInfo>, FileContent> getIndexer() {
		return inputData -> {
			Map<String, List<CssClassInfo>> result = new HashMap<>();

			CharSequence content = inputData.getContentAsText();
			if (content.length() == 0) return result;

			if (P3VariableFileIndex.isBinaryContent(content.toString())) return result;

			String text = content.toString();
			String lowerText = text.toLowerCase();

			int searchFrom = 0;
			while (true) {
				int styleStart = lowerText.indexOf("<style", searchFrom);
				if (styleStart < 0) break;

				int styleEnd = lowerText.indexOf("</style>", styleStart);
				if (styleEnd < 0) break;

				int tagEnd = text.indexOf('>', styleStart);
				if (tagEnd < 0 || tagEnd >= styleEnd) {
					searchFrom = styleStart + 1;
					continue;
				}

				String cssContent = text.substring(tagEnd + 1, styleEnd);
				int cssStartOffset = tagEnd + 1;

				Matcher matcher = CSS_CLASS_PATTERN.matcher(cssContent);
				while (matcher.find()) {
					String className = matcher.group(1);

					int dotPos = matcher.start();
					if (dotPos > 0) {
						char prevChar = cssContent.charAt(dotPos - 1);
						if (Character.isLetterOrDigit(prevChar) || prevChar == '-' || prevChar == '_') {
							continue;
						}
					}

					int absoluteOffset = cssStartOffset + matcher.start(1);
					if (Parser3ClassUtils.isOffsetInComment(text, absoluteOffset)) {
						continue;
					}

					CssClassInfo info = new CssClassInfo(absoluteOffset);
					result.computeIfAbsent(className, k -> new ArrayList<>()).add(info);
				}

				searchFrom = styleEnd + 8;
			}

			if (DEBUG && !result.isEmpty()) {
				System.out.println("[P3CssClassIndex] Файл: " + inputData.getFileName()
						+ " найдено классов: " + result.size());
			}

			return result;
		};
	}

	@Override
	public @NotNull KeyDescriptor<String> getKeyDescriptor() {
		return EnumeratorStringDescriptor.INSTANCE;
	}

	@Override
	public @NotNull DataExternalizer<List<CssClassInfo>> getValueExternalizer() {
		return new DataExternalizer<>() {
			@Override
			public void save(@NotNull DataOutput out, List<CssClassInfo> value) throws IOException {
				out.writeInt(value.size());
				for (CssClassInfo info : value) {
					out.writeInt(info.offset);
				}
			}

			@Override
			public List<CssClassInfo> read(@NotNull DataInput in) throws IOException {
				int size = in.readInt();
				List<CssClassInfo> result = new ArrayList<>(size);
				for (int i = 0; i < size; i++) {
					result.add(new CssClassInfo(in.readInt()));
				}
				return result;
			}
		};
	}

	@Override
	public FileBasedIndex.@NotNull InputFilter getInputFilter() {
		return Parser3FileUtils::isParser3File;
	}

	@Override
	public boolean dependsOnFileContent() {
		return true;
	}

	public static class CssClassInfo {
		public final int offset;

		public CssClassInfo(int offset) {
			this.offset = offset;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			CssClassInfo that = (CssClassInfo) o;
			return offset == that.offset;
		}

		@Override
		public int hashCode() {
			return offset;
		}
	}
}
