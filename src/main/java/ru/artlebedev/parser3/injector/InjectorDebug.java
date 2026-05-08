package ru.artlebedev.parser3.injector;

import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Утилита для логирования инжекций HTML/CSS/JS.
 *
 * Включить/выключить логи: изменить соответствующие флаги.
 * Логи пишутся в файл parser3-injector.log в домашней директории пользователя.
 */
public class InjectorDebug {

	/**
	 * Флаг включения логов HTML injection.
	 */
	public static final boolean HTML_INJECTION_LOG = false;

	/**
	 * Флаг включения логов CSS injection.
	 */
	public static final boolean CSS_INJECTION_LOG = false;

	/**
	 * Флаг включения логов JS injection.
	 */
	public static final boolean JS_INJECTION_LOG = false;

	/**
	 * Общий флаг (для обратной совместимости).
	 */
	public static final boolean ENABLED = HTML_INJECTION_LOG || CSS_INJECTION_LOG || JS_INJECTION_LOG;

	/**
	 * Путь к файлу лога.
	 */
	private static final String LOG_FILE = System.getProperty("user.home") + "/parser3-injector.log";

	/**
	 * Логирует итоговый виртуальный документ для инжекции.
	 *
	 * @param language язык (HTML, CSS, JS)
	 * @param virtualDocument полный текст виртуального документа
	 */
	public static void logVirtualDocument(String language, String virtualDocument) {
		if (!isEnabledFor(language)) return;

		String msg = "\n========== " + language + " Virtual Document ==========\n" +
				virtualDocument +
				"\n========== /" + language + " ==========\n";

		System.out.println(msg);
		writeToFile(msg);
	}

	/**
	 * Логирует произвольное сообщение.
	 */
	public static void log(String message) {
		if (!ENABLED) return;

		System.out.println(message);
		writeToFile(message);
	}

	/**
	 * Проверяет включены ли логи для указанного языка.
	 */
	private static boolean isEnabledFor(String language) {
		if ("HTML".equals(language)) return HTML_INJECTION_LOG;
		if ("CSS".equals(language)) return CSS_INJECTION_LOG;
		if ("JS".equals(language)) return JS_INJECTION_LOG;
		return ENABLED;
	}

	/**
	 * Записывает сообщение в файл лога.
	 */
	private static void writeToFile(String message) {
		try (PrintWriter writer = new PrintWriter(new FileWriter(LOG_FILE, true))) {
			String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
			writer.println("[" + timestamp + "] " + message);
		} catch (Exception e) {
			// Игнорируем ошибки записи
		}
	}
}