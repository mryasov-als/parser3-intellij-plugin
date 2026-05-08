package ru.artlebedev.parser3.lexer.debug;

import ru.artlebedev.parser3.lexer.Parser3LexerCore;
import ru.artlebedev.parser3.lexer.Parser3LexerCore.CoreToken;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

public final class Parser3LexerDebugMain {

	public static void main(String[] args) {
		try {
			// Просто редактируешь этот файл и запускаешь конфигурацию
			String file = args.length > 1 ? args[1] : "src/main/java/ru/artlebedev/parser3/lexer/debug/sample.txt";
			String text = Files.readString(Paths.get(file));
			if (args.length > 2) {
				int limit = Integer.parseInt(args[2]);
				text = text.substring(0, Math.min(limit, text.length()));
			}

			System.out.println("=== Loaded file ===");
			System.out.println(text);
			System.out.println("===================");

			if (args.length > 0 && "compare".equalsIgnoreCase(args[0])) {
				compareModes(text);
			} else {
				dumpTokens(text);
			}

			// Для генерации файла ожидаемых токенов раскомментируй:
			// generateExpectedTokens(text, "src/test/resources/sql_expected_tokens.txt");
		} catch (Throwable t) {
			t.printStackTrace(System.out);
		}
	}

	private static void dumpTokens(String text) {
		List<CoreToken> tokens = Parser3LexerCore.tokenize(text, 0, text.length());

//		tokens = tokens.stream().filter(t -> !t.type.equals("TEMPLATE_DATA")).toList();
//		tokens = tokens.stream().filter(t -> !t.type.equals("LPAREN")).toList();
//		tokens = tokens.stream().filter(t -> !t.type.equals("RPAREN")).toList();
//		tokens = tokens.stream().filter(t -> !t.type.equals("LBRACE")).toList();
//		tokens = tokens.stream().filter(t -> !t.type.equals("RBRACE")).toList();
//		tokens = tokens.stream().filter(t -> !t.type.equals("LBRACKET")).toList();
//		tokens = tokens.stream().filter(t -> !t.type.equals("RBRACKET")).toList();

		System.out.println("=== TOKENS ===");
		for (CoreToken token : tokens) {
			String fragment = text.substring(token.start, token.end)
					.replace("\n", "\\n")
					.replace("\r", "\\r");

			System.out.printf(
					"[%3d, %3d] %-15s '%s'%n",
					token.start,
					token.end,
					token.type,
					fragment
			);
		}
	}

	private static void compareModes(String text) {
		System.clearProperty("parser3.lexer.legacy");
		List<CoreToken> singlePass = Parser3LexerCore.tokenize(text, 0, text.length());

		System.setProperty("parser3.lexer.legacy", "true");
		List<CoreToken> legacy = Parser3LexerCore.tokenize(text, 0, text.length());
		System.clearProperty("parser3.lexer.legacy");

		System.out.printf("singlePass=%d, legacy=%d%n", singlePass.size(), legacy.size());

		int count = Math.min(singlePass.size(), legacy.size());
		for (int i = 0; i < count; i++) {
			CoreToken actual = singlePass.get(i);
			CoreToken expected = legacy.get(i);
			if (actual.start != expected.start
					|| actual.end != expected.end
					|| !actual.type.equals(expected.type)
					|| !safe(actual.debugText).equals(safe(expected.debugText))) {
				System.out.println("=== FIRST DIFF ===");
				System.out.printf("index=%d%n", i);
				System.out.printf("singlePass: [%d,%d] %s '%s'%n",
						actual.start, actual.end, actual.type, escape(actual.debugText));
				System.out.printf("legacy    : [%d,%d] %s '%s'%n",
						expected.start, expected.end, expected.type, escape(expected.debugText));
				return;
			}
		}

		if (singlePass.size() != legacy.size()) {
			System.out.println("=== SIZE DIFF ===");
			List<CoreToken> longer = singlePass.size() > legacy.size() ? singlePass : legacy;
			String label = singlePass.size() > legacy.size() ? "singlePass" : "legacy";
			CoreToken extra = longer.get(count);
			System.out.printf("%s extra: [%d,%d] %s '%s'%n",
					label, extra.start, extra.end, extra.type, escape(extra.debugText));
			return;
		}

		System.out.println("No diffs");
	}

	private static String safe(String value) {
		return value == null ? "" : value;
	}

	private static String escape(String value) {
		return safe(value)
				.replace("\n", "\\n")
				.replace("\r", "\\r")
				.replace("\t", "\\t");
	}

	/**
	 * Генерирует файл ожидаемых токенов для тестов.
	 * Формат: start,end,type
	 */
	private static void generateExpectedTokens(String text, String outputPath) throws Exception {
		List<CoreToken> tokens = Parser3LexerCore.tokenize(text, 0, text.length());

		StringBuilder sb = new StringBuilder();
		sb.append("# Автоматически сгенерированный файл\n");
		sb.append("# Формат: start,end,type\n");
		sb.append("# Для регенерации используй Parser3LexerDebugMain.generateExpectedTokens()\n\n");

		for (CoreToken t : tokens) {
			sb.append(t.start).append(",").append(t.end).append(",").append(t.type).append("\n");
		}

		Files.writeString(Paths.get(outputPath), sb.toString());
		System.out.println("Сгенерировано " + tokens.size() + " токенов в " + outputPath);
	}
}
