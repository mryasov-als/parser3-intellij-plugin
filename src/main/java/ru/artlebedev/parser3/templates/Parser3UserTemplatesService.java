package ru.artlebedev.parser3.templates;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service(Service.Level.APP)
@State(
		name = "Parser3UserTemplates",
		storages = @Storage("parser3_user_templates.xml")
)
public final class Parser3UserTemplatesService implements PersistentStateComponent<Parser3UserTemplatesState> {

	private static final String LEGACY_CURSOR_MARKER = "$CURSOR$";
	private static final String CURSOR_MARKER = "<CURSOR>";

	private Parser3UserTemplatesState state = new Parser3UserTemplatesState();

	public static Parser3UserTemplatesService getInstance() {
		return ApplicationManager.getApplication().getService(Parser3UserTemplatesService.class);
	}

	@Override
	public @NotNull Parser3UserTemplatesState getState() {
		ensureInitialized();
		return state;
	}

	@Override
	public void loadState(@NotNull Parser3UserTemplatesState state) {
		this.state = state;
		ensureInitialized();
	}

	private void ensureInitialized() {
		if (state == null) {
			state = new Parser3UserTemplatesState();
		}
		if (state.templates == null) {
			state.templates = new ArrayList<>();
		}
		if (state.templates.isEmpty()) {
			addDefaultTemplates();
		}
		normalizeTemplates();
	}

	private void normalizeTemplates() {
		for (Parser3UserTemplate template : state.templates) {
			if (template == null) {
				continue;
			}
			if (template.id == null || template.id.isEmpty()) {
				template.id = UUID.randomUUID().toString();
			}
			if (template.name == null) {
				template.name = "";
			}
			if (template.comment == null) {
				template.comment = "";
			}
			if (template.body == null) {
				template.body = "";
			}
			if (template.body.contains(LEGACY_CURSOR_MARKER)) {
				template.body = template.body.replace(LEGACY_CURSOR_MARKER, CURSOR_MARKER);
			}
		}
	}

	private void addDefaultTemplates() {
		state.templates.add(createDefaultCurlTemplate());
		state.templates.add(createDefaultMailTemplate());
		state.templates.add(createDefaultMailTemplateHtml());
		state.templates.add(createDefaultForeachTemplate());
	}

	private Parser3UserTemplate createDefaultCurlTemplate() {
		Parser3UserTemplate template = new Parser3UserTemplate();
		template.id = UUID.randomUUID().toString();
		template.name = "curl:load";
		template.comment = "Загрузка файла с удаленного сервера";
		template.body = "^curl:load[\n" +
				"\t$.url[<CURSOR>]\n" +
				"\t$.useragent[Parser3]\n" +
				"\t$.timeout(10)\n" +
				"\t$.ssl_verifypeer(0)\n" +
				"]";
		template.enabled = true;
		template.priority = 0;
		template.scope = null;
		return template;
	}
	private Parser3UserTemplate createDefaultMailTemplate() {
		Parser3UserTemplate template = new Parser3UserTemplate();
		template.id = UUID.randomUUID().toString();
		template.name = "mail:send";
		template.comment = "Отправка сообщения по электронной почте";
		template.body = "^mail:send[\n" +
				"\t$.from[<CURSOR>]\n" +
				"\t$.to[]\n" +
				"\t$.subject[]\n" +
				"\t$.text[]\n" +
				"]";
		template.enabled = true;
		template.priority = 0;
		template.scope = null;
		return template;
	}

	private Parser3UserTemplate createDefaultMailTemplateHtml() {
		Parser3UserTemplate template = new Parser3UserTemplate();
		template.id = UUID.randomUUID().toString();
		template.name = "mail:send html";
		template.comment = "Отправка письма с HTML";
		template.body = "^mail:send[\n" +
				"\t$.from[<CURSOR>]\n" +
				"\t$.to[]\n" +
				"\t$.subject[]\n" +
				"\t$.html[\n" +
				"\t\t$.value{^html.base64[]}\n" +
				"\t\t$.content-transfer-encoding[base64]\n" +
				"\t]\n" +
				"]";

		template.enabled = true;
		template.priority = 0;
		template.scope = null;
		return template;
	}
	private Parser3UserTemplate createDefaultForeachTemplate() {
		Parser3UserTemplate template = new Parser3UserTemplate();
		template.id = UUID.randomUUID().toString();
		template.name = "foreach";
		template.comment = "Перебор элементов массива или хеша";
		template.body = ".foreach[key;value]{\n\t<CURSOR>\n}";
		template.enabled = true;
		template.priority = 1;
		template.scope = null;
		return template;
	}

	public List<Parser3UserTemplate> getAllTemplates() {
		ensureInitialized();
		return new ArrayList<>(state.templates);
	}

	public List<Parser3UserTemplate> getEnabledTemplates() {
		ensureInitialized();
		List<Parser3UserTemplate> result = new ArrayList<>();
		for (Parser3UserTemplate template : state.templates) {
			if (template != null && template.enabled) {
				result.add(template);
			}
		}
		return result;
	}

	public void setTemplates(@NotNull List<Parser3UserTemplate> templates) {
		state.templates = new ArrayList<>(templates);
		ensureInitialized();
	}

	@Nullable
	public Parser3UserTemplate findById(@NotNull String id) {
		ensureInitialized();
		for (Parser3UserTemplate template : state.templates) {
			if (template != null && id.equals(template.id)) {
				return template;
			}
		}
		return null;
	}

	public @NotNull List<Parser3TemplateDescriptor> getEnabledTemplateDescriptors() {
		List<Parser3TemplateDescriptor> result = new ArrayList<>();
		for (Parser3UserTemplate template : getEnabledTemplates()) {
			if (template == null) {
				continue;
			}
			result.add(buildDescriptor(template));
		}
		return result;
	}

	private Parser3TemplateDescriptor buildDescriptor(@NotNull Parser3UserTemplate template) {
		String rawName = template.name != null ? template.name.trim() : "";
		String comment = template.comment != null ? template.comment : "";
		String body = template.body != null ? template.body : "";

		String prefix = "";
		String suffix = "";
		String computedName = rawName;

		String trimmedBody = body.trim();
		if (!trimmedBody.isEmpty()) {
			char first = trimmedBody.charAt(0);
			if (first == '^' || first == '.' || first == '$') {
				prefix = String.valueOf(first);
				int lineEnd = trimmedBody.indexOf('\n');
				if (lineEnd < 0) {
					lineEnd = trimmedBody.length();
				}
				String firstLine = trimmedBody.substring(1, lineEnd);
				int bracketIndex = findFirstUnescapedBracket(firstLine);
				if (bracketIndex >= 0) {
					char bracket = firstLine.charAt(bracketIndex);
					if (bracket == '[') {
						suffix = "[]";
					} else if (bracket == '(') {
						suffix = "()";
					} else if (bracket == '{') {
						suffix = "{}";
					}
					String namePart = firstLine.substring(0, bracketIndex).trim();
					if (!namePart.isEmpty()) {
						computedName = namePart;
					}
				}
			}
		}

		return new Parser3TemplateDescriptor(computedName, prefix, suffix, comment, template);
	}

	private int findFirstUnescapedBracket(@NotNull String text) {
		for (int i = 0; i < text.length(); i++) {
			char ch = text.charAt(i);
			if (ch == '[' || ch == '(' || ch == '{') {
				if (i == 0) {
					return i;
				}
				char prev = text.charAt(i - 1);
				if (prev != '\\') {
					return i;
				}
			}
		}
		return -1;
	}
}
