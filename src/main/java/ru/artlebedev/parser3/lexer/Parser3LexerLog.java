package ru.artlebedev.parser3.lexer;

/**
 * Утилита для логирования работы лексера.
 * Включить/выключить логи — изменить ENABLED.
 */
public final class Parser3LexerLog {

	// ===== ВКЛЮЧИТЬ/ВЫКЛЮЧИТЬ ЛОГИ =====
	private static final boolean ENABLED = false;
	// ===================================

	private Parser3LexerLog() {
	}

	public static void log(String message) {
		if (ENABLED) {
			System.out.println("[P3Lexer] " + message);
		}
	}

	public static void log(String format, Object... args) {
		if (ENABLED) {
			System.out.println("[P3Lexer] " + String.format(format, args));
		}
	}

	/**
	 * Логирует время выполнения, если оно больше порога.
	 */
	public static void logTime(String name, long startNanos) {
		if (ENABLED) {
			long elapsed = System.nanoTime() - startNanos;
			if (elapsed > 100_000) { // > 0.1ms
				System.out.println(String.format("[P3Lexer] %s: %.2fms", name, elapsed / 1_000_000.0));
			}
		}
	}

	/**
	 * Логирует время выполнения всегда (без порога).
	 */
	public static void logTimeAlways(String name, long startNanos) {
		if (ENABLED) {
			long elapsed = System.nanoTime() - startNanos;
			System.out.println(String.format("[P3Lexer] %s: %.2fms", name, elapsed / 1_000_000.0));
		}
	}

	public static boolean isEnabled() {
		return ENABLED;
	}
}