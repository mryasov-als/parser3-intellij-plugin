package ru.artlebedev.parser3.injector;

/**
 * Централизованное логирование для отладки инжекторов и PolySymbols.
 *
 * По умолчанию отключено. Для включения установить ENABLED = true.
 */
public class Parser3DebugLog {

	/**
	 * Флаг включения логов.
	 */
	public static final boolean ENABLED = false;

	/**
	 * Записывает сообщение в лог.
	 */
	public static void log(String category, String message) {
		if (!ENABLED) return;
		System.out.println("[" + category + "] " + message);
	}

	/**
	 * Записывает многострочный текст (виртуальный документ и т.п.)
	 */
	public static void logMultiline(String category, String title, String content) {
		if (!ENABLED) return;
		System.out.println("[" + category + "] ========== " + title + " ==========");
		System.out.println(content);
		System.out.println("[" + category + "] ========== /" + title + " ==========");
	}

	/**
	 * Записывает разделитель секции.
	 */
	public static void logSection(String title) {
		if (!ENABLED) return;
		System.out.println("============================================================");
		System.out.println("=== " + title + " ===");
		System.out.println("============================================================");
	}
}