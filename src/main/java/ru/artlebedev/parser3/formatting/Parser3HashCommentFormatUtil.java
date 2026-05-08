package ru.artlebedev.parser3.formatting;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.util.Key;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;

final class Parser3HashCommentFormatUtil {

	static final boolean DEBUG = false;

	private static String escapeForLog(@NotNull String s) {
		return s
				.replace("\r", "\\r")
				.replace("\t", "\\t");
	}

	private static void dumpDocument(@NotNull Document document, @NotNull String label) {
		if (!DEBUG) return;
		try {
			String text = document.getText();
		} catch (Throwable t) {

		}
	}

	private static void dumpCaller(@NotNull String label) {
		if (!DEBUG) return;
		try {
			StackTraceElement[] st = new Throwable().getStackTrace();
			int limit = Math.min(st.length, 25);
			for (int i = 0; i < limit; i++) {
				StackTraceElement e = st[i];
			}
		} catch (Throwable t) {
		}
	}


	static final class LineInfo {
		final int anchorLine;
		final int gapColumns;

		LineInfo(int anchorLine, int gapColumns) {
			this.anchorLine = anchorLine;
			this.gapColumns = gapColumns;
		}
	}

	private static final Key<Map<Integer, LineInfo>> HASH_COMMENT_INFO_KEY =
			Key.create("parser3.hashCommentInfo");

	private static final Key<Integer> INSERTED_DOT_OFFSET =
			Key.create("parser3.insertedDotOffset");

	private Parser3HashCommentFormatUtil() {
	}

	@NotNull
	static Map<Integer, LineInfo> getOrCreateInfo(@NotNull Document document) {
		dumpCaller("getOrCreateInfo");
		dumpDocument(document, "getOrCreateInfo BEFORE");
		Map<Integer, LineInfo> info = document.getUserData(HASH_COMMENT_INFO_KEY);
		if (info == null) {
			info = new HashMap<>();
			document.putUserData(HASH_COMMENT_INFO_KEY, info);
		}
		dumpDocument(document, "getOrCreateInfo AFTER");
		return info;
	}

	static void markInsertedDot(@NotNull Document document, int offset) {
		document.putUserData(INSERTED_DOT_OFFSET, offset);
	}

	static Integer consumeInsertedDot(@NotNull Document document) {
		Integer value = document.getUserData(INSERTED_DOT_OFFSET);
		if (value != null) {
			document.putUserData(INSERTED_DOT_OFFSET, null);
		}
		return value;
	}

	static Map<Integer, LineInfo> getAndClearInfo(@NotNull Document document) {
		dumpCaller("getAndClearInfo");
		dumpDocument(document, "getAndClearInfo BEFORE");
		Map<Integer, LineInfo> info = document.getUserData(HASH_COMMENT_INFO_KEY);
		if (info != null) {
			document.putUserData(HASH_COMMENT_INFO_KEY, null);
		}
		dumpDocument(document, "getAndClearInfo AFTER");
		return info;
	}

	private static final Key<Map<Integer, Boolean>> HASH_COMMENT_COL0_KEY =
			Key.create("parser3.hashCommentCol0");

	private static final Key<Map<Integer, String>> HASH_COMMENT_INDENT_KEY =
			Key.create("parser3.hashCommentIndent");

	static void markHashCommentColumn0(@NotNull Document document, int line) {
		Map<Integer, Boolean> map = document.getUserData(HASH_COMMENT_COL0_KEY);
		if (map == null) {
			map = new HashMap<>();
			document.putUserData(HASH_COMMENT_COL0_KEY, map);
		}
		map.put(line, Boolean.TRUE);
	}

	static void markHashCommentWithIndent(@NotNull Document document, int line, @NotNull String originalIndent) {
		Map<Integer, String> map = document.getUserData(HASH_COMMENT_INDENT_KEY);
		if (map == null) {
			map = new HashMap<>();
			document.putUserData(HASH_COMMENT_INDENT_KEY, map);
		}
		map.put(line, originalIndent);
	}

	static Map<Integer, String> getAndClearHashCommentIndents(@NotNull Document document) {
		Map<Integer, String> map = document.getUserData(HASH_COMMENT_INDENT_KEY);
		if (map != null) {
			document.putUserData(HASH_COMMENT_INDENT_KEY, null);
		}
		return map;
	}

	static Map<Integer, Boolean> getAndClearHashCommentColumn0(@NotNull Document document) {
		Map<Integer, Boolean> map = document.getUserData(HASH_COMMENT_COL0_KEY);
		if (map != null) {
			document.putUserData(HASH_COMMENT_COL0_KEY, null);
		}
		return map;
	}
}