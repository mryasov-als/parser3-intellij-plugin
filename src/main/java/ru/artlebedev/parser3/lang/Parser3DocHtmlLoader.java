package ru.artlebedev.parser3.lang;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Загрузка и лёгкая обработка локальных HTML-доков Parser3.
 *
 * Ожидается, что все *.htm будут лежать в resources по пути:
 *   /ru/artlebedev/parser3/docs/lang/<имя_файла>.htm
 *
 * Пример:
 *   https://www.parser.ru/docs/lang/datecreatestring.htm
 *   → /ru/artlebedev/parser3/docs/lang/datecreatestring.htm
 */
public final class Parser3DocHtmlLoader {

	private static final String DOCS_ROOT = "/ru/artlebedev/parser3/docs/lang/";
	private static final Pattern LOCAL_LINK_PATTERN = Pattern.compile("href=\"(.+?)\\.htm\"");

	private Parser3DocHtmlLoader() {
	}

	/**
	 * Загружает локальный HTML по URL из Parser3BuiltinMethods.
	 *
	 * @param docUrl полная ссылка вида https://www.parser.ru/docs/lang/datecreatestring.htm
	 * @return HTML-строка или null, если файла нет в ресурсах
	 */
	public static @Nullable String loadLocalHtml(@Nullable String docUrl) {
		if (docUrl == null || docUrl.isEmpty()) {
			return null;
		}

		final int slash = docUrl.lastIndexOf('/');
		if (slash < 0 || slash == docUrl.length() - 1) {
			return null;
		}

		final String fileName = docUrl.substring(slash + 1);
		String html = getContent(fileName);
		if (html == null) return null;

		StringBuilder sb = new StringBuilder();
		html = html.replace("<link-to-docs />", "<a href=\"" + docUrl + "\">parser.ru</a>");
		sb.append("<style>.title {font-size: 1.2em; font-weight: bold}</style>");

		List<String[]> menu = Parser3BuiltinMethods.findNameUrlConflicts(docUrl);
		int i = 0;
		if (menu.size() > 1) {
			for (String[] pair : menu) {
				i++;
				String menuFile = pair[1].substring(pair[1].lastIndexOf("/") + 1);
				if (pair[1].equals(docUrl)) {
					sb.append("<strong>" + pair[2] + "</strong>");
				} else {
					sb.append("<a href=\"psi_element://p3doc/" + menuFile + "\">" + pair[2] + "</a>");
				}
				if (i < menu.size()) sb.append(" / ");
			}
			sb.append("<hr />");
		}
		sb.append(html);

		return sb.toString();
	}

	private static @Nullable String getContent (@NotNull String fileName) {
		final String resourcePath = DOCS_ROOT + fileName;
		try (InputStream in = Parser3DocHtmlLoader.class.getResourceAsStream(resourcePath)) {
			if (in == null) {
				return null;
			}
			String html = readAll(in);
			html = rewriteLocalLinks(html);
			return html;
		} catch (IOException e) {
			// не роняем подсказку, просто не показываем локальный HTML
			return null;
		}
	}

	private static @NotNull String readAll(@NotNull InputStream in) throws IOException {
		StringBuilder sb = new StringBuilder();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				sb.append(line).append('\n');
			}
		}
		return sb.toString();
	}

	/**
	 * Переписываем ссылки вида href="xxx.htm" на абсолютные parser.ru,
	 * чтобы они гарантированно открывались в браузере.
	 *
	 * В будущем здесь можно заменить на кастомный протокол parser3doc://
	 * и обработать его через DocumentationLinkHandler.
	 */
	private static @NotNull String rewriteLocalLinks(@NotNull String html) {
		Matcher matcher = LOCAL_LINK_PATTERN.matcher(html);
		StringBuffer out = new StringBuffer();
		while (matcher.find()) {
			String fileName = matcher.group(1);
			String absoluteUrl = "psi_element://p3doc/" + fileName + ".htm";
			String replacement = "href=\"" + absoluteUrl + "\"";
			matcher.appendReplacement(out, replacement.replace("$", "\\$"));
		}
		matcher.appendTail(out);
		return out.toString();
	}

	public static String loadLocalHtmlByFileName(String fileName) {
		return loadLocalHtml("https://www.parser.ru/docs/lang/" + fileName);
	}
}