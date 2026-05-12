package ru.artlebedev.parser3;

import com.intellij.codeInsight.AutoPopupController;
import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.codeInsight.editorActions.CompletionAutoPopupHandler;
import com.intellij.codeInsight.lookup.Lookup;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementPresentation;
import com.intellij.codeInsight.lookup.LookupFocusDegree;
import com.intellij.codeInsight.lookup.LookupManager;
import com.intellij.codeInsight.lookup.impl.LookupImpl;
import com.intellij.injected.editor.EditorWindow;
import com.intellij.openapi.actionSystem.IdeActions;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.EditorTestUtil;
import com.intellij.testFramework.TestModeFlags;
import org.jetbrains.annotations.NotNull;
import ru.artlebedev.parser3.completion.P3ClassCompletionContributor;
import ru.artlebedev.parser3.completion.P3CompletionUtils;
import ru.artlebedev.parser3.templates.Parser3UserTemplate;
import ru.artlebedev.parser3.templates.Parser3UserTemplatesService;
import ru.artlebedev.parser3.visibility.P3ScopeContext;
import ru.artlebedev.parser3.visibility.P3VariableScopeContext;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Тесты автокомплита: переменные ($var, $self.var, $MAIN:var),
 * методы объектов (^var.), свойства ($var.), @GET_ геттеры.
 */
public class x9_CompletionTest extends Parser3TestCase {

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		createTestStructure();

		com.intellij.psi.PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
		com.intellij.openapi.project.DumbService.getInstance(getProject()).waitForSmartMode();
	}

	private void createTestStructure() {
		// auto.p — подключает файлы
		createParser3FileInDir("auto.p",
				"@auto[]\n" +
						"^use[lib.p]\n"
		);

		// www/auto.p — подключает классы
		createParser3FileInDir("www/auto.p",
				"@auto[]\n" +
						// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
						"@USE\n" +
						"classes/User.p\n" +
						"classes/Admin.p\n" +
						"classes/222.p\n"
		);

		// www/classes/User.p
		createParser3FileInDir("www/classes/User.p",
				// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
				"@CLASS\n" +
						"User\n" +
						"\n" +
						"@create[name;email]\n" +
						"$self.name[$name]\n" +
						"$self.email[$email]\n" +
						"\n" +
						"@validate[]\n" +
						"$result[ok]\n" +
						"\n" +
						"@save[]\n" +
						"$result[saved]\n" +
						"\n" +
						"@GET_displayName[]\n" +
						"$result[$self.name]\n"
		);

		// www/classes/Admin.p — наследует User
		createParser3FileInDir("www/classes/Admin.p",
				// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
				"@CLASS\n" +
						"Admin\n" +
						"\n" +
						"@BASE\n" +
						"User\n" +
						"\n" +
						"@create[name;email;role]\n" +
						"^BASE:create[$name;$email]\n" +
						"$self.role[$role]\n" +
						"\n" +
						"@ban[user_id]\n" +
						"$result[banned]\n" +
						"\n" +
						"@GET_fullTitle[]\n" +
						"$result[admin]\n"
		);

		createParser3FileInDir("www/classes/222.p",
				// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
				"@CLASS\n" +
						"222\n" +
						"\n" +
						"@create[]\n" +
						"$result[^hash::create[]]\n" +
						"\n" +
						"@method[]\n" +
						"$result[ok]\n"
		);

		// lib.p — MAIN методы (подключен через auto.p)
		createParser3FileInDir("lib.p",
				"@helper[]\n" +
						"$result[ok]\n" +
						"\n" +
						"$libVar[^User::create[lib;lib@x]]\n"
		);
	}

	// ==================== HELPERS ====================

	/**
	 * Выполняет автокомплит в указанном файле на позиции <caret> и возвращает список строк.
	 */
	private List<String> getCompletions(String path, String content) {
		configureParser3TextFile(path, content);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		if (elements == null) return List.of();
		return Arrays.stream(elements)
				.map(LookupElement::getLookupString)
				.collect(Collectors.toList());
	}

	private List<String> getCompletionItemTexts(String path, String content) {
		configureParser3TextFile(path, content);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		if (elements == null) return List.of();
		return Arrays.stream(elements)
				.map(element -> {
					LookupElementPresentation presentation = LookupElementPresentation.renderElement(element);
					String itemText = presentation.getItemText();
					return itemText != null ? itemText : element.getLookupString();
				})
				.collect(Collectors.toList());
	}

	private void configureParser3TextFile(@NotNull String path, @NotNull String content) {
		VirtualFile vf = createParser3FileInDir(path, content);
		myFixture.configureFromExistingVirtualFile(vf);
	}

	private List<String> getCompletionsFromExistingFile(String path) {
		VirtualFile vf = myFixture.findFileInTempDir(path);
		assertNotNull("Файл не найден: " + path, vf);
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		if (elements == null) return List.of();
		return Arrays.stream(elements)
				.map(LookupElement::getLookupString)
				.collect(Collectors.toList());
	}

	private void assertHashKeyDotAutoCompletionWithDotUserTemplates(@NotNull String message, @NotNull List<String> completions) {
		assertTrue(message + " должен содержать ключ x: " + completions, completions.contains("x"));
		assertTrue(message + " должен содержать ключ y: " + completions, completions.contains("y"));
		assertTrue(message + " должен показывать dot-шаблон foreach[] после точки: " + completions,
				completions.contains("foreach[]"));
		assertFalse(message + " не должен показывать пользовательский шаблон curl:load[] без dot-prefix после точки: " + completions,
				completions.contains("curl:load[]"));
		assertFalse(message + " не должен показывать пользовательский шаблон mail:send[] без dot-prefix после точки: " + completions,
				completions.contains("mail:send[]"));

		int xIndex = completions.indexOf("x");
		int yIndex = completions.indexOf("y");
		int foreachIndex = completions.indexOf("foreach[]");
		assertTrue(message + " ключ x должен быть выше dot-шаблона foreach[]: " + completions,
				xIndex < foreachIndex);
		assertTrue(message + " ключ y должен быть выше dot-шаблона foreach[]: " + completions,
				yIndex < foreachIndex);
	}

	private void assertRealKeysAboveDotTemplates(
			@NotNull String message,
			@NotNull List<String> completions,
			@NotNull List<String> realKeys,
			@NotNull List<String> dotTemplates
	) {
		int lastRealIndex = -1;
		for (String realKey : realKeys) {
			int realIndex = completions.indexOf(realKey);
			assertTrue(message + " должен содержать реальный ключ " + realKey + ": " + completions, realIndex >= 0);
			lastRealIndex = Math.max(lastRealIndex, realIndex);
		}
		for (String dotTemplate : dotTemplates) {
			int templateIndex = completions.indexOf(dotTemplate);
			assertTrue(message + " должен содержать dot-шаблон " + dotTemplate + ": " + completions, templateIndex >= 0);
			assertTrue(message + " dot-шаблон " + dotTemplate + " должен быть ниже реальных ключей: " + completions,
					templateIndex > lastRealIndex);
		}
	}

	private void assertActiveLookupFirstItemSelected(@NotNull String message, @NotNull LookupImpl lookup) {
		waitForDefaultLookupSelection(lookup);
		List<String> names = lookup.getItems().stream()
				.map(LookupElement::getLookupString)
				.collect(Collectors.toList());
		assertFalse(message + " должен иметь хотя бы один пункт", names.isEmpty());
		LookupElement selected = lookup.getCurrentItem();
		assertNotNull(message + " должен выбрать первый пункт", selected);
		assertEquals(message + " должен выбрать первый пункт списка", names.get(0), selected.getLookupString());
		assertEquals(message + " должен визуально выбрать первую строку", 0, lookup.getSelectedIndex());
	}

	private LookupImpl assertActiveLookupImpl(@NotNull String message) {
		Lookup activeLookup = getActiveLookup();
		assertNotNull(message + ": popup должен быть открыт", activeLookup);
		assertTrue(message + ": popup должен быть LookupImpl", activeLookup instanceof LookupImpl);
		return (LookupImpl) activeLookup;
	}

	private Lookup getActiveLookup() {
		Editor editor = myFixture.getEditor();
		Lookup lookup = LookupManager.getActiveLookup(editor);
		if (lookup == null && editor instanceof EditorWindow) {
			lookup = LookupManager.getActiveLookup(((EditorWindow) editor).getDelegate());
		}
		return lookup;
	}

	private void assertNoActiveLookup(@NotNull String message) {
		assertNull(message, getActiveLookup());
	}

	private List<String> getLookupNames(@NotNull Lookup lookup) {
		return lookup.getItems().stream()
				.map(LookupElement::getLookupString)
				.collect(Collectors.toList());
	}

	private void typeWithAutoPopup(@NotNull String text, @NotNull String timeoutMessage) {
		TestModeFlags.runWithFlag(CompletionAutoPopupHandler.ourTestingAutopopup, Boolean.TRUE, () -> {
			myFixture.type(text);
			com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents();
			try {
				AutoPopupController.getInstance(getProject()).waitForDelayedActions(5, TimeUnit.SECONDS);
			} catch (java.util.concurrent.TimeoutException e) {
				throw new AssertionError(timeoutMessage, e);
			}
			com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents();
		});
	}

	private void typeInHostEditorWithAutoPopup(@NotNull String text, @NotNull String timeoutMessage) {
		Editor editor = myFixture.getEditor();
		Editor hostEditor = editor instanceof EditorWindow
				? ((EditorWindow) editor).getDelegate()
				: editor;
		TestModeFlags.runWithFlag(CompletionAutoPopupHandler.ourTestingAutopopup, Boolean.TRUE, () -> {
			for (int i = 0; i < text.length(); i++) {
				EditorTestUtil.performTypingAction(hostEditor, text.charAt(i));
			}
			com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents();
			try {
				AutoPopupController.getInstance(getProject()).waitForDelayedActions(5, TimeUnit.SECONDS);
			} catch (java.util.concurrent.TimeoutException e) {
				throw new AssertionError(timeoutMessage, e);
			}
			com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents();
		});
	}

	private void assertHashKeyDotCompletionWithUserTemplates(@NotNull String message, @NotNull List<String> completions) {
		assertTrue(message + " должен содержать ключ x: " + completions, completions.contains("x"));
		assertTrue(message + " должен содержать ключ y: " + completions, completions.contains("y"));
		assertTrue(message + " должен показывать пользовательский шаблон curl:load[] по Ctrl+Space: " + completions,
				completions.contains("curl:load[]"));
		assertTrue(message + " должен показывать пользовательский шаблон foreach[] по Ctrl+Space: " + completions,
				completions.contains("foreach[]"));
		assertTrue(message + " должен показывать пользовательский шаблон mail:send[] по Ctrl+Space: " + completions,
				completions.contains("mail:send[]"));

		int xIndex = completions.indexOf("x");
		int yIndex = completions.indexOf("y");
		int firstTemplateIndex = java.util.stream.Stream.of("curl:load[]", "foreach[]", "mail:send[]")
				.mapToInt(completions::indexOf)
				.filter(i -> i >= 0)
				.min()
				.orElse(-1);
		assertTrue(message + " ключ x должен быть выше пользовательских шаблонов: " + completions,
				firstTemplateIndex < 0 || xIndex < firstTemplateIndex);
		assertTrue(message + " ключ y должен быть выше пользовательских шаблонов: " + completions,
				firstTemplateIndex < 0 || yIndex < firstTemplateIndex);
	}

	private String parser176RealClassA() {
		return "@CLASS\n" +
				"a\n" +
				"\n" +
				"@OPTIONS\n" +
				"locals\n" +
				"\n" +
				"@create[]\n" +
				"$self.one_1[]\n" +
				"$self.one_2[]\n" +
				"$self.one_3[]\n" +
				"$self.two_1[]\n" +
				"$self.two_2[]\n" +
				"$self.two_3[]\n" +
				"\n" +
				"@one[][locals;one_1]\n" +
				"$one_1[one_1]\t^rem{ local }\n" +
				"$one_2[one_2]\t^rem{ local because of 'locals' }\n" +
				"$self.one_3[one_3]\t^rem{ not local }\n" +
				"\n" +
				"@two[][two_1]\n" +
				"$two_1[two_1]\t^rem{ local }\n" +
				"$two_2[two_2]\t^rem{ not local }\n" +
				"$self.two_3[two_3]\t^rem{ not local }\n" +
				"\n" +
				"@run[]\n" +
				"^self.one[]\n" +
				"^self.two[]\n" +
				"^if($self.three is \"junction\"){\n" +
				"\t^self.three[]\n" +
				"}\n" +
				"^if($self.four is \"junction\"){\n" +
				"\t^self.four[]\n" +
				"}\n";
	}

	private String parser176RealClassB() {
		// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
		return "@CLASS\n" +
				"b\n" +
				"\n" +
				"@create[]\n" +
				"$self.one_1[]\n" +
				"$self.one_2[]\n" +
				"$self.one_3[]\n" +
				"$self.two_1[]\n" +
				"$self.two_2[]\n" +
				"$self.two_3[]\n" +
				"\n" +
				"\n" +
				"@one[][locals;one_1]\n" +
				"$one_1[one_1]\t^rem{ local }\n" +
				"$one_2[one_2]\t^rem{ local because of 'locals' }\n" +
				"$self.one_3[one_3]\t^rem{ not local }\n" +
				"\n" +
				"@two[][two_1]\n" +
				"$two_1[two_1]\t^rem{ local }\n" +
				"$two_2[two_2]\t^rem{ not local }\n" +
				"$self.two_3[two_3]\t^rem{ not local }\n" +
				"\n" +
				"@run[]\n" +
				"^self.one[]\n" +
				"^self.two[]\n" +
				"^if($self.three is \"junction\"){\n" +
				"\t^self.three[]\n" +
				"}\n" +
				"^if($self.four is \"junction\"){\n" +
				"\t^self.four[]\n" +
				"}\n";
	}

	/**
	 * Проверяет что автокомплит содержит указанные элементы.
	 */
	private void assertCompletionContains(String path, String content, String... expected) {
		List<String> completions = getCompletions(path, content);
		for (String item : expected) {
			assertTrue("Автокомплит должен содержать '" + item + "', но содержит: " + completions,
					completions.contains(item));
		}
	}

	/**
	 * Проверяет что автокомплит НЕ содержит указанные элементы.
	 */
	private void assertCompletionNotContains(String path, String content, String... unexpected) {
		List<String> completions = getCompletions(path, content);
		for (String item : unexpected) {
			assertFalse("Автокомплит НЕ должен содержать '" + item + "', но содержит: " + completions,
					completions.contains(item));
		}
	}

	private void assertExplicitCompletionContainsUserTemplate(String path, String content) {
		List<String> completions = getCompletions(path, content);
		assertTrue("Ctrl+Space должен показывать пользовательские шаблоны в любом контексте, есть: " + completions,
				completions.contains("curl:load[]"));
	}

	private void selectCompletion(@NotNull String lookupString) {
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("Должны быть варианты completion", elements);
		LookupElement target = Arrays.stream(elements)
				.filter(e -> lookupString.equals(e.getLookupString()))
				.findFirst()
				.orElse(null);
		assertNotNull("Должен быть lookup element '" + lookupString + "', есть: " +
				Arrays.stream(elements).map(LookupElement::getLookupString).collect(Collectors.toList()), target);
		myFixture.getLookup().setCurrentItem(target);
		myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR);
	}

	private void selectCompletionByEnter(@NotNull String lookupString) {
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("Должны быть варианты completion", elements);
		LookupElement target = Arrays.stream(elements)
				.filter(e -> lookupString.equals(e.getLookupString()))
				.findFirst()
				.orElse(null);
		assertNotNull("Должен быть lookup element '" + lookupString + "'", target);
		myFixture.getLookup().setCurrentItem(target);
		myFixture.finishLookup('\n');
	}

	private void selectCompletionByTab(@NotNull String lookupString) {
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("Должны быть варианты completion", elements);
		LookupElement target = Arrays.stream(elements)
				.filter(e -> lookupString.equals(e.getLookupString()))
				.findFirst()
				.orElse(null);
		assertNotNull("Должен быть lookup element '" + lookupString + "'", target);
		myFixture.getLookup().setCurrentItem(target);
		myFixture.finishLookup(Lookup.REPLACE_SELECT_CHAR);
	}

	private void assertManualOpeningBracketDoesNotAcceptLookup(
			@NotNull String path,
			@NotNull String content,
			@NotNull String lookupString,
			char typedBracket,
			@NotNull String expectedManualCall,
			@NotNull String duplicatedCall
	) {
		configureParser3TextFile(path, content);
		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("После полного имени вызова должен быть активный popup", elements);
		LookupElement target = Arrays.stream(elements)
				.filter(e -> lookupString.equals(e.getLookupString()))
				.findFirst()
				.orElse(null);
		assertNotNull("В popup должен быть lookup element '" + lookupString + "'", target);
		myFixture.getLookup().setCurrentItem(target);

		myFixture.type(Character.toString(typedBracket));
		com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents();

		String text = myFixture.getEditor().getDocument().getText();
		assertTrue("Ручной ввод " + typedBracket + " должен попасть в документ: " + text,
				text.contains(expectedManualCall));
		assertFalse("Ручной ввод " + typedBracket + " не должен выбирать lookup и удваивать скобки: " + text,
				text.contains(duplicatedCall));
	}

	private Parser3UserTemplate createUserTemplate(@NotNull String name, @NotNull String comment, @NotNull String body) {
		Parser3UserTemplate template = new Parser3UserTemplate();
		template.id = java.util.UUID.randomUUID().toString();
		template.name = name;
		template.comment = comment;
		template.body = body;
		template.enabled = true;
		template.priority = 0;
		template.scope = null;
		return template;
	}

	private List<Parser3UserTemplate> createDotUserTemplates() {
		return List.of(
				createUserTemplate(
						"foreach",
						"Перебор элементов массива или хеша",
						".foreach[key;value]{\n\t<CURSOR>\n}"
				),
				createUserTemplate(
						"mail:send",
						"Отправка сообщения по электронной почте",
						".mail:send[\n\t$.from[<CURSOR>]\n]"
				)
		);
	}

	private int countPropertyCompletion(@NotNull List<String> completions, @NotNull String name) {
		return java.util.Collections.frequency(completions, name)
				+ java.util.Collections.frequency(completions, name + ".");
	}

	private void assertSinglePropertyCompletion(
			@NotNull String message,
			@NotNull List<String> completions,
			@NotNull String name
	) {
		assertEquals(message + ": " + completions, 1, countPropertyCompletion(completions, name));
	}

	private void withUserTemplates(@NotNull List<Parser3UserTemplate> templates, @NotNull Runnable action) {
		Parser3UserTemplatesService service = Parser3UserTemplatesService.getInstance();
		List<Parser3UserTemplate> oldTemplates = service.getAllTemplates();
		try {
			service.setTemplates(templates);
			action.run();
		} finally {
			service.setTemplates(oldTemplates);
		}
	}

	// =============================================================================
	// РАЗДЕЛ 1: $var автокомплит в MAIN без locals
	// =============================================================================

	/**
	 * $<caret> в MAIN — показывает переменные.
	 */
	public void testDollarVar_main_normal() {
		createParser3FileInDir("www/test_compl.p",
				"@auto[]\n" +
						"# на основе parser3/tests/001.html\n" +
						"$myUser[^User::create[a;b]]\n" +
						"\n" +
						"@main[]\n" +
						"# на основе parser3/tests/001.html\n" +
						"$<caret>"
		);
		VirtualFile vf = myFixture.findFileInTempDir("www/test_compl.p");
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("Должны быть варианты", elements);

		List<String> names = Arrays.stream(elements)
				.map(LookupElement::getLookupString)
				.collect(Collectors.toList());

		assertTrue("Должен быть myUser, есть: " + names, names.contains("myUser"));
	}

	public void testDollarVarPrefix_keepsMatchingStructuralVariable() {
		StringBuilder content = new StringBuilder();
		content.append("@main[]\n");
		for (int i = 0; i < 80; i++) {
			content.append("$aa_").append(i).append("[^table::create{id\tname}]\n");
		}
		content.append("$del_ids[^table::create{id\troom_name}]\n");
		content.append("$de<caret>\n");

		List<String> names = getCompletions("prefix_structural_variable_completion.p", content.toString());

		assertTrue("Должен быть del_ids. как table-переменная, есть: " + names, names.contains("del_ids."));
		assertFalse("Чужой prefix не должен попадать в completion, есть: " + names, names.contains("aa_1."));
	}

	public void testBooleanCompletion_inVariableRoundExpression() {
		assertCompletionContains("test_bool_var.p",
				"@main[]\n" +
						"# на основе parser3/tests/416.html\n" +
						"$var(<caret>)",
				"true", "false");
	}

	public void testBooleanCompletion_inHashFieldRoundExpression() {
		assertCompletionContains("test_bool_hash_field.p",
				"@main[]\n" +
						"# на основе parser3/tests/416.html\n" +
						"$data[\n" +
						"\t$.var(<caret>)\n" +
						"]",
				"true", "false");
	}

	public void testBooleanCompletion_inIfAndWhileExpressions() {
		assertCompletionContains("test_bool_if.p",
				"@main[]\n" +
						"# на основе parser3/tests/416.html\n" +
						"^if(<caret>){}",
				"true", "false");
		assertCompletionContains("test_bool_while.p",
				"@main[]\n" +
						"# на основе parser3/tests/416.html\n" +
						"^while(<caret>){}",
				"true", "false");
	}

	public void testBooleanCompletion_notAfterSpacedIf() {
		assertCompletionNotContains("test_bool_spaced_if.p",
				"@main[]\n" +
						"# на основе parser3/tests/416.html\n" +
						"^if (<caret>){}",
				"true", "false");

		configureParser3TextFile("test_bool_spaced_if_autopopup.p",
				"@main[]\n" +
						"# на основе parser3/tests/416.html\n" +
						"^if (<caret>){}");
		assertFalse("Набор t после ^if ( не должен запускать autopopup",
				ru.artlebedev.parser3.completion.P3CompletionUtils.shouldAutoPopupBooleanLiteral(
						myFixture.getEditor().getDocument().getCharsSequence(),
						myFixture.getCaretOffset(),
						't'
				));
	}

	public void testBooleanCompletion_onlyImmediatelyAfterOpeningParen() {
		assertCompletionNotContains("test_bool_if_after_space.p",
				"@main[]\n" +
						"# на основе parser3/tests/416.html\n" +
						"^if( t<caret>){}",
				"true", "false");

		configureParser3TextFile("test_bool_if_after_space_autopopup.p",
				"@main[]\n" +
						"# на основе parser3/tests/416.html\n" +
						"^if( <caret>){}");
		assertFalse("Набор t после пробела в ^if( не должен запускать autopopup",
				ru.artlebedev.parser3.completion.P3CompletionUtils.shouldAutoPopupBooleanLiteral(
						myFixture.getEditor().getDocument().getCharsSequence(),
						myFixture.getCaretOffset(),
						't'
				));
	}

	public void testMethodCompletion_dynamicClassStaticMethodPrefix() {
		createParser3FileInDir("call-type/DocDynamic.p",
				"# на основе https://www.parser.ru/docs/lang/defineclass.htm: @OPTIONS dynamic и @static:method\n" +
						"@CLASS\n" +
						"DocDynamic\n" +
						"\n" +
						"@OPTIONS\n" +
						"dynamic\n" +
						"\n" +
						"@create[]\n" +
						"$result[$self]\n" +
						"\n" +
						"@method1[]\n" +
						"$result[dynamic]\n" +
						"\n" +
						"@static:method2[]\n" +
						"$result[static]\n");

		List<String> staticNames = getCompletions("call-type/static_call_dynamic_class.p",
				"@USE\n" +
						"DocDynamic.p\n" +
						"\n" +
						"@main[]\n" +
						"^DocDynamic:m<caret>");
		assertTrue("Статический вызов должен показывать @static:method2: " + staticNames,
				staticNames.contains("method2"));
		assertFalse("Статический вызов не должен показывать dynamic method1: " + staticNames,
				staticNames.contains("method1"));

		List<String> dynamicNames = getCompletions("call-type/object_call_dynamic_class.p",
				"@USE\n" +
						"DocDynamic.p\n" +
						"\n" +
						"@main[]\n" +
						"$object[^DocDynamic::create[]]\n" +
						"^object.m<caret>");
		assertTrue("Динамический вызов объекта должен показывать method1: " + dynamicNames,
				dynamicNames.contains("method1"));
		assertFalse("Динамический вызов объекта не должен показывать @static:method2: " + dynamicNames,
				dynamicNames.contains("method2"));
	}

	public void testMethodCompletion_explicitDynamicAndStaticPrefixesWithoutClassOption() {
		createParser3FileInDir("call-type/DocAny.p",
				"# на основе Parser3: без @OPTIONS методы any, @dynamic/@static сужают тип вызова\n" +
						"@CLASS\n" +
						"DocAny\n" +
						"\n" +
						"@create[]\n" +
						"$result[$self]\n" +
						"\n" +
						"@methodAny[]\n" +
						"$result[any]\n" +
						"\n" +
						"@dynamic:methodDynamic[]\n" +
						"$result[dynamic]\n" +
						"\n" +
						"@static:methodStatic[]\n" +
						"$result[static]\n");

		List<String> staticNames = getCompletions("call-type/static_call_any_class.p",
				"@USE\n" +
						"DocAny.p\n" +
						"\n" +
						"@main[]\n" +
						"^DocAny:m<caret>");
		assertTrue("Статический вызов должен показывать any methodAny: " + staticNames,
				staticNames.contains("methodAny"));
		assertTrue("Статический вызов должен показывать @static:methodStatic: " + staticNames,
				staticNames.contains("methodStatic"));
		assertFalse("Статический вызов не должен показывать @dynamic:methodDynamic: " + staticNames,
				staticNames.contains("methodDynamic"));
		assertFalse("В completion не должен попадать префикс static:: " + staticNames,
				staticNames.contains("static:methodStatic"));

		List<String> dynamicNames = getCompletions("call-type/object_call_any_class.p",
				"@USE\n" +
						"DocAny.p\n" +
						"\n" +
						"@main[]\n" +
						"$object[^DocAny::create[]]\n" +
						"^object.m<caret>");
		assertTrue("Динамический вызов должен показывать any methodAny: " + dynamicNames,
				dynamicNames.contains("methodAny"));
		assertTrue("Динамический вызов должен показывать @dynamic:methodDynamic: " + dynamicNames,
				dynamicNames.contains("methodDynamic"));
		assertFalse("Динамический вызов не должен показывать @static:methodStatic: " + dynamicNames,
				dynamicNames.contains("methodStatic"));
		assertFalse("В completion не должен попадать префикс dynamic:: " + dynamicNames,
				dynamicNames.contains("dynamic:methodDynamic"));
	}

	public void testMethodCompletion_autoPopupAfterSingleClassColon() {
		String textBeforeTypedColon = "@main[]\n" +
				"$list[^DocDynamic";
		assertTrue("Набор ':' после уже готового ^ClassName должен стартовать popup методов",
				P3ClassCompletionContributor.shouldAutoPopupBeforeClassMethodSeparator(
						textBeforeTypedColon,
						textBeforeTypedColon.length(),
						':'
				));

		String textAfterTypedColon = "@main[]\n" +
				"$list[^DocDynamic:";
		assertTrue("После одиночного ':' в ^ClassName: должен стартовать popup методов",
				P3ClassCompletionContributor.shouldAutoPopupAfterClassMethodSeparator(
						textAfterTypedColon,
						textAfterTypedColon.length(),
						':'
				));
		assertFalse("Набор ':' после MAIN не должен стартовать popup методов класса",
				P3ClassCompletionContributor.shouldAutoPopupBeforeClassMethodSeparator(
						"@main[]\n$MAIN",
						"@main[]\n$MAIN".length(),
						':'
				));
		assertFalse("После MAIN: автопопап методов класса не должен срабатывать",
				P3ClassCompletionContributor.shouldAutoPopupAfterClassMethodSeparator(
						"@main[]\n$MAIN:",
						"@main[]\n$MAIN:".length(),
						':'
				));

		List<String> names = getCompletions("call-type/single_colon_empty_prefix.p",
				"@main[]\n" +
						"$list[^DocDynamic:<caret>]\n" +
						"\n" +
						"@CLASS\n" +
						"DocDynamic\n" +
						"\n" +
						"@create[]\n" +
						"$result[$self]\n" +
						"\n" +
						"@method1[]\n" +
						"$result[dynamic]\n");
		assertTrue("Completion после ^DocDynamic: с пустым префиксом должен показывать create: " + names,
				names.contains("create"));
		assertTrue("Completion после ^DocDynamic: с пустым префиксом должен показывать method1: " + names,
				names.contains("method1"));
	}

	public void testBooleanCompletion_notAfterHashChainInsideIf() {
		assertCompletionNotContains("test_bool_if_after_hash_chain.p",
				"@main[]\n" +
						"# на основе parser3/tests/010.html и 416.html\n" +
						"$alsLogin[\n" +
						"\t$.user[\n" +
						"\t\t$.id(0)\n" +
						"\t]\n" +
						"]\n" +
						"^if($alsLogin.user.<caret>){}",
				"true", "false");
	}

	public void testExplicitCompletion_showsUserTemplatesInAnyContext() {
		assertExplicitCompletionContainsUserTemplate("test_explicit_user_templates_spaced_if.p",
				"@main[]\n" +
						"# на основе parser3/tests/416.html\n" +
						"^if (t<caret>){}");
		assertCompletionNotContains("test_explicit_user_templates_spaced_if_no_bool.p",
				"@main[]\n" +
						"# на основе parser3/tests/416.html\n" +
						"^if (t<caret>){}",
				"true", "false");

		assertExplicitCompletionContainsUserTemplate("test_explicit_user_templates_plain_text.p",
				"@main[]\n" +
						"# на основе parser3/tests/001.html\n" +
						"text t<caret>");
		assertExplicitCompletionContainsUserTemplate("test_explicit_user_templates_builtin_prop.p",
				"@main[]\n" +
						"# на основе parser3/tests/097.html\n" +
						"$env:t<caret>");
		assertExplicitCompletionContainsUserTemplate("test_explicit_user_templates_dollar_dot_without_caret.p",
				"@main[]\n" +
						"# на основе parser3/tests/010.html\n" +
						"$var.t<caret>");
		assertExplicitCompletionContainsUserTemplate("test_explicit_user_templates_after_finished_dollar_dot_expression.p",
				"@main[]\n" +
						"# на основе parser3/tests/010.html\n" +
						"^render[$var.t + ^cur<caret>]");
		assertExplicitCompletionContainsUserTemplate("test_explicit_user_templates_invalid_caret_dot.p",
				"@main[]\n" +
						"# на основе parser3/tests/010.html\n" +
						"^1&asdf.t<caret>");
	}

	public void testUserTemplateInsert_movesCaretToCursorMarker() {
		withUserTemplates(List.of(createUserTemplate("wrap", "test", "^wrap[<CURSOR>]")), () -> {
			configureParser3TextFile("test_user_template_cursor_marker.p",
					"@main[]\n" +
							"# на основе parser3/tests/001.html\n" +
							"^wr<caret>");

			myFixture.complete(CompletionType.BASIC);
			selectCompletion("wrap[]");

			String text = myFixture.getEditor().getDocument().getText();
			assertEquals("@main[]\n# на основе parser3/tests/001.html\n^wrap[]", text);
			assertEquals("Каретка должна стоять внутри скобок", text.lastIndexOf("[]") + 1, myFixture.getCaretOffset());
		});
	}

	public void testUserTemplateInsert_selectsTextBetweenCursorMarkers() {
		withUserTemplates(List.of(createUserTemplate("wrap", "test", "^wrap[<CURSOR>value</CURSOR>]")), () -> {
			configureParser3TextFile("test_user_template_selection_markers.p",
					"@main[]\n" +
							"# на основе parser3/tests/001.html\n" +
							"^wr<caret>");

			myFixture.complete(CompletionType.BASIC);
			selectCompletion("wrap[]");

			String text = myFixture.getEditor().getDocument().getText();
			assertEquals("@main[]\n# на основе parser3/tests/001.html\n^wrap[value]", text);
			assertEquals("Должен быть выделен текст между маркерами", "value",
					myFixture.getEditor().getSelectionModel().getSelectedText());
		});
	}

	public void testSystemMethodOpeningParenDoesNotAcceptLookupAfterExactTypedName() {
		assertManualOpeningBracketDoesNotAcceptLookup(
				"test_system_method_manual_opening_paren.p",
				"@main[]\n" +
						"# на основе parser3/tests/416.html\n" +
						"^if<caret>",
				"if",
				'(',
				"^if(",
				"^if()()"
		);
		assertManualOpeningBracketDoesNotAcceptLookup(
				"test_system_method_manual_square_bracket.p",
				"@main[]\n" +
						"# на основе parser3/tests/416.html\n" +
						"^switch<caret>",
				"switch",
				'[',
				"^switch[",
				"^switch[][]"
		);
		assertManualOpeningBracketDoesNotAcceptLookup(
				"test_static_method_manual_square_bracket.p",
				"@main[]\n" +
						"# обезличенный кейс конструктора builtin-класса\n" +
						"^hash::create<caret>",
				"hash::create",
				'[',
				"^hash::create[",
				"^hash::create[][]"
		);
		assertManualOpeningBracketDoesNotAcceptLookup(
				"test_user_method_manual_square_bracket.p",
				"@main[]\n" +
						"# обезличенный кейс пользовательского метода\n" +
						"^helper<caret>\n" +
						"\n" +
						"@helper[]\n",
				"helper",
				'[',
				"^helper[",
				"^helper[][]"
		);
	}

	public void testBooleanCompletion_autoPopupOnTypedTrueFalsePrefix() {
		configureParser3TextFile("test_bool_autopopup_true.p",
				"@main[]\n" +
						"# на основе parser3/tests/416.html\n" +
						"$var(<caret>)");
		assertTrue("Набор t в boolean-контексте должен запускать autopopup",
				ru.artlebedev.parser3.completion.P3CompletionUtils.shouldAutoPopupBooleanLiteral(
						myFixture.getEditor().getDocument().getCharsSequence(),
						myFixture.getCaretOffset(),
						't'
				));
		myFixture.type("t");
		myFixture.complete(CompletionType.BASIC);
		LookupElement[] trueElements = myFixture.getLookupElements();
		assertNotNull("После набора t в boolean-контексте должен открыться popup", trueElements);
		List<String> trueNames = Arrays.stream(trueElements)
				.map(LookupElement::getLookupString)
				.collect(Collectors.toList());
		assertTrue("После набора t должен быть true: " + trueNames, trueNames.contains("true"));

		configureParser3TextFile("test_bool_autopopup_false.p",
				"@main[]\n" +
						"# на основе parser3/tests/416.html\n" +
						"^if(<caret>){}");
		assertTrue("Набор f в boolean-контексте должен запускать autopopup",
				ru.artlebedev.parser3.completion.P3CompletionUtils.shouldAutoPopupBooleanLiteral(
						myFixture.getEditor().getDocument().getCharsSequence(),
						myFixture.getCaretOffset(),
						'f'
				));
		myFixture.type("f");
		myFixture.complete(CompletionType.BASIC);
		LookupElement[] falseElements = myFixture.getLookupElements();
		assertNotNull("После набора f в boolean-контексте должен открыться popup", falseElements);
		List<String> falseNames = Arrays.stream(falseElements)
				.map(LookupElement::getLookupString)
				.collect(Collectors.toList());
		assertTrue("После набора f должен быть false: " + falseNames, falseNames.contains("false"));
	}

	public void testBooleanCompletion_noAutoPopupInPlainText() {
		configureParser3TextFile("test_bool_no_autopopup_plain_text.p",
				"@main[]\n" +
						"# на основе parser3/tests/001.html\n" +
						"text (<caret>)");
		assertFalse("Набор t в обычном тексте не должен запускать autopopup",
				ru.artlebedev.parser3.completion.P3CompletionUtils.shouldAutoPopupBooleanLiteral(
						myFixture.getEditor().getDocument().getCharsSequence(),
						myFixture.getCaretOffset(),
						't'
				));
	}

	public void testBooleanCompletion_notInEscapedVariableOrPlainText() {
		assertCompletionNotContains("test_bool_escaped_var.p",
				"@main[]\n" +
						"# на основе parser3/tests/001.html\n" +
						"^$var(<caret>)",
				"true", "false");
		assertCompletionNotContains("test_bool_plain_text.p",
				"@main[]\n" +
						"# на основе parser3/tests/001.html\n" +
						"text (<caret>)",
				"true", "false");
	}

	public void testDollarCompletion_selfAndMainInsertSeparator() {
		configureParser3TextFile("test_dollar_scope_self.p",
				"@main[]\n" +
						"# на основе parser3/tests/001.html\n" +
						"$se<caret>");
		myFixture.complete(CompletionType.BASIC);
		selectCompletion("self.");
		assertTrue("После выбора self должен вставиться $self.: " + myFixture.getEditor().getDocument().getText(),
				myFixture.getEditor().getDocument().getText().contains("$self."));

		configureParser3TextFile("test_dollar_scope_main.p",
				"@main[]\n" +
						"# на основе parser3/tests/001.html\n" +
						"$MA<caret>");
		myFixture.complete(CompletionType.BASIC);
		selectCompletion("MAIN:");
		assertTrue("После выбора MAIN должен вставиться $MAIN:: " + myFixture.getEditor().getDocument().getText(),
				myFixture.getEditor().getDocument().getText().contains("$MAIN:"));
	}

	public void testCaretCompletion_selfAndMainInsertSeparator() {
		configureParser3TextFile("test_caret_scope_self.p",
				"@main[]\n" +
						"# на основе parser3/tests/001.html\n" +
						"^se<caret>");
		myFixture.complete(CompletionType.BASIC);
		selectCompletion("self.");
		assertTrue("После выбора self должен вставиться ^self.: " + myFixture.getEditor().getDocument().getText(),
				myFixture.getEditor().getDocument().getText().contains("^self."));

		configureParser3TextFile("test_caret_scope_main.p",
				"@main[]\n" +
						"# на основе parser3/tests/001.html\n" +
						"^MA<caret>");
		myFixture.complete(CompletionType.BASIC);
		selectCompletion("MAIN:");
		assertTrue("После выбора MAIN должен вставиться ^MAIN:: " + myFixture.getEditor().getDocument().getText(),
				myFixture.getEditor().getDocument().getText().contains("^MAIN:"));
	}

	/**
	 * $self.<caret> в MAIN — показывает те же переменные (эквивалентность).
	 */
	public void testDollarVar_main_self() {
		createParser3FileInDir("www/test_compl_self.p",
				"@auto[]\n" +
						"# на основе parser3/tests/001.html\n" +
						"$myVar2[some_value]\n" +
						"\n" +
						"@main[]\n" +
						"# на основе parser3/tests/001.html\n" +
						"$self.<caret>"
		);
		VirtualFile vf = myFixture.findFileInTempDir("www/test_compl_self.p");
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("Должны быть варианты для $self.", elements);

		List<String> names = Arrays.stream(elements)
				.map(LookupElement::getLookupString)
				.collect(Collectors.toList());

		assertTrue("$self. должен содержать myVar2, есть: " + names, names.contains("myVar2"));
	}

	/**
	 * $MAIN:<caret> в MAIN — показывает те же переменные.
	 */
	public void testDollarVar_main_mainPrefix() {
		createParser3FileInDir("www/test_compl_main.p",
				"@auto[]\n" +
						"# на основе parser3/tests/001.html\n" +
						"$myVar3[some_value]\n" +
						"\n" +
						"@main[]\n" +
						"# на основе parser3/tests/001.html\n" +
						"$MAIN:<caret>"
		);
		VirtualFile vf = myFixture.findFileInTempDir("www/test_compl_main.p");
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("Должны быть варианты для $MAIN:", elements);

		List<String> names = Arrays.stream(elements)
				.map(LookupElement::getLookupString)
				.collect(Collectors.toList());

		assertTrue("$MAIN: должен содержать myVar3, есть: " + names, names.contains("myVar3"));
	}

	// =============================================================================
	// РАЗДЕЛ 2: $var автокомплит в MAIN с locals
	// =============================================================================

	/**
	 * $<caret> в method2 — НЕ видит локальную из method1.
	 */
	public void testDollarVar_mainLocals_localNotVisible() {
		createParser3FileInDir("www/test_compl_locals.p",
				"@OPTIONS\n" +
						"locals\n" +
						"# на основе parser3/tests/176_dir/a.p\n" +
						"\n" +
						"@method1[]\n" +
						"$secretLocal[hidden]\n" +
						"\n" +
						"@method2[]\n" +
						"$<caret>"
		);
		VirtualFile vf = myFixture.findFileInTempDir("www/test_compl_locals.p");
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		if (elements != null) {
			List<String> names = Arrays.stream(elements)
					.map(LookupElement::getLookupString)
					.collect(Collectors.toList());
			assertFalse("secretLocal НЕ должна быть видна из method2, есть: " + names,
					names.contains("secretLocal"));
		}
	}

	/**
	 * $MAIN:<caret> в method2 — видит глобальную $MAIN:gVar.
	 */
	public void testDollarVar_mainLocals_globalVisible() {
		createParser3FileInDir("www/test_compl_locals_g.p",
				"@OPTIONS\n" +
						"locals\n" +
						"# на основе parser3/tests/176.html\n" +
						"\n" +
						"@method1[]\n" +
						"$MAIN:gVar[global]\n" +
						"\n" +
						"@method2[]\n" +
						"$MAIN:<caret>"
		);
		VirtualFile vf = myFixture.findFileInTempDir("www/test_compl_locals_g.p");
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("Должны быть варианты для $MAIN:", elements);

		List<String> names = Arrays.stream(elements)
				.map(LookupElement::getLookupString)
				.collect(Collectors.toList());

		assertTrue("$MAIN: должен содержать gVar, есть: " + names, names.contains("gVar"));
	}

	public void testDollarVar_autoChainInheritedLocalsInsideNestedAuto() {
		try {
			setDocumentRoot("locals_case1");
		} catch (java.io.IOException e) {
			throw new RuntimeException(e);
		}
		createParser3FileInDir("locals_case1/auto.p",
				"@OPTIONS\n" +
						"locals\n" +
						"# на основе parser3/tests/176.html\n"
		);
		createParser3FileInDir("locals_case1/inner/auto.p",
				"@main[]\n" +
						"# на основе parser3/tests/176.html\n" +
						"$var[xxx]\n" +
						"\n" +
						"@method[]\n" +
						"$v<caret>\n"
		);
		VirtualFile vf = myFixture.findFileInTempDir("locals_case1/inner/auto.p");
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		List<String> names = elements == null ? List.of() :
				Arrays.stream(elements).map(LookupElement::getLookupString).collect(Collectors.toList());

		assertFalse("@OPTIONS locals из верхнего auto.p должен делать $var локальной для вложенного auto.p, есть: " + names,
				names.contains("var"));
	}

	public void testDollarVar_autoChainInheritedLocalsDoesNotLeakNestedAutoVars() {
		try {
			setDocumentRoot("locals_case2");
		} catch (java.io.IOException e) {
			throw new RuntimeException(e);
		}
		createParser3FileInDir("locals_case2/auto.p",
				"@OPTIONS\n" +
						"locals\n" +
						"# на основе parser3/tests/176.html\n"
		);
		createParser3FileInDir("locals_case2/inner/auto.p",
				"@main[]\n" +
						"# на основе parser3/tests/176.html\n" +
						"$var[xxx]\n"
		);
		createParser3FileInDir("locals_case2/inner/page.p",
				"@main[]\n" +
						"# на основе parser3/tests/176.html\n" +
						"$v<caret>\n"
		);
		VirtualFile vf = myFixture.findFileInTempDir("locals_case2/inner/page.p");
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		List<String> names = elements == null ? List.of() :
				Arrays.stream(elements).map(LookupElement::getLookupString).collect(Collectors.toList());

		assertFalse("Локальная $var из вложенного auto.p не должна быть видна в соседнем файле, есть: " + names,
				names.contains("var"));
	}

	public void testDollarVar_allMethodsDoesNotLeakVarsFromFileWithInheritedAutoLocals() {
		try {
			setDocumentRoot("locals_case3/www");
		} catch (java.io.IOException e) {
			throw new RuntimeException(e);
		}
		setMethodCompletionMode(ru.artlebedev.parser3.settings.Parser3ProjectSettings.MethodCompletionMode.ALL_METHODS);

		createParser3FileInDir("locals_case3/www/api/auto.p",
				"@OPTIONS\n" +
						"locals\n" +
						"# на основе parser3/tests/176.html\n" +
						"\n" +
						"@auto[]\n" +
						"^use[CONFIG.p]\n" +
						"\n" +
						"@get_connect_data[s]\n" +
						"$result[\n" +
						"\t$.string[$TEST_APP_CONFIG.connects.[$key].string]\n" +
						"\t$.db_name[$db_name]\n" +
						"]\n"
		);
		createParser3FileInDir("locals_case3/www/api/actions/query.p",
				"@mod_code[]\n" +
						"$connectData[^get_connect_data[]]\n"
		);
		createParser3FileInDir("locals_case3/www/api/CONFIG.p",
				"@auto[][locals]\n" +
						"$connectData.<caret>\n"
		);

		List<String> names = getCompletionsFromExistingFile("locals_case3/www/api/CONFIG.p");
		assertFalse("ALL_METHODS не должен показывать поля локальной $connectData из actions/query.p, есть: " + names,
				names.contains("db_name"));
		assertFalse("ALL_METHODS не должен показывать поля локальной $connectData из actions/query.p, есть: " + names,
				names.contains("string"));
	}

	public void testDollarVar_mainOptionsLocalsDoesNotAffectMethodsAboveIt() {
		createParser3FileInDir("www/locals_order_main_before.p",
				"@before[]\n" +
						"# на основе parser3/tests/176.html\n" +
						"$beforeGlobal[\n" +
						"\t$.visible_key[]\n" +
						"]\n" +
						"\n" +
						"@middle[]\n" +
						"$beforeGlobal.<caret>\n" +
						"\n" +
						"@OPTIONS\n" +
						"locals\n" +
						"\n" +
						"@after[]\n" +
						"$afterLocal[\n" +
						"\t$.hidden_key[]\n" +
						"]\n"
		);

		List<String> names = getCompletionsFromExistingFile("www/locals_order_main_before.p");
		assertTrue("@OPTIONS locals ниже метода не должен делать $beforeGlobal локальной, есть: " + names,
				names.contains("visible_key"));
	}

	public void testDollarVar_mainOptionsLocalsHidesMethodsBelowItFromMainPrefix() {
		createParser3FileInDir("www/locals_order_main_after.p",
				"@before[]\n" +
						"# на основе parser3/tests/176.html\n" +
						"$beforeGlobal[ok]\n" +
						"\n" +
						"@OPTIONS\n" +
						"locals\n" +
						"\n" +
						"@after[]\n" +
						"$afterLocal[secret]\n" +
						"\n" +
						"@check[]\n" +
						"$MAIN:<caret>\n"
		);

		List<String> names = getCompletionsFromExistingFile("www/locals_order_main_after.p");
		assertTrue("$MAIN: должен видеть глобальную переменную из метода до @OPTIONS locals, есть: " + names,
				names.contains("beforeGlobal"));
		assertFalse("$MAIN: не должен видеть локальную переменную из метода после @OPTIONS locals, есть: " + names,
				names.contains("afterLocal"));
	}

	public void testDollarVar_classOptionsLocalsDoesNotAffectMethodsAboveIt() {
		createParser3FileInDir("www/locals_order_class_before.p",
				// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
				"@CLASS\n" +
						"OrderClassBefore\n" +
						"\n" +
						"@before[]\n" +
						"# на основе parser3/tests/176_dir/a.p\n" +
						"$beforeClass[\n" +
						"\t$.visible_key[]\n" +
						"]\n" +
						"\n" +
						"@OPTIONS\n" +
						"locals\n" +
						"\n" +
						"@after[]\n" +
						"$self.<caret>\n"
		);

		List<String> names = getCompletionsFromExistingFile("www/locals_order_class_before.p");
		assertTrue("@OPTIONS locals ниже метода класса не должен делать $beforeClass локальной, есть: " + names,
				names.contains("beforeClass."));
	}

	public void testDollarVar_classOptionsLocalsHidesMethodsBelowItFromSelf() {
		createParser3FileInDir("www/locals_order_class_after.p",
				// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
				"@CLASS\n" +
						"OrderClassAfter\n" +
						"\n" +
						"@before[]\n" +
						"# на основе parser3/tests/176_dir/a.p\n" +
						"$beforeClass[ok]\n" +
						"\n" +
						"@OPTIONS\n" +
						"locals\n" +
						"\n" +
						"@after[]\n" +
						"$afterLocal[secret]\n" +
						"\n" +
						"@check[]\n" +
						"$self.<caret>\n"
		);

		List<String> names = getCompletionsFromExistingFile("www/locals_order_class_after.p");
		assertTrue("$self. должен видеть переменную класса из метода до @OPTIONS locals, есть: " + names,
				names.contains("beforeClass"));
		assertFalse("$self. не должен видеть локальную переменную из метода после @OPTIONS locals, есть: " + names,
				names.contains("afterLocal"));
	}

	public void testDollarVar_mainOptionsLocalsDoesNotAffectFollowingClass() {
		createParser3FileInDir("www/locals_main_does_not_affect_class.p",
				"@OPTIONS\n" +
						"locals\n" +
						"# на основе parser3/tests/176.html\n" +
						"\n" +
						"@CLASS\n" +
						"ClassAfterMainLocals\n" +
						"\n" +
						"@create[]\n" +
						"$classField[ok]\n" +
						"\n" +
						"@check[]\n" +
						"$self.<caret>\n"
		);

		List<String> names = getCompletionsFromExistingFile("www/locals_main_does_not_affect_class.p");
		assertTrue("@OPTIONS locals в MAIN не должен делать переменные класса локальными, есть: " + names,
				names.contains("classField"));
	}

	public void testDollarVar_allMethodsRespectsSourceFileOptionsOrder() {
		setMethodCompletionMode(ru.artlebedev.parser3.settings.Parser3ProjectSettings.MethodCompletionMode.ALL_METHODS);

		createParser3FileInDir("www/locals_all_order_source.p",
				"@before[]\n" +
						"# на основе parser3/tests/176.html\n" +
						"$beforeGlobal[\n" +
						"\t$.visible_key[]\n" +
						"]\n" +
						"\n" +
						"@OPTIONS\n" +
						"locals\n" +
						"\n" +
						"@after[]\n" +
						"$afterLocal[\n" +
						"\t$.hidden_key[]\n" +
						"]\n"
		);
		createParser3FileInDir("www/locals_all_order_target.p",
				"@main[]\n" +
						"# на основе parser3/tests/176.html\n" +
						"$beforeGlobal.<caret>\n"
		);

		List<String> names = getCompletionsFromExistingFile("www/locals_all_order_target.p");
		assertTrue("ALL_METHODS должен видеть поля глобальной переменной до @OPTIONS locals, есть: " + names,
				names.contains("visible_key"));
	}

	public void testDollarVar_allMethodsDoesNotExposeHashFieldsFromSourceLocalAfterOptions() {
		setMethodCompletionMode(ru.artlebedev.parser3.settings.Parser3ProjectSettings.MethodCompletionMode.ALL_METHODS);

		createParser3FileInDir("www/locals_all_after_source.p",
				"@OPTIONS\n" +
						"locals\n" +
						"# на основе parser3/tests/176.html\n" +
						"\n" +
						"@after[]\n" +
						"$afterLocal[\n" +
						"\t$.hidden_key[]\n" +
						"]\n"
		);
		createParser3FileInDir("www/locals_all_after_target.p",
				"@main[]\n" +
						"# на основе parser3/tests/176.html\n" +
						"$afterLocal.<caret>\n"
		);

		List<String> names = getCompletionsFromExistingFile("www/locals_all_after_target.p");
		assertFalse("ALL_METHODS не должен показывать поля локальной переменной после @OPTIONS locals, есть: " + names,
				names.contains("hidden_key"));
	}

	public void testDollarVar_useOnlyRespectsSourceFileOptionsOrder() {
		setMethodCompletionMode(ru.artlebedev.parser3.settings.Parser3ProjectSettings.MethodCompletionMode.USE_ONLY);

		createParser3FileInDir("www/locals_use_order_source.p",
				"@before[]\n" +
						"# на основе parser3/tests/176.html\n" +
						"$beforeGlobal[\n" +
						"\t$.visible_key[]\n" +
						"]\n" +
						"\n" +
						"@OPTIONS\n" +
						"locals\n" +
						"\n" +
						"@after[]\n" +
						"$afterLocal[\n" +
						"\t$.hidden_key[]\n" +
						"]\n"
		);
		createParser3FileInDir("www/locals_use_order_target.p",
				"@main[]\n" +
						"# на основе parser3/tests/176.html\n" +
						"^use[locals_use_order_source.p]\n" +
						"$beforeGlobal.<caret>\n"
		);

		List<String> names = getCompletionsFromExistingFile("www/locals_use_order_target.p");
		assertTrue("USE_ONLY должен видеть поля глобальной переменной до @OPTIONS locals, есть: " + names,
				names.contains("visible_key"));
		assertFalse("USE_ONLY не должен смешивать поля локальной переменной после @OPTIONS locals, есть: " + names,
				names.contains("hidden_key"));
	}

	public void testDollarVar_allMethodsDoesNotExposeMethodLocalsModifier() {
		setMethodCompletionMode(ru.artlebedev.parser3.settings.Parser3ProjectSettings.MethodCompletionMode.ALL_METHODS);

		createParser3FileInDir("www/locals_method_modifier_source.p",
				"@source[][locals]\n" +
						"# на основе parser3/tests/176.html\n" +
						"$methodLocal[secret]\n"
		);
		createParser3FileInDir("www/locals_method_modifier_target.p",
				"@main[]\n" +
						"# на основе parser3/tests/176.html\n" +
						"$<caret>\n"
		);

		List<String> names = getCompletionsFromExistingFile("www/locals_method_modifier_target.p");
		assertFalse("ALL_METHODS не должен показывать переменную из метода с [locals], есть: " + names,
				names.contains("methodLocal"));
	}

	public void testDollarVar_allMethodsRespectsNamedLocalList() {
		setMethodCompletionMode(ru.artlebedev.parser3.settings.Parser3ProjectSettings.MethodCompletionMode.ALL_METHODS);

		createParser3FileInDir("www/locals_named_list_source.p",
				"@source[][namedLocal]\n" +
						"# на основе parser3/tests/176.html\n" +
						"$namedLocal[secret]\n" +
						"$globalFromSameMethod[ok]\n"
		);
		createParser3FileInDir("www/locals_named_list_target.p",
				"@main[]\n" +
						"# на основе parser3/tests/176.html\n" +
						"$<caret>\n"
		);

		List<String> names = getCompletionsFromExistingFile("www/locals_named_list_target.p");
		assertFalse("ALL_METHODS не должен показывать переменную из списка локальных, есть: " + names,
				names.contains("namedLocal"));
		assertTrue("ALL_METHODS должен показывать не перечисленную глобальную переменную, есть: " + names,
				names.contains("globalFromSameMethod"));
	}

	public void testDollarVar_useOnlyDoesNotExposeUseAfterCaret() {
		setMethodCompletionMode(ru.artlebedev.parser3.settings.Parser3ProjectSettings.MethodCompletionMode.USE_ONLY);

		createParser3FileInDir("www/locals_use_after_source.p",
				"@source[]\n" +
						"# на основе parser3/tests/176.html\n" +
						"$lateUseVar[secret]\n"
		);
		createParser3FileInDir("www/locals_use_after_target.p",
				"@main[]\n" +
						"# на основе parser3/tests/176.html\n" +
						"$<caret>\n" +
						"^use[locals_use_after_source.p]\n"
		);

		List<String> names = getCompletionsFromExistingFile("www/locals_use_after_target.p");
		assertFalse("USE_ONLY не должен показывать переменные из ^use[] ниже курсора, есть: " + names,
				names.contains("lateUseVar"));
	}

	public void testDollarVar_parser176ClassWithOptionsLocalsShowsOnlySelfFields() {
		createParser3FileInDir("www/parser176_a.p",
				// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
				"@CLASS\n" +
						"a\n" +
						"\n" +
						"@OPTIONS\n" +
						"locals\n" +
						"\n" +
						"@one[][locals;one_1]\n" +
						"$one_1[one_1]\n" +
						"$one_2[one_2]\n" +
						"$self.one_3[one_3]\n" +
						"\n" +
						"@two[][two_1]\n" +
						"$two_1[two_1]\n" +
						"$two_2[two_2]\n" +
						"$self.two_3[two_3]\n" +
						"\n" +
						"@check[]\n" +
						"$self.<caret>\n"
		);

		List<String> names = getCompletionsFromExistingFile("www/parser176_a.p");
		assertTrue("176/a: $self. должен видеть явное поле one_3, есть: " + names,
				names.contains("one_3"));
		assertTrue("176/a: $self. должен видеть явное поле two_3, есть: " + names,
				names.contains("two_3"));
		assertFalse("176/a: $self. не должен видеть локальную one_1, есть: " + names,
				names.contains("one_1"));
		assertFalse("176/a: $self. не должен видеть локальную one_2, есть: " + names,
				names.contains("one_2"));
		assertFalse("176/a: $self. не должен видеть локальную two_1, есть: " + names,
				names.contains("two_1"));
		assertFalse("176/a: $self. не должен видеть локальную two_2, есть: " + names,
				names.contains("two_2"));
	}

	public void testDollarVar_parser176RealClassWithOptionsLocalsKeepsCreateFields() {
		createParser3FileInDir("www/parser176_real_a.p",
				parser176RealClassA() +
						"\n" +
						"@check[]\n" +
						"$self.<caret>\n"
		);

		List<String> names = getCompletionsFromExistingFile("www/parser176_real_a.p");
		assertTrue("176/a real: @create[] должен оставить self-поле one_1 видимым, есть: " + names,
				names.contains("one_1"));
		assertTrue("176/a real: @create[] должен оставить self-поле one_2 видимым, есть: " + names,
				names.contains("one_2"));
		assertTrue("176/a real: @create[] должен оставить self-поле two_1 видимым, есть: " + names,
				names.contains("two_1"));
		assertTrue("176/a real: @create[] должен оставить self-поле two_2 видимым, есть: " + names,
				names.contains("two_2"));
		assertTrue("176/a real: явное self-поле one_3 должно быть видимым, есть: " + names,
				names.contains("one_3"));
		assertTrue("176/a real: явное self-поле two_3 должно быть видимым, есть: " + names,
				names.contains("two_3"));
	}

	public void testDollarVar_parser176ClassWithoutOptionsLocalsKeepsSimpleClassVars() {
		createParser3FileInDir("www/parser176_b.p",
				// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
				"@CLASS\n" +
						"b\n" +
						"\n" +
						"@one[][locals;one_1]\n" +
						"$one_1[one_1]\n" +
						"$one_2[one_2]\n" +
						"$self.one_3[one_3]\n" +
						"\n" +
						"@two[][two_1]\n" +
						"$two_1[two_1]\n" +
						"$two_2[two_2]\n" +
						"$self.two_3[two_3]\n" +
						"\n" +
						"@check[]\n" +
						"$self.<caret>\n"
		);

		List<String> names = getCompletionsFromExistingFile("www/parser176_b.p");
		assertTrue("176/b: $self. должен видеть one_3, есть: " + names,
				names.contains("one_3"));
		assertTrue("176/b: $self. должен видеть простую глобальную two_2, есть: " + names,
				names.contains("two_2"));
		assertTrue("176/b: $self. должен видеть two_3, есть: " + names,
				names.contains("two_3"));
		assertFalse("176/b: $self. не должен видеть one_1 из метода с [locals], есть: " + names,
				names.contains("one_1"));
		assertFalse("176/b: $self. не должен видеть one_2 из метода с [locals], есть: " + names,
				names.contains("one_2"));
		assertFalse("176/b: $self. не должен видеть two_1 из списка локальных, есть: " + names,
				names.contains("two_1"));
	}

	public void testDollarVar_parser176RealClassWithoutOptionsLocalsKeepsCreateFields() {
		createParser3FileInDir("www/parser176_real_b.p",
				parser176RealClassB() +
						"\n" +
						"@check[]\n" +
						"$self.<caret>\n"
		);

		List<String> names = getCompletionsFromExistingFile("www/parser176_real_b.p");
		assertTrue("176/b real: @create[] должен оставить self-поле one_1 видимым, есть: " + names,
				names.contains("one_1"));
		assertTrue("176/b real: @create[] должен оставить self-поле one_2 видимым, есть: " + names,
				names.contains("one_2"));
		assertTrue("176/b real: @create[] должен оставить self-поле two_1 видимым, есть: " + names,
				names.contains("two_1"));
		assertTrue("176/b real: @create[] должен оставить self-поле two_2 видимым, есть: " + names,
				names.contains("two_2"));
		assertTrue("176/b real: явное self-поле one_3 должно быть видимым, есть: " + names,
				names.contains("one_3"));
		assertTrue("176/b real: явное self-поле two_3 должно быть видимым, есть: " + names,
				names.contains("two_3"));
	}

	public void testDollarVar_parser176ChildOfLocalsClassDoesNotInheritLocalsFlag() {
		createParser3FileInDir("www/parser176_c.p",
				// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
				"@CLASS\n" +
						"a\n" +
						"\n" +
						"@OPTIONS\n" +
						"locals\n" +
						"\n" +
						"@one[][locals;one_1]\n" +
						"$one_1[one_1]\n" +
						"$one_2[one_2]\n" +
						"$self.one_3[one_3]\n" +
						"\n" +
						"@two[][two_1]\n" +
						"$two_1[two_1]\n" +
						"$two_2[two_2]\n" +
						"$self.two_3[two_3]\n" +
						"\n" +
						// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
						"@CLASS\n" +
						"c\n" +
						"\n" +
						"@BASE\n" +
						"a\n" +
						"\n" +
						"@two[][two_1]\n" +
						"$two_1[two_1]\n" +
						"$two_2[two_2]\n" +
						"$self.two_3[two_3]\n" +
						"\n" +
						"@check[]\n" +
						"$self.<caret>\n"
		);

		List<String> names = getCompletionsFromExistingFile("www/parser176_c.p");
		assertTrue("176/c: наследник должен видеть поле базового класса one_3, есть: " + names,
				names.contains("one_3"));
		assertTrue("176/c: собственная простая two_2 не должна стать локальной из-за locals базового класса, есть: " + names,
				names.contains("two_2"));
		assertTrue("176/c: наследник должен видеть two_3, есть: " + names,
				names.contains("two_3"));
		assertFalse("176/c: локальная one_1 базового класса не должна подтекать, есть: " + names,
				names.contains("one_1"));
		assertFalse("176/c: локальная one_2 базового класса не должна подтекать, есть: " + names,
				names.contains("one_2"));
		assertFalse("176/c: локальная two_1 не должна подтекать, есть: " + names,
				names.contains("two_1"));
	}

	public void testDollarVar_parser176RealChildOfLocalsClassKeepsBaseFieldsFromUseFiles() {
		createParser3FileInDir("www/parser176_real_use/a.p",
				parser176RealClassA()
		);
		createParser3FileInDir("www/parser176_real_use/c.p",
				// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
				"@CLASS\n" +
						"c\n" +
						"\n" +
						"@BASE\n" +
						"a\n" +
						"\n" +
						"@create[]\n" +
						"^BASE:create[]\n" +
						"\n" +
						"@two[][two_1]\n" +
						"$two_1[two_1]\t^rem{ local }\n" +
						"$two_2[two_2]\t^rem{ not local }\n" +
						"$self.two_3[two_3]\t^rem{ not local }\n" +
						"\n" +
						"@check[]\n" +
						"$self.<caret>\n"
		);
		createParser3FileInDir("www/parser176_real_use/page.p",
				"@main[]\n" +
						"# на основе parser3/tests/176.html\n" +
						"^use[a.p]\n" +
						"^use[c.p]\n"
		);

		List<String> names = getCompletionsFromExistingFile("www/parser176_real_use/c.p");
		assertTrue("176/c real: наследник должен видеть self-поле one_1 из @create[] базового класса, есть: " + names,
				names.contains("one_1"));
		assertTrue("176/c real: наследник должен видеть self-поле one_2 из @create[] базового класса, есть: " + names,
				names.contains("one_2"));
		assertTrue("176/c real: наследник должен видеть one_3 базового класса, есть: " + names,
				names.contains("one_3"));
		assertTrue("176/c real: наследник должен видеть собственное two_2, есть: " + names,
				names.contains("two_2"));
		assertTrue("176/c real: наследник должен видеть собственное two_3, есть: " + names,
				names.contains("two_3"));
	}

	public void testDollarVar_parser176RealChildWithOwnOptionsLocalsKeepsBaseFieldsFromUseFiles() {
		createParser3FileInDir("www/parser176_real_use_d/a.p",
				parser176RealClassA()
		);
		createParser3FileInDir("www/parser176_real_use_d/d.p",
				// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
				"@CLASS\n" +
						"d\n" +
						"\n" +
						"@BASE\n" +
						"a\n" +
						"\n" +
						"@OPTIONS\n" +
						"locals\n" +
						"# на основе parser3/tests/176_dir/d.p\n" +
						"\n" +
						"@create[]\n" +
						"^BASE:create[]\n" +
						"\n" +
						"@two[][two_1]\n" +
						"$two_1[two_1]\t^rem{ local }\n" +
						"$two_2[two_2]\t^rem{ not local }\n" +
						"$self.two_3[two_3]\t^rem{ not local }\n" +
						"\n" +
						"@check[]\n" +
						"$self.<caret>\n"
		);
		createParser3FileInDir("www/parser176_real_use_d/page.p",
				"@main[]\n" +
						"# на основе parser3/tests/176.html\n" +
						"^use[a.p]\n" +
						"^use[d.p]\n"
		);

		List<String> names = getCompletionsFromExistingFile("www/parser176_real_use_d/d.p");
		assertTrue("176/d real: наследник должен видеть self-поле one_1 из @create[] базового класса, есть: " + names,
				names.contains("one_1"));
		assertTrue("176/d real: наследник должен видеть self-поле one_2 из @create[] базового класса, есть: " + names,
				names.contains("one_2"));
		assertTrue("176/d real: наследник должен видеть one_3 базового класса, есть: " + names,
				names.contains("one_3"));
		assertTrue("176/d real: наследник должен видеть унаследованное two_2 из @create[] базового класса, есть: " + names,
				names.contains("two_2"));
		assertTrue("176/d real: явное self-поле two_3 должно быть видимым, есть: " + names,
				names.contains("two_3"));
	}

	public void testDollarVar_parser176ChildWithOwnOptionsLocalsKeepsOnlySelfFields() {
		createParser3FileInDir("www/parser176_d.p",
				// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
				"@CLASS\n" +
						"a\n" +
						"\n" +
						"@OPTIONS\n" +
						"locals\n" +
						"\n" +
						"@one[][locals;one_1]\n" +
						"$one_1[one_1]\n" +
						"$one_2[one_2]\n" +
						"$self.one_3[one_3]\n" +
						"\n" +
						"@two[][two_1]\n" +
						"$two_1[two_1]\n" +
						"$two_2[two_2]\n" +
						"$self.two_3[two_3]\n" +
						"\n" +
						// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
						"@CLASS\n" +
						"d\n" +
						"\n" +
						"@BASE\n" +
						"a\n" +
						"\n" +
						"@OPTIONS\n" +
						"locals\n" +
						"\n" +
						"@two[][two_1]\n" +
						"$two_1[two_1]\n" +
						"$two_2[two_2]\n" +
						"$self.two_3[two_3]\n" +
						"\n" +
						"@check[]\n" +
						"$self.<caret>\n"
		);

		List<String> names = getCompletionsFromExistingFile("www/parser176_d.p");
		assertTrue("176/d: наследник должен видеть поле базового класса one_3, есть: " + names,
				names.contains("one_3"));
		assertTrue("176/d: наследник должен видеть явное поле two_3, есть: " + names,
				names.contains("two_3"));
		assertFalse("176/d: собственная two_2 должна быть локальной из-за @OPTIONS locals наследника, есть: " + names,
				names.contains("two_2"));
		assertFalse("176/d: локальная one_1 базового класса не должна подтекать, есть: " + names,
				names.contains("one_1"));
		assertFalse("176/d: локальная one_2 базового класса не должна подтекать, есть: " + names,
				names.contains("one_2"));
		assertFalse("176/d: локальная two_1 не должна подтекать, есть: " + names,
				names.contains("two_1"));
	}

	// =============================================================================
	// РАЗДЕЛ 3: $var автокомплит в классе
	// =============================================================================

	/**
	 * $self.<caret> в классе — показывает переменные класса.
	 */
	public void testDollarVar_class_self() {
		createParser3FileInDir("www/test_compl_class.p",
				// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
				"@CLASS\n" +
						"TestCompl\n" +
						"\n" +
						"@create[]\n" +
						"# на основе parser3/tests/176_dir/a.p\n" +
						"$self.classVar[val]\n" +
						"\n" +
						"@method[]\n" +
						"$self.<caret>"
		);
		VirtualFile vf = myFixture.findFileInTempDir("www/test_compl_class.p");
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("Должны быть варианты для $self. в классе", elements);

		List<String> names = Arrays.stream(elements)
				.map(LookupElement::getLookupString)
				.collect(Collectors.toList());

		assertTrue("$self. должен содержать classVar, есть: " + names, names.contains("classVar"));
	}

	/**
	 * $self.<caret> в классе с locals — не видит локальные из другого метода.
	 */
	public void testDollarVar_classLocals_selfShowsGlobal() {
		createParser3FileInDir("www/test_compl_cloc.p",
				// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
				"@CLASS\n" +
						"TestComplLoc\n" +
						"\n" +
						"@OPTIONS\n" +
						"locals\n" +
						"# на основе parser3/tests/176_dir/a.p\n" +
						"\n" +
						"@create[]\n" +
						"$localInCreate[loc]\n" +
						"$self.sharedVar[shared]\n" +
						"\n" +
						"@method[]\n" +
						"$self.<caret>"
		);
		VirtualFile vf = myFixture.findFileInTempDir("www/test_compl_cloc.p");
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("Должны быть варианты", elements);

		List<String> names = Arrays.stream(elements)
				.map(LookupElement::getLookupString)
				.collect(Collectors.toList());

		assertTrue("$self. должен содержать sharedVar, есть: " + names, names.contains("sharedVar"));
		assertFalse("$self. НЕ должен содержать localInCreate, есть: " + names,
				names.contains("localInCreate"));
	}

	public void testClassCompletion_numericClassName() {
		createParser3FileInDir("www/test_class_numeric_completion.p",
				"@main[]\n" +
						"# на основе parser3/tests/268.html\n" +
						"^2<caret>");
		VirtualFile vf = myFixture.findFileInTempDir("www/test_class_numeric_completion.p");
		myFixture.configureFromExistingVirtualFile(vf);
		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("Должны быть варианты completion для ^2", elements);

		List<String> names = Arrays.stream(elements)
				.map(LookupElement::getLookupString)
				.collect(Collectors.toList());
		assertTrue("^2 должен содержать 222, есть: " + names, names.contains("222"));
	}

	public void testClassCompletion_numericClassMethods() {
		createParser3FileInDir("www/test_class_numeric_methods_completion.p",
				"@main[]\n" +
						"# на основе parser3/tests/268.html\n" +
						"^222:<caret>");
		VirtualFile vf = myFixture.findFileInTempDir("www/test_class_numeric_methods_completion.p");
		myFixture.configureFromExistingVirtualFile(vf);
		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("Должны быть варианты completion для ^222:", elements);

		List<String> names = Arrays.stream(elements)
				.map(LookupElement::getLookupString)
				.collect(Collectors.toList());
		assertTrue("^222: должен содержать create, есть: " + names, names.contains("create"));
		assertTrue("^222: должен содержать method, есть: " + names, names.contains("method"));
	}

	public void testObjectPropertyFromNumericClassMethodResult_hasHashKeysCompletion() {
		createParser3FileInDir("numeric-object-prop/www/auto.p",
				"@auto[]\n" +
						// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
						"@USE\n" +
						"classes/2Login.p\n"
		);
		createParser3FileInDir("numeric-object-prop/www/classes/2Login.p",
				// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
				"@CLASS\n" +
						"2Login\n" +
						"\n" +
						"@create[]\n" +
						"$self.user[^self.get_user[]]\n" +
						"\n" +
						"@get_user[]\n" +
						"$result[\n" +
						"\t$.login[test]\n" +
						"]\n"
		);
		createParser3FileInDir("numeric-object-prop/www/site.p",
				"@main[]\n" +
						"# на основе parser3/tests/258.html\n" +
						"$LoginTest[^2Login::create[]]\n" +
						"$LoginTest.user.<caret>\n"
		);
		VirtualFile vf = myFixture.findFileInTempDir("numeric-object-prop/www/site.p");
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("$LoginTest.user. должен показать ключи хеша из get_user", elements);
		List<String> names = Arrays.stream(elements)
				.map(LookupElement::getLookupString)
				.collect(Collectors.toList());

		assertTrue("Должен быть login, есть: " + names, names.contains("login"));
	}

	public void testObjectPropertyFromParser258RealConstructorResultShowsHashKeys() {
		createParser3FileInDir("www/parser258_real.p",
				"@main[]\n" +
						"# на основе parser3/tests/258.html\n" +
						"$o[^test::create[hash;value for hash]]\n" +
						"$o.<caret>\n" +
						"\n" +
						// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
						"@CLASS\n" +
						"test\n" +
						"\n" +
						"@create[kind;v]\n" +
						"\n" +
						"^if($kind eq 'hash'){\n" +
						"\t$result[^hash::create[\n" +
						"\t\t$.type[hash]\n" +
						"\t\t$.value[$v]\n" +
						"\t]]\n" +
						"}{\n" +
						"\t$result[$self]\n" +
						"\t$type[object]\n" +
						"\t$value[$v]\n" +
						"}\n"
		);

		List<String> names = getCompletionsFromExistingFile("www/parser258_real.p");
		assertTrue("Реальный Parser3 258: hash-результат конструктора должен дать ключ type, есть: " + names,
				names.contains("type"));
		assertTrue("Реальный Parser3 258: hash-результат конструктора должен дать ключ value, есть: " + names,
				names.contains("value"));
	}

	public void testHashCreate_parser433RealArrayKeysVisibleAfterCopy() {
		createParser3FileInDir("www/parser433_real.p",
				"@main[]\n" +
						"# на основе parser3/tests/433.html\n" +
						"$a[^array::create[]]\n" +
						"$a.05[a5]\n" +
						"$a.20[a20]\n" +
						"$a.10[a30]\n" +
						"$a.25[$void]\n" +
						"$h[^hash::create[$a]]\n" +
						"$h.<caret>\n"
		);

		List<String> names = getCompletionsFromExistingFile("www/parser433_real.p");
		assertTrue("Реальный Parser3 433: hash::create[$array] должен видеть ключ 05, есть: " + names,
				names.contains("05"));
		assertTrue("Реальный Parser3 433: hash::create[$array] должен видеть ключ 10, есть: " + names,
				names.contains("10"));
		assertTrue("Реальный Parser3 433: hash::create[$array] должен видеть ключ 20, есть: " + names,
				names.contains("20"));
		assertTrue("Реальный Parser3 433: hash::create[$array] должен видеть ключ 25, есть: " + names,
				names.contains("25"));
	}

	public void testHashUnion_parser433RealUnionKeepsKeysFromBothSources() {
		createParser3FileInDir("www/parser433_real_union.p",
				"@main[]\n" +
						"# на основе parser3/tests/433.html\n" +
						"$a[^array::create[]]\n" +
						"$a.05[a5] $a.20[a20] $a.10[a30] $a.25[$void]\n" +
						"$h[ $.05[h05] $.10[h10] $.1[h1] ]\n" +
						"$u[^h.union[$a]]\n" +
						"$u.<caret>\n"
		);

		List<String> names = getCompletionsFromExistingFile("www/parser433_real_union.p");
		assertTrue("Реальный Parser3 433: union должен сохранить ключ 1 из хеша, есть: " + names,
				names.contains("1"));
		assertTrue("Реальный Parser3 433: union должен сохранить ключ 20 из массива, есть: " + names,
				names.contains("20"));
		assertTrue("Реальный Parser3 433: union должен сохранить общий ключ 05, есть: " + names,
				names.contains("05"));
	}

	public void testHashIntersection_parser433RealIntersectionKeepsOnlyCommonKeys() {
		createParser3FileInDir("www/parser433_real_intersection.p",
				"@main[]\n" +
						"# на основе parser3/tests/433.html\n" +
						"$a[^array::create[]]\n" +
						"$a.05[a5] $a.20[a20] $a.10[a30] $a.25[$void]\n" +
						"$h[ $.05[h05] $.10[h10] $.1[h1] ]\n" +
						"$i[^h.intersection[$a]]\n" +
						"$i.<caret>\n"
		);

		List<String> names = getCompletionsFromExistingFile("www/parser433_real_intersection.p");
		assertTrue("Реальный Parser3 433: intersection должен сохранить общий ключ 05, есть: " + names,
				names.contains("05"));
		assertTrue("Реальный Parser3 433: intersection должен сохранить общий ключ 10, есть: " + names,
				names.contains("10"));
		assertFalse("Реальный Parser3 433: intersection не должен оставлять ключ только из хеша, есть: " + names,
				names.contains("1"));
		assertFalse("Реальный Parser3 433: intersection не должен оставлять ключ только из массива, есть: " + names,
				names.contains("20"));
	}

	public void testHashAdd_parser014RealAddMergesSourceHash() {
		createParser3FileInDir("www/parser014_real_hash_add.p",
				"@main[]\n" +
						"# на основе parser3/tests/014.html\n" +
						"$a[$.1[a1] $.2[a2] $.3[a3]]\n" +
						"$b[$.2[b2] $.3[b3] $.4[b4]]\n" +
						"^a.add[$b]\n" +
						"$a.<caret>\n"
		);

		List<String> names = getCompletionsFromExistingFile("www/parser014_real_hash_add.p");
		assertTrue("Реальный Parser3 014: add должен сохранить ключ 1 из self, есть: " + names,
				names.contains("1"));
		assertTrue("Реальный Parser3 014: add должен сохранить общий ключ 2, есть: " + names,
				names.contains("2"));
		assertTrue("Реальный Parser3 014: add должен добавить ключ 4 из аргумента, есть: " + names,
				names.contains("4"));
	}

	public void testHashUnion_parser014RealUnionMergesSourceHash() {
		createParser3FileInDir("www/parser014_real_hash_union.p",
				"@main[]\n" +
						"# на основе parser3/tests/014.html\n" +
						"$a[$.1[a1] $.2[a2] $.3[a3]]\n" +
						"$b[$.2[b2] $.3[b3] $.4[b4]]\n" +
						"$d[^a.union[$b]]\n" +
						"$d.<caret>\n"
		);

		List<String> names = getCompletionsFromExistingFile("www/parser014_real_hash_union.p");
		assertTrue("Реальный Parser3 014: union должен сохранить ключ 1 из self, есть: " + names,
				names.contains("1"));
		assertTrue("Реальный Parser3 014: union должен сохранить общий ключ 2, есть: " + names,
				names.contains("2"));
		assertTrue("Реальный Parser3 014: union должен добавить ключ 4 из аргумента, есть: " + names,
				names.contains("4"));
	}

	public void testHashIntersection_parser014RealIntersectionKeepsOnlyCommonKeys() {
		createParser3FileInDir("www/parser014_real_hash_intersection.p",
				"@main[]\n" +
						"# на основе parser3/tests/014.html\n" +
						"$a[$.1[a1] $.2[a2] $.3[a3]]\n" +
						"$b[$.2[b2] $.3[b3] $.4[b4]]\n" +
						"$d[^a.intersection[$b]]\n" +
						"$d.<caret>\n"
		);

		List<String> names = getCompletionsFromExistingFile("www/parser014_real_hash_intersection.p");
		assertTrue("Реальный Parser3 014: intersection должен сохранить общий ключ 2, есть: " + names,
				names.contains("2"));
		assertTrue("Реальный Parser3 014: intersection должен сохранить общий ключ 3, есть: " + names,
				names.contains("3"));
		assertFalse("Реальный Parser3 014: intersection не должен оставлять ключ только из self, есть: " + names,
				names.contains("1"));
		assertFalse("Реальный Parser3 014: intersection не должен оставлять ключ только из аргумента, есть: " + names,
				names.contains("4"));
	}

	public void testHashSub_parser014RealSubRemovesSourceHashKeys() {
		createParser3FileInDir("www/parser014_real_hash_sub.p",
				"@main[]\n" +
						"# на основе parser3/tests/014.html\n" +
						"$a[$.1[a1] $.2[a2] $.3[a3]]\n" +
						"$b[$.2[b2] $.3[b3] $.4[b4]]\n" +
						"^a.sub[$b]\n" +
						"$a.<caret>\n"
		);

		List<String> names = getCompletionsFromExistingFile("www/parser014_real_hash_sub.p");
		assertTrue("Реальный Parser3 014: sub должен оставить ключ 1, есть: " + names,
				names.contains("1"));
		assertFalse("Реальный Parser3 014: sub должен убрать общий ключ 2, есть: " + names,
				names.contains("2"));
		assertFalse("Реальный Parser3 014: sub должен убрать общий ключ 3, есть: " + names,
				names.contains("3"));
	}

	public void testHashDelete_parser014RealDeleteRemovesKey() {
		createParser3FileInDir("www/parser014_real_hash_delete.p",
				"@main[]\n" +
						"# на основе parser3/tests/014.html\n" +
						"$a[$.1[a1] $.2[a2] $.3[a3]]\n" +
						"^a.delete[2]\n" +
						"$a.<caret>\n"
		);

		List<String> names = getCompletionsFromExistingFile("www/parser014_real_hash_delete.p");
		assertTrue("Реальный Parser3 014: delete должен оставить ключ 1, есть: " + names,
				names.contains("1"));
		assertFalse("Реальный Parser3 014: delete должен убрать ключ 2, есть: " + names,
				names.contains("2"));
		assertTrue("Реальный Parser3 014: delete должен оставить ключ 3, есть: " + names,
				names.contains("3"));
	}

	public void testHashAlias_parser209RealDirectAliasTracksSourceButHashCreateDoesNot() {
		String base =
				"@main[]\n" +
						"# на основе parser3/tests/209.html\n" +
						"$a1[\n" +
						"\t$.1[a1]\n" +
						"\t$.2[a2]\n" +
						"]\n" +
						"\n" +
						"$a2[$a1]\n" +
						"$a3[^hash::create[$a1]]\n" +
						"$a4[^hash::create[\n" +
						"\t$.3[a3]\n" +
						"\t$.4[a4]\n" +
						"]]\n" +
						"\n" +
						"^a1.add[$a4]\n";

		createParser3FileInDir("www/parser209_real_hash_alias.p", base + "$a2.<caret>\n");
		List<String> aliasNames = getCompletionsFromExistingFile("www/parser209_real_hash_alias.p");
		assertTrue("Реальный Parser3 209: прямой $a2[$a1] должен видеть поздний ключ 3 из $a1, есть: " + aliasNames,
				aliasNames.contains("3"));

		createParser3FileInDir("www/parser209_real_hash_create_copy.p", base + "$a3.<caret>\n");
		List<String> copyNames = getCompletionsFromExistingFile("www/parser209_real_hash_create_copy.p");
		assertTrue("Реальный Parser3 209: hash::create[$a1] должен сохранить исходный ключ 1, есть: " + copyNames,
				copyNames.contains("1"));
		assertFalse("Реальный Parser3 209: hash::create[$a1] не должен видеть поздний ключ 3, есть: " + copyNames,
				copyNames.contains("3"));
	}

	public void testHashKeys_parser203RealUnderscoreKeysMethodUsesCustomColumnName() {
		createParser3FileInDir("www/parser203_real_hash_keys_custom.p",
				"# на основе parser3/tests/203.html\n" +
						"$h[\n" +
						"\t$.1[paf]\n" +
						"\t$.2[misha]\n" +
						"]\n" +
						"\n" +
						"$t[^h._keys[k]]\n" +
						"\n" +
						"$t.<caret>\n"
		);

		List<String> names = getCompletionsFromExistingFile("www/parser203_real_hash_keys_custom.p");
		assertTrue("Реальный Parser3 203: ^hash._keys[k] должен вернуть таблицу с колонкой k, есть: " + names,
				names.contains("k"));
		assertFalse("Реальный Parser3 203: для ^hash._keys[k] не должна оставаться колонка key, есть: " + names,
				names.contains("key"));
	}

	public void testHashKeys_parser210RealUnderscoreKeysMethodReturnsDefaultKeyColumn() {
		createParser3FileInDir("www/parser210_real_hash_keys_default.p",
				"# на основе parser3/tests/210.html\n" +
						"$a[a\"a]\n" +
						"$b[^taint[b\"b]]\n" +
						"$h[\n" +
						"\t$.[$a][$a]\n" +
						"\t$.[$b][$b]\n" +
						"]\n" +
						"\n" +
						"$t[^h._keys[]]\n" +
						"\n" +
						"$t.<caret>\n"
		);

		List<String> names = getCompletionsFromExistingFile("www/parser210_real_hash_keys_default.p");
		assertTrue("Реальный Parser3 210: ^hash._keys[] должен вернуть таблицу с колонкой key, есть: " + names,
				names.contains("key"));
	}

	public void testObjectPropertyFromAutouseNumericClassMethodResult_hasHashKeysCompletion() throws Exception {
		setMethodCompletionMode(ru.artlebedev.parser3.settings.Parser3ProjectSettings.MethodCompletionMode.USE_ONLY);

		createParser3FileInDir("numeric-object-prop-autouse/www/auto.p",
				"@autouse[]\n" +
						"# на основе parser3/tests/226.html\n"
		);
		createParser3FileInDir("numeric-object-prop-autouse/www/2Login.p",
				// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
				"@CLASS\n" +
						"2Login\n" +
						"\n" +
						"@create[]\n" +
						"$self.user[^self.get_user[]]\n" +
						"\n" +
						"@get_user[]\n" +
						"$result[\n" +
						"\t$.login[test]\n" +
						"]\n"
		);
		createParser3FileInDir("numeric-object-prop-autouse/www/site.p",
				"@main[]\n" +
						"# на основе parser3/tests/258.html\n" +
						"$LoginTest[^2Login::create[]]\n" +
						"$LoginTest.user.<caret>\n"
		);

		com.intellij.psi.PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
		com.intellij.openapi.project.DumbService.getInstance(getProject()).waitForSmartMode();

		VirtualFile vf = myFixture.findFileInTempDir("numeric-object-prop-autouse/www/site.p");
		assertTrue("@autouse из соседнего auto.p должен быть виден из site.p",
				ru.artlebedev.parser3.index.P3ClassIndex.getInstance(getProject()).hasAutouseVisibleFrom(vf, 0));
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("$LoginTest.user. должен показать ключи хеша из get_user через @autouse", elements);
		List<String> names = Arrays.stream(elements)
				.map(LookupElement::getLookupString)
				.collect(Collectors.toList());

		assertTrue("Должен быть login через @autouse, есть: " + names, names.contains("login"));
	}

	public void testObjectPropertyFromAutouseNumericClassMethodResult_keepsAdditiveNestedHashKeys() throws Exception {
		setMethodCompletionMode(ru.artlebedev.parser3.settings.Parser3ProjectSettings.MethodCompletionMode.USE_ONLY);

		createParser3FileInDir("numeric-object-prop-autouse-additive/www/auto.p",
				"@autouse[]\n" +
						"# на основе parser3/tests/226.html\n"
		);
		createParser3FileInDir("numeric-object-prop-autouse-additive/www/2Login.p",
				// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
				"@CLASS\n" +
						"2Login\n" +
						"\n" +
						"@create[]\n" +
						"$self.user[^self.get_user[]]\n" +
						"\n" +
						"@get_user[]\n" +
						"$result[\n" +
						"\t$.login[test]\n" +
						"]\n"
		);
		createParser3FileInDir("numeric-object-prop-autouse-additive/www/site.p",
				"@main[]\n" +
						"# на основе parser3/tests/258.html\n" +
						"$LoginTest[^2Login::create[]]\n" +
						"$LoginTest.x\n" +
						"$LoginTest.user.x[asdf]\n" +
						"$LoginTest.user.<caret>\n"
		);

		com.intellij.psi.PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
		com.intellij.openapi.project.DumbService.getInstance(getProject()).waitForSmartMode();

		VirtualFile vf = myFixture.findFileInTempDir("numeric-object-prop-autouse-additive/www/site.p");
		assertTrue("@autouse из соседнего auto.p должен быть виден из site.p",
				ru.artlebedev.parser3.index.P3ClassIndex.getInstance(getProject()).hasAutouseVisibleFrom(vf, 0));
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("$LoginTest.user. должен показать ключи хеша из метода и additive-записей", elements);
		List<String> names = Arrays.stream(elements)
				.map(LookupElement::getLookupString)
				.collect(Collectors.toList());

		assertTrue("Должен быть login из get_user через @autouse, есть: " + names, names.contains("login"));
		assertTrue("Должен быть x из $LoginTest.user.x[asdf], есть: " + names, names.contains("x"));
	}

	public void testObjectPropertyFromAutouseNumericClassMethodResult_keepsAdditiveObjectProperties() throws Exception {
		setMethodCompletionMode(ru.artlebedev.parser3.settings.Parser3ProjectSettings.MethodCompletionMode.USE_ONLY);

		createParser3FileInDir("numeric-object-prop-autouse-object-additive/www/auto.p",
				"@autouse[]\n" +
						"# на основе parser3/tests/226.html\n"
		);
		createParser3FileInDir("numeric-object-prop-autouse-object-additive/www/2Login.p",
				// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
				"@CLASS\n" +
						"2Login\n" +
						"\n" +
						"@create[]\n" +
						"$result[$self]\n"
		);
		createParser3FileInDir("numeric-object-prop-autouse-object-additive/www/site.p",
				"@main[]\n" +
						"# на основе parser3/tests/258.html\n" +
						"$LoginTest[^2Login::create[]]\n" +
						"$LoginTest.user_var[x]\n" +
						"$LoginTest.<caret>\n"
		);

		com.intellij.psi.PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
		com.intellij.openapi.project.DumbService.getInstance(getProject()).waitForSmartMode();

		VirtualFile vf = myFixture.findFileInTempDir("numeric-object-prop-autouse-object-additive/www/site.p");
		assertTrue("@autouse из соседнего auto.p должен быть виден из site.p",
				ru.artlebedev.parser3.index.P3ClassIndex.getInstance(getProject()).hasAutouseVisibleFrom(vf, 0));
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("$LoginTest. должен показать additive-свойства объекта", elements);
		List<String> names = Arrays.stream(elements)
				.map(LookupElement::getLookupString)
				.collect(Collectors.toList());

		assertTrue("Должен быть user_var из $LoginTest.user_var[x], есть: " + names, names.contains("user_var"));
	}

	public void testObjectPropertyFromAutouseNumericClassMethodResult_deduplicatesClassPropertyAndAdditiveHashKey() throws Exception {
		setMethodCompletionMode(ru.artlebedev.parser3.settings.Parser3ProjectSettings.MethodCompletionMode.USE_ONLY);

		createParser3FileInDir("numeric-object-prop-autouse-dedupe/www/auto.p",
				"@autouse[]\n" +
						"# на основе parser3/tests/226.html\n"
		);
		createParser3FileInDir("numeric-object-prop-autouse-dedupe/www/2Login.p",
				// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
				"@CLASS\n" +
						"2Login\n" +
						"\n" +
						"@create[]\n" +
						"$self.user[^self.get_user[]]\n" +
						"$result[$self]\n" +
						"\n" +
						"@get_user[]\n" +
						"$result[\n" +
						"\t$.login[test]\n" +
						"]\n"
		);
		createParser3FileInDir("numeric-object-prop-autouse-dedupe/www/site.p",
				"@main[]\n" +
						"# на основе parser3/tests/258.html\n" +
						"$LoginTest[^2Login::create[]]\n" +
						"$LoginTest.user_var[x]\n" +
						"$LoginTest.user.x[asdf]\n" +
						"$LoginTest.<caret>\n"
		);

		com.intellij.psi.PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
		com.intellij.openapi.project.DumbService.getInstance(getProject()).waitForSmartMode();

		VirtualFile vf = myFixture.findFileInTempDir("numeric-object-prop-autouse-dedupe/www/site.p");
		assertTrue("@autouse из соседнего auto.p должен быть виден из site.p",
				ru.artlebedev.parser3.index.P3ClassIndex.getInstance(getProject()).hasAutouseVisibleFrom(vf, 0));
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("$LoginTest. должен показать свойства без дубля user/user.", elements);
		List<String> names = Arrays.stream(elements)
				.map(LookupElement::getLookupString)
				.collect(Collectors.toList());

		long userVariants = names.stream()
				.filter(name -> "user".equals(name) || "user.".equals(name))
				.count();
		assertTrue("Должен быть user. с вложенными ключами, есть: " + names, names.contains("user."));
		assertFalse("Не должно быть отдельного user без точки, есть: " + names, names.contains("user"));
		assertEquals("Должен быть только один вариант user/user., есть: " + names, 1, userVariants);
		assertTrue("Должен быть user_var из $LoginTest.user_var[x], есть: " + names, names.contains("user_var"));
	}

	public void testObjectPropertyFromAutouseNumericClassMethodResult_classHashPropertyHasDot() throws Exception {
		setMethodCompletionMode(ru.artlebedev.parser3.settings.Parser3ProjectSettings.MethodCompletionMode.USE_ONLY);

		createParser3FileInDir("numeric-object-prop-autouse-class-hash-dot/www/auto.p",
				"@autouse[]\n" +
						"# на основе parser3/tests/226.html\n"
		);
		createParser3FileInDir("numeric-object-prop-autouse-class-hash-dot/www/2Login.p",
				// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
				"@CLASS\n" +
						"2Login\n" +
						"\n" +
						"@create[]\n" +
						"$self.user[^self.get_user[]]\n" +
						"$result[$self]\n" +
						"\n" +
						"@get_user[]\n" +
						"$result[\n" +
						"\t$.login[test]\n" +
						"]\n"
		);
		createParser3FileInDir("numeric-object-prop-autouse-class-hash-dot/www/site.p",
				"@main[]\n" +
						"# на основе parser3/tests/258.html\n" +
						"$LoginTest[^2Login::create[]]\n" +
						"$LoginTest.<caret>\n"
		);

		com.intellij.psi.PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
		com.intellij.openapi.project.DumbService.getInstance(getProject()).waitForSmartMode();

		VirtualFile vf = myFixture.findFileInTempDir("numeric-object-prop-autouse-class-hash-dot/www/site.p");
		assertTrue("@autouse из соседнего auto.p должен быть виден из site.p",
				ru.artlebedev.parser3.index.P3ClassIndex.getInstance(getProject()).hasAutouseVisibleFrom(vf, 0));
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("$LoginTest. должен показать hash-свойство класса с точкой", elements);
		List<String> names = Arrays.stream(elements)
				.map(LookupElement::getLookupString)
				.collect(Collectors.toList());

		assertTrue("Hash-свойство класса должно быть user., есть: " + names, names.contains("user."));
		assertFalse("Hash-свойство класса не должно быть user без точки, есть: " + names, names.contains("user"));
	}

	public void testClassGetterHashPropertyHasDot() throws Exception {
		createParser3FileInDir("getter-hash-prop-dot/www/auto.p",
				"@auto[]\n" +
						// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
						"@USE\n" +
						"classes/User.p\n"
		);
		createParser3FileInDir("getter-hash-prop-dot/www/classes/User.p",
				// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
				"@CLASS\n" +
						"User\n" +
						"\n" +
						"@create[]\n" +
						"$result[$self]\n" +
						"\n" +
						"########################################\n" +
						"# $result(hash)\n" +
						"########################################\n" +
						"@GET_profile[]\n" +
						"$result[\n" +
						"\t$.login[test]\n" +
						"]\n"
		);
		createParser3FileInDir("getter-hash-prop-dot/www/site.p",
				"@main[]\n" +
						"# на основе parser3/tests/258.html\n" +
						"$user[^User::create[]]\n" +
						"$user.<caret>\n"
		);

		VirtualFile vf = myFixture.findFileInTempDir("getter-hash-prop-dot/www/site.p");
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("$user. должен показать hash-геттер с точкой", elements);
		List<String> names = Arrays.stream(elements)
				.map(LookupElement::getLookupString)
				.collect(Collectors.toList());

		assertTrue("Hash-геттер должен быть profile., есть: " + names, names.contains("profile."));
		assertFalse("Hash-геттер не должен быть profile без точки, есть: " + names, names.contains("profile"));
	}

	public void testAutouseCompletion_classMethodsVisibleWithoutUse() throws Exception {
		setMethodCompletionMode(ru.artlebedev.parser3.settings.Parser3ProjectSettings.MethodCompletionMode.USE_ONLY);

		createParser3FileInDir("autouse-completion/Stat.p",
				// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
				"@CLASS\n" +
						"Stat\n" +
						"\n" +
						"@create[]\n" +
						"$result[^Stat::new[]]\n" +
						"\n" +
						"@get_info[]\n" +
						"$result[ok]\n"
		);

		createParser3FileInDir("autouse-completion/auto.p",
				"@autouse[]\n" +
						"# на основе parser3/tests/226.html\n" +
						"\n" +
						"@main[]\n" +
						"# на основе parser3/tests/258.html\n" +
						"$varStat[^Stat::create[]]\n" +
						"^Stat:<caret>\n"
		);

		com.intellij.psi.PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
		com.intellij.openapi.project.DumbService.getInstance(getProject()).waitForSmartMode();

		List<String> names = getCompletionsFromExistingFile("autouse-completion/auto.p");
		assertTrue("^Stat: должен содержать get_info при видимом @autouse, есть: " + names,
				names.contains("get_info"));
	}

	public void testAutouseCompletion_objectMethodsVisibleWithoutUse() throws Exception {
		setMethodCompletionMode(ru.artlebedev.parser3.settings.Parser3ProjectSettings.MethodCompletionMode.USE_ONLY);

		createParser3FileInDir("autouse-completion-var/Stat.p",
				// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
				"@CLASS\n" +
						"Stat\n" +
						"\n" +
						"@create[]\n" +
						"$result[^Stat::new[]]\n" +
						"\n" +
						"@get_info[]\n" +
						"$result[ok]\n"
		);

		createParser3FileInDir("autouse-completion-var/auto.p",
				"@autouse[]\n" +
						"# на основе parser3/tests/226.html\n" +
						"\n" +
						"@main[]\n" +
						"# на основе parser3/tests/258.html\n" +
						"$varStat[^Stat::create[]]\n" +
						"^varStat.<caret>\n"
		);

		com.intellij.psi.PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
		com.intellij.openapi.project.DumbService.getInstance(getProject()).waitForSmartMode();

		List<String> names = getCompletionsFromExistingFile("autouse-completion-var/auto.p");
		assertTrue("^varStat. должен содержать get_info при видимом @autouse, есть: " + names,
				names.contains("get_info"));
	}

	public void testScopeContext_parser226RealAutouseLoadsClassMethod() {
		setMethodCompletionMode(ru.artlebedev.parser3.settings.Parser3ProjectSettings.MethodCompletionMode.USE_ONLY);

		createParser3FileInDir("scope-autouse-real-226/226.p",
				// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
				"@CLASS\n" +
						"test\n" +
						"\n" +
						"@call[]\n" +
						"it works!\n"
		);
		createParser3FileInDir("scope-autouse-real-226/page.p",
				"# autouse checking\n" +
						"@main[]\n" +
						"# на основе parser3/tests/226.html\n" +
						"^try-catch{^test:call[]}\n" +
						"^try-catch{^zigi:call[]}\n" +
						"^test:c<caret>\n" +
						"\n" +
						"@autouse[name]\n" +
						"^if($name eq \"test\"){\n" +
						"\t^use[226.p]\n" +
						"}\n"
		);

		List<String> names = getCompletionsFromExistingFile("scope-autouse-real-226/page.p");
		assertTrue("Реальный Parser3 226: @autouse должен дать метод test:call, есть: " + names,
				names.contains("call"));
	}

	public void testScopeContext_useOnlyMethodBeforeUseDoesNotShowLaterUse() {
		setMethodCompletionMode(ru.artlebedev.parser3.settings.Parser3ProjectSettings.MethodCompletionMode.USE_ONLY);
		createParser3FileInDir("scope-method-before/lib.p",
				"@late_method[]\n" +
						"$result[late]\n"
		);
		createParser3FileInDir("scope-method-before/page.p",
				"@main[]\n" +
						"# на основе parser3/tests/182.html\n" +
						"^late<caret>\n" +
						"^use[lib.p]\n"
		);

		List<String> names = getCompletionsFromExistingFile("scope-method-before/page.p");
		assertFalse("^use[] ниже курсора не должен давать MAIN-метод в USE_ONLY, есть: " + names,
				names.contains("late_method"));
	}

	public void testScopeContext_useOnlyMethodAfterUseShowsMethod() {
		setMethodCompletionMode(ru.artlebedev.parser3.settings.Parser3ProjectSettings.MethodCompletionMode.USE_ONLY);
		createParser3FileInDir("scope-method-after/lib.p",
				"@late_method[]\n" +
						"$result[late]\n"
		);
		createParser3FileInDir("scope-method-after/page.p",
				"@main[]\n" +
						"# на основе parser3/tests/182.html\n" +
						"^use[lib.p]\n" +
						"^late<caret>\n"
		);

		List<String> names = getCompletionsFromExistingFile("scope-method-after/page.p");
		assertTrue("^use[] выше курсора должен давать MAIN-метод в USE_ONLY, есть: " + names,
				names.contains("late_method"));
	}

	public void testScopeContext_useOnlyClassBeforeUseDoesNotShowLaterClass() {
		setMethodCompletionMode(ru.artlebedev.parser3.settings.Parser3ProjectSettings.MethodCompletionMode.USE_ONLY);
		createParser3FileInDir("scope-class-before/UserLate.p",
				// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
				"@CLASS\n" +
						"UserLate\n" +
						"\n" +
						"@create[]\n"
		);
		createParser3FileInDir("scope-class-before/page.p",
				"@main[]\n" +
						"# на основе parser3/tests/182.html\n" +
						"^User<caret>\n" +
						"^use[UserLate.p]\n"
		);

		List<String> names = getCompletionsFromExistingFile("scope-class-before/page.p");
		assertFalse("^use[] ниже курсора не должен давать класс в USE_ONLY, есть: " + names,
				names.contains("UserLate"));
	}

	public void testScopeContext_useOnlyClassAfterUseShowsClass() {
		setMethodCompletionMode(ru.artlebedev.parser3.settings.Parser3ProjectSettings.MethodCompletionMode.USE_ONLY);
		createParser3FileInDir("scope-class-after/UserLate.p",
				// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
				"@CLASS\n" +
						"UserLate\n" +
						"\n" +
						"@create[]\n"
		);
		createParser3FileInDir("scope-class-after/page.p",
				"@main[]\n" +
						"# на основе parser3/tests/182.html\n" +
						"^use[UserLate.p]\n" +
						"^User<caret>\n"
		);

		List<String> names = getCompletionsFromExistingFile("scope-class-after/page.p");
		assertTrue("^use[] выше курсора должен давать класс в USE_ONLY, есть: " + names,
				names.contains("UserLate"));
	}

	public void testScopeContext_autouseBelowCursorDoesNotExpandClasses() {
		setMethodCompletionMode(ru.artlebedev.parser3.settings.Parser3ProjectSettings.MethodCompletionMode.USE_ONLY);
		createParser3FileInDir("scope-autouse-before/autoload.p",
				"@autouse[name]\n" +
						"^use[$name.p]\n"
		);
		createParser3FileInDir("scope-autouse-before/GhostClass.p",
				// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
				"@CLASS\n" +
						"GhostClass\n" +
						"\n" +
						"@create[]\n"
		);
		createParser3FileInDir("scope-autouse-before/page.p",
				"@main[]\n" +
						"# на основе parser3/tests/226.html\n" +
						"^Ghost<caret>\n" +
						"^use[autoload.p]\n"
		);

		List<String> names = getCompletionsFromExistingFile("scope-autouse-before/page.p");
		assertFalse("@autouse из ^use[] ниже курсора не должен расширять классы, есть: " + names,
				names.contains("GhostClass"));
	}

	public void testScopeContext_autouseAboveCursorExpandsClassesButNotMethods() {
		setMethodCompletionMode(ru.artlebedev.parser3.settings.Parser3ProjectSettings.MethodCompletionMode.USE_ONLY);
		createParser3FileInDir("scope-autouse-after/autoload.p",
				"@autouse[name]\n" +
						"^use[$name.p]\n"
		);
		createParser3FileInDir("scope-autouse-after/GhostClass.p",
				// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
				"@CLASS\n" +
						"GhostClass\n" +
						"\n" +
						"@create[]\n"
		);
		createParser3FileInDir("scope-autouse-after/hidden_methods.p",
				"@ghost_helper[]\n" +
						"$result[hidden]\n"
		);
		createParser3FileInDir("scope-autouse-after/page_class.p",
				"@main[]\n" +
						"# на основе parser3/tests/226.html\n" +
						"^use[autoload.p]\n" +
						"^Ghost<caret>\n"
		);
		createParser3FileInDir("scope-autouse-after/page_method.p",
				"@main[]\n" +
						"# на основе parser3/tests/226.html\n" +
						"^use[autoload.p]\n" +
						"^ghost<caret>\n"
		);

		List<String> classNames = getCompletionsFromExistingFile("scope-autouse-after/page_class.p");
		assertTrue("@autouse выше курсора должен расширять классы, есть: " + classNames,
				classNames.contains("GhostClass"));

		List<String> methodNames = getCompletionsFromExistingFile("scope-autouse-after/page_method.p");
		assertFalse("@autouse не должен расширять MAIN-методы проекта, есть: " + methodNames,
				methodNames.contains("ghost_helper"));
	}

	public void testScopeContext_partialClassMethodsFromParser182VisibleWhenBothPartsUsed() {
		setMethodCompletionMode(ru.artlebedev.parser3.settings.Parser3ProjectSettings.MethodCompletionMode.USE_ONLY);
		createParser3FileInDir("scope-partial/a1.p",
				// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
				"@CLASS\n" +
						"a\n" +
						"\n" +
						"@OPTIONS\n" +
						"partial\n" +
						"\n" +
						"@a1[]\n" +
						"a1<br />\n" +
						"\n" +
						"\n" +
						// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
						"@CLASS\n" +
						"a\n" +
						"\n" +
						"@OPTIONS\n" +
						"partial\n" +
						"\n" +
						"@a2[]\n" +
						"a2<br />\n" +
						"\n" +
						// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
						"@CLASS\n" +
						"b\n" +
						"\n" +
						"@b1[]\n" +
						"b1<br />\n"
		);
		createParser3FileInDir("scope-partial/a2.p",
				// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
				"@CLASS\n" +
						"a\n" +
						"\n" +
						"@OPTIONS\n" +
						"partial\n" +
						"\n" +
						"@a3[]\n" +
						"a3<br />\n"
		);
		createParser3FileInDir("scope-partial/page_a.p",
				"@main[]\n" +
						"# на основе parser3/tests/182.html\n" +
						"^use[a1.p]\n" +
						"^use[a2.p]\n" +
						"^a:a<caret>\n"
		);
		createParser3FileInDir("scope-partial/page_b.p",
				"@main[]\n" +
						"# на основе parser3/tests/182.html\n" +
						"^use[a1.p]\n" +
						"^use[a2.p]\n" +
						"^b:b<caret>\n"
		);

		List<String> names = getCompletionsFromExistingFile("scope-partial/page_a.p");
		assertTrue("182/partial: должен быть метод из первой части partial-класса, есть: " + names,
				names.contains("a1"));
		assertTrue("182/partial: должен быть метод из второй части partial-класса, есть: " + names,
				names.contains("a2"));
		assertTrue("182/partial: должен быть метод из подключённого a2.p, есть: " + names,
				names.contains("a3"));

		List<String> bNames = getCompletionsFromExistingFile("scope-partial/page_b.p");
		assertTrue("182/partial: должен быть метод класса b из реального a1.p, есть: " + bNames,
				bNames.contains("b1"));
	}

	public void testScopeContext_parser182ReplaceUseOverridesPreviousClassAndKeepsNestedUse() {
		setMethodCompletionMode(ru.artlebedev.parser3.settings.Parser3ProjectSettings.MethodCompletionMode.USE_ONLY);
		createParser3FileInDir("scope-parser182-replace/a1.p",
				// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
				"@CLASS\n" +
						"a\n" +
						"\n" +
						"@OPTIONS\n" +
						"partial\n" +
						"\n" +
						"@a1[]\n" +
						"a1<br />\n" +
						"\n" +
						"\n" +
						// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
						"@CLASS\n" +
						"a\n" +
						"\n" +
						"@OPTIONS\n" +
						"partial\n" +
						"\n" +
						"@a2[]\n" +
						"a2<br />\n" +
						"\n" +
						// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
						"@CLASS\n" +
						"b\n" +
						"\n" +
						"@b1[]\n" +
						"b1<br />\n"
		);
		createParser3FileInDir("scope-parser182-replace/a2.p",
				// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
				"@CLASS\n" +
						"a\n" +
						"\n" +
						"@OPTIONS\n" +
						"partial\n" +
						"\n" +
						"@a3[]\n" +
						"a3<br />\n"
		);
		createParser3FileInDir("scope-parser182-replace/a3.p",
				// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
				"@CLASS\n" +
						"a\n" +
						"\n" +
						"@a1[]\n" +
						"new a1<br />\n" +
						"\n" +
						"\n" +
						// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
						"@CLASS\n" +
						"c\n" +
						"\n" +
						"@OPTIONS\n" +
						"partial\n" +
						"\n" +
						// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
						"@USE\n" +
						"a4.p\n" +
						"\n" +
						"@c1[]\n" +
						"c1<br />\n"
		);
		createParser3FileInDir("scope-parser182-replace/a4.p",
				// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
				"@CLASS\n" +
						"c\n" +
						"\n" +
						"@OPTIONS\n" +
						"partial\n" +
						"\n" +
						"@c2[]\n" +
						"c2<br />\n"
		);
		createParser3FileInDir("scope-parser182-replace/page_a.p",
				"@main[]\n" +
						"# на основе parser3/tests/182.html\n" +
						"^use[a1.p]\n" +
						"^use[a2.p]\n" +
						"^use[a3.p; $.replace(true)]\n" +
						"^a:a<caret>\n"
		);
		createParser3FileInDir("scope-parser182-replace/page_c.p",
				"@main[]\n" +
						"# на основе parser3/tests/182.html\n" +
						"^use[a1.p]\n" +
						"^use[a2.p]\n" +
						"^use[a3.p; $.replace(true)]\n" +
						"^c:c<caret>\n"
		);

		List<String> aNames = getCompletionsFromExistingFile("scope-parser182-replace/page_a.p");
		assertTrue("182/replace: заменяющий a3.p должен оставить новый a1, есть: " + aNames,
				aNames.contains("a1"));
		assertFalse("182/replace: старый partial-метод a2 после $.replace(true) должен пропасть, есть: " + aNames,
				aNames.contains("a2"));
		assertFalse("182/replace: старый partial-метод a3 после $.replace(true) должен пропасть, есть: " + aNames,
				aNames.contains("a3"));

		List<String> cNames = getCompletionsFromExistingFile("scope-parser182-replace/page_c.p");
		assertTrue("182/replace: c1 из заменяющего a3.p должен быть виден, есть: " + cNames,
				cNames.contains("c1"));
		assertTrue("182/replace: c2 из @USE a4.p внутри a3.p должен быть виден, есть: " + cNames,
				cNames.contains("c2"));
	}

	public void testScopeContext_partialClassMethodFromUnusedPartIsHidden() {
		setMethodCompletionMode(ru.artlebedev.parser3.settings.Parser3ProjectSettings.MethodCompletionMode.USE_ONLY);
		createParser3FileInDir("scope-partial-hidden/a1.p",
				// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
				"@CLASS\n" +
						"a\n" +
						"\n" +
						"@OPTIONS\n" +
						"partial\n" +
						"\n" +
						"@a1[]\n"
		);
		createParser3FileInDir("scope-partial-hidden/a2.p",
				// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
				"@CLASS\n" +
						"a\n" +
						"\n" +
						"@OPTIONS\n" +
						"partial\n" +
						"\n" +
						"@a2[]\n"
		);
		createParser3FileInDir("scope-partial-hidden/page.p",
				"@main[]\n" +
						"# на основе parser3/tests/182.html\n" +
						"^use[a1.p]\n" +
						"^a:a<caret>\n"
		);

		List<String> names = getCompletionsFromExistingFile("scope-partial-hidden/page.p");
		assertTrue("Видимая часть partial-класса должна показываться, есть: " + names,
				names.contains("a1"));
		assertFalse("Неподключённая часть partial-класса не должна показываться, есть: " + names,
				names.contains("a2"));
	}

	public void testScopeContext_parser182PartialMethodsFromTransitiveUseVisible() {
		setMethodCompletionMode(ru.artlebedev.parser3.settings.Parser3ProjectSettings.MethodCompletionMode.USE_ONLY);
		createParser3FileInDir("scope-parser182-transitive/a3.p",
				// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
				"@CLASS\n" +
						"c\n" +
						"\n" +
						"@OPTIONS\n" +
						"partial\n" +
						"\n" +
						// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
						"@USE\n" +
						"a4.p\n" +
						"\n" +
						"@c1[]\n"
		);
		createParser3FileInDir("scope-parser182-transitive/a4.p",
				// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
				"@CLASS\n" +
						"c\n" +
						"\n" +
						"@OPTIONS\n" +
						"partial\n" +
						"\n" +
						"@c2[]\n"
		);
		createParser3FileInDir("scope-parser182-transitive/page.p",
				"@main[]\n" +
						"# на основе parser3/tests/182.html\n" +
						"^use[a3.p]\n" +
						"^c:c<caret>\n"
		);

		List<String> names = getCompletionsFromExistingFile("scope-parser182-transitive/page.p");
		assertTrue("182/transitive: метод из прямо подключённой partial-части должен быть виден, есть: " + names,
				names.contains("c1"));
		assertTrue("182/transitive: метод из partial-части через @USE внутри a3.p должен быть виден, есть: " + names,
				names.contains("c2"));
	}

	public void testScopeContext_duplicateUseDoesNotDuplicateMainMethodsFromParser366() {
		setMethodCompletionMode(ru.artlebedev.parser3.settings.Parser3ProjectSettings.MethodCompletionMode.USE_ONLY);
		createParser3FileInDir("scope-parser366/shared.p",
				"@shared_method[]\n" +
						"$result[ok]\n"
		);
		createParser3FileInDir("scope-parser366/first.p",
				// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
				"@USE\n" +
						"shared.p\n"
		);
		createParser3FileInDir("scope-parser366/second.p",
				// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
				"@USE\n" +
						"shared.p\n"
		);
		createParser3FileInDir("scope-parser366/page.p",
				"@main[]\n" +
						"# на основе parser3/tests/366.html\n" +
						"^use[first.p]\n" +
						"^use[second.p]\n" +
						"^shared<caret>\n"
		);

		List<String> names = getCompletionsFromExistingFile("scope-parser366/page.p");
		long count = names.stream().filter("shared_method"::equals).count();
		assertEquals("366: повторный @USE одного файла не должен дублировать метод, есть: " + names,
				1, count);
	}

	public void testScopeContext_parser366RealDuplicateUseKeepsSingleVisibleFile() {
		setMethodCompletionMode(ru.artlebedev.parser3.settings.Parser3ProjectSettings.MethodCompletionMode.USE_ONLY);
		createParser3FileInDir("scope-parser366-real/366.p",
				"@auto[]\n" +
						"$LOG[$LOG 366.p used\n" +
						"]\n"
		);
		createParser3FileInDir("scope-parser366-real/test-duplicate.p",
				// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
				"@USE\n" +
						"366.p\n" +
						"\n" +
						"@auto[]\n" +
						"$LOG[$LOG test-duplicate.p used\n" +
						"]\n"
		);
		VirtualFile page = createParser3FileInDir("scope-parser366-real/test.p",
				// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
				"@USE\n" +
						"366.p\n" +
						"test-duplicate.p\n" +
						"\n" +
						"@auto[]\n" +
						"$LOG[$LOG test.p used\n" +
						"]\n"
		);

		List<VirtualFile> visibleFiles = new ru.artlebedev.parser3.visibility.P3ScopeContext(
				getProject(), page, page.getLength() > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) page.getLength()
		).getVisibleFiles();
		long rootUseCount = visibleFiles.stream().filter(file -> "366.p".equals(file.getName())).count();
		long duplicateUseCount = visibleFiles.stream().filter(file -> "test-duplicate.p".equals(file.getName())).count();
		assertEquals("366 real: повторный @USE 366.p должен попасть в область видимости один раз, есть: " + visibleFiles,
				1, rootUseCount);
		assertEquals("366 real: test-duplicate.p должен попасть в область видимости один раз, есть: " + visibleFiles,
				1, duplicateUseCount);
	}

	public void testVariableScopeContextUsesAlreadyComputedUseOffsets() {
		setMethodCompletionMode(ru.artlebedev.parser3.settings.Parser3ProjectSettings.MethodCompletionMode.USE_ONLY);
		VirtualFile currentFile = createParser3FileInDir("scope-context-reuse/current.p",
				// Минимальный fixture для проверки переиспользования уже рассчитанного use-offset.
				"@main[]\n" +
						"$current[1]\n");
		VirtualFile futureUseFile = createParser3FileInDir("scope-context-reuse/future-use.p",
				// Минимальный fixture для проверки позиционной видимости внешнего файла.
				"@main[]\n" +
						"$external[1]\n");

		java.util.Map<VirtualFile, Integer> useOffsets = new java.util.HashMap<>();
		useOffsets.put(futureUseFile, 100);
		P3VariableScopeContext scopeContext = new P3VariableScopeContext(
				getProject(),
				currentFile,
				10,
				java.util.Arrays.asList(currentFile, futureUseFile),
				useOffsets);

		assertEquals("Переданный use-offset должен сохраниться без пересборки P3ScopeContext",
				100, scopeContext.getUseOffset(futureUseFile));
		assertFalse("Файл из будущего ^use[] не должен быть видим в позиции до use-offset",
				scopeContext.isSourceVisibleAtCursor(futureUseFile));
	}

	public void testAllProjectFilesCacheRefreshesAfterParser3Modification() {
		ru.artlebedev.parser3.visibility.P3VisibilityService visibilityService =
				ru.artlebedev.parser3.visibility.P3VisibilityService.getInstance(getProject());
		VirtualFile firstFile = createParser3FileInDir("all-project-cache/first.p",
				// Минимальный fixture для проверки кеша списка Parser3-файлов.
				"@main[]\n" +
						"$first[1]\n");
		com.intellij.psi.PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
		com.intellij.openapi.project.DumbService.getInstance(getProject()).waitForSmartMode();

		List<VirtualFile> firstRead = visibilityService.getAllProjectFiles();
		assertTrue("Первый файл должен быть в списке Parser3-файлов",
				firstRead.contains(firstFile));

		VirtualFile secondFile = createParser3FileInDir("all-project-cache/second.p",
				// Минимальный fixture для проверки инвалидации кеша списка Parser3-файлов.
				"@main[]\n" +
						"$second[1]\n");
		com.intellij.psi.PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
		com.intellij.openapi.project.DumbService.getInstance(getProject()).waitForSmartMode();

		List<VirtualFile> secondRead = visibilityService.getAllProjectFiles();
		assertTrue("Кеш списка Parser3-файлов должен обновиться после добавления файла",
				secondRead.contains(secondFile));
	}

	public void testScopeContext_cyclicBaseFromParser387DoesNotOverflowCompletion() {
		setMethodCompletionMode(ru.artlebedev.parser3.settings.Parser3ProjectSettings.MethodCompletionMode.USE_ONLY);
		createParser3FileInDir("scope-parser387/A.p",
				// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
				"@CLASS\n" +
						"A\n" +
						"\n" +
						"@BASE\n" +
						"B\n" +
						"\n" +
						"@a_method[]\n"
		);
		createParser3FileInDir("scope-parser387/B.p",
				// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
				"@CLASS\n" +
						"B\n" +
						"\n" +
						"@BASE\n" +
						"A\n" +
						"\n" +
						"@b_method[]\n"
		);
		createParser3FileInDir("scope-parser387/page.p",
				"@main[]\n" +
						"# на основе parser3/tests/387_dir/A.p\n" +
						"^use[A.p]\n" +
						"^use[B.p]\n" +
						"^A:<caret>\n"
		);

		List<String> names = getCompletionsFromExistingFile("scope-parser387/page.p");
		assertTrue("387: собственный метод A должен быть виден при циклическом @BASE, есть: " + names,
				names.contains("a_method"));
		assertTrue("387: метод B должен быть виден через @BASE без зацикливания, есть: " + names,
				names.contains("b_method"));
	}

	public void testScopeContext_parser387RealCyclicBaseDoesNotOverflowCompletion() {
		setMethodCompletionMode(ru.artlebedev.parser3.settings.Parser3ProjectSettings.MethodCompletionMode.USE_ONLY);
		createParser3FileInDir("scope-parser387-real/A.p",
				// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
				"@CLASS\n" +
						"A\n" +
						"\n" +
						"@BASE\n" +
						"B\n" +
						"\n" +
						"@create[]\n"
		);
		createParser3FileInDir("scope-parser387-real/B.p",
				// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
				"@CLASS\n" +
						"B\n" +
						"\n" +
						"@BASE\n" +
						"A\n" +
						"\n" +
						"@create[]\n" +
						"\n"
		);
		createParser3FileInDir("scope-parser387-real/page.p",
				"@main[]\n" +
						"# на основе parser3/tests/387_dir/A.p\n" +
						"^use[A.p]\n" +
						"^use[B.p]\n" +
						"^A:<caret>\n"
		);

		List<String> names = getCompletionsFromExistingFile("scope-parser387-real/page.p");
		long createCount = names.stream().filter("create"::equals).count();
		assertEquals("387 real: create должен быть виден один раз без зацикливания @BASE, есть: " + names,
				1, createCount);
	}

	public void testScopeContext_parser368RealProcessGeneratedClassMethodVisible() {
		createParser3FileInDir("scope-parser368-real/368.p",
				"@main[]\n" +
						"# на основе parser3/tests/368.html\n" +
						"\n" +
						"$o[^O::create[]]\n" +
						"object: ^show[$o]\n" +
						"class: ^show[$O:CLASS]\n" +
						"\n" +
						"^process{@CLASS\n" +
						"P\n" +
						"\n" +
						"^@create[]\n" +
						"}[ $.file[process.p] ]\n" +
						"\n" +
						"$p[^P::create[]]\n" +
						"process object: ^show[$p]\n" +
						"process class: ^show[$P:CLASS]\n" +
						"^P:<caret>\n" +
						"\n" +
						"^process{@CLASS\n" +
						"P2\n" +
						"\n" +
						"^@create[]\n" +
						"}\n" +
						"process without file specified: ^show[$P2:CLASS]\n" +
						"\n" +
						"@show[o]\n" +
						"$v[^reflection:filename[$o]]\n" +
						"$result[^v.match[^^.+/][]{/-real-path-was-here-/}]\n" +
						"\n" +
						// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
						"@CLASS\n" +
						"O\n" +
						"\n" +
						"@create[]\n"
		);

		List<String> names = getCompletionsFromExistingFile("scope-parser368-real/368.p");
		assertTrue("Реальный Parser3 368: метод create из process-generated класса P должен быть виден, есть: " + names,
				names.contains("create"));
	}

	public void testScopeContext_getDefaultFromParser178IsNotPlainPropertyCompletion() {
		setMethodCompletionMode(ru.artlebedev.parser3.settings.Parser3ProjectSettings.MethodCompletionMode.USE_ONLY);
		createParser3FileInDir("scope-parser178/a.p",
				// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
				"@CLASS\n" +
						"a\n" +
						"\n" +
						"@create[]\n"
		);
		createParser3FileInDir("scope-parser178/b.p",
				// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
				"@CLASS\n" +
						"b\n" +
						"\n" +
						"@BASE\n" +
						"a\n" +
						"\n" +
						"@GET_DEFAULT[sName]\n" +
						"$result[$sName]\n" +
						"\n" +
						"@b_method[]\n"
		);
		createParser3FileInDir("scope-parser178/page.p",
				"@main[]\n" +
						"# на основе parser3/tests/178.html\n" +
						"^use[a.p]\n" +
						"^use[b.p]\n" +
						"^b:<caret>\n"
		);

		List<String> names = getCompletionsFromExistingFile("scope-parser178/page.p");
		assertTrue("178: обычный метод наследника должен быть виден, есть: " + names,
				names.contains("b_method"));
		assertFalse("178: GET_DEFAULT не должен попадать в обычный completion методов/свойств, есть: " + names,
				names.contains("GET_DEFAULT"));
		assertFalse("178: GET_DEFAULT не должен превращаться в DEFAULT-свойство, есть: " + names,
				names.contains("DEFAULT"));
	}

	public void testScopeContext_parser178RealGetDefaultDoesNotBecomePlainMethodOrProperty() {
		setMethodCompletionMode(ru.artlebedev.parser3.settings.Parser3ProjectSettings.MethodCompletionMode.USE_ONLY);
		createParser3FileInDir("scope-parser178-real/178a.p",
				// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
				"@CLASS\n" +
						"a\n" +
						"\n" +
						"@auto[]\n" +
						"$_a[a]\n" +
						"\n" +
						"@create[]\n" +
						"$a[a]\n" +
						"\n" +
						"@_default[sName;sFrom]\n" +
						"Unknown field '$sName' was requested (gefault getter from class '$sFrom' executed)\n"
		);
		createParser3FileInDir("scope-parser178-real/178b.p",
				// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
				"@CLASS\n" +
						"b\n" +
						"\n" +
						"@BASE\n" +
						"a\n" +
						"\n" +
						"@auto[]\n" +
						"$_b[b]\n" +
						"\n" +
						"@create[]\n" +
						"^BASE:create[]\n" +
						"$b[b]\n" +
						"\n" +
						"@GET_DEFAULT[sName]\n" +
						"^_default[$sName;b]\n"
		);
		createParser3FileInDir("scope-parser178-real/178c.p",
				// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
				"@CLASS\n" +
						"c\n" +
						"\n" +
						"@BASE\n" +
						"b\n" +
						"\n" +
						"@auto[]\n" +
						"$_c[c]\n" +
						"\n" +
						"@create[]\n" +
						"^BASE:create[]\n" +
						"$c[c]\n" +
						"\n" +
						"@GET_DEFAULT[sName]\n" +
						"^_default[$sName;c]\n"
		);
		createParser3FileInDir("scope-parser178-real/page.p",
				"@main[]\n" +
						"# на основе parser3/tests/178.html\n" +
						"^use[178a.p]\n" +
						"^use[178b.p]\n" +
						"^use[178c.p]\n" +
						"^c:<caret>\n"
		);

		List<String> names = getCompletionsFromExistingFile("scope-parser178-real/page.p");
		assertTrue("178 real: обычный create должен остаться видимым, есть: " + names,
				names.contains("create"));
		assertFalse("178 real: GET_DEFAULT не должен попадать в обычный completion методов, есть: " + names,
				names.contains("GET_DEFAULT"));
		assertFalse("178 real: GET_DEFAULT не должен превращаться в DEFAULT-свойство, есть: " + names,
				names.contains("DEFAULT"));
	}

	public void testScopeContext_parser182UserClassCanExtendBuiltinArrayMethods() {
		setMethodCompletionMode(ru.artlebedev.parser3.settings.Parser3ProjectSettings.MethodCompletionMode.USE_ONLY);
		createParser3FileInDir("scope-parser182-array/a7.p",
				// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
				"@CLASS\n" +
						"array\n" +
						"\n" +
						"@is-user[]\n" +
						"$result[yes]\n"
		);
		createParser3FileInDir("scope-parser182-array/page.p",
				"@main[]\n" +
						"# на основе parser3/tests/182.html\n" +
						"^use[a7.p]\n" +
						"^array:is<caret>\n"
		);

		List<String> names = getCompletionsFromExistingFile("scope-parser182-array/page.p");
		assertTrue("182/a7: пользовательский метод класса array с дефисом должен быть виден, есть: " + names,
				names.contains("is-user"));
	}

	public void testScopeContext_parser257WhitespaceAroundClassDirectivesKeepsInheritance() {
		setMethodCompletionMode(ru.artlebedev.parser3.settings.Parser3ProjectSettings.MethodCompletionMode.USE_ONLY);
		createParser3FileInDir("scope-parser257/classes.p",
				"@CLASS \t\n" +
						"a\t \n" +
						"\n" +
						"@OPTIONS    \n" +
						"partial \t\n" +
						"locals\t \n" +
						"\n" +
						"@base_method[]\n" +
						"$result[base]\n" +
						"\n" +
						// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
						"@CLASS\n" +
						"b   \n" +
						"\n" +
						"@OPTIONS    \n" +
						"locals \t\n" +
						"\n" +
						"@BASE\t   \n" +
						"a\n" +
						"\n" +
						"@child_method[]\n" +
						"$result[child]\n"
		);
		createParser3FileInDir("scope-parser257/page.p",
				"@main[]\n" +
						"# на основе parser3/tests/257.html\n" +
						"^use[classes.p]\n" +
						"^b:<caret>\n"
		);

		List<String> names = getCompletionsFromExistingFile("scope-parser257/page.p");
		assertTrue("257: пробелы после @CLASS/@BASE не должны ломать метод наследника, есть: " + names,
				names.contains("child_method"));
		assertTrue("257: пробелы после @OPTIONS/@BASE не должны ломать наследование, есть: " + names,
				names.contains("base_method"));
	}

	public void testScopeContext_parser257RealWhitespaceAroundClassDirectivesKeepsMethodVisible() {
		setMethodCompletionMode(ru.artlebedev.parser3.settings.Parser3ProjectSettings.MethodCompletionMode.USE_ONLY);
		createParser3FileInDir("scope-parser257-real/classes.p",
				"@main[]\n" +
						"# на основе parser3/tests/257.html\n" +
						"^b:m[]\n" +
						"\n" +
						"\n" +
						"# The whitespaces after @METACOMMANDS and their options are typed intentionally.\n" +
						"# Also, they should not cause a compilers' exceptions.\n" +
						"@CLASS \n" +
						"a\t \n" +
						"\n" +
						"@OPTIONS    \n" +
						"partial \n" +
						"locals\t \n" +
						"\n" +
						"@m[]\n" +
						"a\n" +
						"\n" +
						// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
						"@CLASS\n" +
						"b   \n" +
						"\n" +
						"@OPTIONS    \n" +
						"locals \t\n" +
						"\n" +
						"@BASE\t   \n" +
						"a\n" +
						"\n" +
						"@m[]\n" +
						"^BASE:m[]b\n" +
						"\n" +
						"^b:m<caret>\n"
		);

		List<String> names = getCompletionsFromExistingFile("scope-parser257-real/classes.p");
		long mCount = names.stream().filter("m"::equals).count();
		assertEquals("257 real: метод m должен быть виден один раз несмотря на пробелы в директивах, есть: " + names,
				1, mCount);
	}

	public void testScopeContext_baseMethodFromUseAboveCursorVisible() {
		setMethodCompletionMode(ru.artlebedev.parser3.settings.Parser3ProjectSettings.MethodCompletionMode.USE_ONLY);
		createParser3FileInDir("scope-base-visible/Base.p",
				// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
				"@CLASS\n" +
						"Base\n" +
						"\n" +
						"@base_method[]\n"
		);
		createParser3FileInDir("scope-base-visible/Child.p",
				// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
				"@CLASS\n" +
						"Child\n" +
						"\n" +
						"@BASE\n" +
						"Base\n" +
						"\n" +
						"@child_method[]\n"
		);
		createParser3FileInDir("scope-base-visible/page.p",
				"@main[]\n" +
						"# на основе parser3/tests/051.html\n" +
						"^use[Base.p]\n" +
						"^use[Child.p]\n" +
						"^Child:base<caret>\n"
		);

		List<String> names = getCompletionsFromExistingFile("scope-base-visible/page.p");
		assertTrue("@BASE из видимого файла должен давать метод базового класса, есть: " + names,
				names.contains("base_method"));
	}

	public void testScopeContext_parser051RealAbsoluteUseShowsBaseMethod() {
		setMethodCompletionMode(ru.artlebedev.parser3.settings.Parser3ProjectSettings.MethodCompletionMode.USE_ONLY);
		setDocumentRootToProjectBase();

		createParser3FileInDir("tests/051b.p",
				// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
				"@CLASS\n" +
						"b\n" +
						"\n" +
						"@auto[]\n" +
						"$test[b]\n" +
						"\n" +
						"@method[]\n" +
						"base.test=$test<br>\n" +
						"class.test=$CLASS.test<br>\n" +
						"\n" +
						"@base_method[]\n" +
						"bm\n" +
						"^child_method[]\n"
		);
		createParser3FileInDir("tests/051t.p",
				// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
				"@USE\n" +
						"/tests/051b.p\n" +
						"\n" +
						// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
						"@CLASS\n" +
						"t\n" +
						"\n" +
						"@BASE\n" +
						"b\n" +
						"\n" +
						"@auto[]\n" +
						"$test[t]\n" +
						"\n" +
						"@create[]\n" +
						"$test[ct]\n" +
						"\n" +
						"@method[]\n" +
						"t.test=$test<br>\n" +
						"^b:method[]<br>\n" +
						"^method2[]\n" +
						"\n" +
						"@child_method[]\n" +
						"cm\n" +
						"\n" +
						"@method2[]\n" +
						"tm2\n"
		);
		createParser3FileInDir("tests/051.p",
				// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
				"@USE\n" +
						"/tests/051t.p\n" +
						"\n" +
						"@main[]\n" +
						"# на основе parser3/tests/051.html\n" +
						"$t[^t::create[]]\n" +
						"^t.method[]\n" +
						"<hr>\n" +
						"^t.base<caret>\n"
		);

		List<String> names = getCompletionsFromExistingFile("tests/051.p");
		assertTrue("Реальный Parser3 051: абсолютный @USE должен дать метод базового класса, есть: " + names,
				names.contains("base_method"));
	}

	public void testScopeContext_parser191RealObjectCompletionShowsBaseAndChildMethods() {
		setMethodCompletionMode(ru.artlebedev.parser3.settings.Parser3ProjectSettings.MethodCompletionMode.USE_ONLY);

		createParser3FileInDir("scope-parser191/191_a.p",
				// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
				"@CLASS\n" +
						"A\n" +
						"\n" +
						"@auto[]\n" +
						"$sa[sa_a]\n" +
						"$sv[sv_a]\n" +
						"\n" +
						"@create[]\n" +
						"$da[da_a]\n" +
						"$dv[dv_a]\n" +
						"\n" +
						"@print_a[]\n" +
						"A/^^print_a[]:<br />\n" +
						"\n" +
						"@print_v[]\n" +
						"A/^^print_v[]:<br />\n" +
						"\n" +
						"@class_a[]\n" +
						"$result[$CLASS]\n" +
						"\n" +
						"@class_v[]\n" +
						"$result[$CLASS]\n"
		);
		createParser3FileInDir("scope-parser191/191_b.p",
				// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
				"@CLASS\n" +
						"B\n" +
						"\n" +
						// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
						"@USE\n" +
						"191_a.p\n" +
						"\n" +
						"@BASE\n" +
						"A\n" +
						"\n" +
						"@auto[]\n" +
						"$sb[sb_b]\n" +
						"$sv[sv_b]\n" +
						"\n" +
						"@create[]\n" +
						"^BASE:create[]\n" +
						"$db[db_b]\n" +
						"$dv[dv_b]\n" +
						"\n" +
						"@print_b[]\n" +
						"B/^^print_b[]:<br />\n" +
						"\n" +
						"@print_v[]\n" +
						"B/^^print_v[]:<br />\n" +
						"\n" +
						"@class_b[]\n" +
						"$result[$CLASS]\n" +
						"\n" +
						"@class_v[]\n" +
						"$result[$CLASS]\n"
		);
		createParser3FileInDir("scope-parser191/page.p",
				// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
				"@USE\n" +
						"191_a.p\n" +
						"191_b.p\n" +
						"\n" +
						"@main[]\n" +
						"# на основе parser3/tests/191.html\n" +
						"$oB1[^B::create[]]\n" +
						"^oB1.print_<caret>\n"
		);

		List<String> names = getCompletionsFromExistingFile("scope-parser191/page.p");
		assertTrue("Реальный Parser3 191: объект B должен видеть метод A.print_a, есть: " + names,
				names.contains("print_a"));
		assertTrue("Реальный Parser3 191: объект B должен видеть метод B.print_b, есть: " + names,
				names.contains("print_b"));
		assertTrue("Реальный Parser3 191: объект B должен видеть переопределённый print_v, есть: " + names,
				names.contains("print_v"));
	}

	public void testScopeContext_parser191RealStaticCompletionShowsBaseAndChildMethods() {
		setMethodCompletionMode(ru.artlebedev.parser3.settings.Parser3ProjectSettings.MethodCompletionMode.USE_ONLY);

		createParser3FileInDir("scope-parser191-static/191_a.p",
				// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
				"@CLASS\n" +
						"A\n" +
						"\n" +
						"@class_a[]\n" +
						"$result[$CLASS]\n" +
						"\n" +
						"@class_v[]\n" +
						"$result[$CLASS]\n"
		);
		createParser3FileInDir("scope-parser191-static/191_b.p",
				// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
				"@CLASS\n" +
						"B\n" +
						"\n" +
						// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
						"@USE\n" +
						"191_a.p\n" +
						"\n" +
						"@BASE\n" +
						"A\n" +
						"\n" +
						"@class_b[]\n" +
						"$result[$CLASS]\n" +
						"\n" +
						"@class_v[]\n" +
						"$result[$CLASS]\n"
		);
		createParser3FileInDir("scope-parser191-static/page.p",
				// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
				"@USE\n" +
						"191_a.p\n" +
						"191_b.p\n" +
						"\n" +
						"@main[]\n" +
						"# на основе parser3/tests/191.html\n" +
						"^B:class_<caret>\n"
		);

		List<String> names = getCompletionsFromExistingFile("scope-parser191-static/page.p");
		assertTrue("Реальный Parser3 191: класс B должен видеть статический метод A.class_a, есть: " + names,
				names.contains("class_a"));
		assertTrue("Реальный Parser3 191: класс B должен видеть статический метод B.class_b, есть: " + names,
				names.contains("class_b"));
		assertTrue("Реальный Parser3 191: класс B должен видеть переопределённый class_v, есть: " + names,
				names.contains("class_v"));
	}

	public void testScopeContext_baseMethodFromUseBelowCursorHidden() {
		setMethodCompletionMode(ru.artlebedev.parser3.settings.Parser3ProjectSettings.MethodCompletionMode.USE_ONLY);
		createParser3FileInDir("scope-base-hidden/Base.p",
				// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
				"@CLASS\n" +
						"Base\n" +
						"\n" +
						"@base_method[]\n"
		);
		createParser3FileInDir("scope-base-hidden/Child.p",
				// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
				"@CLASS\n" +
						"Child\n" +
						"\n" +
						"@BASE\n" +
						"Base\n" +
						"\n" +
						"@child_method[]\n"
		);
		createParser3FileInDir("scope-base-hidden/page.p",
				"@main[]\n" +
						"# на основе parser3/tests/051.html\n" +
						"^Child:base<caret>\n" +
						"^use[Base.p]\n" +
						"^use[Child.p]\n"
		);

		List<String> names = getCompletionsFromExistingFile("scope-base-hidden/page.p");
		assertFalse("@BASE из ^use[] ниже курсора не должен давать метод базового класса, есть: " + names,
				names.contains("base_method"));
	}

	public void testScopeContext_reverseMethodVisibilityFiltersCallFiles() {
		setMethodCompletionMode(ru.artlebedev.parser3.settings.Parser3ProjectSettings.MethodCompletionMode.USE_ONLY);
		VirtualFile lib = createParser3FileInDir("scope-reverse-method/lib.p",
				"@target_method[]\n" +
						"$result[ok]\n"
		);
		VirtualFile visibleCaller = createParser3FileInDir("scope-reverse-method/visible.p",
				"@main[]\n" +
						"# на основе parser3/tests/182.html\n" +
						"^use[lib.p]\n" +
						"^target_method[]\n"
		);
		VirtualFile hiddenCaller = createParser3FileInDir("scope-reverse-method/hidden.p",
				"@main[]\n" +
						"# на основе parser3/tests/182.html\n" +
						"^target_method[]\n"
		);

		com.intellij.psi.PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
		com.intellij.openapi.project.DumbService.getInstance(getProject()).waitForSmartMode();

		List<VirtualFile> result = P3ScopeContext.filterFilesThatCanSeeMethod(
				getProject(),
				lib,
				null,
				Arrays.asList(visibleCaller, hiddenCaller));

		assertTrue("Обратная видимость метода должна оставить файл с ^use[], есть: " + result,
				result.contains(visibleCaller));
		assertFalse("Обратная видимость метода не должна оставлять файл без ^use[], есть: " + result,
				result.contains(hiddenCaller));
	}

	public void testScopeContext_reverseClassVisibilityUsesAutouseAndUse() {
		setMethodCompletionMode(ru.artlebedev.parser3.settings.Parser3ProjectSettings.MethodCompletionMode.USE_ONLY);
		VirtualFile userClass = createParser3FileInDir("scope-reverse-class/User.p",
				// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
				"@CLASS\n" +
						"User\n" +
						"\n" +
						"@create[]\n"
		);
		VirtualFile usePage = createParser3FileInDir("scope-reverse-class/use_page.p",
				"@main[]\n" +
						"# на основе parser3/tests/182.html\n" +
						"^use[User.p]\n" +
						"^User:create[]\n"
		);
		VirtualFile autousePage = createParser3FileInDir("scope-reverse-class/autouse_page.p",
				"@main[]\n" +
						"# на основе parser3/tests/226.html\n" +
						"^use[autoload.p]\n" +
						"^User:create[]\n"
		);
		VirtualFile hiddenPage = createParser3FileInDir("scope-reverse-class/hidden_page.p",
				"@main[]\n" +
						"# на основе parser3/tests/182.html\n" +
						"^User:create[]\n"
		);
		createParser3FileInDir("scope-reverse-class/autoload.p",
				"@autouse[name]\n" +
						"^use[$name.p]\n"
		);

		com.intellij.psi.PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
		com.intellij.openapi.project.DumbService.getInstance(getProject()).waitForSmartMode();

		List<VirtualFile> result = P3ScopeContext.getReverseClassSearchFiles(getProject(), userClass);

		assertTrue("Класс должен быть виден из файла с прямым ^use[], есть: " + result,
				result.contains(usePage));
		assertTrue("Класс должен быть виден из файла с видимым @autouse, есть: " + result,
				result.contains(autousePage));
		assertFalse("Класс не должен быть виден из файла без ^use[] и без @autouse, есть: " + result,
				result.contains(hiddenPage));
	}

	// =============================================================================
	// РАЗДЕЛ 4: ^var. автокомплит методов объекта
	// =============================================================================

	/**
	 * ^user.<caret> — показывает методы User.
	 */
	public void testCaretVarDot_methods() {
		createParser3FileInDir("www/test_compl_methods.p",
				"@auto[]\n" +
						"# на основе parser3/tests/191.html\n" +
						"$user[^User::create[a;b]]\n" +
						"\n" +
						"@main[]\n" +
						"# на основе parser3/tests/191.html\n" +
						"^user.<caret>"
		);
		VirtualFile vf = myFixture.findFileInTempDir("www/test_compl_methods.p");
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("Должны быть методы User", elements);

		List<String> names = Arrays.stream(elements)
				.map(LookupElement::getLookupString)
				.collect(Collectors.toList());

		assertTrue("Должен быть validate[], есть: " + names,
				names.stream().anyMatch(n -> n.contains("validate")));
		assertTrue("Должен быть save[], есть: " + names,
				names.stream().anyMatch(n -> n.contains("save")));
	}

	public void testCaretMethodCompletion_stopsOnSlashBeforePathSegment() {
		createParser3FileInDir("www/test_method_completion_stop_slash.p",
				"@main[]\n" +
						"# реальный минимальный кейс остановки completion после /\n" +
						"$last_json_src[$self.cacheDirType/^self.options.dt_end.sql-string[date]/ids.<caret>]"
		);
		VirtualFile vf = myFixture.findFileInTempDir("www/test_method_completion_stop_slash.p");
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		List<String> names = elements == null ? List.of() : Arrays.stream(elements)
				.map(LookupElement::getLookupString)
				.collect(Collectors.toList());

		assertFalse("После /ids. не должны предлагаться методы объекта: " + names,
				names.stream().anyMatch(n -> n.contains("join") || n.contains("select")));
		assertTrue("После /ids. должны оставаться пользовательские шаблоны: " + names,
				names.contains("curl:load[]"));
	}

	public void testCaretMethodCompletion_stopsOnAmpersandBeforeText() {
		createParser3FileInDir("www/test_method_completion_stop_ampersand.p",
				"@main[]\n" +
						"# реальный минимальный кейс остановки completion после &\n" +
						"^1&asdf.<caret>"
		);
		VirtualFile vf = myFixture.findFileInTempDir("www/test_method_completion_stop_ampersand.p");
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		List<String> names = elements == null ? List.of() : Arrays.stream(elements)
				.map(LookupElement::getLookupString)
				.collect(Collectors.toList());

		assertFalse("После ^1&asdf. не должны предлагаться методы объекта: " + names,
				names.stream().anyMatch(n -> n.contains("join") || n.contains("select")));
		assertTrue("После ^1&asdf. должны оставаться пользовательские шаблоны: " + names,
				names.contains("curl:load[]"));
	}

	public void testCaretMethodCompletion_stopCharDotDoesNotAutoPopup() {
		createParser3FileInDir("www/test_method_completion_stop_autopopup.p",
				"@main[]\n" +
						"# реальный минимальный кейс остановки auto-popup после &\n" +
						"^1&asdf<caret>"
		);
		VirtualFile vf = myFixture.findFileInTempDir("www/test_method_completion_stop_autopopup.p");
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.type(".");
		assertNull("После ввода точки в ^1&asdf. автопопап не должен открываться",
				myFixture.getLookupElements());
	}

	/**
	 * ^admin.<caret> — показывает методы Admin + наследованные из User.
	 */
	public void testCaretVarDot_inheritedMethods() {
		createParser3FileInDir("www/test_compl_inherit.p",
				"@auto[]\n" +
						"# на основе parser3/tests/191.html\n" +
						"$admin[^Admin::create[a;b;c]]\n" +
						"\n" +
						"@main[]\n" +
						"# на основе parser3/tests/191.html\n" +
						"^admin.<caret>"
		);
		VirtualFile vf = myFixture.findFileInTempDir("www/test_compl_inherit.p");
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("Должны быть методы Admin + User", elements);

		List<String> names = Arrays.stream(elements)
				.map(LookupElement::getLookupString)
				.collect(Collectors.toList());

		assertTrue("Должен быть ban (Admin), есть: " + names,
				names.stream().anyMatch(n -> n.contains("ban")));
		assertTrue("Должен быть validate (User), есть: " + names,
				names.stream().anyMatch(n -> n.contains("validate")));
	}

	/**
	 * ^alsSite.<caret> — показывает методы класса, даже если имя метода начинается с цифры.
	 */
	public void testCaretVarDot_numericMethod() {
		createParser3FileInDir("numeric/auto.p",
				"@auto[]\n" +
						"$alsSite[^AlsSite::create[]]\n"
		);
		createParser3FileInDir("numeric/site.p",
				"@main[]\n" +
						"# на основе parser3/tests/257.html\n" +
						"$alsSite.notShow(true)\n" +
						"^alsSite.<caret>\n"
		);
		createParser3FileInDir("numeric/AlsSite.p",
				// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
				"@CLASS\n" +
						"AlsSite\n" +
						"\n" +
						"@OPTIONS\n" +
						"locals\n" +
						"\n" +
						"@create[]\n" +
						"\n" +
						"@notShow[value]\n" +
						"\n" +
						"@404[]\n"
		);

		VirtualFile vf = myFixture.findFileInTempDir("numeric/site.p");
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("Должны быть методы AlsSite", elements);

		List<String> names = Arrays.stream(elements)
				.map(LookupElement::getLookupString)
				.collect(Collectors.toList());

		assertTrue("Должен быть 404, есть: " + names, names.contains("404"));
		assertTrue("Должен быть notShow, есть: " + names, names.contains("notShow"));
	}

	// =============================================================================
	// РАЗДЕЛ 5: $var. автокомплит свойств (переменные + @GET_)
	// =============================================================================

	/**
	 * $user.<caret> — показывает свойства: $self.name, $self.email, @GET_displayName.
	 */
	public void testDollarVarDot_properties() {
		createParser3FileInDir("www/test_compl_props.p",
				"@auto[]\n" +
						"# на основе parser3/tests/191.html\n" +
						"$user[^User::create[a;b]]\n" +
						"\n" +
						"@main[]\n" +
						"# на основе parser3/tests/191.html\n" +
						"$user.<caret>"
		);
		VirtualFile vf = myFixture.findFileInTempDir("www/test_compl_props.p");
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("Должны быть свойства User", elements);

		List<String> names = Arrays.stream(elements)
				.map(LookupElement::getLookupString)
				.collect(Collectors.toList());

		assertTrue("Должен быть name (переменная), есть: " + names, names.contains("name"));
		assertTrue("Должен быть email (переменная), есть: " + names, names.contains("email"));
		assertTrue("Должен быть displayName (@GET_), есть: " + names, names.contains("displayName"));
	}

	/**
	 * $admin.<caret> — показывает свойства Admin + наследованные из User.
	 */
	public void testDollarVarDot_inheritedProperties() {
		createParser3FileInDir("www/test_compl_iprops.p",
				"@auto[]\n" +
						"# на основе parser3/tests/191.html\n" +
						"$admin[^Admin::create[a;b;c]]\n" +
						"\n" +
						"@main[]\n" +
						"# на основе parser3/tests/191.html\n" +
						"$admin.<caret>"
		);
		VirtualFile vf = myFixture.findFileInTempDir("www/test_compl_iprops.p");
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("Должны быть свойства Admin + User", elements);

		List<String> names = Arrays.stream(elements)
				.map(LookupElement::getLookupString)
				.collect(Collectors.toList());

		assertTrue("Должен быть role (Admin), есть: " + names, names.contains("role"));
		assertTrue("Должен быть name (User), есть: " + names, names.contains("name"));
		assertTrue("Должен быть displayName (@GET_ User), есть: " + names, names.contains("displayName"));
		assertTrue("Должен быть fullTitle (@GET_ Admin), есть: " + names, names.contains("fullTitle"));
	}

	public void testDollarVarDot_parser302RealObjectShowsAutoAndCreateFields() {
		createParser3FileInDir("www/parser302_real.p",
				"@main[]\n" +
						"# на основе parser3/tests/302.html\n" +
						"$d_object[^d::create[]]\n" +
						"$d_object.<caret>\n" +
						"\n" +
						// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
						"@CLASS\n" +
						"c\n" +
						"\n" +
						"@auto[]\n" +
						"$self.class_c[class_c_value]\n" +
						"\n" +
						"@create[]\n" +
						"$self.object_c[object_c_value]\n" +
						"\n" +
						// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
						"@CLASS\n" +
						"d\n" +
						"\n" +
						"@BASE\n" +
						"c\n" +
						"\n" +
						"@auto[]\n" +
						"$self.class_d[class_d_value]\n"
		);

		List<String> names = getCompletionsFromExistingFile("www/parser302_real.p");
		assertTrue("Реальный Parser3 302: объект d должен видеть поле class_c базового класса, есть: " + names,
				names.contains("class_c"));
		assertTrue("Реальный Parser3 302: объект d должен видеть поле object_c из create базового класса, есть: " + names,
				names.contains("object_c"));
		assertTrue("Реальный Parser3 302: объект d должен видеть поле class_d дочернего класса, есть: " + names,
				names.contains("class_d"));
	}

	public void testDollarVarDot_parser288RealGetterPropertiesVisible() {
		createParser3FileInDir("www/parser288_real.p",
				"@main[]\n" +
						"# на основе parser3/tests/288.html\n" +
						"$o[^O::create[]]\n" +
						"$o.<caret>\n" +
						"\n" +
						// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
						"@CLASS\n" +
						"O\n" +
						"\n" +
						"@create[]\n" +
						"$prop2[set_prop2]\n" +
						"\n" +
						"@GET_prop1[]\n" +
						"prop1\n" +
						"\n" +
						"@GET_prop2[]\n" +
						"prop2\n" +
						"\n" +
						"@SET_DEFAULT[name;value]\n" +
						"$$name[$value - via SET_DEFAULT]\n"
		);

		List<String> names = getCompletionsFromExistingFile("www/parser288_real.p");
		assertTrue("Реальный Parser3 288: getter-свойство prop1 должно быть видно, есть: " + names,
				names.contains("prop1"));
		assertTrue("Реальный Parser3 288: getter-свойство prop2 должно быть видно, есть: " + names,
				names.contains("prop2"));
	}

	public void testDollarVarDot_parser227RealChildGetterPropertyVisible() {
		createParser3FileInDir("www/parser227_real.p",
				"@main[]\n" +
						"# на основе parser3/tests/227.html\n" +
						"$a[^child::create[]]\n" +
						"$a.<caret>\n" +
						"\n" +
						// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
						"@CLASS\n" +
						"parent\n" +
						"\n" +
						"@create[]\n" +
						"\n" +
						"@test[]\n" +
						"$no_such_field\n" +
						"\n" +
						"@test2[]\n" +
						"$a\n" +
						"\n" +
						"@GET_a[]\n" +
						"parent_a\n" +
						"\n" +
						"@GET_DEFAULT[]\n" +
						"403\n" +
						"\n" +
						"@auto[]\n" +
						"$cnt[$cnt call]\n" +
						"\n" +
						// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
						"@CLASS\n" +
						"child\n" +
						"\n" +
						"@BASE\n" +
						"parent\n" +
						"\n" +
						"@create[]\n" +
						"^BASE:create[]\n" +
						"\n" +
						"@GET_a[]\n" +
						"child_a\n" +
						"\n" +
						"@GET_DEFAULT[]\n" +
						"404\n"
		);

		List<String> names = getCompletionsFromExistingFile("www/parser227_real.p");
		assertTrue("Реальный Parser3 227: объект child должен видеть GET-свойство a, есть: " + names,
				names.contains("a"));
		assertFalse("Реальный Parser3 227: обычный метод test не должен быть свойством объекта, есть: " + names,
				names.contains("test"));
		assertFalse("Реальный Parser3 227: обычный метод test2 не должен быть свойством объекта, есть: " + names,
				names.contains("test2"));
	}

	public void testDollarVarDot_parser225RealGeneratedSetDoesNotDuplicateGetterProperty() {
		createParser3FileInDir("www/parser225_real.p",
				"@main[]\n" +
						"# на основе parser3/tests/225.html\n" +
						"$o1[^child1::create[]]\n" +
						"$o2[^child2::create[]]\n" +
						"\n" +
						"$o1.a\n" +
						"$o2.a\n" +
						"\n" +
						"$o1.a[1]\n" +
						"$o2.a[2]\n" +
						"\n" +
						"$o1._a\n" +
						"$o2._a\n" +
						"\n" +
						"$parent:_a[]\n" +
						"\n" +
						"^process[$parent:CLASS]{@SET_a[v]\n" +
						"\t^$_a[p0=^$v]\n" +
						"}\n" +
						"\n" +
						"$o1[^child1::create[]]\n" +
						"$o2[^child2::create[]]\n" +
						"$o1.<caret>\n" +
						"\n" +
						// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
						"@CLASS\n" +
						"parent\n" +
						"\n" +
						"@create[]\n" +
						"\n" +
						"@GET_a[]\n" +
						"parent\n" +
						"\n" +
						"@SET_a[v]\n" +
						"$_a[0=$v]\n" +
						"\n" +
						// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
						"@CLASS\n" +
						"child1\n" +
						"\n" +
						"@BASE\n" +
						"parent\n" +
						"\n" +
						"@GET_a[]\n" +
						"child1\n" +
						"\n" +
						// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
						"@CLASS\n" +
						"child2\n" +
						"\n" +
						"@BASE\n" +
						"parent\n" +
						"\n" +
						"@GET_a[]\n" +
						"child2\n" +
						"\n" +
						"@SET_a[v]\n" +
						"$_a[2=$v]\n"
		);

		List<String> names = getCompletionsFromExistingFile("www/parser225_real.p");
		assertSinglePropertyCompletion("Реальный Parser3 225: generated SET не должен давать дубль свойства a",
				names, "a");
	}

	public void testDollarVarDot_parser351RealProcessedBaseGetterVisibleInChild() {
		createParser3FileInDir("www/parser351_real.p",
				"@main[]\n" +
						"# на основе parser3/tests/351.html\n" +
						"$o[^c2::create[]]\n" +
						"$o.<caret>\n" +
						"\n" +
						// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
						"@CLASS\n" +
						"base\n" +
						"\n" +
						"^process[$self.CLASS]{@GET_property1[]\n" +
						"	included base property1\n" +
						"}\n" +
						"\n" +
						// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
						"@CLASS\n" +
						"c1\n" +
						"\n" +
						"@BASE\n" +
						"base\n" +
						"\n" +
						// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
						"@CLASS\n" +
						"c2\n" +
						"\n" +
						"@BASE\n" +
						"c1\n"
		);

		List<String> names = getCompletionsFromExistingFile("www/parser351_real.p");
		assertTrue("Реальный Parser3 351: getter из process-блока базового класса должен быть виден в наследнике, есть: " + names,
				names.contains("property1"));
	}

	public void testDollarVarDot_parser393RealProcessedGetterWithoutParamsVisibleOnce() {
		createParser3FileInDir("www/parser393_real.p",
				"@main[]\n" +
						"# на основе parser3/tests/393.html\n" +
						"\n" +
						"$o[^test::create[]]\n" +
						"$o.<caret>\n" +
						"\n" +
						"base field replaced with property: $o.field\n" +
						"self field replaced with property: $o.child_field\n" +
						"\n" +
						"base untached: $base:field\n" +
						"just in case: $child:child_field\n" +
						"\n" +
						"\n" +
						// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
						"@CLASS\n" +
						"base\n" +
						"\n" +
						"@auto[]\n" +
						"$field[base field]\n" +
						"\n" +
						"@create[]\n" +
						"\n" +
						// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
						"@CLASS\n" +
						"child\n" +
						"\n" +
						"@BASE\n" +
						"base\n" +
						"\n" +
						"@auto[]\n" +
						"\n" +
						"$child_field[child field]\n" +
						"\n" +
						"^process{@GET_field[]\n" +
						"property}\n" +
						"\n" +
						"^process{@GET_child_field[]\n" +
						"child property\n" +
						"}\n" +
						"\n" +
						// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
						"@CLASS\n" +
						"test\n" +
						"\n" +
						"@BASE\n" +
						"child\n" +
						"\n" +
						"@auto[]\n"
		);

		List<String> names = getCompletionsFromExistingFile("www/parser393_real.p");
		assertSinglePropertyCompletion("Реальный Parser3 393: field из process-блока должен быть виден один раз",
				names, "field");
		assertSinglePropertyCompletion("Реальный Parser3 393: child_field из process-блока должен быть виден один раз",
				names, "child_field");
	}

	public void testDollarVarDot_parser383RealGetterSetterAndClassValueVisibleOnce() {
		createParser3FileInDir("www/parser383_real.p",
				"@main[]\n" +
						"# на основе parser3/tests/383.html\n" +
						"^m1[p1]\n" +
						"$set_prop\n" +
						"$constructor\n" +
						"\n" +
						"@m1[p1][local1]\n" +
						"$local1[value1]\n" +
						"$global[global value]\n" +
						"\n" +
						"^if(1){\n" +
						"\t^m2{ code }\n" +
						"}\n" +
						"\n" +
						"@m2[p2][i;o]\n" +
						"\n" +
						"$o[^O::create[]]\n" +
						"$o.<caret>\n" +
						"^for[i](0;0){\n" +
						"\t^o.m-o[p-m-o]\n" +
						"}\n" +
						"$o.prop\n" +
						"$o.prop[value]\n" +
						"\n" +
						"@m3[p3]\n" +
						"1: ^json[^reflection:stack[ $.args(true) $.locals(true) ]]\n" +
						"2: ^json[^reflection:stack[ $.args(true) $.limit(2) $.offset(2) ]]\n" +
						"\n" +
						"@json[stack][v]\n" +
						"^stack.foreach[;v]{\n" +
						"\t$v.file[^v.file.match[^^.+/][]{/-real-path-was-here-/}]\n" +
						"}\n" +
						"^json:string[$stack; $.indent(true) ]\n" +
						"\n" +
						// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
						"@CLASS\n" +
						"O\n" +
						"\n" +
						"@create[]\n" +
						"$value[self value]\n" +
						"\n" +
						"@m-o[p]\n" +
						"^m3[p3]\n" +
						"\n" +
						"@GET_prop[]\n" +
						"3: ^json[^reflection:stack[ $.args(true) ]]\n" +
						"\n" +
						"@SET_prop[value][temp]\n" +
						"$MAIN:set_prop[4: ^json[^reflection:stack[ $.args(true) ]]]\n" +
						"$temp[^O::constructor[]]\n" +
						"\n" +
						"@constructor[]\n" +
						"$MAIN:constructor[5: ^json[^reflection:stack[ $.args(true) $.limit(2) ]]]\n"
		);

		List<String> names = getCompletionsFromExistingFile("www/parser383_real.p");
		assertSinglePropertyCompletion("Реальный Parser3 383: getter/setter prop должен быть виден один раз",
				names, "prop");
		assertTrue("Реальный Parser3 383: class value из create должен быть виден как property, есть: " + names,
				names.contains("value"));
	}

	public void testDollarVarDot_parser410RealGetterCachedFieldsVisible() {
		createParser3FileInDir("www/parser410_real.p",
				"@main[]\n" +
						"# на основе parser3/tests/410.html\n" +
						"\n" +
						"$object[^object::create[]]\n" +
						"\n" +
						"# getter will be called and the result will be cached\n" +
						"o1: $object.field\n" +
						"# without getter call, cached result will be returned\n" +
						"o2: $object.field\n" +
						"o3: $object.field\n" +
						"$object.<caret>\n" +
						"\n" +
						"calls count: $object.cnt\n" +
						"\n" +
						"@GET_field[]\n" +
						"$result[some code result]\n" +
						"$self.field[$result]\n" +
						"$self.cnt($self.cnt+1)\n" +
						"\n" +
						// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
						"@CLASS\n" +
						"object\n" +
						"\n" +
						"@create[]\n" +
						"\n" +
						"@GET_field[]\n" +
						"$result[some code result]\n" +
						"$self.field[$result]\n" +
						"$self.cnt($self.cnt+1)\n"
		);

		List<String> names = getCompletionsFromExistingFile("www/parser410_real.p");
		assertSinglePropertyCompletion("Реальный Parser3 410: getter field должен быть виден один раз",
				names, "field");
		assertTrue("Реальный Parser3 410: счетчик cnt из getter должен быть виден как property, есть: " + names,
				names.contains("cnt"));
	}

	/**
	 * $user.<caret> — НЕ показывает обычные методы (validate, save).
	 */
	public void testDollarVarDot_noMethods() {
		createParser3FileInDir("www/test_compl_nometh.p",
				"@auto[]\n" +
						"# на основе parser3/tests/191.html\n" +
						"$user[^User::create[a;b]]\n" +
						"\n" +
						"@main[]\n" +
						"# на основе parser3/tests/191.html\n" +
						"$user.<caret>"
		);
		VirtualFile vf = myFixture.findFileInTempDir("www/test_compl_nometh.p");
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		if (elements != null) {
			List<String> names = Arrays.stream(elements)
					.map(LookupElement::getLookupString)
					.collect(Collectors.toList());

			assertFalse("$var. НЕ должен содержать validate, есть: " + names,
					names.stream().anyMatch(n -> n.contains("validate")));
			assertFalse("$var. НЕ должен содержать save, есть: " + names,
					names.stream().anyMatch(n -> n.contains("save")));
		}
	}

	// =============================================================================
	// РАЗДЕЛ 6: ^self.var. и ^MAIN:var. — эквивалентность
	// =============================================================================

	/**
	 * ^self.user.<caret> в MAIN — те же методы что и ^user.
	 */
	public void testCaretSelfVarDot_main() {
		createParser3FileInDir("www/test_compl_selfvar.p",
				"@auto[]\n" +
						"# на основе parser3/tests/191.html\n" +
						"$user[^User::create[a;b]]\n" +
						"\n" +
						"@main[]\n" +
						"# на основе parser3/tests/191.html\n" +
						"^self.user.<caret>"
		);
		VirtualFile vf = myFixture.findFileInTempDir("www/test_compl_selfvar.p");
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("^self.user. должен показать методы", elements);

		List<String> names = Arrays.stream(elements)
				.map(LookupElement::getLookupString)
				.collect(Collectors.toList());

		assertTrue("Должен быть validate, есть: " + names,
				names.stream().anyMatch(n -> n.contains("validate")));
	}

	/**
	 * ^MAIN:user.<caret> в MAIN — те же методы что и ^user.
	 */
	public void testCaretMainVarDot_main() {
		createParser3FileInDir("www/test_compl_mainvar.p",
				"@auto[]\n" +
						"# на основе parser3/tests/191.html\n" +
						"$user[^User::create[a;b]]\n" +
						"\n" +
						"@main[]\n" +
						"# на основе parser3/tests/191.html\n" +
						"^MAIN:user.<caret>"
		);
		VirtualFile vf = myFixture.findFileInTempDir("www/test_compl_mainvar.p");
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("^MAIN:user. должен показать методы", elements);

		List<String> names = Arrays.stream(elements)
				.map(LookupElement::getLookupString)
				.collect(Collectors.toList());

		assertTrue("Должен быть validate, есть: " + names,
				names.stream().anyMatch(n -> n.contains("validate")));
	}

	// =============================================================================
	// РАЗДЕЛ 7: Переменная из другого файла (подключён через use)
	// =============================================================================

	/**
	 * Переменная $libVar определена в lib.p (подключён через auto.p).
	 * ^libVar.<caret> должен показать методы User.
	 */
	public void testCaretVarDot_crossFile() {
		createParser3FileInDir("www/test_compl_crossfile.p",
				"@main[]\n" +
						"# на основе parser3/tests/182.html\n" +
						"^libVar.<caret>"
		);
		VirtualFile vf = myFixture.findFileInTempDir("www/test_compl_crossfile.p");
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("^libVar. должен показать методы (переменная из lib.p)", elements);

		List<String> names = Arrays.stream(elements)
				.map(LookupElement::getLookupString)
				.collect(Collectors.toList());

		assertTrue("Должен быть validate (User), есть: " + names,
				names.stream().anyMatch(n -> n.contains("validate")));
	}

	// =============================================================================
	// РАЗДЕЛ 8: $BASE:var автокомплит
	// =============================================================================

	/**
	 * $BASE:<caret> в классе с @BASE — показывает переменные базового класса.
	 */
	public void testDollarBaseVar() {
		createParser3FileInDir("www/test_compl_base.p",
				// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
				"@CLASS\n" +
						"UserBC\n" +
						"\n" +
						"@create[]\n" +
						"$self.baseVar[bv]\n" +
						"\n" +
						"@GET_baseProp[]\n" +
						"$result[bp]\n" +
						"\n" +
						"\n" +
						// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
						"@CLASS\n" +
						"AdminBC\n" +
						"\n" +
						"@BASE\n" +
						"UserBC\n" +
						"\n" +
						"@method[]\n" +
						"$BASE:<caret>"
		);
		VirtualFile vf = myFixture.findFileInTempDir("www/test_compl_base.p");
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("$BASE: должен показать свойства базового класса", elements);

		List<String> names = Arrays.stream(elements)
				.map(LookupElement::getLookupString)
				.collect(Collectors.toList());

		assertTrue("Должен быть baseVar, есть: " + names, names.contains("baseVar"));
		assertTrue("Должен быть baseProp (@GET_), есть: " + names, names.contains("baseProp"));
	}

	// =============================================================================
	// РАЗДЕЛ: foreach по хешу — ключи элементов видны через $v.
	// =============================================================================

	/**
	 * ^self.config.foreach[k;v]{$v.<caret>} — $v должна видеть ключи элементов $self.config
	 * (т.е. ключи вложенного хеша: "name").
	 */
	public void testForeachSelfHashKeys_selfConfig() {
		createParser3FileInDir("www/test_foreach_self.p",
				"@main[]\n" +
						"# реальный минимальный кейс foreach по $self.config\n" +
						"$self.config[\n" +
						"    $.130[\n" +
						"       $.name[xxx]\n" +
						"    ]\n" +
						"]\n" +
						"^self.config.foreach[k;v]{\n" +
						"    $v.<caret>\n" +
						"}\n"
		);
		VirtualFile vf = myFixture.findFileInTempDir("www/test_foreach_self.p");
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("$v. после ^self.config.foreach должен показать ключи", elements);
		List<String> names = Arrays.stream(elements).map(LookupElement::getLookupString).collect(Collectors.toList());
		assertTrue("Должен быть 'name', есть: " + names, names.contains("name"));
	}

	/**
	 * ^config.foreach[k;v]{$v.<caret>} — без префикса, config = $self.config
	 */
	public void testForeachSelfHashKeys_plainConfig() {
		createParser3FileInDir("www/test_foreach_plain.p",
				"@main[]\n" +
						"# реальный минимальный кейс foreach по config без префикса\n" +
						"$self.config[\n" +
						"    $.130[\n" +
						"       $.name[xxx]\n" +
						"    ]\n" +
						"]\n" +
						"^config.foreach[k;v]{\n" +
						"    $v.<caret>\n" +
						"}\n"
		);
		VirtualFile vf = myFixture.findFileInTempDir("www/test_foreach_plain.p");
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("$v. после ^config.foreach (self) должен показать ключи", elements);
		List<String> names = Arrays.stream(elements).map(LookupElement::getLookupString).collect(Collectors.toList());
		assertTrue("Должен быть 'name', есть: " + names, names.contains("name"));
	}

	/**
	 * ^MAIN:config.foreach[k;v]{$v.<caret>} — MAIN: префикс, config = $self.config
	 */
	public void testForeachSelfHashKeys_mainConfig() {
		createParser3FileInDir("www/test_foreach_main.p",
				"@main[]\n" +
						"# реальный минимальный кейс foreach по $MAIN:config\n" +
						"$self.config[\n" +
						"    $.130[\n" +
						"       $.name[xxx]\n" +
						"    ]\n" +
						"]\n" +
						"^MAIN:config.foreach[k;v]{\n" +
						"    $v.<caret>\n" +
						"}\n"
		);
		VirtualFile vf = myFixture.findFileInTempDir("www/test_foreach_main.p");
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("$v. после ^MAIN:config.foreach (self) должен показать ключи", elements);
		List<String> names = Arrays.stream(elements).map(LookupElement::getLookupString).collect(Collectors.toList());
		assertTrue("Должен быть 'name', есть: " + names, names.contains("name"));
	}

	/**
	 * ^config2.foreach[;v]{$v.<caret>} — простая локальная переменная (без $self.)
	 */
	public void testForeachHashKeys_localVar() {
		createParser3FileInDir("www/test_foreach_local.p",
				"@main[]\n" +
						"# реальный минимальный кейс foreach по локальному хешу\n" +
						"$config2[\n" +
						"    $.130[\n" +
						"       $.name[yyy]\n" +
						"    ]\n" +
						"]\n" +
						"^config2.foreach[;v]{\n" +
						"    $v.<caret>\n" +
						"}\n"
		);
		VirtualFile vf = myFixture.findFileInTempDir("www/test_foreach_local.p");
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("$v. после ^config2.foreach должен показать ключи", elements);
		List<String> names = Arrays.stream(elements).map(LookupElement::getLookupString).collect(Collectors.toList());
		assertTrue("Должен быть 'name', есть: " + names, names.contains("name"));
	}

	public void testForeachValueParam_parser342RealTableFields() {
		createParser3FileInDir("www/parser342_real.p",
				"@main[]\n" +
						"# на основе parser3/tests/342.html\n" +
						"$t[^table::create{c1\tc2\tc3}]\n" +
						"^for[i](1;4){\n" +
						"\t^t.insert{a$i\tb\tc\td\te}\n" +
						"}\n" +
						"\n" +
						"^t.foreach[n;v]{\n" +
						"\t$n : $v.<caret>\n" +
						"}\n"
		);

		List<String> names = getCompletionsFromExistingFile("www/parser342_real.p");
		assertTrue("Реальный Parser3 342: foreach value по table должен видеть колонку c1, есть: " + names,
				names.contains("c1"));
		assertTrue("Реальный Parser3 342: foreach value по table должен видеть колонку c2, есть: " + names,
				names.contains("c2"));
		assertTrue("Реальный Parser3 342: foreach value по table должен видеть колонку c3, есть: " + names,
				names.contains("c3"));
	}

	/**
	 * ^MAIN:config3.foreach[;v]{$v.<caret>} — $MAIN:config3 с MAIN: префиксом
	 */
	public void testForeachHashKeys_mainPrefixVar() {
		createParser3FileInDir("www/test_foreach_mainprefix.p",
				"@main[]\n" +
						"# реальный минимальный кейс foreach по $MAIN:config3\n" +
						"$MAIN:config3[\n" +
						"    $.130[\n" +
						"       $.name[zzz]\n" +
						"    ]\n" +
						"]\n" +
						"^MAIN:config3.foreach[;v]{\n" +
						"    $v.<caret>\n" +
						"}\n"
		);
		VirtualFile vf = myFixture.findFileInTempDir("www/test_foreach_mainprefix.p");
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("$v. после ^MAIN:config3.foreach должен показать ключи", elements);
		List<String> names = Arrays.stream(elements).map(LookupElement::getLookupString).collect(Collectors.toList());
		assertTrue("Должен быть 'name', есть: " + names, names.contains("name"));
	}

	/**
	 * ^v.items.foreach[;v2]{$v2.<caret>} — элемент вложенного foreach по полю должен
	 * видеть структуру значений, добавленных в hash через динамический индекс.
	 */
	public void testForeachFieldHashKeys_dynamicIndexInsideOuterForeach() {
		String content =
				"@main[]\n" +
						"# реальный минимальный кейс вложенного foreach по динамическому индексу\n" +
						"$companies[\n" +
						"    $.1[\n" +
						"        $.items[^hash::create[]]\n" +
						"    ]\n" +
						"]\n" +
						"^companies.foreach[company_id;v]{\n" +
						"    $v.items.[^v.items._count[]][\n" +
						"        $.title[Hello]\n" +
						"        $.url[/hello]\n" +
						"    ]\n" +
						"    ^v.items.foreach[;v2]{\n" +
						"        $v2.<caret>\n" +
						"    }\n" +
						"}\n";
		createParser3FileInDir("www/test_foreach_field_nested_dynamic_index.p", content);
		VirtualFile vf = myFixture.findFileInTempDir("www/test_foreach_field_nested_dynamic_index.p");
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("$v2. после ^v.items.foreach должен показать ключи элемента", elements);
		List<String> names = Arrays.stream(elements).map(LookupElement::getLookupString).collect(Collectors.toList());
		assertTrue("Должен быть 'title', есть: " + names, names.contains("title"));
		assertTrue("Должен быть 'url', есть: " + names, names.contains("url"));
	}

	/**
	 * Одинаковое имя value-переменной в двух foreach не должно смешивать ключи между циклами.
	 */
	public void testForeachFieldHashKeys_sameValueNameInDifferentForeachDoesNotLeak() {
		String content =
				"@main[]\n" +
						"# реальный минимальный кейс одинакового имени value в разных foreach\n" +
						"$companies[\n" +
						"    $.1[\n" +
						"        $.items[^hash::create[]]\n" +
						"    ]\n" +
						"]\n" +
						"^companies.foreach[company_id;v]{\n" +
						"    $itemData[\n" +
						"        $.selected_for_company[\n" +
						"            $.1[\n" +
						"                $.to[10]\n" +
						"            ]\n" +
						"        ]\n" +
						"    ]\n" +
						"    $v.items[^hash::create[]]\n" +
						"    ^itemData.selected_for_company.foreach[;v2]{\n" +
						"        ^if($v2.to){\n" +
						"            $tmp[$v2.to]\n" +
						"        }\n" +
						"        $v.items.[^v.items._count[]][\n" +
						"            $.title[Hello]\n" +
						"            $.url[/company/$company_id]\n" +
						"        ]\n" +
						"    }\n" +
						"}\n" +
						"^companies.foreach[company_id;v]{\n" +
						"    ^v.items.foreach[;v2]{\n" +
						"        $v2.<caret>\n" +
						"    }\n" +
						"}\n";
		createParser3FileInDir("www/test_foreach_field_same_value_name_does_not_leak.p", content);
		VirtualFile vf = myFixture.findFileInTempDir("www/test_foreach_field_same_value_name_does_not_leak.p");
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("$v2. во втором foreach должен показывать ключи из v.items", elements);
		List<String> names = Arrays.stream(elements).map(LookupElement::getLookupString).collect(Collectors.toList());
		assertTrue("Должен быть 'title', есть: " + names, names.contains("title"));
		assertTrue("Должен быть 'url', есть: " + names, names.contains("url"));
		assertFalse("'to' не должен подтекать из другого foreach: " + names, names.contains("to"));
	}

	public void testForeachFieldHashKeys_realItemsApiCaseDoesNotLeakForeignKeys() {
		String content =
				"@main[]\n" +
						"# обезличенный минимальный кейс test api без протекания чужих ключей foreach\n" +
						"$accounts[\n" +
						"    $.1[\n" +
						"        $.name[test_account]\n" +
						"        $.user[\n" +
						"            $.id[7]\n" +
						"            $.name[test_user]\n" +
						"        ]\n" +
						"        $.items[^hash::create[]]\n" +
						"    ]\n" +
						"]\n" +
						"^accounts.foreach[account_id;v]{\n" +
						"    $itemData[^test_api[https://test.com/api/items/$account_id;;1]]\n" +
						"    $v.items[^hash::create[]]\n" +
						"    ^itemData.selected_for_company.foreach[;v2]{\n" +
						"        $v.items.[^v.items._count[]][\n" +
						"            $.title[$v2.title]\n" +
						"            $.url[$v2.url]\n" +
						"        ]\n" +
						"    }\n" +
						"}\n" +
						"^accounts.foreach[account_id;v]{\n" +
						"    <strong><a href=\"$url\">$v.name</a></strong>, <a href=\"https://test.com/users/$v.user.id/\">$v.user.name</a><br />\n" +
						"    ^v.items.foreach[;v2]{\n" +
						"        — <a href=\"$v2.url\">$v2.</a>\n" +
						"        $v2.<caret>\n" +
						"    }\n" +
						"}\n";
		createParser3FileInDir("www/test_foreach_field_real_test_api_case.p", content);
		VirtualFile vf = myFixture.findFileInTempDir("www/test_foreach_field_real_test_api_case.p");
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("$v2. в реальном кейсе должен показывать ключи из v.items", elements);
		List<String> names = Arrays.stream(elements).map(LookupElement::getLookupString).collect(Collectors.toList());
		assertTrue("Должен быть 'title', есть: " + names, names.contains("title"));
		assertTrue("Должен быть 'url', есть: " + names, names.contains("url"));
		assertFalse("'to' не должен появляться в этом кейсе: " + names, names.contains("to"));
	}

	/**
	 * @main[][locals] $var[x] — $MAIN:<caret> НЕ должен показывать $var (он локальный)
	 */
	public void testTableMenu_tableFieldsRemainAfterFieldMethodCall() {
		String content =
				"@main[]\n" +
						"# обезличенный минимальный кейс сохранения table-полей после вызова метода поля\n" +
						"$users[^table::sql{\n" +
						"    SELECT\n" +
						"      id, company_id, CONCAT(first_name, ' ', last_name) AS name, email\n" +
						"    FROM\n" +
						"      users\n" +
						"}]\n" +
						"^users.menu{\n" +
						"    $name[^users.name.lower[]]\n" +
						"    $users.<caret>\n" +
						"}\n";
		createParser3FileInDir("www/test_table_menu_field_method_keeps_columns.p", content);
		VirtualFile vf = myFixture.findFileInTempDir("www/test_table_menu_field_method_keeps_columns.p");
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("$users. после ^users.name.lower[] должен сохранить table fields", elements);
		List<String> names = Arrays.stream(elements).map(LookupElement::getLookupString).collect(Collectors.toList());
		assertTrue("Должен содержать 'id', есть: " + names, names.contains("id"));
		assertTrue("Должен содержать 'company_id', есть: " + names, names.contains("company_id"));
		assertTrue("Должен содержать 'name', есть: " + names, names.contains("name"));
		assertTrue("Должен содержать 'email', есть: " + names, names.contains("email"));
	}

	public void testTableMenu_parser064RealTableColumnsVisible() {
		createParser3FileInDir("www/parser064_real.p",
				"@main[]\n" +
						"# на основе parser3/tests/064.html\n" +
						"$new_after[^date::create[2002-07-01]]\n" +
						"$articles[^table::create{id\ttitle\tlast_update\n" +
						"1\tfirst\t2002-06-30\n" +
						"2\tsecond\t2002-07-03\n" +
						"}]\n" +
						"^articles.menu{\n" +
						"\t$last_update[^date::create[$articles.last_update]]\n" +
						"\t$articles.<caret>\n" +
						"\t^if($last_update > $new_after){new;old}\n" +
						"}\n"
		);

		List<String> names = getCompletionsFromExistingFile("www/parser064_real.p");
		assertTrue("Реальный Parser3 064: table.menu должен видеть колонку id, есть: " + names,
				names.contains("id"));
		assertTrue("Реальный Parser3 064: table.menu должен видеть колонку title, есть: " + names,
				names.contains("title"));
		assertTrue("Реальный Parser3 064: table.menu должен видеть колонку last_update, есть: " + names,
				names.contains("last_update"));
	}

	public void testForeachKeyParam_noDotInDollarCompletion() {
		createParser3FileInDir("www/test_foreach_key_no_dot.p",
				"@main[]\n" +
						"# реальный минимальный кейс key-переменной foreach без точки\n" +
						"$users[^table::sql{SELECT id, target_user_id FROM users}]\n" +
						"^users.foreach[target_user_id;]{\n" +
						"    $.target_user_id[$<caret>]\n" +
						"}\n"
		);
		VirtualFile vf = myFixture.findFileInTempDir("www/test_foreach_key_no_dot.p");
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("$ внутри foreach должен видеть key-переменную", elements);
		List<String> names = Arrays.stream(elements).map(LookupElement::getLookupString).collect(Collectors.toList());
		assertTrue("Должен быть 'target_user_id', есть: " + names, names.contains("target_user_id"));
		assertFalse("Не должно быть 'target_user_id.' с точкой, есть: " + names, names.contains("target_user_id."));
	}

	public void testForeachKeyParam_realCaseDoesNotInsertDotFromEarlierForeach() {
		String content =
				"@main[]\n" +
						"# реальный минимальный кейс key-переменной foreach без точки после другого foreach\n" +
						"$users[^table::sql{SELECT id, account_id FROM users}]\n" +
						"^if($v2.to){\n" +
						"    ^v2.to.foreach[;target_user_id]{\n" +
						"        $p.[$target_user_id][]\n" +
						"    }\n" +
						"}\n" +
						"^users.foreach[target_user_id;]{\n" +
						"    ^contine($target_user_id eq $user_id)\n" +
						"    $item[\n" +
						"        $.source_user_id[$users.[$user_id].account_id]\n" +
						"        $.target_user_id[$target_us<caret>]\n" +
						"    ]\n" +
						"}\n";
		createParser3FileInDir("www/test_foreach_key_real_case_no_dot_insert.p", content);
		VirtualFile vf = myFixture.findFileInTempDir("www/test_foreach_key_real_case_no_dot_insert.p");
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("В реальном кейсе должен быть completion для target_user_id", elements);
		List<String> names = Arrays.stream(elements).map(LookupElement::getLookupString).collect(Collectors.toList());
		assertTrue("Должен быть 'target_user_id', есть: " + names, names.contains("target_user_id"));
		assertFalse("Не должно быть 'target_user_id.' с точкой, есть: " + names, names.contains("target_user_id."));

		LookupElement target = Arrays.stream(elements)
				.filter(e -> "target_user_id".equals(e.getLookupString()))
				.findFirst()
				.orElse(null);
		assertNotNull("Должен найти lookup element 'target_user_id'", target);

		myFixture.getLookup().setCurrentItem(target);
		myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR);

		String text = myFixture.getEditor().getDocument().getText();
		assertTrue("После вставки должно быть без точки: " + text, text.contains("$.target_user_id[$target_user_id]"));
		assertFalse("После вставки не должно быть точки: " + text, text.contains("$.target_user_id[$target_user_id.]"));
	}

	public void testTableMenu_dynamicBracketExpr_keepsSqlAliasCompletion() {
		String content =
				"@main[]\n" +
						"# обезличенный минимальный кейс SQL alias в dynamic bracket expr\n" +
						"$_items[^table::sql{\n" +
						"	SELECT\n" +
						"		COUNT(*) AS cnt, s.type, s.source_user_id AS `from`, s.target_user_id AS `to`,\n" +
						"		u.user_id,\n" +
						"		u2.user_id AS target_user_id\n" +
						"	FROM\n" +
						"		tasks AS s,\n" +
						"		users AS u,\n" +
						"		users AS u2\n" +
						"	WHERE\n" +
						"		u.id = s.source_user_id\n" +
						"		AND u2.id = s.target_user_id\n" +
						"	GROUP BY s.type, s.source_user_id, s.target_user_id\n" +
						"}]\n" +
						"$items[^hash::create[]]\n" +
						"^_items.menu{\n" +
						"	$items.[$_items.target_user_<caret>][]\n" +
						"}\n";
		createParser3FileInDir("www/test_table_menu_dynamic_bracket_expr_completion.p", content);
		List<String> completions = getCompletionsFromExistingFile("www/test_table_menu_dynamic_bracket_expr_completion.p");

		assertTrue("Должен быть completion 'target_user_id', есть: " + completions,
				completions.contains("target_user_id"));
		assertFalse("Не должно быть 'target_user_id.' с точкой, есть: " + completions,
				completions.contains("target_user_id."));
	}

	public void testDollarCompletion_keepsSimpleHashVarAfterObjectMethodVars() {
		String content =
				// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
				"@CLASS\n" +
						"TestApi\n" +
						"\n" +
						"@create[]\n" +
						"$result[$self]\n" +
						"\n" +
						"@getItem[]\n" +
						"# тип намеренно не указан\n" +
						"\n" +
						"@call[params]\n" +
						"# тип намеренно не указан\n" +
						"\n" +
						"@main[]\n" +
						"# реальный минимальный кейс $completion после object method vars\n" +
						"$TestApi[^TestApi::create[]]\n" +
						"$item[^TestApi.getItem[]]\n" +
						"$activities[^TestApi.call[action=get_activity]]\n" +
						"$meeting[^hash::create[]]\n" +
						"$email[^hash::create[]]\n" +
						"$emai<caret>\n";
		createParser3FileInDir("www/test_dollar_completion_after_object_method_vars.p", content);
		List<String> completions = getCompletionsFromExistingFile("www/test_dollar_completion_after_object_method_vars.p");

		assertTrue("После object method vars должен остаться completion 'email', есть: " + completions,
				completions.contains("email"));
	}

	public void testDollarCompletion_infersHashKeysFromReadChainUsage() {
		String content =
				"@main[]\n" +
						"# реальный минимальный кейс read-chain из json/curl\n" +
						"$f[^curl:load[\n" +
						"	$.url[https://test.com]\n" +
						"	$.mode[text]\n" +
						"]]\n" +
						"$item[^json:parse[^taint[as-is][$f.text]]]\n" +
						"$person_email[$item.person.email]\n" +
						"$it<caret>\n";
		List<String> completions = getCompletions("read_chain_hash_keys.p", content);

		assertTrue("После чтения $item.person.email должен появиться completion 'item.', есть: " + completions,
				completions.contains("item."));
	}

	public void testDollarCompletion_parser050RealIfHashResultShowsKey() {
		String content =
				"@main[]\n" +
						"# на основе parser3/tests/050.html\n" +
						"$a[^if(1){$.k[y]}{$.k[n]}]\n" +
						"$a.<caret>\n";
		List<String> completions = getCompletions("parser050_real_if_hash_result.p", content);

		assertTrue("Реальный Parser3 050: hash-result из ^if должен показывать ключ k, есть: " + completions,
				completions.contains("k"));
	}

	public void testDollarCompletion_parser016RealIfHashSourceVarsShowsKey() {
		String content =
				"@main[]\n" +
						"# на основе parser3/tests/016.html\n" +
						"$a[$.e[a]]\n" +
						"$b[$.e[b]]\n" +
						"$x[^if(1){$a}{$b}]\n" +
						"$x.<caret>\n";
		List<String> completions = getCompletions("parser016_real_if_hash_source_vars.p", content);

		assertTrue("Реальный Parser3 016: ^if из hash-переменных должен показывать ключ e, есть: " + completions,
				completions.contains("e"));
	}

	public void testDollarCompletion_parser017RealIfBranchHashStatementsShowsKey() {
		String content =
				"@main[]\n" +
						"# на основе parser3/tests/017.html\n" +
						"$h[^if(1){$.a(1);$.a(2)}]\n" +
						"$h.<caret>\n";
		List<String> completions = getCompletions("parser017_real_if_hash_statements.p", content);

		assertTrue("Реальный Parser3 017: hash-записи внутри ветки ^if должны показывать ключ a, есть: " + completions,
				completions.contains("a"));
	}

	public void testDollarCompletion_getterHidesSyntheticSelfReadChainRoot() {
		String content =
				// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
				"@CLASS\n" +
						"Print\n" +
						"\n" +
						"@create[]\n" +
						"^if($self.uriData.uri){}\n" +
						"\n" +
						"@check[]\n" +
						"$self.uri<caret>\n" +
						"\n" +
						"@GET_uriData[]\n" +
						"$result[^self.getUriData[]]\n" +
						"\n" +
						"@getUriData[]\n" +
						"$result[\n" +
						"	$.uri[/]\n" +
						"]\n";
		List<String> completions = getCompletions("getter_self_read_chain_completion.p", content);

		assertEquals("Синтетическая read-chain и @GET_ не должны давать два uriData: " + completions,
				1, java.util.Collections.frequency(completions, "uriData"));
	}

	public void testDollarCompletion_getterMethodResultKeepsAllHashKeysAfterSelfReadChain() {
		String content =
				// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
				"@CLASS\n" +
						"Print\n" +
						"\n" +
						"@create[]\n" +
						"^if($self.uriData.uri){\n" +
						"	^rem{...}\n" +
						"}\n" +
						"$self.uriData.<caret>\n" +
						"\n" +
						"@GET_uriData[]\n" +
						"$result[^self.getUriData[]]\n" +
						"\n" +
						"@getUriData[]\n" +
						"$uri[^request:uri.split[?;lh]]\n" +
						"$tUri[^uri.split[/;lh]]\n" +
						"\n" +
						"$result[\n" +
						"	$.uri[/$uri/]\n" +
						"	$.tUri[$tUri]\n" +
						"]\n";
		List<String> completions = getCompletions("getter_method_result_hash_keys_after_read_chain.p", content);

		assertTrue("Getter-result должен давать ключ uri: " + completions, completions.contains("uri"));
		assertSinglePropertyCompletion(
				"Getter-result должен давать ключ tUri, а не только synthetic uri",
				completions,
				"tUri");
	}

	public void testDollarCompletion_realClassPGetterResultKeepsHashKeysAfterWeakResultOverride() {
		String content =
				// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
				"@CLASS\n" +
						"Print\n" +
						"\n" +
						"@create[]\n" +
						"^if($self.uriData.uri){\n" +
						"	^rem{...}\n" +
						"}\n" +
						"#$self.uriData[\n" +
						"#	$.x[]\n" +
						"#]\n" +
						"\n" +
						"$self.uriData.uri — клик по uri никуда не ведет\n" +
						"$self.uriData.<caret>error — ообще нет автокомплита\n" +
						"\n" +
						"@GET_uriData[]\n" +
						"$result[^self.getUriData[]]\n" +
						"^if(1){\n" +
						"	$result(1)\n" +
						"}\n" +
						"\n" +
						"@getUriData[]\n" +
						"$uri[^request:uri.split[?;lh]]\n" +
						"$uri[^uri.0.match[^^$self.setting.basePath][i]{}]\n" +
						"$uri[^uri.trim[both;/]]\n" +
						"$tUri[^uri.split[/;lh]]\n" +
						"\n" +
						"$result[\n" +
						"	$.uri[/$uri/]\n" +
						"	$.tUri[$tUri]\n" +
						"]\n" +
						"^if($result.uri eq '//'){\n" +
						"	$result.uri[/]\n" +
						"}\n" +
						"\n" +
						"^if(def $tUri.0){\n" +
						"	$tag[^self.getCachedTagInfo[$tUri.0]]\n" +
						"\n" +
						"	^if(!$tag || !$tag.tag){\n" +
						"		$result.error(true)\n" +
						"	}{\n" +
						"		$result.tag[$tag]\n" +
						"		^if(def $tUri.1){\n" +
						"			$hasInList(false)\n" +
						"			^tag.list.foreach[;v]{\n" +
						"				^if($v.web_name eq $tUri.1){\n" +
						"					$hasInList(true)\n" +
						"					^break[]\n" +
						"				}\n" +
						"			}\n" +
						"			^if(!$hasInList){\n" +
						"				$result.error(true)\n" +
						"			}\n" +
						"		}\n" +
						"		^if($result.tag && def $tUri.2){\n" +
						"			$result.error(true)\n" +
						"		}\n" +
						"	}\n" +
						"}\n";
		List<String> completions = getCompletions("real_class_p_getter_result_weak_override.p", content);

		assertTrue("Реальный class.p должен показывать uri у $self.uriData.: " + completions, completions.contains("uri"));
		assertSinglePropertyCompletion(
				"Реальный class.p должен показывать tUri у $self.uriData.",
				completions,
				"tUri");
		assertTrue("Реальный class.p должен показывать error у $self.uriData.: " + completions, completions.contains("error"));
	}

	public void testDollarCompletion_realClassPGetterRootDoesNotDuplicateUriData() {
		String content =
				// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
				"@CLASS\n" +
						"Print\n" +
						"\n" +
						"@create[]\n" +
						"^if($self.uriData.uri){\n" +
						"	^rem{...}\n" +
						"}\n" +
						"#$self.uriData[\n" +
						"#	$.x[]\n" +
						"#]\n" +
						"\n" +
						"$self.uriData.uri — клик по uri никуда не ведет\n" +
						"$self.uri<caret>Data.error — ообще нет автокомплита\n" +
						"\n" +
						"@GET_uriData[]\n" +
						"$result[^self.getUriData[]]\n" +
						"^if(1){\n" +
						"	$result(1)\n" +
						"}\n" +
						"\n" +
						"@getUriData[]\n" +
						"$uri[^request:uri.split[?;lh]]\n" +
						"$uri[^uri.0.match[^^$self.setting.basePath][i]{}]\n" +
						"$uri[^uri.trim[both;/]]\n" +
						"$tUri[^uri.split[/;lh]]\n" +
						"\n" +
						"$result[\n" +
						"	$.uri[/$uri/]\n" +
						"	$.tUri[$tUri]\n" +
						"]\n" +
						"^if($result.uri eq '//'){\n" +
						"	$result.uri[/]\n" +
						"}\n" +
						"\n" +
						"^if(def $tUri.0){\n" +
						"	$tag[^self.getCachedTagInfo[$tUri.0]]\n" +
						"\n" +
						"	^if(!$tag || !$tag.tag){\n" +
						"		$result.error(true)\n" +
						"	}{\n" +
						"		$result.tag[$tag]\n" +
						"		^if(def $tUri.1){\n" +
						"			$hasInList(false)\n" +
						"			^tag.list.foreach[;v]{\n" +
						"				^if($v.web_name eq $tUri.1){\n" +
						"					$hasInList(true)\n" +
						"					^break[]\n" +
						"				}\n" +
						"			}\n" +
						"			^if(!$hasInList){\n" +
						"				$result.error(true)\n" +
						"			}\n" +
						"		}\n" +
						"		^if($result.tag && def $tUri.2){\n" +
						"			$result.error(true)\n" +
						"		}\n" +
						"	}\n" +
						"}\n";
		List<String> completions = getCompletions("real_class_p_getter_root_no_duplicate.p", content);

		assertEquals("Реальный class.p не должен давать два uriData в completion: " + completions,
				1, java.util.Collections.frequency(completions, "uriData"));
	}

	public void testDollarCompletion_getterResultWeakOverrideKeepsRicherMethodResult() {
		String content =
				// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
				"@CLASS\n" +
						"Print\n" +
						"\n" +
						"@create[]\n" +
						"$self.uriData.<caret>\n" +
						"\n" +
						"@GET_uriData[]\n" +
						"$result[^self.getUriData[]]\n" +
						"^if(1){\n" +
						"	$result(1)\n" +
						"}\n" +
						"\n" +
						"@getUriData[]\n" +
						"$result[\n" +
						"	$.uri[/]\n" +
						"	$.tUri[]\n" +
						"]\n";
		List<String> completions = getCompletions("getter_result_weak_override_minimal.p", content);

		assertTrue("Слабое $result(1) не должно затирать hash-result getter-а: " + completions, completions.contains("uri"));
		assertTrue("Слабое $result(1) не должно затирать hash-result getter-а: " + completions, completions.contains("tUri"));
	}

	public void testDollarCompletion_objectMethodResultWeakOverrideKeepsClassProperties() {
		String content =
				"@makeUser[]\n" +
						"$result[^User::create[]]\n" +
						"^if(1){\n" +
						"	$result(1)\n" +
						"}\n" +
						"\n" +
						"@main[]\n" +
						"# реальный минимальный кейс weak $result object-result\n" +
						"$user[^makeUser[]]\n" +
						"$user.<caret>\n" +
						"\n" +
						// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
						"@CLASS\n" +
						"User\n" +
						"\n" +
						"@create[]\n" +
						"$self.name[]\n" +
						"\n" +
						"@GET_displayName[]\n" +
						"$result[$self.name]\n";
		List<String> completions = getCompletions("object_result_weak_override_minimal.p", content);

		assertTrue("Слабое $result(1) не должно затирать class-result property name: " + completions, completions.contains("name"));
		assertTrue("Слабое $result(1) не должно затирать class-result getter displayName: " + completions, completions.contains("displayName"));
	}

	public void testDollarCompletion_explicitSelfHashLiteralAfterReadChainDeduplicatesRoot() {
		String content =
				// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
				"@CLASS\n" +
						"Print\n" +
						"\n" +
						"@create[]\n" +
						"^if($self.uriData.uri){\n" +
						"	^rem{...}\n" +
						"}\n" +
						"$self.uriData[\n" +
						"	$.x[]\n" +
						"]\n" +
						"\n" +
						"@check[]\n" +
						"$self.uri<caret>\n";
		List<String> completions = getCompletions("self_read_chain_explicit_hash_literal.p", content);

		assertSinglePropertyCompletion(
				"Явный hash literal $self.uriData после read-chain должен давать один completion uriData",
				completions,
				"uriData");
	}

	public void testDollarCompletion_explicitSelfHashLiteralAfterReadChainDeduplicatesExactPrefixInSameMethod() {
		String content =
				// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
				"@CLASS\n" +
						"Print\n" +
						"\n" +
						"@create[]\n" +
						"^if($self.uriData.uri){\n" +
						"	^rem{...}\n" +
						"}\n" +
						"$self.uriData[\n" +
						"	$.x[]\n" +
						"]\n" +
						"$self.uriData<caret>\n";
		List<String> completions = getCompletions("self_read_chain_explicit_hash_literal_exact_prefix.p", content);

		assertSinglePropertyCompletion(
				"После полного префикса $self.uriData в том же методе не должно быть двух uriData",
				completions,
				"uriData");
	}

	public void testDollarCompletion_explicitSelfHashLiteralAfterReadChainDeduplicatesVisibleText() {
		String content =
				// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
				"@CLASS\n" +
						"Print\n" +
						"\n" +
						"@create[]\n" +
						"^if($self.uriData.uri){\n" +
						"	^rem{...}\n" +
						"}\n" +
						"$self.uriData[\n" +
						"	$.x[]\n" +
						"]\n" +
						"$self.uriData<caret>\n";
		List<String> itemTexts = getCompletionItemTexts("self_read_chain_explicit_hash_literal_visible_text.p", content);

		assertSinglePropertyCompletion(
				"Видимый текст completion после $self.uriData не должен показывать два uriData",
				itemTexts,
				"uriData");
	}

	public void testDollarCompletion_realClassPReadChainAndExplicitHashDeduplicatesVisibleText() {
		String content =
				// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
				"@CLASS\n" +
						"Print\n" +
						"\n" +
						"@create[]\n" +
						"^if($self.uriData.uri){\n" +
						"	^rem{...}\n" +
						"}\n" +
						"$self.uriData[\n" +
						"	$.x[]\n" +
						"]\n" +
						"\n" +
						"$self.uriData<caret> — ОПЯТЬ в автокомплите 2 \"uriData\"\n" +
						"\n" +
						"@GET_uriData[]\n" +
						"$result[^self.getUriData[]]\n" +
						"^if(1){\n" +
						"	$result(1)\n" +
						"}\n" +
						"\n" +
						"@getUriData[]\n" +
						"$uri[^request:uri.split[?;lh]]\n" +
						"$uri[^uri.0.match[^^$self.setting.basePath][i]{}]\n" +
						"$uri[^uri.trim[both;/]]\n" +
						"$tUri[^uri.split[/;lh]]\n" +
						"\n" +
						"$result[\n" +
						"	$.uri[/$uri/]\n" +
						"	$.tUri[$tUri]\n" +
						"]\n" +
						"^if($result.uri eq '//'){\n" +
						"	$result.uri[/]\n" +
						"}\n" +
						"\n" +
						"^if(def $tUri.0){\n" +
						"	$tag[^self.getCachedTagInfo[$tUri.0]]\n" +
						"\n" +
						"	^if(!$tag || !$tag.tag){\n" +
						"		$result.error(true)\n" +
						"	}{\n" +
						"		$result.tag[$tag]\n" +
						"		^if(def $tUri.1){\n" +
						"			$hasInList(false)\n" +
						"			^tag.list.foreach[;v]{\n" +
						"				^if($v.web_name eq $tUri.1){\n" +
						"					$hasInList(true)\n" +
						"					^break[]\n" +
						"				}\n" +
						"			}\n" +
						"			^if(!$hasInList){\n" +
						"				$result.error(true)\n" +
						"			}\n" +
						"		}\n" +
						"		^if($result.tag && def $tUri.2){\n" +
						"			$result.error(true)\n" +
						"		}\n" +
						"	}\n" +
						"}\n";
		List<String> itemTexts = getCompletionItemTexts("real_class_p_read_chain_explicit_hash.p", content);

		assertSinglePropertyCompletion(
				"1:1 class.p не должен показывать два видимых uriData",
				itemTexts,
				"uriData");
	}

	public void testDollarCompletion_explicitSelfHashAndGetterDeduplicatesVisibleText() {
		String content =
				// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
				"@CLASS\n" +
						"Print\n" +
						"\n" +
						"@create[]\n" +
						"$self.uriData[\n" +
						"	$.x[]\n" +
						"]\n" +
						"$self.uriData<caret>\n" +
						"\n" +
						"@GET_uriData[]\n" +
						"$result[^self.getUriData[]]\n";
		List<String> itemTexts = getCompletionItemTexts("self_hash_and_getter_visible_text.p", content);

		assertSinglePropertyCompletion(
				"Явное свойство и @GET_ одного имени не должны показываться двумя uriData",
				itemTexts,
				"uriData");
	}

	public void testDollarCompletion_explicitSelfHashCreateAfterReadChainDeduplicatesRoot() {
		String content =
				// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
				"@CLASS\n" +
						"Print\n" +
						"\n" +
						"@create[]\n" +
						"^if($self.uriData.uri){\n" +
						"	^rem{...}\n" +
						"}\n" +
						"$self.uriData[^hash::create[]]\n" +
						"\n" +
						"@check[]\n" +
						"$self.uri<caret>\n";
		List<String> completions = getCompletions("self_read_chain_explicit_hash_create.p", content);

		assertSinglePropertyCompletion(
				"Явный ^hash::create[] $self.uriData после read-chain должен давать один completion uriData",
				completions,
				"uriData");
	}

	public void testDollarCompletion_explicitSelfTableAfterReadChainDeduplicatesRoot() {
		String content =
				// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
				"@CLASS\n" +
						"Print\n" +
						"\n" +
						"@create[]\n" +
						"^if($self.uriData.uri){\n" +
						"	^rem{...}\n" +
						"}\n" +
						"$self.uriData[^table::create{x}]\n" +
						"\n" +
						"@check[]\n" +
						"$self.uri<caret>\n";
		List<String> completions = getCompletions("self_read_chain_explicit_table.p", content);

		assertSinglePropertyCompletion(
				"Явный table $self.uriData после read-chain должен давать один completion uriData",
				completions,
				"uriData");
	}

	public void testDollarCompletion_explicitSelfObjectAfterReadChainDeduplicatesRoot() {
		String content =
				// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
				"@CLASS\n" +
						"Data\n" +
						"\n" +
						"@create[]\n" +
						"$result[]\n" +
						"\n" +
						// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
						"@CLASS\n" +
						"Print\n" +
						"\n" +
						"@create[]\n" +
						"^if($self.uriData.uri){\n" +
						"	^rem{...}\n" +
						"}\n" +
						"$self.uriData[^Data::create[]]\n" +
						"\n" +
						"@check[]\n" +
						"$self.uri<caret>\n";
		List<String> completions = getCompletions("self_read_chain_explicit_object.p", content);

		assertSinglePropertyCompletion(
				"Явный объект $self.uriData после read-chain должен давать один completion uriData",
				completions,
				"uriData");
	}

	public void testDollarCompletion_explicitSelfScalarAfterReadChainDeduplicatesRoot() {
		String content =
				// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
				"@CLASS\n" +
						"Print\n" +
						"\n" +
						"@create[]\n" +
						"^if($self.uriData.uri){\n" +
						"	^rem{...}\n" +
						"}\n" +
						"$self.uriData[value]\n" +
						"\n" +
						"@check[]\n" +
						"$self.uri<caret>\n";
		List<String> completions = getCompletions("self_read_chain_explicit_scalar.p", content);

		assertSinglePropertyCompletion(
				"Явный scalar $self.uriData после read-chain должен давать один completion uriData",
				completions,
				"uriData");
	}

	public void testDollarCompletion_explicitSelfHashLiteralAfterReadChainKeepsNestedKeys() {
		String content =
				// реальный минимальный fixture completion; при расширении сверять с тестами Parser3.
				"@CLASS\n" +
						"Print\n" +
						"\n" +
						"@create[]\n" +
						"^if($self.uriData.uri){\n" +
						"	^rem{...}\n" +
						"}\n" +
						"$self.uriData[\n" +
						"	$.x[]\n" +
						"]\n" +
						"\n" +
						"@check[]\n" +
						"$self.uriData.<caret>\n";
		List<String> completions = getCompletions("self_read_chain_explicit_hash_nested.p", content);

		assertTrue("Явный hash literal должен сохранить nested key x: " + completions,
				completions.contains("x"));
	}

	public void testDollarCompletion_doesNotLeakIncompleteReadChainSegment() {
		String content =
				"@main[]\n" +
						"# реальный минимальный кейс неполного read-chain segment\n" +
						"$f[^curl:load[\n" +
						"	$.url[https://test.com]\n" +
						"	$.mode[text]\n" +
						"]]\n" +
						"$item[^json:parse[^taint[as-is][$f.text]]]\n" +
						"$item.perso<caret>\n";
		List<String> completions = getCompletions("read_chain_incomplete_segment.p", content);

		assertFalse("Недописанный сегмент не должен сам попадать в completion: " + completions,
				completions.contains("perso."));
	}

	public void testDollarCompletion_infersIntermediateReadChainSegment() {
		String content =
				"@main[]\n" +
						"# реальный минимальный кейс intermediate read-chain segment\n" +
						"$f[^curl:load[\n" +
						"	$.url[https://test.com]\n" +
						"	$.mode[text]\n" +
						"]]\n" +
						"$item[^json:parse[^taint[as-is][$f.text]]]\n" +
						"$person_name[$item.person.name]\n" +
						"$item.per<caret>\n";
		List<String> completions = getCompletions("read_chain_intermediate_segment.p", content);

		assertTrue("После чтения $item.person.name должен появиться completion 'person.', есть: " + completions,
				completions.contains("person."));
	}

	public void testDollarMainCompletion_selectReadChainVariableKeepsCurrentLine() {
		String content =
				"@main[]\n" +
						"$MAIN:person.subgroups[^hash::create[]]\n" +
						"\n" +
						"@create[]\n" +
						"$MAIN:perso<caret>\n" +
						"\n" +
						"@get_rights[person]\n" +
						"^if(!$person){$person[$MAIN:person]}\n" +
						"\n" +
						"$person.subgroups[^hash::create[]]\n";
		configureParser3TextFile("main_completion_read_chain_insert.p", content);

		myFixture.complete(CompletionType.BASIC);
		selectCompletion("person.");
		com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents();
		try {
			AutoPopupController.getInstance(getProject()).waitForDelayedActions(5, TimeUnit.SECONDS);
		} catch (java.util.concurrent.TimeoutException e) {
			throw new AssertionError("Не дождались delayed auto-popup actions", e);
		}
		com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents();

		String text = myFixture.getEditor().getDocument().getText();
		assertTrue("Выбор person. после $MAIN:perso должен править текущую строку: " + text,
				text.contains("$MAIN:person.\n"));
		assertTrue("Выбор person. не должен затирать нижний read-chain key subgroups: " + text,
				text.contains("$person.subgroups[^hash::create[]]"));

		Lookup nextLookup = myFixture.getLookup();
		assertNotNull("Автопопап после person. должен быть открыт", nextLookup);
		assertTrue("Автопопап после person. должен быть LookupImpl", nextLookup instanceof LookupImpl);
		LookupImpl nextLookupImpl = (LookupImpl) nextLookup;
		waitForDefaultLookupSelection(nextLookupImpl);
		LookupElement[] nextElements = myFixture.getLookupElements();
		List<String> nextLookupStrings = nextElements == null ? List.of() : Arrays.stream(nextElements)
				.map(LookupElement::getLookupString)
				.collect(Collectors.toList());
		assertNotNull("Автопопап после person. должен сразу выбрать первый пункт", nextLookup.getCurrentItem());
		assertTrue("Автопопап после person. должен быть сфокусирован, чтобы Enter сразу вставлял выбранный пункт",
				nextLookup.isFocused());
		assertEquals("Автопопап после person. должен визуально выбрать первую строку",
				0,
				nextLookupImpl.getSelectedIndex());
		assertTrue("Автопопап после person. должен иметь хотя бы один пункт", nextElements != null && nextElements.length > 0);
		assertEquals("Автопопап после person. должен выбрать первый пункт, чтобы Enter сразу вставлял его",
				nextElements[0].getLookupString(),
				nextLookup.getCurrentItem().getLookupString());
		nextLookupImpl.setLookupFocusDegree(LookupFocusDegree.UNFOCUSED);
		waitForFocusedLookup(nextLookupImpl);
		assertEquals("Автопопап после сброса focus degree должен вернуть синий focused-selection",
				LookupFocusDegree.FOCUSED,
				nextLookupImpl.getLookupFocusDegree());
		assertEquals("Автопопап после сброса focus degree должен сохранить визуальный выбор первой строки",
				0,
				nextLookupImpl.getSelectedIndex());
		assertTrue("Автопопап после person. должен показывать dot-шаблон .foreach[]: " + nextLookupStrings,
				nextLookupStrings.contains("foreach[]"));
		assertTrue("Ключ subgroups должен быть выше dot-шаблона .foreach[]: " + nextLookupStrings,
				nextLookupStrings.indexOf("subgroups") >= 0
						&& nextLookupStrings.indexOf("subgroups") < nextLookupStrings.indexOf("foreach[]"));
		assertFalse("Автопопап после person. не должен показывать caret-шаблон ^curl:load[]: " + nextLookupStrings,
				nextLookupStrings.contains("curl:load[]"));
		assertFalse("Автопопап после person. не должен показывать mail-шаблоны без явного Ctrl+Space: " + nextLookupStrings,
				nextLookupStrings.contains("mail:send[]"));
	}

	public void testDollarCompletion_manualTypedDotOpensHashKeysPopup() throws Throwable {
		try {
			setDocumentRoot("www");
		} catch (java.io.IOException e) {
			throw new AssertionError("Не удалось настроить document_root для обезличенного fixture", e);
		}
		replaceParser3FileInDir("www/auto.p",
				"@auto[]\n" +
						"$person[\n" +
						"\t$.login[test_user]\n" +
						"\t$.fields[^hash::create[]]\n" +
						"]\n" +
						"$person.fields.remote[test_value]\n");

		createParser3FileInDir("www/_mod/auto.p",
				"@CLASS\n" +
						"TestModule\n" +
						"\n" +
						"@OPTIONS\n" +
						"locals\n" +
						"\n" +
						"@create[]\n" +
						"<caret>\n");
		VirtualFile vf = myFixture.findFileInTempDir("www/_mod/auto.p");
		myFixture.configureFromExistingVirtualFile(vf);

		TestModeFlags.runWithFlag(CompletionAutoPopupHandler.ourTestingAutopopup, Boolean.TRUE, () -> {
			myFixture.type("$person.");
			com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents();
			try {
				AutoPopupController.getInstance(getProject()).waitForDelayedActions(5, TimeUnit.SECONDS);
			} catch (java.util.concurrent.TimeoutException e) {
				throw new AssertionError("Не дождались delayed auto-popup actions после ручного ввода $person.", e);
			}
			com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents();
		});

		com.intellij.openapi.editor.Document hostDocument =
				com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf);
		assertNotNull("Должен существовать document исходного Parser3-файла", hostDocument);
		String text = hostDocument.getText();
		assertTrue("Ручной ввод должен оставить в файле $person.: " + text,
				text.contains("$person."));

		Lookup activeLookup = LookupManager.getActiveLookup(myFixture.getEditor());
		assertNotNull("После ручного ввода $person. должен автоматически открыться popup с ключами", activeLookup);
		assertTrue("Popup после ручного ввода $person. должен быть LookupImpl", activeLookup instanceof LookupImpl);
		waitForDefaultLookupSelection((LookupImpl) activeLookup);
		List<String> names = activeLookup.getItems().stream()
				.map(LookupElement::getLookupString)
				.collect(Collectors.toList());
		assertTrue("Popup после ручного ввода $person. должен содержать login, есть: " + names,
				names.contains("login"));
		assertTrue("Popup после ручного ввода $person. должен содержать fields., есть: " + names,
				names.contains("fields."));
		assertFalse("После ручной точки не должен оставаться popup корневой переменной person.: " + names,
				names.contains("person."));
		LookupElement selected = activeLookup.getCurrentItem();
		assertNotNull("После открытия popup первый пункт должен быть выбран", selected);
		assertEquals("Выбранным должен быть первый пункт popup", names.get(0), selected.getLookupString());
	}

	public void testDollarCompletion_typedDotWithActiveVariablePopupDoesNotAcceptPartialVariable() throws Throwable {
		try {
			setDocumentRoot("www");
		} catch (java.io.IOException e) {
			throw new AssertionError("Не удалось настроить document_root для обезличенного fixture", e);
		}
		replaceParser3FileInDir("www/auto.p",
				"@auto[]\n" +
						"$person[\n" +
						"\t$.login[test_user]\n" +
						"\t$.fields[^hash::create[]]\n" +
						"]\n" +
						"$person.fields.remote[test_value]\n");

		createParser3FileInDir("www/_mod/active_variable_popup_dot.p",
				"@CLASS\n" +
						"TestModule\n" +
						"\n" +
						"@OPTIONS\n" +
						"locals\n" +
						"\n" +
						"@create[]\n" +
						"$pers<caret>\n");
		VirtualFile vf = myFixture.findFileInTempDir("www/_mod/active_variable_popup_dot.p");
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		Lookup variableLookup = myFixture.getLookup();
		assertNotNull("После $pers должен открыться popup переменных", variableLookup);
		assertTrue("Popup переменных должен быть LookupImpl", variableLookup instanceof LookupImpl);
		LookupElement personElement = Arrays.stream(myFixture.getLookupElements())
				.filter(e -> "person.".equals(e.getLookupString()))
				.findFirst()
				.orElse(null);
		assertNotNull("Popup переменных должен содержать person.", personElement);
		((LookupImpl) variableLookup).setCurrentItem(personElement);

		TestModeFlags.runWithFlag(CompletionAutoPopupHandler.ourTestingAutopopup, Boolean.TRUE, () -> {
			myFixture.type(".");
			com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents();
			try {
				AutoPopupController.getInstance(getProject()).waitForDelayedActions(5, TimeUnit.SECONDS);
			} catch (java.util.concurrent.TimeoutException e) {
				throw new AssertionError("Не дождались delayed auto-popup actions после точки в активном popup", e);
			}
			com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents();
		});

		com.intellij.openapi.editor.Document hostDocument =
				com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf);
		assertNotNull("Должен существовать document исходного Parser3-файла", hostDocument);
		String text = hostDocument.getText();
		assertTrue("Ручная точка в активном popup не должна подтверждать частичный $pers: " + text,
				text.contains("$pers."));
		assertFalse("Ручная точка в активном popup не должна вставлять выбранный $person: " + text,
				text.contains("$person."));
	}

	public void testDollarCompletion_continueTypingVariableNameThenDotOpensHashKeysPopup() throws Throwable {
		try {
			setDocumentRoot("www");
		} catch (java.io.IOException e) {
			throw new AssertionError("Не удалось настроить document_root для обезличенного fixture", e);
		}
		replaceParser3FileInDir("www/auto.p",
				"@auto[]\n" +
						"$person[\n" +
						"\t$.login[test_user]\n" +
						"\t$.fields[^hash::create[]]\n" +
						"]\n" +
						"$person.fields.remote[test_value]\n");

		createParser3FileInDir("www/_mod/continue_typing_variable_dot.p",
				"@CLASS\n" +
						"TestModule\n" +
						"\n" +
						"@OPTIONS\n" +
						"locals\n" +
						"\n" +
						"@create[]\n" +
						"<caret>\n");
		VirtualFile vf = myFixture.findFileInTempDir("www/_mod/continue_typing_variable_dot.p");
		myFixture.configureFromExistingVirtualFile(vf);

		TestModeFlags.runWithFlag(CompletionAutoPopupHandler.ourTestingAutopopup, Boolean.TRUE, () -> {
			myFixture.type("$per");
			com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents();
			try {
				AutoPopupController.getInstance(getProject()).waitForDelayedActions(5, TimeUnit.SECONDS);
			} catch (java.util.concurrent.TimeoutException e) {
				throw new AssertionError("Не дождались popup переменных после ручного ввода $per", e);
			}
			com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents();

			myFixture.type("son.");
			com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents();
			try {
				AutoPopupController.getInstance(getProject()).waitForDelayedActions(5, TimeUnit.SECONDS);
			} catch (java.util.concurrent.TimeoutException e) {
				throw new AssertionError("Не дождались popup ключей после допечатки son.", e);
			}
			com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents();
		});

		com.intellij.openapi.editor.Document hostDocument =
				com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf);
		assertNotNull("Должен существовать document исходного Parser3-файла", hostDocument);
		String text = hostDocument.getText();
		assertTrue("Допечатка son. после $per должна оставить в файле $person.: " + text,
				text.contains("$person."));
		assertFalse("Допечатка son. после $per не должна дублировать точку: " + text,
				text.contains("$person.."));

		Lookup activeLookup = LookupManager.getActiveLookup(myFixture.getEditor());
		assertNotNull("После допечатки son. должен автоматически открыться popup с ключами", activeLookup);
		assertTrue("Popup после допечатки son. должен быть LookupImpl", activeLookup instanceof LookupImpl);
		waitForDefaultLookupSelection((LookupImpl) activeLookup);
		List<String> names = activeLookup.getItems().stream()
				.map(LookupElement::getLookupString)
				.collect(Collectors.toList());
		assertTrue("Popup после допечатки son. должен содержать login, есть: " + names,
				names.contains("login"));
		assertTrue("Popup после допечатки son. должен содержать fields., есть: " + names,
				names.contains("fields."));
		assertFalse("После допечатки son. не должен оставаться popup корневой переменной person.: " + names,
				names.contains("person."));
		LookupElement selected = activeLookup.getCurrentItem();
		assertNotNull("После открытия popup первый пункт должен быть выбран", selected);
		assertEquals("Выбранным должен быть первый пункт popup", names.get(0), selected.getLookupString());
	}

	public void testDollarCompletion_continueExistingVariableNameThenDotOpensHashKeysPopup() throws Throwable {
		try {
			setDocumentRoot("www");
		} catch (java.io.IOException e) {
			throw new AssertionError("Не удалось настроить document_root для обезличенного fixture", e);
		}
		replaceParser3FileInDir("www/auto.p",
				"@auto[]\n" +
						"$person[\n" +
						"\t$.login[test_user]\n" +
						"\t$.fields[^hash::create[]]\n" +
						"]\n" +
						"$person.fields.remote[test_value]\n");

		createParser3FileInDir("www/_mod/continue_existing_variable_dot.p",
				"@CLASS\n" +
						"TestModule\n" +
						"\n" +
						"@OPTIONS\n" +
						"locals\n" +
						"\n" +
						"@create[]\n" +
						"$per<caret>\n");
		VirtualFile vf = myFixture.findFileInTempDir("www/_mod/continue_existing_variable_dot.p");
		myFixture.configureFromExistingVirtualFile(vf);

		TestModeFlags.runWithFlag(CompletionAutoPopupHandler.ourTestingAutopopup, Boolean.TRUE, () -> {
			myFixture.type("son.");
			com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents();
			try {
				AutoPopupController.getInstance(getProject()).waitForDelayedActions(5, TimeUnit.SECONDS);
			} catch (java.util.concurrent.TimeoutException e) {
				throw new AssertionError("Не дождались popup ключей после допечатки существующего $per до $person.", e);
			}
			com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents();
		});

		com.intellij.openapi.editor.Document hostDocument =
				com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf);
		assertNotNull("Должен существовать document исходного Parser3-файла", hostDocument);
		String text = hostDocument.getText();
		assertTrue("Допечатка существующего $per должна оставить в файле $person.: " + text,
				text.contains("$person."));
		assertFalse("Допечатка существующего $per не должна дублировать точку: " + text,
				text.contains("$person.."));

		Lookup activeLookup = LookupManager.getActiveLookup(myFixture.getEditor());
		assertNotNull("После допечатки существующего $per должен автоматически открыться popup с ключами", activeLookup);
		assertTrue("Popup после допечатки существующего $per должен быть LookupImpl", activeLookup instanceof LookupImpl);
		waitForDefaultLookupSelection((LookupImpl) activeLookup);
		List<String> names = activeLookup.getItems().stream()
				.map(LookupElement::getLookupString)
				.collect(Collectors.toList());
		assertTrue("Popup после допечатки существующего $per должен содержать login, есть: " + names,
				names.contains("login"));
		assertTrue("Popup после допечатки существующего $per должен содержать fields., есть: " + names,
				names.contains("fields."));
		assertFalse("После допечатки существующего $per не должен оставаться popup корневой переменной person.: " + names,
				names.contains("person."));
		LookupElement selected = activeLookup.getCurrentItem();
		assertNotNull("После открытия popup первый пункт должен быть выбран", selected);
		assertEquals("Выбранным должен быть первый пункт popup", names.get(0), selected.getLookupString());
	}

	public void testDollarCompletion_continueExistingVariableNameInModuleAutoThenDotOpensHashKeysPopup() throws Throwable {
		try {
			setDocumentRoot("www");
		} catch (java.io.IOException e) {
			throw new AssertionError("Не удалось настроить document_root для обезличенного fixture", e);
		}
		replaceParser3FileInDir("www/auto.p",
				"@USE\n" +
						"search.p\n" +
						"\n" +
						"@auto[]\n" +
						"$person[^getPerson[]]\n");
		createParser3FileInDir("www/search.p",
				"@getPerson[]\n" +
						"$result[\n" +
						"\t$.login[test_user]\n" +
						"\t$.fields[^hash::create[]]\n" +
						"]\n" +
						"$result.fields.remote[test_value]\n" +
						"^result\n");

		createParser3FileInDir("www/_mod/auto.p",
				"@CLASS\n" +
						"TestModule\n" +
						"\n" +
						"@OPTIONS\n" +
						"locals\n" +
						"\n" +
						"@create[]\n" +
						"$per<caret>\n" +
						"$person.subgroups[^hash::create[]]\n" +
						"\n" +
						"@get_rights[person]\n" +
						"^if(!$person){$person[$MAIN:person]}\n");
		VirtualFile vf = myFixture.findFileInTempDir("www/_mod/auto.p");
		myFixture.configureFromExistingVirtualFile(vf);

		TestModeFlags.runWithFlag(CompletionAutoPopupHandler.ourTestingAutopopup, Boolean.TRUE, () -> {
			myFixture.type("son.");
			com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents();
			try {
				AutoPopupController.getInstance(getProject()).waitForDelayedActions(5, TimeUnit.SECONDS);
			} catch (java.util.concurrent.TimeoutException e) {
				throw new AssertionError("Не дождались popup ключей после допечатки $per в модульном auto.p", e);
			}
			com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents();
		});

		com.intellij.openapi.editor.Document hostDocument =
				com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf);
		assertNotNull("Должен существовать document исходного Parser3-файла", hostDocument);
		String text = hostDocument.getText();
		assertTrue("Допечатка существующего $per в модульном auto.p должна оставить в файле $person.: " + text,
				text.contains("$person."));
		assertFalse("Допечатка существующего $per в модульном auto.p не должна дублировать точку: " + text,
				text.contains("$person.."));

		Lookup activeLookup = LookupManager.getActiveLookup(myFixture.getEditor());
		assertNotNull("После допечатки существующего $per в модульном auto.p должен автоматически открыться popup с ключами", activeLookup);
		assertTrue("Popup после допечатки существующего $per в модульном auto.p должен быть LookupImpl", activeLookup instanceof LookupImpl);
		waitForDefaultLookupSelection((LookupImpl) activeLookup);
		List<String> names = activeLookup.getItems().stream()
				.map(LookupElement::getLookupString)
				.collect(Collectors.toList());
		assertTrue("Popup после допечатки существующего $per в модульном auto.p должен содержать login, есть: " + names,
				names.contains("login"));
		assertTrue("Popup после допечатки существующего $per в модульном auto.p должен содержать fields., есть: " + names,
				names.contains("fields."));
		assertFalse("После допечатки существующего $per в модульном auto.p не должен оставаться popup корневой переменной person.: " + names,
				names.contains("person."));
		LookupElement selected = activeLookup.getCurrentItem();
		assertNotNull("После открытия popup первый пункт должен быть выбран", selected);
		assertEquals("Выбранным должен быть первый пункт popup", names.get(0), selected.getLookupString());
	}

	public void testDollarCompletion_typedDotAfterExactVariableWithActiveRootPopupHidesRootPopup() throws Throwable {
		try {
			setDocumentRoot("www");
		} catch (java.io.IOException e) {
			throw new AssertionError("Не удалось настроить document_root для обезличенного fixture", e);
		}
		replaceParser3FileInDir("www/auto.p",
				"@auto[]\n" +
						"$person[\n" +
						"\t$.login[test_user]\n" +
						"\t$.fields[^hash::create[]]\n" +
						"]\n" +
						"$person.fields.remote[test_value]\n");

		createParser3FileInDir("www/_mod/active_exact_variable_popup_dot.p",
				"@CLASS\n" +
						"TestModule\n" +
						"\n" +
						"@OPTIONS\n" +
						"locals\n" +
						"\n" +
						"@create[]\n" +
						"$person<caret>\n");
		VirtualFile vf = myFixture.findFileInTempDir("www/_mod/active_exact_variable_popup_dot.p");
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		Lookup variableLookup = myFixture.getLookup();
		assertNotNull("После $person должен открыться popup переменных", variableLookup);
		assertTrue("Popup переменных должен быть LookupImpl", variableLookup instanceof LookupImpl);
		LookupElement personElement = Arrays.stream(myFixture.getLookupElements())
				.filter(e -> "person.".equals(e.getLookupString()))
				.findFirst()
				.orElse(null);
		assertNotNull("Popup переменных должен содержать person.", personElement);
		((LookupImpl) variableLookup).setCurrentItem(personElement);

		TestModeFlags.runWithFlag(CompletionAutoPopupHandler.ourTestingAutopopup, Boolean.TRUE, () -> {
			myFixture.type(".");
			com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents();
			try {
				AutoPopupController.getInstance(getProject()).waitForDelayedActions(5, TimeUnit.SECONDS);
			} catch (java.util.concurrent.TimeoutException e) {
				throw new AssertionError("Не дождались popup ключей после точки при точном $person и активном root-popup", e);
			}
			com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents();
		});

		com.intellij.openapi.editor.Document hostDocument =
				com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf);
		assertNotNull("Должен существовать document исходного Parser3-файла", hostDocument);
		String text = hostDocument.getText();
		assertTrue("Точка при точном root-popup должна оставить в файле $person.: " + text,
				text.contains("$person."));
		assertFalse("Точка при точном root-popup не должна дублировать точку: " + text,
				text.contains("$person.."));

		Lookup activeLookup = LookupManager.getActiveLookup(myFixture.getEditor());
		assertNotNull("После точки при точном root-popup должен открыться popup с ключами", activeLookup);
		assertTrue("Popup после $person. должен быть LookupImpl", activeLookup instanceof LookupImpl);
		waitForDefaultLookupSelection((LookupImpl) activeLookup);
		List<String> names = activeLookup.getItems().stream()
				.map(LookupElement::getLookupString)
				.collect(Collectors.toList());
		assertTrue("Popup после $person. должен содержать login, есть: " + names,
				names.contains("login"));
		assertTrue("Popup после $person. должен содержать fields., есть: " + names,
				names.contains("fields."));
		assertFalse("После точки не должен оставаться popup корневой переменной person.: " + names,
				names.contains("person."));
		LookupElement selected = activeLookup.getCurrentItem();
		assertNotNull("После открытия popup первый пункт должен быть выбран", selected);
		assertEquals("Выбранным должен быть первый пункт popup", names.get(0), selected.getLookupString());
	}

	public void testDollarCompletion_bareDollarDotDoesNotInsertSelfFromActivePopup() {
		configureParser3TextFile("test_bare_dollar_dot_does_not_insert_self.p",
				"@main[]\n" +
						"$data[\n" +
						"\t$.xxx[]\n" +
						"\t$<caret>\n" +
						"]\n");

		myFixture.complete(CompletionType.BASIC);
		Lookup variableLookup = myFixture.getLookup();
		assertNotNull("После голого $ должен открыться popup переменных", variableLookup);
		assertTrue("Popup переменных должен быть LookupImpl", variableLookup instanceof LookupImpl);
		LookupElement selfElement = Arrays.stream(myFixture.getLookupElements())
				.filter(e -> "self.".equals(e.getLookupString()))
				.findFirst()
				.orElse(null);
		assertNotNull("Popup переменных должен содержать self.", selfElement);
		((LookupImpl) variableLookup).setCurrentItem(selfElement);

		myFixture.type(".");
		com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents();

		String text = myFixture.getEditor().getDocument().getText();
		assertTrue("Точка после голого $ должна остаться ручным вводом $.: " + text,
				text.contains("\t$.\n"));
		assertFalse("Точка после голого $ не должна выбирать $self: " + text,
				text.contains("$self"));
	}

	public void testDollarCompletion_tabDoesNotInsertSelfFromActivePopup() {
		configureParser3TextFile("test_tab_does_not_insert_self.p",
				"@main[]\n" +
						"$data[\n" +
						"\t$.xxx[]\n" +
						"\t$<caret>\n" +
						"]\n");

		myFixture.complete(CompletionType.BASIC);
		Lookup variableLookup = myFixture.getLookup();
		assertNotNull("После голого $ должен открыться popup переменных", variableLookup);
		assertTrue("Popup переменных должен быть LookupImpl", variableLookup instanceof LookupImpl);
		LookupElement selfElement = Arrays.stream(myFixture.getLookupElements())
				.filter(e -> "self.".equals(e.getLookupString()))
				.findFirst()
				.orElse(null);
		assertNotNull("Popup переменных должен содержать self.", selfElement);
		((LookupImpl) variableLookup).setCurrentItem(selfElement);

		myFixture.performEditorAction(IdeActions.ACTION_CHOOSE_LOOKUP_ITEM_REPLACE);
		com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents();

		String text = myFixture.getEditor().getDocument().getText();
		assertTrue("Tab в активном popup должен остаться обычным Tab, а не выбором completion: " + text,
				text.contains("\t$\t\n"));
		assertFalse("Tab в активном popup не должен выбирать $self: " + text,
				text.contains("$self"));
		assertNull("Tab должен закрыть popup без вставки", LookupManager.getActiveLookup(myFixture.getEditor()));
	}

	public void testEditorTabWorksWithoutCompletionPopup() {
		configureParser3TextFile("test_editor_tab_without_popup.p",
				"@main[]\n" +
						"<caret>$value[test]\n");
		assertNull("Перед обычным Tab popup не должен быть открыт", LookupManager.getActiveLookup(myFixture.getEditor()));

		myFixture.performEditorAction(IdeActions.ACTION_CHOOSE_LOOKUP_ITEM_REPLACE);
		com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents();

		String text = myFixture.getEditor().getDocument().getText();
		assertTrue("Обычный Tab без popup должен вставлять табуляцию/отступ: " + text,
				text.contains("\n\t$value[test]"));
	}

	public void testDollarCompletion_typedDotWithWrongCurrentItemKeepsTypedVariable() throws Throwable {
		try {
			setDocumentRoot("www");
		} catch (java.io.IOException e) {
			throw new AssertionError("Не удалось настроить document_root для обезличенного fixture", e);
		}
		replaceParser3FileInDir("www/auto.p",
				"@auto[]\n" +
						"$personName[test_value]\n" +
						"$person[\n" +
						"\t$.login[test_user]\n" +
						"\t$.fields[^hash::create[]]\n" +
						"]\n" +
						"$person.fields.remote[test_value]\n");

		createParser3FileInDir("www/_mod/active_variable_popup_wrong_item_dot.p",
				"@CLASS\n" +
						"TestModule\n" +
						"\n" +
						"@OPTIONS\n" +
						"locals\n" +
						"\n" +
						"@create[]\n" +
						"$person<caret>\n");
		VirtualFile vf = myFixture.findFileInTempDir("www/_mod/active_variable_popup_wrong_item_dot.p");
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		Lookup variableLookup = myFixture.getLookup();
		assertNotNull("После $person должен открыться popup переменных", variableLookup);
		assertTrue("Popup переменных должен быть LookupImpl", variableLookup instanceof LookupImpl);
		LookupElement personNameElement = Arrays.stream(myFixture.getLookupElements())
				.filter(e -> "personName".equals(e.getLookupString()))
				.findFirst()
				.orElse(null);
		assertNotNull("Popup переменных должен содержать соседнюю переменную personName", personNameElement);
		((LookupImpl) variableLookup).setCurrentItem(personNameElement);

		TestModeFlags.runWithFlag(CompletionAutoPopupHandler.ourTestingAutopopup, Boolean.TRUE, () -> {
			myFixture.type(".");
			com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents();
			try {
				AutoPopupController.getInstance(getProject()).waitForDelayedActions(5, TimeUnit.SECONDS);
			} catch (java.util.concurrent.TimeoutException e) {
				throw new AssertionError("Не дождались popup ключей после точки при неверном текущем пункте lookup", e);
			}
			com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents();
		});

		com.intellij.openapi.editor.Document hostDocument =
				com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf);
		assertNotNull("Должен существовать document исходного Parser3-файла", hostDocument);
		String text = hostDocument.getText();
		assertTrue("Точка в активном popup должна оставить вручную набранный $person.: " + text,
				text.contains("$person."));
		assertFalse("Точка в активном popup не должна выбирать соседнюю переменную: " + text,
				text.contains("$personName"));

		Lookup activeLookup = LookupManager.getActiveLookup(myFixture.getEditor());
		assertNotNull("После точки при неверном текущем пункте lookup должен открыться popup с ключами", activeLookup);
		assertTrue("Popup после $person. должен быть LookupImpl", activeLookup instanceof LookupImpl);
		waitForDefaultLookupSelection((LookupImpl) activeLookup);
		List<String> names = activeLookup.getItems().stream()
				.map(LookupElement::getLookupString)
				.collect(Collectors.toList());
		assertTrue("Popup после $person. должен содержать login, есть: " + names,
				names.contains("login"));
		assertTrue("Popup после $person. должен содержать fields., есть: " + names,
				names.contains("fields."));
		assertFalse("После точки не должен оставаться popup корневой переменной person.: " + names,
				names.contains("person."));
		LookupElement selected = activeLookup.getCurrentItem();
		assertNotNull("После открытия popup первый пункт должен быть выбран", selected);
		assertEquals("Выбранным должен быть первый пункт popup", names.get(0), selected.getLookupString());
	}

	public void testDollarHashKeyDotExplicitCompletionInsideMethodArgumentsRanksUserTemplatesLast() {
		String base =
				"@main[]\n" +
						"$data[\n" +
						"\t$.x[]\n" +
						"\t$.y[]\n" +
						"]\n";

		String[][] cases = {
				{"square", "^render[$data.<caret>]"},
				{"square_nested", "^render[^wrap[$data.<caret>]]"},
				{"round", "^if($data.<caret>){}"},
				{"round_nested", "^if(^check[$data.<caret>]){}"},
				{"curly", "^render{$data.<caret>}"},
				{"mixed_brackets", "^render[^wrap($data.<caret>)]"}
		};

		for (String[] completionCase : cases) {
			List<String> completions = getCompletions(
					"hash_key_dot_user_templates_last_" + completionCase[0] + ".p",
					base + completionCase[1] + "\n"
			);
			assertHashKeyDotCompletionWithUserTemplates(completionCase[0], completions);
		}
	}

	public void testDollarHashKeyDotAutoPopupInsideMethodArgumentShowsDotUserTemplatesLast() throws Throwable {
		configureParser3TextFile("hash_key_dot_auto_dot_user_templates_last.p",
				"@main[]\n" +
						"$data[\n" +
						"\t$.x[]\n" +
						"\t$.y[]\n" +
						"]\n" +
						"^render[$data<caret>]\n");

		TestModeFlags.runWithFlag(CompletionAutoPopupHandler.ourTestingAutopopup, Boolean.TRUE, () -> {
			myFixture.type(".");
			com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents();
			try {
				AutoPopupController.getInstance(getProject()).waitForDelayedActions(5, TimeUnit.SECONDS);
			} catch (java.util.concurrent.TimeoutException e) {
				throw new AssertionError("Не дождались popup ключей после ручного ввода точки внутри аргумента метода", e);
			}
			com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents();
		});

		Lookup activeLookup = LookupManager.getActiveLookup(myFixture.getEditor());
		assertNotNull("После $data. внутри аргумента метода должен открыться popup с ключами", activeLookup);
		assertTrue("Popup после $data. должен быть LookupImpl", activeLookup instanceof LookupImpl);
		waitForDefaultLookupSelection((LookupImpl) activeLookup);
		List<String> names = activeLookup.getItems().stream()
				.map(LookupElement::getLookupString)
				.collect(Collectors.toList());
		assertHashKeyDotAutoCompletionWithDotUserTemplates("autopopup", names);
	}

	public void testDollarHashKeyDotDelayedAutoPopupKeepsDotUserTemplatesLastAfterTypedPrefix() throws Throwable {
		withUserTemplates(createDotUserTemplates(), () -> {
			configureParser3TextFile("hash_key_dot_delayed_prefix_filters_templates.p",
					"@main[]\n" +
							"$data[\n" +
							"\t$.id[]\n" +
							"\t$.name[]\n" +
							"]\n" +
							"$data<caret>\n");

			TestModeFlags.runWithFlag(CompletionAutoPopupHandler.ourTestingAutopopup, Boolean.TRUE, () -> {
				myFixture.type(".id");
				com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents();
				try {
					AutoPopupController.getInstance(getProject()).waitForDelayedActions(5, TimeUnit.SECONDS);
				} catch (java.util.concurrent.TimeoutException e) {
					throw new AssertionError("Не дождались delayed popup после быстрого ввода $data.id", e);
				}
				com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents();
			});

			Lookup activeLookup = LookupManager.getActiveLookup(myFixture.getEditor());
			assertNotNull("После быстрого ввода $data.id должен открыться popup с ключом id", activeLookup);
			assertTrue("Popup после $data.id должен быть LookupImpl", activeLookup instanceof LookupImpl);
			waitForDefaultLookupSelection((LookupImpl) activeLookup);
			List<String> names = activeLookup.getItems().stream()
					.map(LookupElement::getLookupString)
					.collect(Collectors.toList());
			assertTrue("Delayed popup после $data.id должен содержать ключ id, есть: " + names,
					names.contains("id"));
			assertRealKeysAboveDotTemplates(
					"Delayed popup после $data.id",
					names,
					List.of("id"),
					List.of("foreach[]", "mail:send[]"));
			LookupElement selected = activeLookup.getCurrentItem();
			assertNotNull("После delayed popup первый пункт должен быть выбран", selected);
			assertEquals("Выбранным должен быть первый пункт popup", names.get(0), selected.getLookupString());
		});
	}

	public void testDollarVariableAutoPopupInsideIfArgumentAfterPlainText() throws Throwable {
		configureParser3TextFile("if_argument_dollar_variable_autopopup.p",
				"@main[]\n" +
						"$data[\n" +
						"\t$.xxx[]\n" +
						"\t$.yyy[]\n" +
						"]\n" +
						"^if(def <caret>)\n");

		TestModeFlags.runWithFlag(CompletionAutoPopupHandler.ourTestingAutopopup, Boolean.TRUE, () -> {
			myFixture.type("$da");
			com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents();
			try {
				AutoPopupController.getInstance(getProject()).waitForDelayedActions(5, TimeUnit.SECONDS);
			} catch (java.util.concurrent.TimeoutException e) {
				throw new AssertionError("Не дождались popup переменных после $da внутри ^if(def ...)", e);
			}
			com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents();
		});

		Lookup activeLookup = LookupManager.getActiveLookup(myFixture.getEditor());
		assertNotNull("После $da внутри ^if(def ...) должен открыться popup переменных", activeLookup);
		assertTrue("Popup после $da должен быть LookupImpl", activeLookup instanceof LookupImpl);
		waitForDefaultLookupSelection((LookupImpl) activeLookup);
		List<String> names = activeLookup.getItems().stream()
				.map(LookupElement::getLookupString)
				.collect(Collectors.toList());
		assertTrue("Popup после $da должен содержать переменную data, есть: " + names,
				names.contains("data."));
		LookupElement selected = activeLookup.getCurrentItem();
		assertNotNull("После открытия popup первый пункт должен быть выбран", selected);
		assertEquals("Выбранным должен быть первый пункт popup", names.get(0), selected.getLookupString());
	}

	public void testDollarVariableFastTypedTerminatorsDoNotAcceptRootPopup() throws Throwable {
		String[][] cases = {
				{"space", " ", "$da "},
				{"square", "]", "$da]"},
				{"round", ")", "$da)"}
		};

		for (String[] completionCase : cases) {
			configureParser3TextFile("dollar_variable_terminator_no_lookup_accept_" + completionCase[0] + ".p",
					"@main[]\n" +
							"$data[\n" +
							"\t$.id[]\n" +
							"]\n" +
							"<caret>\n");

			typeWithAutoPopup("$da" + completionCase[1],
					"Не дождались delayed actions после быстрого ввода $da" + completionCase[1]);

			String text = myFixture.getEditor().getDocument().getText();
			assertTrue("Быстрый ввод должен оставить ручной текст " + completionCase[2] + ": " + text,
					text.contains(completionCase[2]));
			assertFalse("Терминатор не должен выбирать root-переменную data.: " + text,
					text.contains("$data."));
			assertNoActiveLookup("После терминатора " + completionCase[1] + " не должен оставаться поздний popup");
		}
	}

	public void testDollarHashKeyDotSemicolonDoesNotInsertDotUserTemplateFromActivePopup() {
		Parser3UserTemplate foreachTemplate = createUserTemplate(
				"foreach",
				"Перебор элементов массива или хеша",
				".foreach[key;value]{\n\t<CURSOR>\n}"
		);

		withUserTemplates(List.of(foreachTemplate), () -> {
			configureParser3TextFile("hash_key_dot_semicolon_hides_lookup.p",
					"@main[]\n" +
							"$data[\n" +
							"\t$.x[]\n" +
							"\t$.y[]\n" +
							"]\n" +
							"$data.<caret>\n");

			myFixture.complete(CompletionType.BASIC);
			LookupElement[] elements = myFixture.getLookupElements();
			assertNotNull("После $data. должен быть активный popup", elements);
			LookupElement foreach = Arrays.stream(elements)
					.filter(e -> "foreach[]".equals(e.getLookupString()))
					.findFirst()
					.orElse(null);
			assertNotNull("В popup должен быть dot-шаблон .foreach[]", foreach);
			myFixture.getLookup().setCurrentItem(foreach);

			myFixture.type(";");
			com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents();

			String text = myFixture.getEditor().getDocument().getText();
			assertTrue("Символ ; должен быть напечатан как обычный текст: " + text,
					text.contains("$data.;"));
			assertFalse("Символ ; не должен выбирать dot-шаблон .foreach[]: " + text,
					text.contains("$data.foreach"));
		});
	}

	public void testDollarMainCompletion_usesParentAutoShapeWithoutLocalParamLeak() {
		try {
			setDocumentRoot("www");
		} catch (java.io.IOException e) {
			throw new AssertionError("Не удалось настроить document_root для обезличенного fixture", e);
		}
		replaceParser3FileInDir("cgi/auto.p",
				"@auto[]\n" +
						"$config[^hash::create[]]\n" +
						"$MAIN:user.itemFlag\n");
		setMainAuto("cgi/auto.p");
		replaceParser3FileInDir("www/auto.p",
				"@USE\n" +
						"search.p\n" +
						"\n" +
						"@auto[]\n" +
						"$user[^getUser[]]\n" +
						"\n" +
						"@getUser[][locals]\n" +
						"$local[^get_users[\n" +
						"\t$.itemFlag(true)\n" +
						"]]\n" +
						"$local[^local.at[first]]\n" +
						"$local.itemFlag(true)\n" +
						"$result[$local]\n");
		replaceParser3FileInDir("www/search.p",
				"@get_users[settings][locals]\n" +
						"$result[^hash::sql{SELECT user_id, login FROM users}]\n" +
						"\n" +
						"@search[settings]\n" +
						"$result[\n" +
						"\t$.itemFlag[$MAIN:user.itemFlag]\n" +
						"]\n");

		String content =
				"@CLASS\n" +
						"TestModule\n" +
						"\n" +
						"@OPTIONS\n" +
						"locals\n" +
						"\n" +
						"@create[]\n" +
						"$MAIN:user.<caret> -- здесь нужен только обезличенный ключ itemFlag\n" +
						"\n" +
						"@get_rights[user]\n" +
						"^if(!$user){$user[$MAIN:user]}\n" +
						"\n" +
						"$user.groups[^hash::create[]]\n";
		List<String> completions = getCompletions("www/_mod/auto.p", content);

		assertTrue("$MAIN:user. должен показывать ключ из parent auto/search, есть: " + completions,
				completions.contains("itemFlag"));
		assertFalse("$MAIN:user. не должен подтягивать SQL-поля результата parent auto, есть: " + completions,
				completions.contains("login"));
		assertFalse("$MAIN:user. не должен подтягивать ключ локального параметра нижнего метода, есть: " + completions,
				completions.contains("groups"));
	}

	public void testDollarMainCompletion_doesNotUseSyntheticReadChainWhenExplicitMainShapeExists() {
		try {
			setDocumentRoot("www");
		} catch (java.io.IOException e) {
			throw new AssertionError("Не удалось настроить document_root для обезличенного fixture", e);
		}
		replaceParser3FileInDir("www/auto.p",
				"@USE\n" +
						"search.p\n" +
						"\n" +
						"@auto[]\n" +
						"$user[^hash::create[]]\n" +
						"$user.login[test_user]\n");
		replaceParser3FileInDir("www/search.p",
				"@search[]\n" +
						"$result[\n" +
						"\t$.itemFlag($MAIN:user.itemFlag)\n" +
						"]\n");

		String content =
				"@CLASS\n" +
						"TestModule\n" +
						"\n" +
						"@OPTIONS\n" +
						"locals\n" +
						"\n" +
						"@create[]\n" +
						"$MAIN:user.<caret>\n";
		List<String> completions = getCompletions("www/_mod/auto.p", content);

		assertTrue("$MAIN:user. должен брать реальные ключи из parent auto, есть: " + completions,
				completions.contains("login"));
		assertFalse("$MAIN:user. не должен брать synthetic read-chain из $.itemFlag($MAIN:user.itemFlag), есть: " + completions,
				completions.contains("itemFlag"));
	}

	public void testDollarCompletion_readOnlyLocalChainDoesNotShadowMainHashShape() {
		try {
			setDocumentRoot("www");
		} catch (java.io.IOException e) {
			throw new AssertionError("Не удалось настроить document_root для обезличенного fixture", e);
		}
		replaceParser3FileInDir("www/auto.p",
				"@auto[]\n" +
						"$user[\n" +
						"\t$.login[test_user]\n" +
						"\t$.email[test@example.com]\n" +
						"\t$.roles[^hash::create[]]\n" +
						"]\n");

		String content =
				"@CLASS\n" +
						"TestModule\n" +
						"\n" +
						"@OPTIONS\n" +
						"locals\n" +
						"\n" +
						"@create[]\n" +
						"$user.login\n" +
						"$user.<caret>\n";
		List<String> completions = getCompletions("www/_mod/auto.p", content);

		assertTrue("Read-only $user.login не должен затенять MAIN hash key email, есть: " + completions,
				completions.contains("email"));
		assertTrue("Read-only $user.login должен сохранить MAIN hash key roles, есть: " + completions,
				completions.contains("roles"));
		assertEquals("login не должен дублироваться: " + completions,
				1, java.util.Collections.frequency(completions, "login"));
	}

	public void testDollarCompletion_repeatedReadOnlyLocalChainKeepsMainHashShape() {
		try {
			setDocumentRoot("www");
		} catch (java.io.IOException e) {
			throw new AssertionError("Не удалось настроить document_root для обезличенного fixture", e);
		}
		replaceParser3FileInDir("www/auto.p",
				"@auto[]\n" +
						"$user[\n" +
						"\t$.login[test_user]\n" +
						"\t$.email[test@example.com]\n" +
						"\t$.settings[$.enabled(true)]\n" +
						"]\n");

		String content =
				"@CLASS\n" +
						"TestModule\n" +
						"\n" +
						"@OPTIONS\n" +
						"locals\n" +
						"\n" +
						"@create[]\n" +
						"$user.login\n" +
						"$user.login\n" +
						"$user.<caret>\n";
		List<String> completions = getCompletions("www/_mod/auto.p", content);

		assertTrue("Повторный read-only $user.login не должен оставлять только login, есть: " + completions,
				completions.contains("email"));
		assertTrue("Повторный read-only $user.login должен сохранить settings, есть: " + completions,
				completions.contains("settings."));
		assertEquals("login не должен дублироваться: " + completions,
				1, java.util.Collections.frequency(completions, "login"));
	}

	public void testDollarCompletion_sourceCopyThenReadOnlyChainKeepsCopiedMainHashShape() {
		try {
			setDocumentRoot("www");
		} catch (java.io.IOException e) {
			throw new AssertionError("Не удалось настроить document_root для обезличенного fixture", e);
		}
		replaceParser3FileInDir("www/auto.p",
				"@auto[]\n" +
						"$user[\n" +
						"\t$.login[test_user]\n" +
						"\t$.email[test@example.com]\n" +
						"\t$.rights[$.read(true)]\n" +
						"]\n");

		String content =
				"@CLASS\n" +
						"TestModule\n" +
						"\n" +
						"@OPTIONS\n" +
						"locals\n" +
						"\n" +
						"@getRights[user]\n" +
						"^if(!$user){$user[$MAIN:user]}\n" +
						"$user.login\n" +
						"$user.groups[^hash::create[]]\n" +
						"$user.<caret>\n";
		List<String> completions = getCompletions("www/_mod/auto.p", content);

		assertTrue("Source-copy $user[$MAIN:user] должен сохранить email после read-only ключа, есть: " + completions,
				completions.contains("email"));
		assertTrue("Source-copy $user[$MAIN:user] должен сохранить rights после read-only ключа, есть: " + completions,
				completions.contains("rights."));
		assertTrue("Локальная additive-запись должна добавиться поверх source-copy, есть: " + completions,
				completions.contains("groups"));
		assertEquals("login не должен дублироваться: " + completions,
				1, java.util.Collections.frequency(completions, "login"));
	}

	public void testDollarCompletion_selfHashReadOnlyChainDoesNotNarrowExplicitSelfShape() {
		String content =
				"@CLASS\n" +
						"TestModule\n" +
						"\n" +
						"@OPTIONS\n" +
						"locals\n" +
						"\n" +
						"@create[]\n" +
						"$self.user[\n" +
						"\t$.login[test_user]\n" +
						"\t$.email[test@example.com]\n" +
						"\t$.rights[$.read(true)]\n" +
						"]\n" +
						"$self.user.login\n" +
						"$self.user.<caret>\n";
		List<String> completions = getCompletions("self_hash_read_only_chain_keeps_shape.p", content);

		assertTrue("$self.user.login не должен сужать явный $self.user до login, есть: " + completions,
				completions.contains("email"));
		assertTrue("$self.user.login должен сохранить rights, есть: " + completions,
				completions.contains("rights."));
		assertEquals("login не должен дублироваться: " + completions,
				1, java.util.Collections.frequency(completions, "login"));
	}

	public void testDollarCompletion_tableColumnReadOnlyChainDoesNotNarrowTableShape() {
		String content =
				"@main[]\n" +
						"# на основе parser3/tests/012.html: чтение table column не меняет table shape\n" +
						"$items[^table::create{user_id\tlogin\temail\n1\ttest_user\ttest@example.com}]\n" +
						"$items.login\n" +
						"$items.<caret>\n";
		List<String> completions = getCompletions("table_column_read_only_chain_keeps_shape.p", content);

		assertTrue("Read-only $items.login не должен удалить колонку email, есть: " + completions,
				completions.contains("email"));
		assertTrue("Read-only $items.login не должен удалить колонку user_id, есть: " + completions,
				completions.contains("user_id"));
		assertEquals("login не должен дублироваться: " + completions,
				1, java.util.Collections.frequency(completions, "login"));
	}

	public void testDollarSelfCompletion_usesClassShapeFromOtherFileWithoutLocalParamLeak() {
		try {
			setDocumentRoot("www");
		} catch (java.io.IOException e) {
			throw new AssertionError("Не удалось настроить document_root для обезличенного fixture", e);
		}
		replaceParser3FileInDir("www/classes/TestModuleShape.p",
				"@CLASS\n" +
						"TestModule\n" +
						"\n" +
						"@OPTIONS\n" +
						"locals\n" +
						"\n" +
						"@create[]\n" +
						"$self.user.itemFlag(true)\n" +
						"$user[^hash::sql{SELECT user_id, login FROM users}]\n" +
						"$user.login[test_user]\n");
		replaceParser3FileInDir("www/classes/OtherModuleShape.p",
				"@CLASS\n" +
						"OtherModule\n" +
						"\n" +
						"@create[]\n" +
						"$self.user.foreignFlag(true)\n");

		String content =
				"@USE\n" +
						"../classes/TestModuleShape.p\n" +
						"../classes/OtherModuleShape.p\n" +
						"\n" +
						"@CLASS\n" +
						"TestModule\n" +
						"\n" +
						"@OPTIONS\n" +
						"locals\n" +
						"\n" +
						"@create[]\n" +
						"$self.user.<caret> -- здесь нужен только обезличенный class key itemFlag\n" +
						"\n" +
						"@get_rights[user]\n" +
						"^if(!$user){$user[$self.user]}\n" +
						"$user.groups[^hash::create[]]\n";
		List<String> completions = getCompletions("www/_mod/auto.p", content);

		assertTrue("$self.user. должен показывать ключ из того же класса другого файла, есть: " + completions,
				completions.contains("itemFlag"));
		assertFalse("$self.user. не должен подтягивать SQL-поля простой $user из другого файла, есть: " + completions,
				completions.contains("login"));
		assertFalse("$self.user. не должен подтягивать ключ локального параметра нижнего метода, есть: " + completions,
				completions.contains("groups"));
		assertFalse("$self.user. не должен подтягивать ключ self.user из другого класса, есть: " + completions,
				completions.contains("foreignFlag"));
	}

	private static void waitForFocusedLookup(@NotNull LookupImpl lookup) {
		for (int i = 0; i < 30; i++) {
			com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents();
			if (lookup.getLookupFocusDegree() == LookupFocusDegree.FOCUSED) {
				return;
			}
			try {
				Thread.sleep(30);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new AssertionError("Прервали ожидание focused-состояния lookup", e);
			}
		}
		com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents();
	}

	private static void waitForDefaultLookupSelection(@NotNull LookupImpl lookup) {
		for (int i = 0; i < 30; i++) {
			com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents();
			if (lookup.getCurrentItem() != null
					&& lookup.getSelectedIndex() == 0
					&& lookup.getLookupFocusDegree() == LookupFocusDegree.FOCUSED) {
				return;
			}
			try {
				Thread.sleep(30);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new AssertionError("Прервали ожидание выбора первого пункта lookup", e);
			}
		}
		com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents();
	}

	public void testDollarCompletion_infersReadChainFromBraceVariableInCallArgs() {
		String content =
				"@main[]\n" +
						"# реальный минимальный кейс brace variable в call args\n" +
						"^call[a=$item.x.name&b=${item.x.var}]\n" +
						"$item.x.<caret>\n";
		List<String> completions = getCompletions("read_chain_brace_var_in_call_args.p", content);

		assertTrue("После ${item.x.var} должен появиться completion 'var', есть: " + completions,
				completions.contains("var"));
	}

	public void testDollarCompletion_infersBracketHashKeyFromReadChainUsage() {
		String content =
				"@main[]\n" +
						"# реальный минимальный кейс bracket hash key из read-chain\n" +
						"$item.[data x].var[x]\n" +
						"$item.<caret>\n";
		List<String> completions = getCompletions("read_chain_bracket_hash_key.p", content);

		assertTrue("После чтения $item.[data x].var[x] должен появиться completion '[data x].', есть: " + completions,
				completions.contains("[data x]."));
	}

	public void testDollarCompletion_resolvesBracketHashKeyChain() {
		String content =
				"@main[]\n" +
						"# реальный минимальный кейс resolve bracket hash key chain\n" +
						"$str[$item.[data x].var]\n" +
						"$item.[data x].<caret>\n";
		List<String> completions = getCompletions("read_chain_bracket_hash_key_resolve.p", content);

		assertTrue("После чтения $item.[data x].var должен появиться completion 'var', есть: " + completions,
				completions.contains("var"));
	}

	public void testDollarCompletion_doesNotLeakIncompleteBracketHashKeySegmentAtEof() {
		String content =
				"@main[]\n" +
						"# реальный минимальный кейс недописанного bracket hash key на EOF\n" +
						"$str[$item.[data x].varx]\n" +
						"$item.[data x].v<caret>\n";
		List<String> completions = getCompletions("read_chain_bracket_hash_key_incomplete_eof.p", content);

		assertTrue("Ключ из предыдущего чтения должен оставаться в completion, есть: " + completions,
				completions.contains("varx"));
		assertFalse("Недописанный сегмент на последней строке не должен попадать в completion сам на себя: " + completions,
				completions.contains("v"));
	}

	public void testDollarCompletion_hidesOnlyCurrentWeakExactPrefix() {
		String content =
				"@main[]\n" +
						"# реальный минимальный кейс hide current weak exact prefix\n" +
						"$item.data.var[x]\n" +
						"$item.data.va<caret>\n";
		List<String> completions = getCompletions("weak_exact_prefix_hidden.p", content);

		assertTrue("Доказанный key 'var' должен оставаться в completion, есть: " + completions,
				completions.contains("var"));
		assertFalse("Текущий недописанный слабый key 'va' не должен показываться сам на себя: " + completions,
				completions.contains("va"));
	}

	public void testDollarCompletion_keepsWeakCandidateForShorterPrefix() {
		String content =
				"@main[]\n" +
						"# реальный минимальный кейс keep weak candidate for shorter prefix\n" +
						"$item.data.var[x]\n" +
						"$item.data.v<caret>a\n";
		List<String> completions = getCompletions("weak_shorter_prefix_visible.p", content);

		assertTrue("Доказанный key 'var' должен оставаться в completion, есть: " + completions,
				completions.contains("var"));
		assertTrue("Слабый key 'va' должен показываться на более коротком префиксе 'v': " + completions,
				completions.contains("va"));
	}

	public void testDollarCompletion_showsExactPrefixWhenProvenElsewhereInFile() {
		String content =
				"@main[]\n" +
						"# реальный минимальный кейс exact prefix proven elsewhere\n" +
						"$str[param=$item.data.va]\n" +
						"$item.data.va<caret>\n";
		List<String> completions = getCompletions("weak_exact_prefix_proven_elsewhere.p", content);

		assertTrue("Если key 'va' уже доказан в другом завершённом месте файла, он должен показываться: " + completions,
				completions.contains("va"));
	}

	public void testDollarCompletion_keepsSiblingBranchWhenOtherWeakBranchExists() {
		String content =
				"@main[]\n" +
						"# реальный минимальный кейс sibling branch with weak branch\n" +
						"$str[str=${item.[data x].varx}]\n" +
						"$item.[data x].v\n" +
						"\n" +
						"$item.data.var[x]\n" +
						"$item.data.va<caret>\n";
		List<String> completions = getCompletions("weak_sibling_branch_preserved.p", content);

		assertTrue("Доказанный sibling key 'var' не должен теряться из-за другой weak-ветки того же root: " + completions,
				completions.contains("var"));
		assertFalse("Текущий недописанный слабый key 'va' не должен показываться сам на себя: " + completions,
				completions.contains("va"));
	}

	public void testDollarCompletion_keepsSiblingBranchInResolvedChain() {
		String content =
				"@main[]\n" +
						"# реальный минимальный кейс resolved sibling branch\n" +
						"$str[str=${item.[data x].varx}]\n" +
						"$item.[data x].v\n" +
						"\n" +
						"$item.data.var[x]\n" +
						"$item.data.va<caret>\n";
		createParser3FileInDir("www/weak_sibling_branch_resolve_chain.p", content);

		VirtualFile vf = myFixture.findFileInTempDir("www/weak_sibling_branch_resolve_chain.p");
		assertNotNull("Файл теста должен быть создан", vf);

		java.util.List<VirtualFile> visibleFiles =
				ru.artlebedev.parser3.visibility.P3VisibilityService.getInstance(getProject()).getVisibleFiles(vf);
		ru.artlebedev.parser3.index.P3VariableIndex variableIndex =
				ru.artlebedev.parser3.index.P3VariableIndex.getInstance(getProject());
		int cursorOffset = content.indexOf("$item.data.va<caret>") + "$item.data.va".length();

		ru.artlebedev.parser3.index.P3VariableIndex.ChainResolveInfo chainInfo =
				variableIndex.resolveEffectiveChain("item.data", visibleFiles, vf, cursorOffset);

		assertNotNull("Цепочка item.data должна успешно резолвиться", chainInfo);
		assertNotNull("У цепочки item.data должны быть hashKeys", chainInfo.hashKeys);
		assertTrue("Ключ var должен оставаться в resolved chain несмотря на другую weak-ветку: " + chainInfo.hashKeys.keySet(),
				chainInfo.hashKeys.containsKey("var"));
	}

	public void testDollarCompletion_keepsSiblingBranchInParsedFileData() {
		String content =
				"@main[]\n" +
						"# реальный минимальный кейс parsed file data sibling branch\n" +
						"$str[str=${item.[data x].varx}]\n" +
						"$item.[data x].v\n" +
						"\n" +
						"$item.data.var[x]\n" +
						"$item.data.va\n";

		java.util.Map<String, java.util.List<ru.artlebedev.parser3.index.P3VariableFileIndex.VariableTypeInfo>> fileData =
				ru.artlebedev.parser3.index.P3VariableFileIndex.parseVariablesFromText(content);

		java.util.List<ru.artlebedev.parser3.index.P3VariableFileIndex.VariableTypeInfo> itemInfos = fileData.get("item");
		assertNotNull("Для переменной item должны быть записи в сыром fileData", itemInfos);

		java.util.Map<String, ru.artlebedev.parser3.index.HashEntryInfo> merged = new java.util.LinkedHashMap<>();
		for (ru.artlebedev.parser3.index.P3VariableFileIndex.VariableTypeInfo info : itemInfos) {
			if (info.hashKeys == null) continue;
			for (java.util.Map.Entry<String, ru.artlebedev.parser3.index.HashEntryInfo> entry : info.hashKeys.entrySet()) {
				ru.artlebedev.parser3.index.HashEntryInfo oldInfo = merged.get(entry.getKey());
				if (oldInfo != null && oldInfo.nestedKeys != null && entry.getValue().nestedKeys != null) {
					java.util.Map<String, ru.artlebedev.parser3.index.HashEntryInfo> mergedNested =
							new java.util.LinkedHashMap<>(oldInfo.nestedKeys);
					mergedNested.putAll(entry.getValue().nestedKeys);
					merged.put(entry.getKey(), oldInfo.withNestedKeys(mergedNested));
				} else {
					merged.put(entry.getKey(), entry.getValue());
				}
			}
		}

		assertTrue("В сыром fileData у item должен быть top-level key data: " + merged.keySet(),
				merged.containsKey("data"));
		assertNotNull("У key data должны быть nested keys", merged.get("data").nestedKeys);
		assertTrue("В сыром fileData у data должен сохраняться key var: " + merged.get("data").nestedKeys.keySet(),
				merged.get("data").nestedKeys.containsKey("var"));
	}

	public void testDollarCompletion_keepsAdditiveHashKeysForMethodCallRoot() {
		String content =
				"@main[]\n" +
						"# реальный минимальный кейс additive hash keys for method-call root\n" +
						"$item[^TestApi.call[action=get_item&id=$self.item_id&set_item_data=1]]\n" +
						"$item.x[asdf]\n" +
						"$it<caret>\n";
		java.util.List<String> completions = getCompletions("test_api_call_additive_hash_root.p", content);

		assertTrue("После additive-ключа у method-call root должен оставаться completion 'item.': " + completions,
				completions.contains("item."));
	}

	public void testDollarCompletion_methodCallRootShowsAdditiveHashKeyOnDot() {
		String content =
				"@main[]\n" +
						"# реальный минимальный кейс method-call root dot completion\n" +
						"$item[^TestApi.call[action=get_item&id=$self.item_id&set_item_data=1]]\n" +
						"$item.x[asdf]\n" +
						"$item.<caret>\n";
		java.util.List<String> completions = getCompletions("test_api_call_additive_hash_dot_completion.p", content);

		assertTrue("У $item. должен быть key x после additive-записи, есть: " + completions,
				completions.contains("x"));
	}

	public void testDollarCompletion_methodResultFromHashSqlAtKeepsAllSqlFields() {
		String content =
				"@main[]\n" +
						"# обезличенный минимальный кейс method-result hash::sql после at[first]\n" +
						"$account[^get_user[]]\n" +
						"$account.<caret>\n" +
						"\n" +
						"@get_user[][locals]\n" +
						"$login[test_user]\n" +
						"^server{\n" +
						"\t$account[^hash::sql{\n" +
						"\t\tSELECT\n" +
						"\t\t\tuser_id, user_id, login, email, display_name, full_name, score\n" +
						"\t\tFROM\n" +
						"\t\t\tusers\n" +
						"\t\tWHERE\n" +
						"\t\t\tlogin = '$login'\n" +
						"\t}]\n" +
						"\n" +
						"\t$account[^account.at[first]]\n" +
						"\t$account.score($account.score)\n" +
						"\t$account.id[$account.user_id]\n" +
						"\t$account.name[$account.display_name $account.full_name]\n" +
						"\t$account.avatar[https://test.com/users/$account.user_id/photo.jpg]\n" +
						"\t$result[$account]\n" +
						"\t^if(!$account){^return[$result]}\n" +
						"\t$account.groups[^hash::sql{\n" +
						"\t\tSELECT\n" +
						"\t\t\ti.item_id, i.item_id AS id, i.name\n" +
						"\t\tFROM\n" +
						"\t\t\titems AS i\n" +
						"\t}]\n" +
						"}\n";
		java.util.List<String> completions = getCompletions("method_result_hash_sql_at_fields.p", content);

		assertTrue("Должен быть SQL-field login после ^account.at[first], есть: " + completions,
				completions.contains("login"));
		assertTrue("Должен быть SQL-field email после ^account.at[first], есть: " + completions,
				completions.contains("email"));
		assertTrue("Должен быть SQL-field user_id после ^account.at[first], есть: " + completions,
				completions.contains("user_id"));
		assertTrue("Должен быть SQL-field display_name после ^account.at[first], есть: " + completions,
				completions.contains("display_name"));
		assertTrue("Должен быть SQL-field full_name после ^account.at[first], есть: " + completions,
				completions.contains("full_name"));
		assertTrue("Должен быть SQL-field score после ^account.at[first], есть: " + completions,
				completions.contains("score"));
		assertTrue("Должен быть добавленный key id, есть: " + completions,
				completions.contains("id"));
		assertTrue("Должен быть добавленный key name, есть: " + completions,
				completions.contains("name"));
		assertTrue("Должен быть добавленный key avatar, есть: " + completions,
				completions.contains("avatar"));
	}

	public void testDollarCompletion_tableSelectReadChainKeepsAllColumns() {
		String content =
				"@main[]\n" +
						"# реальный минимальный кейс table.select сохраняет columns\n" +
						"$comments[^table::sql{SELECT id, parent_id, reply_id, question_id, account_id, score, comment FROM comments}]\n" +
						"$comment[^comments.select($comments.parent_id == 1)]\n" +
						"$sql[reply_id=$comment.id]\n" +
						"$comment.<caret>\n";
		java.util.List<String> completions = getCompletions("table_select_read_chain_keeps_columns.p", content);

		assertTrue("Должен остаться столбец id, есть: " + completions, completions.contains("id"));
		assertTrue("Должен остаться столбец parent_id, есть: " + completions, completions.contains("parent_id"));
		assertTrue("Должен остаться столбец reply_id, есть: " + completions, completions.contains("reply_id"));
		assertTrue("Должен остаться столбец question_id, есть: " + completions, completions.contains("question_id"));
		assertTrue("Должен остаться столбец account_id, есть: " + completions, completions.contains("account_id"));
		assertTrue("Должен остаться столбец score, есть: " + completions, completions.contains("score"));
		assertTrue("Должен остаться столбец comment, есть: " + completions, completions.contains("comment"));
	}

	public void testDollarCompletion_parser407RealTableSelectKeepsSourceColumnsAfterReadChain() {
		String content =
				"@main[]\n" +
						"# на основе parser3/tests/407.html\n" +
						"$log[^table::create{cpu\turl\n" +
						"0.2200\t/novosibirsk/retail/\n" +
						"0.1600\t/nizhninovgorod/\n" +
						"0.0200\t/_/rss/_rss.html\n" +
						"}]\n" +
						"$hit[^log.select[;cpu]($cpu<0.05)]\n" +
						"$url[$hit.url]\n" +
						"$hit.<caret>\n";
		java.util.List<String> completions = getCompletions("parser407_real_table_select_keeps_columns.p", content);

		assertTrue("Реальный Parser3 407: select должен сохранить колонку cpu, есть: " + completions,
				completions.contains("cpu"));
		assertTrue("Реальный Parser3 407: select должен сохранить колонку url, есть: " + completions,
				completions.contains("url"));
	}

	public void testDollarCompletion_tableSelectReadChainInsideLaterSqlKeepsAllColumns() {
		String content =
				"@main[]\n" +
						"# реальный минимальный кейс read-chain внутри последующего sql\n" +
						"$list[\n" +
						"\t$.id[1]\n" +
						"]\n" +
						"$comments[^table::sql{\n" +
						"\tSELECT c.id, c.parent_id, c.reply_id, c.question_id, c.account_id, c.score, c.comment\n" +
						"\tFROM comments AS c\n" +
						"}]\n" +
						"$comment[^comments.select($comments.parent_id == $list.id)]\n" +
						"$items[^table::sql{SELECT question_id, score, comment FROM scores WHERE reply_id = '$comment.id'}]\n" +
						"$comment.<caret>\n";
		java.util.List<String> completions = getCompletions("table_select_later_sql_read_chain_keeps_columns.p", content);

		assertTrue("Должен остаться столбец id, есть: " + completions, completions.contains("id"));
		assertTrue("Должен остаться столбец parent_id, есть: " + completions, completions.contains("parent_id"));
		assertTrue("Должен остаться столбец reply_id, есть: " + completions, completions.contains("reply_id"));
		assertTrue("Должен остаться столбец question_id, есть: " + completions, completions.contains("question_id"));
		assertTrue("Должен остаться столбец account_id, есть: " + completions, completions.contains("account_id"));
		assertTrue("Должен остаться столбец score, есть: " + completions, completions.contains("score"));
		assertTrue("Должен остаться столбец comment, есть: " + completions, completions.contains("comment"));
	}

	public void testDollarCompletion_hashSqlAtReadChainKeepsAllSqlFields() {
		String content =
				"@main[]\n" +
						"# обезличенный минимальный кейс hash::sql at read-chain\n" +
						"$person[^hash::sql{SELECT user_id, user_id, login, email, display_name, full_name FROM users}]\n" +
						"$row[^person.at[first]]\n" +
						"$str[$row.user_id]\n" +
						"$row.<caret>\n";
		java.util.List<String> completions = getCompletions("hash_sql_at_read_chain_keeps_fields.p", content);

		assertTrue("Должен остаться SQL-field user_id, есть: " + completions, completions.contains("user_id"));
		assertTrue("Должен остаться SQL-field login, есть: " + completions, completions.contains("login"));
		assertTrue("Должен остаться SQL-field email, есть: " + completions, completions.contains("email"));
		assertTrue("Должен остаться SQL-field display_name, есть: " + completions, completions.contains("display_name"));
		assertTrue("Должен остаться SQL-field full_name, есть: " + completions, completions.contains("full_name"));
	}

	public void testDollarCompletion_arraySqlAtReadChainKeepsAllSqlFields() {
		String content =
				"@main[]\n" +
						"# обезличенный минимальный кейс array::sql at read-chain\n" +
						"$people[^array::sql{SELECT user_id, login, email, display_name, full_name FROM users}]\n" +
						"$person[^people.at[first]]\n" +
						"$str[$person.user_id]\n" +
						"$person.<caret>\n";
		java.util.List<String> completions = getCompletions("array_sql_at_read_chain_keeps_fields.p", content);

		assertTrue("Должен остаться SQL-field user_id, есть: " + completions, completions.contains("user_id"));
		assertTrue("Должен остаться SQL-field login, есть: " + completions, completions.contains("login"));
		assertTrue("Должен остаться SQL-field email, есть: " + completions, completions.contains("email"));
		assertTrue("Должен остаться SQL-field display_name, есть: " + completions, completions.contains("display_name"));
		assertTrue("Должен остаться SQL-field full_name, есть: " + completions, completions.contains("full_name"));
	}

	public void testDollarCompletion_explicitOverrideAfterReadChainWins() {
		String content =
				"@main[]\n" +
						"# реальный минимальный кейс explicit override after read-chain\n" +
						"$comments[^table::sql{SELECT id, parent_id, reply_id FROM comments}]\n" +
						"$comment[^comments.select($comments.parent_id == 1)]\n" +
						"$sql[reply_id=$comment.id]\n" +
						"$comment[\n" +
						"\t$.local_only[]\n" +
						"]\n" +
						"$comment.<caret>\n";
		java.util.List<String> completions = getCompletions("explicit_override_after_read_chain_wins.p", content);

		assertTrue("После полного override должен быть local_only, есть: " + completions, completions.contains("local_only"));
		assertFalse("После полного override не должен оставаться старый столбец parent_id, есть: " + completions, completions.contains("parent_id"));
		assertFalse("После полного override не должен оставаться synthetic key id, есть: " + completions, completions.contains("id"));
	}

	public void testDollarCompletion_hashCreateOverrideAfterTableSelectDoesNotKeepOldColumns() {
		String content =
				"@main[]\n" +
						"# реальный минимальный кейс hash::create override после table.select\n" +
						"$list[\n" +
						"\t$.id[1]\n" +
						"]\n" +
						"$comments[^table::sql{\n" +
						"\tSELECT c.id, c.parent_id, c.reply_id, c.question_id, c.account_id, c.score, c.comment,\n" +
						"\t\tlk.pipedrive_user_id, lk.pipedrive_org_id\n" +
						"\tFROM comments AS c, accounts AS p, account_links AS lk\n" +
						"\tWHERE c.parent_id in (^list.menu{$list.id}[,])\n" +
						"\t\tAND p.id = c.account_id\n" +
						"\t\tAND lk.id = p.parent_id\n" +
						"}]\n" +
						"$comment[^comments.select($comments.parent_id == $list.id)]\n" +
						"$comment[^hash::create[]]\n" +
						"$items[^table::sql{SELECT question_id, score, comment FROM scores WHERE reply_id = '$comment.id'}]\n" +
						"$comment.<caret>\n";
		java.util.List<String> completions = getCompletions("hash_create_override_after_table_select.p", content);

		assertTrue("Ctrl+Space после явного override пустого hash должен показывать пользовательские шаблоны, есть: " + completions,
				completions.contains("foreach[]"));
		assertTrue("После позднего $comment.id должен оставаться доказанный synthetic key id, есть: " + completions,
				completions.contains("id"));
		assertFalse("После явного override не должен оставаться старый столбец parent_id, есть: " + completions,
				completions.contains("parent_id"));
		assertFalse("После явного override не должен оставаться старый столбец reply_id, есть: " + completions,
				completions.contains("reply_id"));
		assertFalse("После явного override не должен оставаться старый столбец question_id, есть: " + completions,
				completions.contains("question_id"));
		assertFalse("После явного override не должен оставаться старый столбец account_id, есть: " + completions,
				completions.contains("account_id"));
		assertFalse("После явного override не должен оставаться старый столбец score, есть: " + completions,
				completions.contains("score"));
		assertFalse("После явного override не должен оставаться старый столбец comment, есть: " + completions,
				completions.contains("comment"));
	}

	public void testDollarCompletion_methodCallRootInfersBraceReadChain() {
		String content =
				"@main[]\n" +
						"# реальный минимальный кейс method-call root brace read-chain\n" +
						"$item[^TestApi.call[action=get_item&id=$self.item_id&set_item_data=1]]\n" +
						"$str[item_id=${item.data.id}]\n" +
						"$item.data.<caret>\n";
		java.util.List<String> completions = getCompletions("test_api_call_brace_read_chain.p", content);

		assertTrue("После ${item.data.id} у $item.data. должен быть key id, есть: " + completions,
				completions.contains("id"));
	}

	public void testDollarCompletion_methodCallRootInfersDefBracketReadChain() {
		String content =
				"@main[]\n" +
						"# реальный минимальный кейс method-call root def bracket read-chain\n" +
						"$item[^TestApi.call[action=get_item&id=$self.item_id&set_item_data=1]]\n" +
						"^if(def $item.[data name].var){\n" +
						"\t$noop[1]\n" +
						"}\n" +
						"$item.[data name].<caret>\n";
		java.util.List<String> completions = getCompletions("test_api_call_def_bracket_read_chain.p", content);

		assertTrue("После def $item.[data name].var у $item.[data name]. должен быть key var, есть: " + completions,
				completions.contains("var"));
	}

	public void testForeachValueParam_unknownSource_noDotInDollarCompletion() {
		createParser3FileInDir("www/test_foreach_value_unknown_no_dot.p",
				"@main[]\n" +
						"# реальный минимальный кейс неизвестного foreach source без точки\n" +
						"^var.foreach[key;value]{\n" +
						"    $<caret>\n" +
						"}\n"
		);
		VirtualFile vf = myFixture.findFileInTempDir("www/test_foreach_value_unknown_no_dot.p");
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("$ внутри foreach должен видеть value-переменную", elements);
		List<String> names = Arrays.stream(elements).map(LookupElement::getLookupString).collect(Collectors.toList());
		assertTrue("Должен быть 'value', есть: " + names, names.contains("value"));
		assertFalse("Не должно быть 'value.' без известной структуры, есть: " + names, names.contains("value."));
	}

	public void testForeachFieldValue_unknownSource_noDotInDollarCompletion() {
		createParser3FileInDir("www/test_foreach_field_value_unknown_no_dot.p",
				"@main[]\n" +
						"# реальный минимальный кейс field foreach source без точки\n" +
						"^var.data.foreach[;yyy]{\n" +
						"    $<caret>\n" +
						"}\n"
		);
		VirtualFile vf = myFixture.findFileInTempDir("www/test_foreach_field_value_unknown_no_dot.p");
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("$ внутри field.foreach должен видеть value-переменную", elements);
		List<String> names = Arrays.stream(elements).map(LookupElement::getLookupString).collect(Collectors.toList());
		assertTrue("Должен быть 'yyy', есть: " + names, names.contains("yyy"));
		assertFalse("Не должно быть 'yyy.' без доказанной структуры, есть: " + names, names.contains("yyy."));
	}

	public void testForeachValueParam_sameNameDoesNotLeakDotAcrossLoops() {
		createParser3FileInDir("www/test_foreach_value_same_name_no_leak_dot.p",
				"@main[]\n" +
						"# реальный минимальный кейс без утечки точки между foreach с одинаковым value\n" +
						"^var.foreach[;xxx]{\n" +
						"    $xxx.val[11]\n" +
						"}\n" +
						"^var2.foreach[;xxx]{\n" +
						"    $<caret>\n" +
						"}\n"
		);
		VirtualFile vf = myFixture.findFileInTempDir("www/test_foreach_value_same_name_no_leak_dot.p");
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("$ внутри второго foreach должен видеть свой xxx", elements);
		List<String> names = Arrays.stream(elements).map(LookupElement::getLookupString).collect(Collectors.toList());
		assertTrue("Должен быть 'xxx', есть: " + names, names.contains("xxx"));
		assertFalse("Не должно быть 'xxx.' из-за утечки из другого foreach, есть: " + names, names.contains("xxx."));
	}

	public void testForeachValueParam_sameNameDoesNotLeakHashTypeAcrossLoops() {
		createParser3FileInDir("www/test_foreach_value_same_name_no_leak_type.p",
				"@main[]\n" +
						"# реальный минимальный кейс без утечки hash-типа между foreach с одинаковым value\n" +
						"^var.foreach[;xxx]{\n" +
						"    $xxx.val[11]\n" +
						"}\n" +
						"^var2.foreach[;xxx]{\n" +
						"    $<caret>\n" +
						"}\n"
		);
		VirtualFile vf = myFixture.findFileInTempDir("www/test_foreach_value_same_name_no_leak_type.p");
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("$ внутри второго foreach должен видеть свой xxx", elements);

		LookupElement xxx = Arrays.stream(elements)
				.filter(e -> "xxx".equals(e.getLookupString()))
				.findFirst()
				.orElse(null);
		assertNotNull("Должен быть lookup element 'xxx'", xxx);

		LookupElementPresentation presentation = LookupElementPresentation.renderElement(xxx);
		assertTrue("У второго xxx не должно быть type text, есть: " + presentation.getTypeText(),
				presentation.getTypeText() == null || presentation.getTypeText().isEmpty());
	}

	public void testForeachValueParam_laterUnknownOverridesEarlierHashOutsideLoops() {
		createParser3FileInDir("www/test_foreach_value_later_unknown_overrides_outside.p",
				"@main[]\n" +
						"# реальный минимальный кейс later unknown overrides earlier hash outside loops\n" +
						"^var.foreach[;xxx]{\n" +
						"    $xxx.val[11]\n" +
						"}\n" +
						"^var2.foreach[;xxx]{\n" +
						"    $xxx\n" +
						"}\n" +
						"$<caret>\n"
		);
		VirtualFile vf = myFixture.findFileInTempDir("www/test_foreach_value_later_unknown_overrides_outside.p");
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("Снаружи после второго foreach должен быть виден xxx", elements);

		LookupElement xxx = Arrays.stream(elements)
				.filter(e -> "xxx".equals(e.getLookupString()))
				.findFirst()
				.orElse(null);
		assertNotNull("Должен быть lookup element 'xxx'", xxx);

		LookupElementPresentation presentation = LookupElementPresentation.renderElement(xxx);
		assertTrue("Снаружи после второго foreach у xxx не должно быть type text, есть: " + presentation.getTypeText(),
				presentation.getTypeText() == null || presentation.getTypeText().isEmpty());
	}

	public void testForeachValueParam_laterUnknownOverridesEarlierHashWithTypedPropertyAccess() {
		createParser3FileInDir("www/test_foreach_value_later_unknown_overrides_typed_access.p",
				"@main[]\n" +
						"# реальный минимальный кейс later unknown overrides earlier hash with typed access\n" +
						"^var.foreach[;xxx]{\n" +
						"    $xxx.val[11]\n" +
						"}\n" +
						"^var2.foreach[;xxx]{\n" +
						"    $xxx\n" +
						"}\n" +
						"$xx<caret>x.val\n"
		);
		VirtualFile vf = myFixture.findFileInTempDir("www/test_foreach_value_later_unknown_overrides_typed_access.p");
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("В typed-access сценарии должен быть виден xxx", elements);

		LookupElement xxx = Arrays.stream(elements)
				.filter(e -> "xxx".equals(e.getLookupString()))
				.findFirst()
				.orElse(null);
		assertNotNull("Должен быть lookup element 'xxx'", xxx);

		LookupElementPresentation presentation = LookupElementPresentation.renderElement(xxx);
		assertTrue("В typed-access сценарии у xxx не должно быть type text, есть: " + presentation.getTypeText(),
				presentation.getTypeText() == null || presentation.getTypeText().isEmpty());
	}

	public void testTryCatch_exceptionVisibleOnlyInsideSecondBlock() {
		createParser3FileInDir("www/test_try_catch_exception_scope.p",
				"@main[]\n" +
						"# реальный минимальный кейс видимости exception внутри второго блока try\n" +
						"^try{\n" +
						"    ^broken[]\n" +
						"}{\n" +
						"    $ex<caret>\n" +
						"}\n" +
						"$\n"
		);
		VirtualFile vf = myFixture.findFileInTempDir("www/test_try_catch_exception_scope.p");
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("$ внутри второго блока ^try должен видеть exception", elements);
		List<String> names = Arrays.stream(elements).map(LookupElement::getLookupString).collect(Collectors.toList());
		assertTrue("Должен быть 'exception.', есть: " + names, names.contains("exception."));
	}

	public void testTryCatch_exceptionNotVisibleAfterCatchBlock() {
		createParser3FileInDir("www/test_try_catch_exception_not_visible_after.p",
				"@main[]\n" +
						"# реальный минимальный кейс невидимости exception после второго блока try\n" +
						"^try{\n" +
						"    ^broken[]\n" +
						"}{\n" +
						"    $exception.comment\n" +
						"    $exception.custom_marker(true)\n" +
						"}\n" +
						"$<caret>\n"
		);
		VirtualFile vf = myFixture.findFileInTempDir("www/test_try_catch_exception_not_visible_after.p");
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		List<String> names = elements == null ? List.of() :
				Arrays.stream(elements).map(LookupElement::getLookupString).collect(Collectors.toList());
		assertFalse("После второго блока ^try не должно быть 'exception.', есть: " + names, names.contains("exception."));
		assertFalse("После второго блока ^try не должно быть 'exception', есть: " + names, names.contains("exception"));
		assertFalse("После второго блока ^try не должен протекать custom_marker из catch-$exception, есть: " + names,
				names.contains("custom_marker"));
	}

	public void testTryCatch_existingExceptionRestoredAfterCatchBlock() {
		createParser3FileInDir("www/test_try_catch_exception_restore_existing.p",
				"@main[]\n" +
						"$exception[^hash::create[]]\n" +
						"$exception.outer(true)\n" +
						"^try{\n" +
						"\t^broken[]\n" +
						"}{\n" +
						"\t$exception.handled(true)\n" +
						"\t$exception.custom_marker(true)\n" +
						"}\n" +
						"$exception.<caret>\n"
		);
		VirtualFile vf = myFixture.findFileInTempDir("www/test_try_catch_exception_restore_existing.p");
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("После catch должен восстановиться ранее существующий $exception", elements);
		List<String> names = Arrays.stream(elements).map(LookupElement::getLookupString).collect(Collectors.toList());
		assertTrue("После catch должен быть ключ outer из прежнего $exception, есть: " + names,
				names.contains("outer"));
		assertFalse("После catch не должен протекать ключ handled из catch-$exception, есть: " + names,
				names.contains("handled"));
		assertFalse("После catch не должен протекать пользовательский ключ custom_marker из catch-$exception, есть: " + names,
				names.contains("custom_marker"));
	}

	public void testTryCatch_exceptionCustomKeyVisibleInsideCatchBlock() {
		createParser3FileInDir("www/test_try_catch_exception_custom_key_visible.p",
				"@main[]\n" +
						"^try{\n" +
						"\t^broken[]\n" +
						"}{\n" +
						"\t$exception.custom_marker(true)\n" +
						"\t$exception.<caret>\n" +
						"}\n"
		);
		VirtualFile vf = myFixture.findFileInTempDir("www/test_try_catch_exception_custom_key_visible.p");
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("$exception. внутри catch должен показывать стандартные и пользовательские ключи", elements);
		List<String> names = Arrays.stream(elements).map(LookupElement::getLookupString).collect(Collectors.toList());
		assertTrue("Внутри catch должны быть стандартные ключи exception, есть: " + names,
				names.contains("type"));
		assertTrue("Внутри catch должен быть пользовательский ключ custom_marker, есть: " + names,
				names.contains("custom_marker"));
	}

	public void testTryCatch_nestedExceptionRestoresOuterCustomKeys() {
		createParser3FileInDir("www/test_try_catch_nested_exception_restore_outer_custom.p",
				"@main[]\n" +
						"^try{\n" +
						"\t^broken[]\n" +
						"}{\n" +
						"\t$exception.outer_marker(true)\n" +
						"\t^try{\n" +
						"\t\t^broken2[]\n" +
						"\t}{\n" +
						"\t\t$exception.handled(true)\n" +
						"\t\t$exception.inner_marker(true)\n" +
						"\t}\n" +
						"\t$exception.<caret>\n" +
						"}\n"
		);
		VirtualFile vf = myFixture.findFileInTempDir("www/test_try_catch_nested_exception_restore_outer_custom.p");
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("После вложенного catch должен остаться видимым внешний $exception", elements);
		List<String> names = Arrays.stream(elements).map(LookupElement::getLookupString).collect(Collectors.toList());
		assertTrue("Во внешнем catch должен остаться пользовательский ключ outer_marker, есть: " + names,
				names.contains("outer_marker"));
		assertFalse("Во внешний catch не должен протекать ключ inner_marker из вложенного catch, есть: " + names,
				names.contains("inner_marker"));
	}

	public void testTryCatch_nestedExceptionDoesNotLeakInnerCatchException() {
		createParser3FileInDir("www/test_try_catch_nested_exception_no_inner_leak.p",
				"@main[]\n" +
						"^try{\n" +
						"\t^broken[]\n" +
						"}{\n" +
						"\t^try{\n" +
						"\t\t^broken2[]\n" +
						"\t}{\n" +
						"\t\t$exception.handled(true)\n" +
						"\t\t$exception.inner_marker(true)\n" +
						"\t}\n" +
						"\t$exception.<caret>\n" +
						"}\n"
		);
		VirtualFile vf = myFixture.findFileInTempDir("www/test_try_catch_nested_exception_no_inner_leak.p");
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("После вложенного catch должен остаться видимым внешний $exception", elements);
		List<String> names = Arrays.stream(elements).map(LookupElement::getLookupString).collect(Collectors.toList());
		assertTrue("Во внешнем catch должны быть стандартные ключи внешнего $exception, есть: " + names,
				names.contains("type"));
		assertFalse("Во внешний catch не должен протекать ключ inner_marker из вложенного catch, есть: " + names,
				names.contains("inner_marker"));
	}

	public void testTryCatch_exceptionNotVisibleInsideFinallyBlock() {
		createParser3FileInDir("www/test_try_catch_exception_not_visible_in_finally.p",
				"@main[]\n" +
						"^try{\n" +
						"\t^broken[]\n" +
						"}{\n" +
						"\t$exception.handled(true)\n" +
						"}{\n" +
						"\t$<caret>\n" +
						"}\n"
		);
		VirtualFile vf = myFixture.findFileInTempDir("www/test_try_catch_exception_not_visible_in_finally.p");
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		List<String> names = elements == null ? List.of() :
				Arrays.stream(elements).map(LookupElement::getLookupString).collect(Collectors.toList());
		assertFalse("В finally не должно быть catch-$exception, есть: " + names, names.contains("exception."));
		assertFalse("В finally не должно быть catch-$exception без точки, есть: " + names, names.contains("exception"));
	}

	public void testTryCatch_exceptionHashKeysCompletion() {
		createParser3FileInDir("www/test_try_catch_exception_hash_keys.p",
				"@main[]\n" +
						"# реальный минимальный кейс completion ключей exception\n" +
						"^try{\n" +
						"    ^broken[]\n" +
						"}{\n" +
						"    $exception.<caret>\n" +
						"}\n"
		);
		VirtualFile vf = myFixture.findFileInTempDir("www/test_try_catch_exception_hash_keys.p");
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("$exception. должен показывать ключи exception-хеша", elements);
		List<String> names = Arrays.stream(elements).map(LookupElement::getLookupString).collect(Collectors.toList());
		assertTrue("Должен быть 'type', есть: " + names, names.contains("type"));
		assertTrue("Должен быть 'source', есть: " + names, names.contains("source"));
		assertTrue("Должен быть 'comment', есть: " + names, names.contains("comment"));
		assertTrue("Должен быть 'file', есть: " + names, names.contains("file"));
		assertTrue("Должен быть 'lineno', есть: " + names, names.contains("lineno"));
		assertTrue("Должен быть 'colno', есть: " + names, names.contains("colno"));
		assertTrue("Должен быть 'handled', есть: " + names, names.contains("handled"));
	}

	public void testCache_exceptionHashKeysCompletionInsideCatchBlock() {
		createParser3FileInDir("www/test_cache_exception_hash_keys.p",
				"@main[]\n" +
						"# на основе parser3/tests/316.html: ^cache использует тот же exception catch-контекст\n" +
						"^cache[/tmp/test](0){\n" +
						"\t^broken[]\n" +
						"}{\n" +
						"\t$exception.custom_marker(true)\n" +
						"\t$exception.<caret>\n" +
						"}\n"
		);
		VirtualFile vf = myFixture.findFileInTempDir("www/test_cache_exception_hash_keys.p");
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("$exception. внутри catch-блока ^cache должен показывать ключи exception-хеша", elements);
		List<String> names = Arrays.stream(elements).map(LookupElement::getLookupString).collect(Collectors.toList());
		assertTrue("В catch-блоке ^cache должен быть стандартный ключ type, есть: " + names,
				names.contains("type"));
		assertTrue("В catch-блоке ^cache должен быть пользовательский ключ custom_marker, есть: " + names,
				names.contains("custom_marker"));
	}

	public void testCache_exceptionNotVisibleAfterCatchBlock() {
		createParser3FileInDir("www/test_cache_exception_not_visible_after.p",
				"@main[]\n" +
						"^cache[/tmp/test](0){\n" +
						"\t^broken[]\n" +
						"}{\n" +
						"\t$exception.handled(true)\n" +
						"\t$exception.custom_marker(true)\n" +
						"}\n" +
						"$<caret>\n"
		);
		VirtualFile vf = myFixture.findFileInTempDir("www/test_cache_exception_not_visible_after.p");
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		List<String> names = elements == null ? List.of() :
				Arrays.stream(elements).map(LookupElement::getLookupString).collect(Collectors.toList());
		assertFalse("После catch-блока ^cache не должно быть 'exception.', есть: " + names,
				names.contains("exception."));
		assertFalse("После catch-блока ^cache не должно быть 'exception', есть: " + names,
				names.contains("exception"));
		assertFalse("После catch-блока ^cache не должен протекать custom_marker, есть: " + names,
				names.contains("custom_marker"));
	}

	public void testCache_exceptionDoesNotLeakFromAutoCacheCatch() throws Exception {
		setDocumentRoot("www");
		replaceParser3FileInDir("www/auto.p",
				"@auto[]\n" +
						"^cache[/tmp/test](0){\n" +
						"\t^broken[]\n" +
						"}{\n" +
						"\t$exception.handled(true)\n" +
						"\t$exception.auto_marker(true)\n" +
						"}\n"
		);
		createParser3FileInDir("www/test_cache_exception_auto_leak.p",
				"@main[]\n" +
						"$exception.<caret>\n"
		);
		VirtualFile vf = myFixture.findFileInTempDir("www/test_cache_exception_auto_leak.p");
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		List<String> names = elements == null ? List.of() :
				Arrays.stream(elements).map(LookupElement::getLookupString).collect(Collectors.toList());
		assertFalse("В текущий файл не должен протекать handled из catch-блока ^cache в auto.p, есть: " + names,
				names.contains("handled"));
		assertFalse("В текущий файл не должен протекать auto_marker из catch-блока ^cache в auto.p, есть: " + names,
				names.contains("auto_marker"));
	}

	public void testStringMatch_matchCompletionInsideReplacementBlock() {
		createParser3FileInDir("www/test_string_match_temp_completion.p",
				"@main[]\n" +
						"# на основе parser3/tests/052.html: $match создаётся только для replacement-code\n" +
						"$s[test]\n" +
						"^s.match[(t)(e)][g]{\n" +
						"\t$match.<caret>\n" +
						"}\n"
		);
		VirtualFile vf = myFixture.findFileInTempDir("www/test_string_match_temp_completion.p");
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("$match. внутри replacement-блока ^string.match должен показывать поля match-таблицы", elements);
		List<String> names = Arrays.stream(elements).map(LookupElement::getLookupString).collect(Collectors.toList());
		assertTrue("В replacement-блоке ^string.match должен быть prematch, есть: " + names,
				names.contains("prematch"));
		assertTrue("В replacement-блоке ^string.match должен быть match, есть: " + names,
				names.contains("match"));
		assertTrue("В replacement-блоке ^string.match должен быть postmatch, есть: " + names,
				names.contains("postmatch"));
	}

	public void testStringMatch_matchNotVisibleAfterReplacementBlock() {
		createParser3FileInDir("www/test_string_match_temp_not_visible_after.p",
				"@main[]\n" +
						"$s[test]\n" +
						"^s.match[(t)(e)][g]{\n" +
						"\t$match.1\n" +
						"}\n" +
						"$<caret>\n"
		);
		VirtualFile vf = myFixture.findFileInTempDir("www/test_string_match_temp_not_visible_after.p");
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		List<String> names = elements == null ? List.of() :
				Arrays.stream(elements).map(LookupElement::getLookupString).collect(Collectors.toList());
		assertFalse("После replacement-блока ^string.match не должно быть 'match.', есть: " + names,
				names.contains("match."));
		assertFalse("После replacement-блока ^string.match не должно быть 'match', есть: " + names,
				names.contains("match"));
	}

	public void testStringMatch_matchNotVisibleInsideDefaultBlock() {
		createParser3FileInDir("www/test_string_match_temp_not_visible_default.p",
				"@main[]\n" +
						"$s[test]\n" +
						"^s.match[(z)][g]{\n" +
						"\t$match.1\n" +
						"}{\n" +
						"\t$match.<caret>\n" +
						"}\n"
		);
		VirtualFile vf = myFixture.findFileInTempDir("www/test_string_match_temp_not_visible_default.p");
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		List<String> names = elements == null ? List.of() :
				Arrays.stream(elements).map(LookupElement::getLookupString).collect(Collectors.toList());
		assertFalse("В default-блок ^string.match не должен протекать prematch, есть: " + names,
				names.contains("prematch"));
		assertFalse("В default-блок ^string.match не должен протекать postmatch, есть: " + names,
				names.contains("postmatch"));
	}

	public void testStringMatch_matchDoesNotLeakFromAutoReplacementBlock() throws Exception {
		setDocumentRoot("www");
		replaceParser3FileInDir("www/auto.p",
				"@auto[]\n" +
						"$s[test]\n" +
						"^s.match[(t)][g]{\n" +
						"\t$match.1\n" +
						"}\n"
		);
		createParser3FileInDir("www/test_string_match_temp_auto_leak.p",
				"@main[]\n" +
						"$match.<caret>\n"
		);
		VirtualFile vf = myFixture.findFileInTempDir("www/test_string_match_temp_auto_leak.p");
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		List<String> names = elements == null ? List.of() :
				Arrays.stream(elements).map(LookupElement::getLookupString).collect(Collectors.toList());
		assertFalse("В текущий файл не должен протекать prematch из replacement-блока ^string.match в auto.p, есть: " + names,
				names.contains("prematch"));
		assertFalse("В текущий файл не должен протекать postmatch из replacement-блока ^string.match в auto.p, есть: " + names,
				names.contains("postmatch"));
	}

	public void testTryCatch_parser042RealExceptionFieldsCompletion() {
		createParser3FileInDir("www/parser042_real.p",
				"@main[]\n" +
						"# на основе parser3/tests/042.html\n" +
						"^try{\n" +
						"\t^throw[test;this is the cause of error;comment value]\n" +
						"}{\n" +
						"\t^if($exception.type eq test){\n" +
						"\t\t$exception.handled(1)\n" +
						"\n" +
						"\t\t$exception.<caret>\n" +
						"\t}\n" +
						"}\n"
		);

		List<String> names = getCompletionsFromExistingFile("www/parser042_real.p");
		assertTrue("Реальный Parser3 042: exception должен видеть type, есть: " + names,
				names.contains("type"));
		assertTrue("Реальный Parser3 042: exception должен видеть source, есть: " + names,
				names.contains("source"));
		assertTrue("Реальный Parser3 042: exception должен видеть lineno, есть: " + names,
				names.contains("lineno"));
		assertTrue("Реальный Parser3 042: exception должен видеть comment, есть: " + names,
				names.contains("comment"));
		assertTrue("Реальный Parser3 042: exception должен видеть handled, есть: " + names,
				names.contains("handled"));
	}

	public void testTryCatch_exceptionHashKeysHaveDocsAndTypes() {
		createParser3FileInDir("www/test_try_catch_exception_hash_keys_docs.p",
				"@main[]\n" +
						"# реальный минимальный кейс docs/type для ключей exception\n" +
						"^try{\n" +
						"    ^broken[]\n" +
						"}{\n" +
						"    $exception.<caret>\n" +
						"}\n"
		);
		VirtualFile vf = myFixture.findFileInTempDir("www/test_try_catch_exception_hash_keys_docs.p");
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("$exception. должен показывать описание и типы", elements);

		LookupElement lineno = Arrays.stream(elements)
				.filter(e -> "lineno".equals(e.getLookupString()))
				.findFirst()
				.orElse(null);
		assertNotNull("Должен быть lookup element 'lineno'", lineno);
		LookupElementPresentation linenoPresentation = LookupElementPresentation.renderElement(lineno);
		assertEquals("int", linenoPresentation.getTypeText());
		assertTrue("У lineno должно быть описание из документации: " + linenoPresentation.getTailText(),
				linenoPresentation.getTailText() != null && linenoPresentation.getTailText().contains("номер строки"));

		LookupElement handled = Arrays.stream(elements)
				.filter(e -> "handled".equals(e.getLookupString()))
				.findFirst()
				.orElse(null);
		assertNotNull("Должен быть lookup element 'handled'", handled);
		LookupElementPresentation handledPresentation = LookupElementPresentation.renderElement(handled);
		assertEquals("bool", handledPresentation.getTypeText());
		assertTrue("У handled должно быть описание из документации: " + handledPresentation.getTailText(),
				handledPresentation.getTailText() != null && handledPresentation.getTailText().contains("ошибка обработана"));
	}

	public void testTryCatch_exceptionCopyRemainsAvailableAfterBlock() {
		createParser3FileInDir("www/test_try_catch_exception_copy_after_block.p",
				"@main[]\n" +
						"# реальный минимальный кейс копии exception после второго блока try\n" +
						"^try{\n" +
						"    ^error[]\n" +
						"}{\n" +
						"    $exception.\n" +
						"    $e[$exception]\n" +
						"}\n" +
						"$e.<caret>\n"
		);
		VirtualFile vf = myFixture.findFileInTempDir("www/test_try_catch_exception_copy_after_block.p");
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("$e после catch должен быть копией exception", elements);
		List<String> names = Arrays.stream(elements).map(LookupElement::getLookupString).collect(Collectors.toList());
		assertTrue("Должен быть 'type', есть: " + names, names.contains("type"));
		assertTrue("Должен быть 'source', есть: " + names, names.contains("source"));
		assertTrue("Должен быть 'comment', есть: " + names, names.contains("comment"));
		assertTrue("Должен быть 'file', есть: " + names, names.contains("file"));
		assertTrue("Должен быть 'lineno', есть: " + names, names.contains("lineno"));
		assertTrue("Должен быть 'colno', есть: " + names, names.contains("colno"));
		assertTrue("Должен быть 'handled', есть: " + names, names.contains("handled"));
	}

	public void testTryCatch_realErrors4ExceptionPrefixHiddenAfterCatchButCopyRemains() {
		createParser3FileInDir("www/test_try_catch_errors4_exception_scope.p",
				"@main[]\n" +
						"^try{\n" +
						"\t^xp[]\n" +
						"}{\n" +
						"\t$exception.handled(1)\n" +
						"\t$exceptionCopy[$exception]\n" +
						"}\n" +
						"$exception<caret>\n"
		);
		VirtualFile exceptionFile = myFixture.findFileInTempDir("www/test_try_catch_errors4_exception_scope.p");
		myFixture.configureFromExistingVirtualFile(exceptionFile);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] exceptionElements = myFixture.getLookupElements();
		List<String> exceptionNames = exceptionElements == null ? List.of()
				: Arrays.stream(exceptionElements).map(LookupElement::getLookupString).collect(Collectors.toList());
		assertFalse("После catch не должно быть completion для exception по точному префиксу, есть: " + exceptionNames,
				exceptionNames.contains("exception"));
		assertFalse("После catch не должно быть completion для exception. по точному префиксу, есть: " + exceptionNames,
				exceptionNames.contains("exception."));

		createParser3FileInDir("www/test_try_catch_errors4_exception_copy_scope.p",
				"@main[]\n" +
						"^try{\n" +
						"\t^xp[]\n" +
						"}{\n" +
						"\t$exception.handled(1)\n" +
						"\t$exceptionCopy[$exception]\n" +
						"}\n" +
						"$exceptionCopy.<caret>\n"
		);
		VirtualFile copyFile = myFixture.findFileInTempDir("www/test_try_catch_errors4_exception_copy_scope.p");
		myFixture.configureFromExistingVirtualFile(copyFile);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] copyElements = myFixture.getLookupElements();
		assertNotNull("$exceptionCopy после catch должен оставаться копией exception", copyElements);
		List<String> copyNames = Arrays.stream(copyElements).map(LookupElement::getLookupString).collect(Collectors.toList());
		assertTrue("Должен быть 'type', есть: " + copyNames, copyNames.contains("type"));
		assertTrue("Должен быть 'source', есть: " + copyNames, copyNames.contains("source"));
		assertTrue("Должен быть 'comment', есть: " + copyNames, copyNames.contains("comment"));
		assertTrue("Должен быть 'file', есть: " + copyNames, copyNames.contains("file"));
		assertTrue("Должен быть 'lineno', есть: " + copyNames, copyNames.contains("lineno"));
		assertTrue("Должен быть 'colno', есть: " + copyNames, copyNames.contains("colno"));
		assertTrue("Должен быть 'handled', есть: " + copyNames, copyNames.contains("handled"));
	}

	public void testTryCatch_realErrors4ExceptionDoesNotLeakFromMainAutoMethodParam() {
		createParser3FileInDir("cgi/auto.p",
				"@auto[]\n" +
						"\n" +
						"@handle_exception_debug[exception;stack]\n" +
						"$exception.type\n" +
						"$exception.source\n" +
						"$exception.comment\n" +
						"$exception.file\n" +
						"$exception.lineno\n" +
						"$exception.colno\n"
		);
		setMainAuto("cgi/auto.p");
		createParser3FileInDir("www/test_try_catch_errors4_exception_main_auto_leak.p",
				"@main[]\n" +
						"^try{\n" +
						"\t^xp[]\n" +
						"}{\n" +
						"\t$exceptionCopy[$exception]\n" +
						"}\n" +
						"$exception.<caret>\n"
		);
		VirtualFile exceptionFile = myFixture.findFileInTempDir("www/test_try_catch_errors4_exception_main_auto_leak.p");
		myFixture.configureFromExistingVirtualFile(exceptionFile);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] exceptionElements = myFixture.getLookupElements();
		List<String> exceptionNames = exceptionElements == null ? List.of()
				: Arrays.stream(exceptionElements).map(LookupElement::getLookupString).collect(Collectors.toList());
		assertFalse("После catch не должно быть completion для exception.type из параметра main auto, есть: " + exceptionNames,
				exceptionNames.contains("type"));
		assertFalse("После catch не должно быть completion для exception.source из параметра main auto, есть: " + exceptionNames,
				exceptionNames.contains("source"));
		assertFalse("После catch не должно быть completion для exception.comment из параметра main auto, есть: " + exceptionNames,
				exceptionNames.contains("comment"));
	}

	public void testTryCatch_realErrors4ExceptionDoesNotLeakFromAutoTryCatch() throws Exception {
		setDocumentRoot("www");
		replaceParser3FileInDir("www/auto.p",
				"@auto[]\n" +
						"^try{\n" +
						"\t^a[]\n" +
						"}{\n" +
						"\t$exception.handled(true)\n" +
						"}\n" +
						"\n" +
						"@main[]\n" +
						"$response:content-type[application/octet-stream]\n"
		);
		createParser3FileInDir("www/errors4_auto_try_exception_leak.p",
				"@main[]\n" +
						"^try{\n" +
						"\t^xp[]\n" +
						"}{\n" +
						"\t$exceptionCopy[$exception]\n" +
						"\n" +
						"}\n" +
						"$exception.<caret>\n"
		);
		VirtualFile exceptionFile = myFixture.findFileInTempDir("www/errors4_auto_try_exception_leak.p");
		myFixture.configureFromExistingVirtualFile(exceptionFile);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] exceptionElements = myFixture.getLookupElements();
		List<String> exceptionNames = exceptionElements == null ? List.of()
				: Arrays.stream(exceptionElements).map(LookupElement::getLookupString).collect(Collectors.toList());
		assertFalse("После catch не должно быть completion для exception.handled из www/auto.p, есть: " + exceptionNames,
				exceptionNames.contains("handled"));
	}

	public void testTryCatch_parser437RealExceptionStackFieldsInMenu() {
		createParser3FileInDir("www/parser437_real.p",
				"@main[]\n" +
						"# на основе parser3/tests/437.html\n" +
						"$response:status[500]\n" +
						"^method[]\n" +
						"\n" +
						"@method[]\n" +
						"^deeper[]\n" +
						"\n" +
						"@deeper[]\n" +
						"^bug[]\n" +
						"\n" +
						"@unhandled_exception[exception;stack]\n" +
						"\n" +
						"$exception.file[-cleared-]\n" +
						"$exception.handled(1)\n" +
						"^stack.menu{ $stack.<caret> }\n"
		);

		List<String> names = getCompletionsFromExistingFile("www/parser437_real.p");
		assertTrue("Реальный Parser3 437: stack должен видеть file из exception-структуры, есть: " + names,
				names.contains("file"));
		assertTrue("Реальный Parser3 437: stack должен видеть handled из exception-структуры, есть: " + names,
				names.contains("handled"));
	}

	public void testForeachValueParam_knownStructuredSource_hasDotInDollarCompletion() {
		createParser3FileInDir("www/test_foreach_value_known_source_has_dot.p",
				"@main[]\n" +
						"# реальный минимальный кейс structured foreach source с точкой у value\n" +
						"$users[^table::sql{SELECT id, account_id FROM users}]\n" +
						"^users.foreach[target_user_id;var]{\n" +
						"    $<caret>\n" +
						"}\n"
		);
		VirtualFile vf = myFixture.findFileInTempDir("www/test_foreach_value_known_source_has_dot.p");
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("$ внутри foreach должен видеть key и value", elements);
		List<String> names = Arrays.stream(elements).map(LookupElement::getLookupString).collect(Collectors.toList());
		assertTrue("Должен быть 'target_user_id', есть: " + names, names.contains("target_user_id"));
		assertFalse("Key-параметр не должен получать точку, есть: " + names, names.contains("target_user_id."));
		assertTrue("Value-параметр должен получать точку от структуры источника, есть: " + names, names.contains("var."));
	}

	public void testForeachValueParam_parser430RealArrayValueHasNoDot() {
		createParser3FileInDir("www/parser430_real.p",
				"@main[]\n" +
						"# на основе parser3/tests/430.html\n" +
						"$a[^array::copy[ $.1[v1] $.5[v5] $.6[v6] $.8[v8] ]]\n" +
						"\n" +
						"^a.foreach[k;v]{\n" +
						"\t$<caret>\n" +
						"}\n"
		);

		List<String> names = getCompletionsFromExistingFile("www/parser430_real.p");
		assertTrue("Реальный Parser3 430: array foreach должен видеть key-параметр, есть: " + names,
				names.contains("k"));
		assertTrue("Реальный Parser3 430: array foreach должен видеть value-параметр, есть: " + names,
				names.contains("v"));
		assertFalse("Реальный Parser3 430: key-параметр массива не должен получать точку, есть: " + names,
				names.contains("k."));
		assertFalse("Реальный Parser3 430: value-параметр массива без структуры не должен получать точку, есть: " + names,
				names.contains("v."));
	}

	public void testSelectValueParam_parser430RealArrayValueHasNoDot() {
		createParser3FileInDir("www/parser430_select_real.p",
				"@main[]\n" +
						"# на основе parser3/tests/430.html\n" +
						"$a[^array::copy[ $.1[v1] $.5[v5] $.6[v6] $.8[v8] ]]\n" +
						"\n" +
						"2.20 ^show[^a.select[k;v]($k>5)]\n" +
						"2.21 ^show[^a.select[k;v]($k==5)]\n" +
						"2.22 ^show[^a.select[k;v]($v eq 'v6')]\n" +
						"^a.select[k;v]{\n" +
						"\t$<caret>\n" +
						"}\n"
		);

		List<String> names = getCompletionsFromExistingFile("www/parser430_select_real.p");
		assertTrue("Реальный Parser3 430: array select должен видеть key-параметр, есть: " + names,
				names.contains("k"));
		assertTrue("Реальный Parser3 430: array select должен видеть value-параметр, есть: " + names,
				names.contains("v"));
		assertFalse("Реальный Parser3 430: key-параметр select не должен получать точку, есть: " + names,
				names.contains("k."));
		assertFalse("Реальный Parser3 430: value-параметр select без структуры не должен получать точку, есть: " + names,
				names.contains("v."));
	}

	public void testSortValueParam_parser431RealArrayValueHasNoDot() {
		createParser3FileInDir("www/parser431_sort_real.p",
				"@main[]\n" +
						"# на основе parser3/tests/431.html\n" +
						"$b[^array::copy[]]\n" +
						"$b.10[] $b.15(false) $b.20[^hash::create[]] $b.25[last]\n" +
						"\n" +
						"3.7 ^b.sort[k;](-$k)\n" +
						"3.8 ^b.sort[;v]{$v}\n" +
						"^b.sort[;v]{\n" +
						"\t$<caret>\n" +
						"}\n"
		);

		List<String> names = getCompletionsFromExistingFile("www/parser431_sort_real.p");
		assertTrue("Реальный Parser3 431: array sort должен видеть value-параметр, есть: " + names,
				names.contains("v"));
		assertFalse("Реальный Parser3 431: value-параметр sort для смешанного массива не должен получать точку, есть: " + names,
				names.contains("v."));
	}

	public void testLocals_mainPrefix_noLocalVar() {
		createParser3FileInDir("www/test_locals_main.p",
				"@main[][locals]\n" +
						"# на основе parser3/tests/176.html\n" +
						"$var[x]\n" +
						"$MAIN:<caret>"
		);
		VirtualFile vf = myFixture.findFileInTempDir("www/test_locals_main.p");
		myFixture.configureFromExistingVirtualFile(vf);
		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		List<String> names = elements == null ? List.of() :
				Arrays.stream(elements).map(LookupElement::getLookupString).collect(Collectors.toList());
		System.out.println("[TEST] testLocals_mainPrefix_noLocalVar: " + names);
		assertFalse("$MAIN: НЕ должен содержать локальный $var, есть: " + names, names.contains("var"));
	}

	/**
	 * @main[][locals] $var[x] — $self.<caret> НЕ должен показывать $var (он локальный)
	 */
	public void testLocals_selfPrefix_noLocalVar() {
		createParser3FileInDir("www/test_locals_self.p",
				"@main[][locals]\n" +
						"# на основе parser3/tests/176.html\n" +
						"$var[x]\n" +
						"$self.<caret>"
		);
		VirtualFile vf = myFixture.findFileInTempDir("www/test_locals_self.p");
		myFixture.configureFromExistingVirtualFile(vf);
		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		List<String> names = elements == null ? List.of() :
				Arrays.stream(elements).map(LookupElement::getLookupString).collect(Collectors.toList());
		System.out.println("[TEST] testLocals_selfPrefix_noLocalVar: " + names);
		assertFalse("$self. НЕ должен содержать локальный $var, есть: " + names, names.contains("var"));
	}

	/**
	 * @main[][locals] $var[x] — ^MAIN:v<caret> НЕ должен показывать $var (он локальный)
	 */
	public void testLocals_caretMainPrefix_noLocalVar() {
		createParser3FileInDir("www/test_locals_caret_main.p",
				"@main[][locals]\n" +
						"# на основе parser3/tests/176.html\n" +
						"$var[x]\n" +
						"^MAIN:v<caret>"
		);
		VirtualFile vf = myFixture.findFileInTempDir("www/test_locals_caret_main.p");
		myFixture.configureFromExistingVirtualFile(vf);
		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		List<String> names = elements == null ? List.of() :
				Arrays.stream(elements).map(LookupElement::getLookupString).collect(Collectors.toList());
		assertFalse("^MAIN: НЕ должен содержать локальный $var, есть: " + names, names.contains("var."));
	}

	/**
	 * @main[][locals] $var[x] — ^self.v<caret> НЕ должен показывать $var (он локальный)
	 */
	public void testLocals_caretSelfPrefix_noLocalVar() {
		createParser3FileInDir("www/test_locals_caret_self.p",
				"@main[][locals]\n" +
						"# на основе parser3/tests/176.html\n" +
						"$var[x]\n" +
						"^self.v<caret>"
		);
		VirtualFile vf = myFixture.findFileInTempDir("www/test_locals_caret_self.p");
		myFixture.configureFromExistingVirtualFile(vf);
		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		List<String> names = elements == null ? List.of() :
				Arrays.stream(elements).map(LookupElement::getLookupString).collect(Collectors.toList());
		assertFalse("^self. НЕ должен содержать локальный $var, есть: " + names, names.contains("var."));
	}
	public void testTableCopyDollarCompletion_keepsDotAfterSourceReset() {
		createParser3FileInDir("www/test_table_copy_dollar_completion_keeps_dot_after_source_reset.p",
				"@main[]\n" +
						"# реальный минимальный кейс completion table copy после reset source\n" +
						"$list[^table::create{id\tname\tvalue}]\n" +
						"$list2[^list.select(\n" +
						"\tdef $list.id\n" +
						")]\n" +
						"$list[^list.select(\n" +
						"\tdef $list.id\n" +
						")]\n" +
						"$list[]\n" +
						"$<caret>\n"
		);
		VirtualFile vf = myFixture.findFileInTempDir("www/test_table_copy_dollar_completion_keeps_dot_after_source_reset.p");
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("$ должен видеть list2 как табличную копию", elements);
		List<String> names = Arrays.stream(elements).map(LookupElement::getLookupString).collect(Collectors.toList());
		assertTrue("Должен быть 'list2.' с точкой, есть: " + names, names.contains("list2."));
	}

	public void testTableCopy_parser308RealCreateWithOptionsKeepsColumns() {
		createParser3FileInDir("www/parser308_real.p",
				"# на основе parser3/tests/308.html\n" +
						"$t[^table::create{name\n" +
						"\n" +
						"vasya\n" +
						"petya}]\n" +
						"\n" +
						"$v[^table::create[$t;^hash::create[]]]\n" +
						"\n" +
						"^v.menu{\n" +
						"\t$v.<caret>\n" +
						"}\n"
		);

		List<String> names = getCompletionsFromExistingFile("www/parser308_real.p");
		assertTrue("Реальный Parser3 308: table::create[$t;options] должен сохранить колонку name, есть: " + names,
				names.contains("name"));
	}

	public void testTableCopy_parser348RealCreateWithReverseOptionKeepsColumnsAfterSelfOverride() {
		createParser3FileInDir("www/parser348_real_reverse_copy.p",
				"# на основе parser3/tests/348.html\n" +
						"$t[^table::create{v1\tv2\tv3\n" +
						"a\tb\tc}]\n" +
						"\n" +
						"$t[^table::create[$t; $.reverse(true) ]]\n" +
						"\n" +
						"$t.<caret>\n"
		);

		List<String> names = getCompletionsFromExistingFile("www/parser348_real_reverse_copy.p");
		assertTrue("Реальный Parser3 348: table::create[$t;reverse] должен сохранить колонку v1, есть: " + names,
				names.contains("v1"));
		assertTrue("Реальный Parser3 348: table::create[$t;reverse] должен сохранить колонку v2, есть: " + names,
				names.contains("v2"));
		assertTrue("Реальный Parser3 348: table::create[$t;reverse] должен сохранить колонку v3, есть: " + names,
				names.contains("v3"));
	}

	public void testTableCopy_parser312RealCopyKeepsOriginalColumnsAfterSourceChanges() {
		createParser3FileInDir("www/parser312_real.p",
				"# на основе parser3/tests/312.html\n" +
						"$t[^table::create{c1\tc2\tc3}]\n" +
						"\n" +
						"$copy[^table::create[$t]]\n" +
						"\n" +
						"$t.c1[]\n" +
						"^t.append{a\tb\tc\td\te}\n" +
						"\n" +
						"$copy.<caret>\n"
		);

		List<String> names = getCompletionsFromExistingFile("www/parser312_real.p");
		assertTrue("Реальный Parser3 312: копия таблицы должна сохранить c1, есть: " + names,
				names.contains("c1"));
		assertTrue("Реальный Parser3 312: копия таблицы должна сохранить c2, есть: " + names,
				names.contains("c2"));
		assertTrue("Реальный Parser3 312: копия таблицы должна сохранить c3, есть: " + names,
				names.contains("c3"));
		assertFalse("Реальный Parser3 312: копия не должна наследовать новые поля исходной таблицы, есть: " + names,
				names.contains("d"));
	}

	public void testTableDelete_parser312RealDeleteRowsKeepsColumns() {
		createParser3FileInDir("www/parser312_real_delete_rows.p",
				"# на основе parser3/tests/312.html\n" +
						"$t[^table::create{c1\tc2\tc3}]\n" +
						"^t.insert{v0}\n" +
						"^t.append{a\tb\tc\td\te}\n" +
						"^t.offset(-5)\n" +
						"^t.insert[ $.c1[v2] ]\n" +
						"\n" +
						"^t.delete[]\n" +
						"^t.delete[]\n" +
						"^t.delete[]\n" +
						"\n" +
						"$t.<caret>\n"
		);

		List<String> names = getCompletionsFromExistingFile("www/parser312_real_delete_rows.p");
		assertTrue("Реальный Parser3 312: ^table.delete[] не должен удалять колонку c1, есть: " + names,
				names.contains("c1"));
		assertTrue("Реальный Parser3 312: ^table.delete[] не должен удалять колонку c2, есть: " + names,
				names.contains("c2"));
		assertTrue("Реальный Parser3 312: ^table.delete[] не должен удалять колонку c3, есть: " + names,
				names.contains("c3"));
	}

	public void testTableJoin_parser071RealJoinKeepsColumns() {
		createParser3FileInDir("www/parser071_real.p",
				"@main[]\n" +
						"# на основе parser3/tests/071.html\n" +
						"$source[^table::create{a\n" +
						"aa\n" +
						"bb\n" +
						"cc}]\n" +
						"$dest0[^table::create{a\n" +
						"xx\n" +
						"}]\n" +
						"^source.offset(2)\n" +
						"$dest[^table::create[$dest0]]\n" +
						"^dest.join[$source]\n" +
						"$dest.<caret>\n"
		);

		List<String> names = getCompletionsFromExistingFile("www/parser071_real.p");
		assertTrue("Реальный Parser3 071: после join должна остаться колонка a, есть: " + names,
				names.contains("a"));
	}

	public void testTableJoin_parser150RealJoinWithOptionsKeepsColumns() {
		createParser3FileInDir("www/parser150_real_join_options.p",
				"# на основе parser3/tests/150.html\n" +
						"$source[^table::create{name\n" +
						"1\n" +
						"2\n" +
						"3}]\n" +
						"\n" +
						"$empty[^table::create{name}]\n" +
						"$dest[^table::create[$empty]]\n" +
						"^dest.join[$source][\n" +
						"\t$.offset(1)\n" +
						"\t$.limit(2)\n" +
						"\t$.reverse(false)\n" +
						"]\n" +
						"\n" +
						"$dest.<caret>\n"
		);

		List<String> names = getCompletionsFromExistingFile("www/parser150_real_join_options.p");
		assertTrue("Реальный Parser3 150: после join с options должна остаться колонка name, есть: " + names,
				names.contains("name"));
	}

	public void testTableCellAssign_parser386RealHashValueDoesNotBreakColumns() {
		createParser3FileInDir("www/parser386_real.p",
				"# на основе parser3/tests/386.html\n" +
						"$t[^table::create{c1\tc2\tc3}]\n" +
						"^t.insert[ $.c2(1) ]\n" +
						"^try-catch{ $t.c3[^hash::create[]] }\n" +
						"\n" +
						"$t.<caret>\n"
		);

		List<String> names = getCompletionsFromExistingFile("www/parser386_real.p");
		assertTrue("Реальный Parser3 386: после записи в ячейку должна остаться колонка c1, есть: " + names,
				names.contains("c1"));
		assertTrue("Реальный Parser3 386: после записи в ячейку должна остаться колонка c2, есть: " + names,
				names.contains("c2"));
		assertTrue("Реальный Parser3 386: после записи в ячейку должна остаться колонка c3, есть: " + names,
				names.contains("c3"));
	}

	public void testTableRename_parser406RealRenameUpdatesColumns() {
		createParser3FileInDir("www/parser406_real.p",
				"# на основе parser3/tests/406.html\n" +
						"$t[^table::create{a\tb\ts\ta\n" +
						"va\tvb\tvc\tvd}]\n" +
						"\n" +
						"^t.rename[b;e]\n" +
						"\n" +
						"$t.<caret>\n"
		);

		List<String> names = getCompletionsFromExistingFile("www/parser406_real.p");
		assertTrue("Реальный Parser3 406: после rename должна быть колонка e, есть: " + names,
				names.contains("e"));
		assertFalse("Реальный Parser3 406: после rename старая колонка b не должна оставаться, есть: " + names,
				names.contains("b"));
	}

	public void testTableRename_parser406RealHashRenameUpdatesColumns() {
		createParser3FileInDir("www/parser406_real_hash_rename.p",
				"# на основе parser3/tests/406.html\n" +
						"$t[^table::create{a\tb\ts\ta\n" +
						"va\tvb\tvc\tvd}]\n" +
						"\n" +
						"^t.rename[b;e]\n" +
						"^t.rename[ $.e[] $.a[z] $.z[a] ]\n" +
						"\n" +
						"$t.<caret>\n"
		);

		List<String> names = getCompletionsFromExistingFile("www/parser406_real_hash_rename.p");
		assertTrue("Реальный Parser3 406: после hash-rename должна быть колонка z, есть: " + names,
				names.contains("z"));
		assertFalse("Реальный Parser3 406: после hash-rename колонка e должна быть удалена, есть: " + names,
				names.contains("e"));
		assertFalse("Реальный Parser3 406: после hash-rename старая колонка a не должна оставаться, есть: " + names,
				names.contains("a"));
	}

	public void testHashRename_parser414RealRenameUpdatesKeys() {
		createParser3FileInDir("www/parser414_real.p",
				"# на основе parser3/tests/414.html\n" +
						"$demo[\n" +
						"\t$.a[1]\n" +
						"\t$.b[2]\n" +
						"\t$.c[3]\n" +
						"\t$.d[4]\n" +
						"]\n" +
						"\n" +
						"$h[^hash::create[$demo]]\n" +
						"^h.rename[b;x]\n" +
						"\n" +
						"$h.<caret>\n"
		);

		List<String> names = getCompletionsFromExistingFile("www/parser414_real.p");
		assertTrue("Реальный Parser3 414: после rename должен быть ключ x, есть: " + names,
				names.contains("x"));
		assertFalse("Реальный Parser3 414: после rename старый ключ b не должен оставаться, есть: " + names,
				names.contains("b"));
	}

	public void testTableCreateOptions_parser426RealLimitOffsetKeepsColumns() {
		createParser3FileInDir("www/parser426_real.p",
				"# на основе parser3/tests/426.html\n" +
						"$t[^table::create{data\n" +
						"data1\n" +
						"data2\n" +
						"data3\n" +
						"data4}]\n" +
						"\n" +
						"$b[^table::create[$t; $.limit(1) $.offset(1) ]]\n" +
						"\n" +
						"$b.<caret>\n"
		);

		List<String> names = getCompletionsFromExistingFile("www/parser426_real.p");
		assertTrue("Реальный Parser3 426: table::create[$t;limit/offset] должен сохранить колонку data, есть: " + names,
				names.contains("data"));
	}

	public void testTableColumns_parser168RealColumnsMethodReturnsColumnTable() {
		createParser3FileInDir("www/parser168_real_columns_default.p",
				"# на основе parser3/tests/168.html\n" +
						"$tData[^table::create{a\tb1\tzigi}]\n" +
						"\n" +
						"$tColumn[^tData.columns[]]\n" +
						"\n" +
						"$tColumn.<caret>\n"
		);

		List<String> names = getCompletionsFromExistingFile("www/parser168_real_columns_default.p");
		assertTrue("Реальный Parser3 168: ^table.columns[] должен вернуть таблицу с колонкой column, есть: " + names,
				names.contains("column"));
	}

	public void testTableColumns_parser168RealColumnsMethodUsesCustomColumnName() {
		createParser3FileInDir("www/parser168_real_columns_custom.p",
				"# на основе parser3/tests/168.html\n" +
						"$tData[^table::create{a\tb1\tzigi}]\n" +
						"\n" +
						"$tColumn[^tData.columns[zzz]]\n" +
						"\n" +
						"$tColumn.<caret>\n"
		);

		List<String> names = getCompletionsFromExistingFile("www/parser168_real_columns_custom.p");
		assertTrue("Реальный Parser3 168: ^table.columns[zzz] должен вернуть таблицу с колонкой zzz, есть: " + names,
				names.contains("zzz"));
		assertFalse("Реальный Parser3 168: для ^table.columns[zzz] не должна оставаться колонка column, есть: " + names,
				names.contains("column"));
	}

	public void testTableCreateSeparator_parser035RealCommaColumnsVisible() {
		createParser3FileInDir("www/parser035_real.p",
				"# на основе parser3/tests/035.html\n" +
						"$t[^table::create{a,b\n" +
						"1,2}[$.separator[,]]]\n" +
						"\n" +
						"$t.<caret>\n"
		);

		List<String> names = getCompletionsFromExistingFile("www/parser035_real.p");
		assertTrue("Реальный Parser3 035: separator[,] должен показать колонку a, есть: " + names,
				names.contains("a"));
		assertTrue("Реальный Parser3 035: separator[,] должен показать колонку b, есть: " + names,
				names.contains("b"));
		assertFalse("Реальный Parser3 035: separator[,] не должен оставлять колонку a,b целиком, есть: " + names,
				names.contains("a,b"));
	}

	public void testTableCreateEncloser_parser350RealQuotedColumnsVisible() {
		createParser3FileInDir("www/parser350_real.p",
				"# на основе parser3/tests/350.html\n" +
						"$cfg[^table::create{first,\"second\"\n" +
						"one,two}[\n" +
						"\t$.separator[,]\n" +
						"\t$.encloser[\"]\n" +
						"]]\n" +
						"\n" +
						"$cfg.<caret>\n"
		);

		List<String> names = getCompletionsFromExistingFile("www/parser350_real.p");
		assertTrue("Реальный Parser3 350: encloser должен показать колонку first, есть: " + names,
				names.contains("first"));
		assertTrue("Реальный Parser3 350: encloser должен показать колонку second без кавычек, есть: " + names,
				names.contains("second"));
		assertFalse("Реальный Parser3 350: encloser не должен оставлять кавычки в имени колонки, есть: " + names,
				names.contains("\"second\""));
	}

	public void testArrayDelete_parser432RealDeleteRemovesKey() {
		createParser3FileInDir("www/parser432_real.p",
				"# на основе parser3/tests/432.html\n" +
						"$a[^array::copy[\n" +
						"\t$.1[a]\n" +
						"\t$.2[b]\n" +
						"]]\n" +
						"^a.delete(1)\n" +
						"\n" +
						"$a.<caret>\n"
		);

		List<String> names = getCompletionsFromExistingFile("www/parser432_real.p");
		assertTrue("Реальный Parser3 432: после delete должен остаться ключ 2, есть: " + names,
				names.contains("2"));
		assertFalse("Реальный Parser3 432: после delete удалённый ключ 1 не должен оставаться, есть: " + names,
				names.contains("1"));
	}

	public void testHashDelete_parser439RealDeleteRemovesKey() {
		createParser3FileInDir("www/parser439_real.p",
				"# на основе parser3/tests/439.html\n" +
						"$h[\n" +
						"\t$.0[v0]\n" +
						"\t$.1[v1]\n" +
						"\t$.2[v2]\n" +
						"]\n" +
						"$o[$h]\n" +
						"^o.delete[1]\n" +
						"\n" +
						"$o.<caret>\n"
		);

		List<String> names = getCompletionsFromExistingFile("www/parser439_real.p");
		assertTrue("Реальный Parser3 439: после delete должен остаться ключ 2, есть: " + names,
				names.contains("2"));
		assertFalse("Реальный Parser3 439: после delete удалённый ключ 1 не должен оставаться, есть: " + names,
				names.contains("1"));
	}

	public void testHashSub_parser281RealSubRemovesKeysFromOtherHash() {
		createParser3FileInDir("www/parser281_real_hash_sub.p",
				"# на основе parser3/tests/281.html\n" +
						"$h[ $.key[value] ]\n" +
						"$h.k[v]\n" +
						"$h.k2[v2]\n" +
						"$h2[ $.k[v] ]\n" +
						"\n" +
						"^h.sub[$h2]\n" +
						"\n" +
						"$h.<caret>\n"
		);

		List<String> names = getCompletionsFromExistingFile("www/parser281_real_hash_sub.p");
		assertTrue("Реальный Parser3 281: после ^h.sub[$h2] ключ key должен остаться, есть: " + names,
				names.contains("key"));
		assertTrue("Реальный Parser3 281: после ^h.sub[$h2] ключ k2 должен остаться, есть: " + names,
				names.contains("k2"));
		assertFalse("Реальный Parser3 281: после ^h.sub[$h2] ключ k должен исчезнуть, есть: " + names,
				names.contains("k"));
	}

	public void testHashCreate_parser281RealCopyKeepsOriginalKeysAfterSourceChanges() {
		createParser3FileInDir("www/parser281_real_hash_copy.p",
				"# на основе parser3/tests/281.html\n" +
						"$h[ $.key[value] ]\n" +
						"$h2[^hash::create[$h]]\n" +
						"\n" +
						"$h.k[v]\n" +
						"$h.k2[v2]\n" +
						"\n" +
						"$h2.<caret>\n"
		);

		List<String> names = getCompletionsFromExistingFile("www/parser281_real_hash_copy.p");
		assertTrue("Реальный Parser3 281: копия через ^hash::create[$h] должна сохранить key, есть: " + names,
				names.contains("key"));
		assertFalse("Реальный Parser3 281: копия через ^hash::create[$h] не должна наследовать поздний ключ k, есть: " + names,
				names.contains("k"));
		assertFalse("Реальный Parser3 281: копия через ^hash::create[$h] не должна наследовать поздний ключ k2, есть: " + names,
				names.contains("k2"));
	}

	public void testHashCreateMethod_parser427RealCreateAddsKeys() {
		createParser3FileInDir("www/parser427_real_hash_create_method.p",
				"# на основе parser3/tests/427.html\n" +
						"$h[ $.1[1] $.2[2] ]\n" +
						"\n" +
						"^h.create[ $.3[3] ]\n" +
						"\n" +
						"$h.<caret>\n"
		);

		List<String> names = getCompletionsFromExistingFile("www/parser427_real_hash_create_method.p");
		assertTrue("Реальный Parser3 427: ^hash.create[...] должен сохранить старый ключ 1, есть: " + names,
				names.contains("1"));
		assertTrue("Реальный Parser3 427: ^hash.create[...] должен сохранить старый ключ 2, есть: " + names,
				names.contains("2"));
		assertTrue("Реальный Parser3 427: ^hash.create[...] должен добавить ключ 3, есть: " + names,
				names.contains("3"));
	}

	public void testArrayCopyMethod_parser427RealCopyAddsKeys() {
		createParser3FileInDir("www/parser427_real_array_copy_method.p",
				"# на основе parser3/tests/427.html\n" +
						"$a[1;2]\n" +
						"\n" +
						"^a.copy[ $.3[3] ]\n" +
						"\n" +
						"$a.<caret>\n"
		);

		List<String> names = getCompletionsFromExistingFile("www/parser427_real_array_copy_method.p");
		assertTrue("Реальный Parser3 427: ^array.copy[...] должен сохранить ключ 0, есть: " + names,
				names.contains("0"));
		assertTrue("Реальный Parser3 427: ^array.copy[...] должен сохранить ключ 1, есть: " + names,
				names.contains("1"));
		assertTrue("Реальный Parser3 427: ^array.copy[...] должен добавить ключ 3, есть: " + names,
				names.contains("3"));
	}

	public void testArrayCreateMethod_parser427RealCreateAddsKeys() {
		createParser3FileInDir("www/parser427_real_array_create_method.p",
				"# на основе parser3/tests/427.html\n" +
						"$a[1;2]\n" +
						"\n" +
						"^a.create[ $.3[3] ]\n" +
						"\n" +
						"$a.<caret>\n"
		);

		List<String> names = getCompletionsFromExistingFile("www/parser427_real_array_create_method.p");
		assertTrue("Реальный Parser3 427: ^array.create[...] должен сохранить ключ 0, есть: " + names,
				names.contains("0"));
		assertTrue("Реальный Parser3 427: ^array.create[...] должен сохранить ключ 1, есть: " + names,
				names.contains("1"));
		assertTrue("Реальный Parser3 427: ^array.create[...] должен добавить ключ 3, есть: " + names,
				names.contains("3"));
	}

	public void testArrayJoin_parser430RealJoinAddsHashKeys() {
		createParser3FileInDir("www/parser430_real_array_join_hash.p",
				"# на основе parser3/tests/430.html\n" +
						"$a[^array::create[]]\n" +
						"\n" +
						"^a.join[ $.2[over] ]\n" +
						"\n" +
						"$a.<caret>\n"
		);

		List<String> names = getCompletionsFromExistingFile("www/parser430_real_array_join_hash.p");
		assertTrue("Реальный Parser3 430: ^array.join[...] должен добавить ключ 2, есть: " + names,
				names.contains("2"));
	}

	public void testArrayKeys_parser431RealKeysMethodReturnsColumnTable() {
		createParser3FileInDir("www/parser431_real_array_keys.p",
				"# на основе parser3/tests/431.html\n" +
						"$a[^array::copy[ $.5[v5] $.7[v7] $.10[v10]  $.12[v12] ]]\n" +
						"\n" +
						"$keys[^a.keys[column]]\n" +
						"\n" +
						"$keys.<caret>\n"
		);

		List<String> names = getCompletionsFromExistingFile("www/parser431_real_array_keys.p");
		assertTrue("Реальный Parser3 431: ^array.keys[column] должен вернуть таблицу с колонкой column, есть: " + names,
				names.contains("column"));
	}

	public void testArrayKeys_parser431RealKeysMethodReturnsDefaultKeyColumn() {
		createParser3FileInDir("www/parser431_real_array_keys_default.p",
				"# на основе parser3/tests/431.html\n" +
						"$a[^array::copy[ $.5[v5] $.7[v7] $.10[v10]  $.12[v12] ]]\n" +
						"\n" +
						"$keys[^a.keys[]]\n" +
						"\n" +
						"$keys.<caret>\n"
		);

		List<String> names = getCompletionsFromExistingFile("www/parser431_real_array_keys_default.p");
		assertTrue("Реальный Parser3 431: ^array.keys[] должен вернуть таблицу с колонкой key, есть: " + names,
				names.contains("key"));
	}

	public void testArrayRemove_parser431RealCopyKeepsLocalKeysAfterRemove() {
		createParser3FileInDir("www/parser431_real.p",
				"# на основе parser3/tests/431.html\n" +
						"$b[^array::copy[\n" +
						"\t$.5[v5]\n" +
						"\t$.7[v7]\n" +
						"\t$.10[v10]\n" +
						"\t$.12[v12]\n" +
						"]]\n" +
						"$c[^array::copy[$b]]\n" +
						"$c.10[] $c.15(false) $c.20[^hash::create[]] $c.25[last]\n" +
						"^c.remove(10)\n" +
						"\n" +
						"$c.<caret>\n"
		);

		List<String> names = getCompletionsFromExistingFile("www/parser431_real.p");
		assertTrue("Реальный Parser3 431: после remove должен остаться локальный ключ 15, есть: " + names,
				names.contains("15"));
		assertTrue("Реальный Parser3 431: после remove должен остаться локальный ключ 25, есть: " + names,
				names.contains("25"));
		assertFalse("Реальный Parser3 431: после remove удалённый ключ 10 не должен оставаться, есть: " + names,
				names.contains("10"));
	}

	public void testArraySet_parser435RealSetAddsKeys() {
		createParser3FileInDir("www/parser435_real.p",
				"# на основе parser3/tests/435.html\n" +
						"$a[v1;v2;$void;v5]\n" +
						"$a.8[v6] $a.10[v7]\n" +
						"\n" +
						"^a.set[first;first]\n" +
						"^a.set(3)[set3]\n" +
						"\n" +
						"$a.<caret>\n"
		);

		List<String> names = getCompletionsFromExistingFile("www/parser435_real.p");
		assertTrue("Реальный Parser3 435: set[first;...] должен добавить ключ first, есть: " + names,
				names.contains("first"));
		assertTrue("Реальный Parser3 435: set(3)[...] должен добавить ключ 3, есть: " + names,
				names.contains("3"));
	}

	public void testHtmlDollarCompletion_replacesWholeVariableWithoutCorruption() {
		createParser3FileInDir("www/test_html_dollar_completion_replaces_whole_variable_without_corruption.p",
				"# реальный минимальный кейс HTML dollar completion без порчи текста\n" +
						"$mainCnt(^int:sql{SELECT COUNT(*) FROM url_checks})\n" +
						"<pre>main: $main<caret>, credo: $credoCnt</pre>\n"
		);
		VirtualFile vf = myFixture.findFileInTempDir("www/test_html_dollar_completion_replaces_whole_variable_without_corruption.p");
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("В HTML-контексте должны быть варианты completion для $main", elements);

		LookupElement mainCnt = Arrays.stream(elements)
				.filter(e -> "mainCnt".equals(e.getLookupString()))
				.findFirst()
				.orElse(null);
		assertNotNull("Должен быть lookup element 'mainCnt'", mainCnt);

		myFixture.getLookup().setCurrentItem(mainCnt);
		myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR);
		com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents();

		com.intellij.openapi.editor.Document hostDocument =
				com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf);
		assertNotNull("Должен существовать document исходного Parser3-файла", hostDocument);
		String text = hostDocument.getText();
		assertTrue("После вставки должен подставиться полный идентификатор: " + text,
				text.contains("<pre>main: $mainCnt, credo: $credoCnt</pre>"));
		assertFalse("После вставки не должно оставаться битого текста: " + text,
				text.contains("$mainmainCnt"));
	}

	public void testHtmlDollarHashCompletion_opensKeysPopupAfterDotInsert() throws Throwable {
		String content =
				"@main[]\n" +
						"# реальный минимальный кейс HTML dollar completion после вставки переменной с точкой\n" +
						"$user[\n" +
						"\t$.name[]\n" +
						"\t$.value[]\n" +
						"]\n" +
						"\n" +
						"<tr>\n" +
						"\t<td>$u<caret></td>\n" +
						"</tr>\n";
		createParser3FileInDir("www/errors/5.p", content);
		VirtualFile vf = myFixture.findFileInTempDir("www/errors/5.p");
		myFixture.configureFromExistingVirtualFile(vf);

		TestModeFlags.runWithFlag(CompletionAutoPopupHandler.ourTestingAutopopup, Boolean.TRUE, () -> {
			myFixture.complete(CompletionType.BASIC);
			selectCompletionByEnter("user.");
			com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents();
			try {
				AutoPopupController.getInstance(getProject()).waitForDelayedActions(5, TimeUnit.SECONDS);
			} catch (java.util.concurrent.TimeoutException e) {
				throw new AssertionError("Не дождались delayed auto-popup actions", e);
			}
			com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents();
			try {
				AutoPopupController.getInstance(getProject()).waitForDelayedActions(5, TimeUnit.SECONDS);
			} catch (java.util.concurrent.TimeoutException e) {
				throw new AssertionError("Не дождались delayed auto-popup actions после HTML restore", e);
			}
			com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents();
		});

		com.intellij.openapi.editor.Document hostDocument =
				com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf);
		assertNotNull("Должен существовать document исходного Parser3-файла", hostDocument);
		String text = hostDocument.getText();
		assertTrue("После вставки должна быть переменная с точкой внутри HTML: " + text,
				text.contains("<td>$user.</td>"));

		int caretOffset = myFixture.getEditor().getCaretModel().getOffset();
		Lookup activeLookup = LookupManager.getActiveLookup(myFixture.getEditor());
		assertNotNull("После вставки $user. внутри HTML должен автоматически открыться popup с ключами; caret=" +
				caretOffset + ", text=\n" + text, activeLookup);
		assertTrue("Popup после $user. должен быть LookupImpl", activeLookup instanceof LookupImpl);
		waitForDefaultLookupSelection((LookupImpl) activeLookup);
		List<String> names = activeLookup.getItems().stream()
				.map(LookupElement::getLookupString)
				.collect(Collectors.toList());
		assertTrue("Popup после $user. должен содержать name, есть: " + names, names.contains("name"));
		assertTrue("Popup после $user. должен содержать value, есть: " + names, names.contains("value"));
		assertFalse("Popup после $user. не должен быть пустым", names.isEmpty());
		LookupElement selected = activeLookup.getCurrentItem();
		assertNotNull("После открытия popup первый пункт должен быть выбран", selected);
		assertEquals("Выбранным должен быть первый пункт popup", names.get(0), selected.getLookupString());
	}

	public void testVariableDotCompletionRanksHashKeysAboveUserTemplates() {
		Parser3UserTemplate foreachTemplate = createUserTemplate(
				"foreach",
				"Перебор элементов массива или хеша",
				".foreach[key;value]{\n\t<CURSOR>\n}"
		);

		withUserTemplates(List.of(foreachTemplate), () -> {
			createParser3FileInDir("www/user_template_priority_hash_keys.p",
					"@main[]\n" +
							"$person[\n" +
							"\t$.login[]\n" +
							"\t$.person_id[]\n" +
							"\t$.fields[^hash::create[]]\n" +
							"]\n" +
							"\n" +
							"^person.<caret>\n"
			);
			VirtualFile vf = myFixture.findFileInTempDir("www/user_template_priority_hash_keys.p");
			myFixture.configureFromExistingVirtualFile(vf);

			myFixture.complete(CompletionType.BASIC);
			LookupElement[] elements = myFixture.getLookupElements();
			assertNotNull("После ^person. должны быть варианты completion", elements);
			List<String> names = Arrays.stream(elements)
					.map(LookupElement::getLookupString)
					.collect(Collectors.toList());

			int loginIndex = names.indexOf("login.");
			int personIdIndex = names.indexOf("person_id.");
			int foreachIndex = names.indexOf("foreach[]");

			assertTrue("Hash key login. должен быть в completion, есть: " + names, loginIndex >= 0);
			assertTrue("Hash key person_id. должен быть в completion, есть: " + names, personIdIndex >= 0);
			assertTrue("Пользовательский шаблон .foreach[] должен быть в completion, есть: " + names, foreachIndex >= 0);
			assertTrue("Hash key login должен быть выше пользовательского шаблона, есть: " + names,
					loginIndex < foreachIndex);
			assertTrue("Hash key person_id должен быть выше пользовательского шаблона, есть: " + names,
					personIdIndex < foreachIndex);
			assertFalse("Пользовательский шаблон не должен быть первым пунктом, есть: " + names,
					"foreach[]".equals(names.get(0)));
		});
	}

	public void testSqlInjectedMethodCompletion_savedPrefixKeepsObjectMethods() {
		createParser3FileInDir("www/errors_1_real_sql_injected_saved_prefix.p",
				"@main[]\n" +
						"# errors/1.p: реальный минимальный SQL injected saved-prefix completion\n" +
						"$data[^hash::create[]]\n" +
						"$list[^table::sql{\n" +
						"\tSELECT\n" +
						"\t\tid, name\n" +
						"\tFROM\n" +
						"\t\titems\n" +
						"\tWHERE\n" +
						"\t\tid in (^data.for<caret>)\n" +
						"}]\n" +
						"\n" +
						"<div ^data.foreach>text</div>\n"
		);
		VirtualFile vf = myFixture.findFileInTempDir("www/errors_1_real_sql_injected_saved_prefix.p");
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("В injected SQL completion после сохранённого префикса for должен показывать методы hash", elements);
		List<String> names = Arrays.stream(elements).map(LookupElement::getLookupString).collect(Collectors.toList());
		assertTrue("В injected SQL для ^data.for должен быть foreach, есть: " + names, names.contains("foreach"));
	}

	public void testSqlInjectedParser3IfPrefixDoesNotShowSqlDefaultKeyword() {
		createParser3FileInDir("www/sql_injected_if_no_sql_default.p",
				"@main[]\n" +
						"# реальный минимальный SQL-кейс: SQL completion не должен лезть внутрь Parser3 ^if(...)\n" +
						"$list[^table::sql{\n" +
						"\tSELECT\n" +
						"\t\tid, user_id\n" +
						"\tFROM\n" +
						"\t\titems\n" +
						"\tWHERE\n" +
						"\t\t^if(def<caret>\n" +
						"}]\n"
		);
		VirtualFile vf = myFixture.findFileInTempDir("www/sql_injected_if_no_sql_default.p");
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		List<String> names = elements == null
				? List.of()
				: Arrays.stream(elements).map(LookupElement::getLookupString).collect(Collectors.toList());
		assertFalse("SQL keyword default не должен показываться внутри Parser3 ^if(...), есть: " + names,
				names.contains("default"));
	}

	public void testSqlInjectedParser3IfAutoPopupConfidenceSkipsSqlKeywordCompletion() {
		createParser3FileInDir("www/sql_injected_if_autopopup_confidence.p",
				"@main[]\n" +
						"# реальный минимальный SQL auto-popup кейс: SQL completion не должен стартовать внутри ^if(...)\n" +
						"$list[^table::sql{\n" +
						"\tSELECT\n" +
						"\t\tid, user_id\n" +
						"\tFROM\n" +
						"\t\titems\n" +
						"\tWHERE\n" +
						"\t\t^if(def<caret>\n" +
						"}]\n"
		);
		VirtualFile vf = myFixture.findFileInTempDir("www/sql_injected_if_autopopup_confidence.p");
		myFixture.configureFromExistingVirtualFile(vf);

		int offset = myFixture.getEditor().getCaretModel().getOffset();
		com.intellij.psi.PsiElement contextElement = myFixture.getFile().findElementAt(Math.max(0, offset - 1));
		assertNotNull("В SQL-блоке должен быть PSI element перед caret", contextElement);
		com.intellij.util.ThreeState decision =
				new ru.artlebedev.parser3.lang.Parser3CompletionConfidence()
						.shouldSkipAutopopup(contextElement, myFixture.getFile(), offset);
		assertEquals("SQL auto-popup должен подавляться внутри Parser3 ^if(...)", com.intellij.util.ThreeState.YES, decision);
	}

	public void testSqlInjectedDollarVariableAutoPopupInsideIfArgumentDoesNotCrash() throws Throwable {
		createParser3FileInDir("www/sql_injected_if_dollar_variable_autopopup.p",
				"@main[]\n" +
						"$data[\n" +
						"\t$.xxx[]\n" +
						"\t$.yyy[]\n" +
						"]\n" +
						"$list[^table::sql{\n" +
						"\tSELECT\n" +
						"\t\t*\n" +
						"\tFROM\n" +
						"\t\titems\n" +
						"\tWHERE\n" +
						"\t\t^if(def <caret>)\n" +
						"}]\n"
		);
		VirtualFile vf = myFixture.findFileInTempDir("www/sql_injected_if_dollar_variable_autopopup.p");
		myFixture.configureFromExistingVirtualFile(vf);

		TestModeFlags.runWithFlag(CompletionAutoPopupHandler.ourTestingAutopopup, Boolean.TRUE, () -> {
			myFixture.type("$da");
			com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents();
			try {
				AutoPopupController.getInstance(getProject()).waitForDelayedActions(5, TimeUnit.SECONDS);
			} catch (java.util.concurrent.TimeoutException e) {
				throw new AssertionError("Не дождались popup переменных после $da внутри SQL ^if(def ...)", e);
			}
			com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents();
		});

		Lookup activeLookup = LookupManager.getActiveLookup(myFixture.getEditor());
		assertNotNull("После $da внутри SQL ^if(def ...) должен открыться popup переменных", activeLookup);
		assertTrue("Popup после SQL $da должен быть LookupImpl", activeLookup instanceof LookupImpl);
		waitForDefaultLookupSelection((LookupImpl) activeLookup);
		List<String> names = activeLookup.getItems().stream()
				.map(LookupElement::getLookupString)
				.collect(Collectors.toList());
		assertTrue("Popup после SQL $da должен содержать переменную data, есть: " + names,
				names.contains("data."));
		LookupElement selected = activeLookup.getCurrentItem();
		assertNotNull("После открытия SQL popup первый пункт должен быть выбран", selected);
		assertEquals("Выбранным должен быть первый пункт SQL popup", names.get(0), selected.getLookupString());
	}

	public void testSqlInjectedDollarHashKeyFastTypedPrefixKeepsDotTemplatesLast() throws Throwable {
		withUserTemplates(createDotUserTemplates(), () -> {
			createParser3FileInDir("www/sql_injected_hash_key_fast_typed_prefix.p",
					"@main[]\n" +
							"$data[\n" +
							"\t$.id[]\n" +
							"\t$.name[]\n" +
							"]\n" +
							"$list[^table::sql{\n" +
							"\tSELECT\n" +
							"\t\t*\n" +
							"\tFROM\n" +
							"\t\titems\n" +
							"\tWHERE\n" +
							"\t\t^if(def <caret>)\n" +
							"}]\n"
			);
			VirtualFile vf = myFixture.findFileInTempDir("www/sql_injected_hash_key_fast_typed_prefix.p");
			myFixture.configureFromExistingVirtualFile(vf);

			typeWithAutoPopup("$data.id", "Не дождались popup ключей после быстрого ввода SQL $data.id");

			LookupImpl lookup = assertActiveLookupImpl("SQL popup после $data.id");
			assertActiveLookupFirstItemSelected("SQL popup после $data.id", lookup);
			List<String> names = getLookupNames(lookup);
			assertEquals("После SQL $data.id выбранным должен быть реальный ключ id", "id",
					lookup.getCurrentItem().getLookupString());
			assertRealKeysAboveDotTemplates(
					"SQL popup после $data.id",
					names,
					List.of("id"),
					List.of("foreach[]", "mail:send[]"));
		});
	}

	public void testSqlInjectedDollarVariableSemicolonDoesNotShowLatePopup() throws Throwable {
		createParser3FileInDir("www/sql_injected_dollar_variable_semicolon_no_late_popup.p",
				"@main[]\n" +
						"$data[\n" +
						"\t$.id[]\n" +
						"]\n" +
						"$list[^table::sql{\n" +
						"\tSELECT\n" +
						"\t\t*\n" +
						"\tFROM\n" +
						"\t\titems\n" +
						"\tWHERE\n" +
						"\t\t^if(def <caret>)\n" +
						"}]\n"
		);
		VirtualFile vf = myFixture.findFileInTempDir("www/sql_injected_dollar_variable_semicolon_no_late_popup.p");
		myFixture.configureFromExistingVirtualFile(vf);

		typeWithAutoPopup("$da;", "Не дождались delayed actions после быстрого ввода SQL $da;");

		com.intellij.openapi.editor.Document hostDocument =
				com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf);
		assertNotNull("Должен существовать document исходного Parser3-файла", hostDocument);
		String text = hostDocument.getText();
		assertTrue("SQL ввод $da; должен остаться ручным текстом: " + text,
				text.contains("^if(def $da;)"));
		assertFalse("SQL ввод $da; не должен выбирать $data из popup: " + text,
				text.contains("^if(def $data"));
		assertNoActiveLookup("После SQL $da; не должен оставаться поздний popup");
	}

	public void testSqlInjectedManualDollarDotOpensHashKeyPopupWithDotTemplatesLast() throws Throwable {
		withUserTemplates(createDotUserTemplates(), () -> {
			createParser3FileInDir("www/sql_injected_manual_dollar_dot_hash_keys.p",
					"@main[]\n" +
							"$data[\n" +
							"\t$.id[]\n" +
							"\t$.name[]\n" +
							"]\n" +
							"$list[^table::sql{\n" +
							"\tSELECT\n" +
							"\t\t*\n" +
							"\tFROM\n" +
							"\t\titems\n" +
							"\tWHERE\n" +
							"\t\t^if(def <caret>)\n" +
							"}]\n"
			);
			VirtualFile vf = myFixture.findFileInTempDir("www/sql_injected_manual_dollar_dot_hash_keys.p");
			myFixture.configureFromExistingVirtualFile(vf);

			typeWithAutoPopup("$data.", "Не дождались popup ключей после ручного SQL $data.");

			com.intellij.openapi.editor.Document hostDocument =
					com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf);
			assertNotNull("Должен существовать document исходного Parser3-файла", hostDocument);
			String text = hostDocument.getText();
			assertTrue("SQL ручная точка должна оставить $data.: " + text,
					text.contains("^if(def $data.)"));

			LookupImpl lookup = assertActiveLookupImpl("SQL popup после $data.");
			assertActiveLookupFirstItemSelected("SQL popup после $data.", lookup);
			List<String> names = getLookupNames(lookup);
			assertFalse("После SQL $data. не должен оставаться root-variable popup: " + names,
					names.contains("data."));
			assertRealKeysAboveDotTemplates(
					"SQL popup после $data.",
					names,
					List.of("id", "name"),
					List.of("foreach[]", "mail:send[]"));
		});
	}

	public void testSqlInjectedActiveRootPopupDotKeepsTypedVariableAndOpensKeys() throws Throwable {
		withUserTemplates(createDotUserTemplates(), () -> {
			createParser3FileInDir("www/sql_injected_active_root_popup_dot.p",
					"@main[]\n" +
							"$dataName[test_value]\n" +
							"$data[\n" +
							"\t$.id[]\n" +
							"\t$.name[]\n" +
							"]\n" +
							"$list[^table::sql{\n" +
							"\tSELECT\n" +
							"\t\t*\n" +
							"\tFROM\n" +
							"\t\titems\n" +
							"\tWHERE\n" +
							"\t\t^if(def $data<caret>)\n" +
							"}]\n"
			);
			VirtualFile vf = myFixture.findFileInTempDir("www/sql_injected_active_root_popup_dot.p");
			myFixture.configureFromExistingVirtualFile(vf);

			myFixture.complete(CompletionType.BASIC);
			Lookup variableLookup = myFixture.getLookup();
			assertNotNull("В SQL после $data должен открыться popup переменных", variableLookup);
			assertTrue("SQL popup переменных должен быть LookupImpl", variableLookup instanceof LookupImpl);
			LookupElement wrongElement = Arrays.stream(myFixture.getLookupElements())
					.filter(e -> "dataName".equals(e.getLookupString()))
					.findFirst()
					.orElse(null);
			assertNotNull("SQL popup переменных должен содержать соседнюю переменную dataName", wrongElement);
			((LookupImpl) variableLookup).setCurrentItem(wrongElement);

			typeWithAutoPopup(".", "Не дождались popup ключей после точки в активном SQL root-popup");

			com.intellij.openapi.editor.Document hostDocument =
					com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf);
			assertNotNull("Должен существовать document исходного Parser3-файла", hostDocument);
			String text = hostDocument.getText();
			assertTrue("Точка в активном SQL popup должна оставить вручную набранный $data.: " + text,
					text.contains("^if(def $data.)"));
			assertFalse("Точка в активном SQL popup не должна выбирать соседнюю переменную: " + text,
					text.contains("^if(def $dataName"));

			LookupImpl lookup = assertActiveLookupImpl("SQL popup ключей после точки");
			assertActiveLookupFirstItemSelected("SQL popup ключей после точки", lookup);
			assertRealKeysAboveDotTemplates(
					"SQL popup ключей после точки",
					getLookupNames(lookup),
					List.of("id", "name"),
					List.of("foreach[]", "mail:send[]"));
		});
	}

	public void testSqlInjectedObjectMethodAutoPopupConfidenceKeepsParser3Completion() {
		createParser3FileInDir("www/sql_injected_object_method_autopopup_confidence.p",
				"@main[]\n" +
						"# Parser3 completion внутри SQL должен оставаться разрешённым для ^data.for\n" +
						"$data[^hash::create[]]\n" +
						"$list[^table::sql{\n" +
						"\tSELECT\n" +
						"\t\tid, name\n" +
						"\tFROM\n" +
						"\t\titems\n" +
						"\tWHERE\n" +
						"\t\tid in (^data.for<caret>)\n" +
						"}]\n"
		);
		VirtualFile vf = myFixture.findFileInTempDir("www/sql_injected_object_method_autopopup_confidence.p");
		myFixture.configureFromExistingVirtualFile(vf);

		int offset = myFixture.getEditor().getCaretModel().getOffset();
		com.intellij.psi.PsiElement contextElement = myFixture.getFile().findElementAt(Math.max(0, offset - 1));
		assertNotNull("В SQL-блоке должен быть PSI element перед caret", contextElement);
		com.intellij.util.ThreeState decision =
				new ru.artlebedev.parser3.lang.Parser3CompletionConfidence()
						.shouldSkipAutopopup(contextElement, myFixture.getFile(), offset);
		assertEquals("Parser3 auto-popup для ^data.for внутри SQL должен быть разрешён", com.intellij.util.ThreeState.NO, decision);
	}

	public void testParser3CaretCallArgumentContextRecognizesBracketVariants() {
		assertTrue("Круглые скобки Parser3-вызова должны считаться аргументами",
				P3CompletionUtils.isParser3CaretCallArgumentContext("^if(def", "^if(def".length()));
		assertTrue("Квадратные скобки Parser3-вызова должны считаться аргументами",
				P3CompletionUtils.isParser3CaretCallArgumentContext("^method[def", "^method[def".length()));
		assertTrue("Фигурные скобки Parser3-вызова должны считаться аргументами",
				P3CompletionUtils.isParser3CaretCallArgumentContext("^method{def", "^method{def".length()));
		assertFalse("Объектный Parser3 completion после точки не должен считаться простым аргументом вызова",
				P3CompletionUtils.isParser3CaretCallArgumentContext("^data.for", "^data.for".length()));
	}

	public void testHtmlInjectedMethodCompletion_savedPrefixKeepsObjectMethods() {
		createParser3FileInDir("www/errors_1_real_html_injected_saved_prefix.p",
				"@main[]\n" +
						"# errors/1.p: реальный минимальный HTML injected saved-prefix completion\n" +
						"$data[^hash::create[]]\n" +
						"$list[^table::sql{\n" +
						"\tSELECT\n" +
						"\t\tid, name\n" +
						"\tFROM\n" +
						"\t\titems\n" +
						"\tWHERE\n" +
						"\t\tid in (^data.foreach)\n" +
						"}]\n" +
						"\n" +
						"<div ^data.for<caret>>text</div>\n"
		);
		VirtualFile vf = myFixture.findFileInTempDir("www/errors_1_real_html_injected_saved_prefix.p");
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("В injected HTML completion после сохранённого префикса for должен показывать методы hash", elements);
		List<String> names = Arrays.stream(elements).map(LookupElement::getLookupString).collect(Collectors.toList());
		assertTrue("В injected HTML для ^data.for должен быть foreach, есть: " + names, names.contains("foreach"));
	}

	public void testHtmlInjectedManualDollarDotOpensHashKeyPopupWithDotTemplatesLast() throws Throwable {
		withUserTemplates(createDotUserTemplates(), () -> {
			createParser3FileInDir("www/html_injected_manual_dollar_dot_hash_keys.p",
					"@main[]\n" +
							"$data[\n" +
							"\t$.id[]\n" +
							"\t$.name[]\n" +
							"]\n" +
							"<div><caret></div>\n"
			);
			VirtualFile vf = myFixture.findFileInTempDir("www/html_injected_manual_dollar_dot_hash_keys.p");
			myFixture.configureFromExistingVirtualFile(vf);

			typeInHostEditorWithAutoPopup("$data.", "Не дождались popup ключей после ручного HTML $data.");

			com.intellij.openapi.editor.Document hostDocument =
					com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf);
			assertNotNull("Должен существовать document исходного Parser3-файла", hostDocument);
			String text = hostDocument.getText();
			assertTrue("HTML ручная точка должна оставить $data.: " + text,
					text.contains("<div>$data.</div>"));

			LookupImpl lookup = assertActiveLookupImpl("HTML popup после $data.");
			assertActiveLookupFirstItemSelected("HTML popup после $data.", lookup);
			List<String> names = getLookupNames(lookup);
			assertFalse("После HTML $data. не должен оставаться root-variable popup: " + names,
					names.contains("data."));
			assertRealKeysAboveDotTemplates(
					"HTML popup после $data.",
					names,
					List.of("id", "name"),
					List.of("foreach[]", "mail:send[]"));
		});
	}

	public void testHtmlInjectedDollarVariableSemicolonDoesNotShowLatePopup() throws Throwable {
		createParser3FileInDir("www/html_injected_dollar_variable_semicolon_no_late_popup.p",
				"@main[]\n" +
						"$data[\n" +
						"\t$.id[]\n" +
						"]\n" +
						"<div><caret></div>\n"
		);
		VirtualFile vf = myFixture.findFileInTempDir("www/html_injected_dollar_variable_semicolon_no_late_popup.p");
		myFixture.configureFromExistingVirtualFile(vf);

		typeInHostEditorWithAutoPopup("$da;", "Не дождались delayed actions после быстрого HTML $da;");

		com.intellij.openapi.editor.Document hostDocument =
				com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf);
		assertNotNull("Должен существовать document исходного Parser3-файла", hostDocument);
		String text = hostDocument.getText();
		assertTrue("HTML ввод $da; должен остаться ручным текстом: " + text,
				text.contains("<div>$da;</div>"));
		assertFalse("HTML ввод $da; не должен выбирать $data из popup: " + text,
				text.contains("<div>$data"));
		assertNoActiveLookup("После HTML $da; не должен оставаться поздний popup");
	}

	public void testHtmlInjectedMethodCompletion_savedPrefixShowsBuiltinObjectMethods() {
		createParser3FileInDir("www/errors_1_real_html_injected_saved_prefix_delete.p",
				"@main[]\n" +
						"# errors/1.p: реальный минимальный HTML injected delete completion\n" +
						"$data[^hash::create[]]\n" +
						"\n" +
						"<div ^data.de<caret>>text</div>\n"
		);
		VirtualFile vf = myFixture.findFileInTempDir("www/errors_1_real_html_injected_saved_prefix_delete.p");
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("В injected HTML completion после сохранённого префикса de должен показывать методы hash", elements);
		List<String> names = Arrays.stream(elements).map(LookupElement::getLookupString).collect(Collectors.toList());
		assertTrue("В injected HTML для ^data.de должен быть delete, есть: " + names, names.contains("delete"));
	}

	public void testRealMixedSqlAndHtmlInjectedCompletion_htmlDeleteIsNotHiddenByUserTemplates() {
		createParser3FileInDir("www/errors_1_real_mixed_html_delete.p",
				"@main[]\n" +
						"# errors/1.p: реальный смешанный SQL+HTML delete completion\n" +
						"$data[^hash::create[]]\n" +
						"$list[^table::sql{\n" +
						"\tSELECT\n" +
						"\t\tid, name\n" +
						"\tFROM\n" +
						"\t\titems\n" +
						"\tWHERE\n" +
						"\t\tid in (^data.for)\n" +
						"}]\n" +
						"\n" +
						"<div ^data.de<caret>>text</div>\n"
		);
		VirtualFile vf = myFixture.findFileInTempDir("www/errors_1_real_mixed_html_delete.p");
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("В смешанном SQL+HTML файле completion должен показывать методы hash, а не только шаблоны", elements);
		List<String> names = Arrays.stream(elements).map(LookupElement::getLookupString).collect(Collectors.toList());
		assertTrue("В смешанном SQL+HTML файле для ^data.de должен быть delete, есть: " + names, names.contains("delete"));
	}

	public void testErrors1RealFileHtmlDeleteCompletion() {
		String content =
				"@main[]\n" +
						"# errors/1.p: реальный файл с HTML delete completion\n" +
						"$data[^hash::create[]]\n" +
						"$list[^table::sql{\n" +
						"\tSELECT\n" +
						"\t\tid, name\n" +
						"\tFROM\n" +
						"\t\titems\n" +
						"\tWHERE\n" +
						"\t\tid in (^data.de)\n" +
						"}]\n" +
						"\n" +
						"<div ^data.de>text</div>\n" +
						"\n" +
						"после \"for\" нажать Ctrl + space - никаких вариантов нет, а должно быть \"foreach\". похоже что это только в injected, в остальных местах нормально\n" +
						"\n" +
						"уточнение: сразу после точки \"foreach\" появляется, но если написать \"for\" ничего не выбрать, убрать курсор, потом снова венуть на место и нажать Ctrl + space — тогда ничего не появляется\n";
		createParser3FileInDir("www/errors/1.p", content);
		VirtualFile vf = myFixture.findFileInTempDir("www/errors/1.p");
		myFixture.configureFromExistingVirtualFile(vf);
		int htmlOffset = content.indexOf("<div ^data.de") + "<div ^data.de".length();
		assertTrue("В тестовом errors/1.p должен быть HTML-кейс ^data.de", htmlOffset >= 0);
		myFixture.getEditor().getCaretModel().moveToOffset(htmlOffset);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("В реальном errors/1.p completion должен показывать методы hash, а не только шаблоны", elements);
		List<String> names = Arrays.stream(elements).map(LookupElement::getLookupString).collect(Collectors.toList());
		assertTrue("В реальном errors/1.p для HTML ^data.de должен быть delete, есть: " + names, names.contains("delete"));
	}

	public void testHtmlInjectedUserTemplateInsert_replacesOnlyMethodPrefix() {
		createParser3FileInDir("www/errors_1_real_html_injected_user_template_insert.p",
				"@main[]\n" +
						"# errors/1.p: реальный HTML user template insert\n" +
						"$data[^hash::create[]]\n" +
						"\n" +
						"<div ^data.for<caret>>text</div>\n"
		);
		VirtualFile vf = myFixture.findFileInTempDir("www/errors_1_real_html_injected_user_template_insert.p");
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		selectCompletionByEnter("foreach[]");
		com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents();

		com.intellij.openapi.editor.Document hostDocument =
				com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf);
		assertNotNull("Должен существовать document исходного Parser3-файла", hostDocument);
		String text = hostDocument.getText();
		assertTrue("Шаблон .foreach[] в HTML должен сохранить receiver и surrounding HTML: " + text,
				text.contains("<div ^data.foreach[key;value]{"));
		assertTrue("Шаблон .foreach[] в HTML должен сохранить закрытие тега: " + text,
				text.contains("}>text</div>"));
		assertFalse("Шаблон .foreach[] в HTML не должен оставлять двойную точку: " + text,
				text.contains("^data..foreach"));
		assertFalse("Шаблон .foreach[] в HTML не должен оставлять временный lookup: " + text,
				text.contains("^data.foreach[]"));
	}

	public void testHtmlInjectedUserTemplateInsert_shortPrefixKeepsTagTail() {
		String content =
				"@main[]\n" +
						"# errors/1.p: реальный HTML user template short prefix\n" +
						"$data[^hash::create[]]\n" +
						"\n" +
						"<div ^data.fo>text</div>\n";
		createParser3FileInDir("www/errors_1_real_html_injected_user_template_short_prefix.p", content);
		VirtualFile vf = myFixture.findFileInTempDir("www/errors_1_real_html_injected_user_template_short_prefix.p");
		myFixture.configureFromExistingVirtualFile(vf);
		int htmlOffset = content.indexOf("<div ^data.fo") + "<div ^data.fo".length();
		assertTrue("В тестовом HTML-кейсе должен быть ^data.fo", htmlOffset >= 0);
		myFixture.getEditor().getCaretModel().moveToOffset(htmlOffset);

		myFixture.complete(CompletionType.BASIC);
		selectCompletionByEnter("foreach[]");
		com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents();

		com.intellij.openapi.editor.Document hostDocument =
				com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf);
		assertNotNull("Должен существовать document исходного Parser3-файла", hostDocument);
		String text = hostDocument.getText();
		assertTrue("Шаблон .foreach[] в HTML должен сохранить receiver: " + text,
				text.contains("<div ^data.foreach[key;value]{"));
		assertTrue("Шаблон .foreach[] в HTML не должен удалять хвост тега: " + text,
				text.contains("}>text</div>"));
		assertFalse("Шаблон .foreach[] в HTML не должен оставлять набранный префикс: " + text,
				text.contains("^data.foforeach"));
	}

	public void testErrors1RealFileHtmlUserTemplateShortPrefixKeepsTagTail() {
		String content =
				"@main[]\n" +
						"# errors/1.p: реальный файл HTML user template short prefix\n" +
						"$data[^hash::create[]]\n" +
						"$list[^table::sql{\n" +
						"\tSELECT\n" +
						"\t\tid, name\n" +
						"\tFROM\n" +
						"\t\titems\n" +
						"\tWHERE\n" +
						"\t\tid in (^data.de)\n" +
						"}]\n" +
						"\n" +
						"<div ^data.fo>text</div>\n" +
						"\n" +
						"после \"for\" нажать Ctrl + space - никаких вариантов нет, а должно быть \"foreach\". похоже что это только в injected, в остальных местах нормально\n" +
						"\n" +
						"уточнение: сразу после точки \"foreach\" появляется, но если написать \"for\" ничего не выбрать, убрать курсор, потом снова венуть на место и нажать Ctrl + space — тогда ничего не появляется\n";
		createParser3FileInDir("www/errors/1.p", content);
		VirtualFile vf = myFixture.findFileInTempDir("www/errors/1.p");
		myFixture.configureFromExistingVirtualFile(vf);
		int htmlOffset = content.indexOf("<div ^data.fo") + "<div ^data.fo".length();
		assertTrue("В реальном errors/1.p должен быть HTML-кейс ^data.fo", htmlOffset >= 0);
		myFixture.getEditor().getCaretModel().moveToOffset(htmlOffset);

		myFixture.complete(CompletionType.BASIC);
		selectCompletionByEnter("foreach[]");
		com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents();

		com.intellij.openapi.editor.Document hostDocument =
				com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf);
		assertNotNull("Должен существовать document исходного Parser3-файла", hostDocument);
		String text = hostDocument.getText();
		assertTrue("Шаблон .foreach[] в реальном errors/1.p должен сохранить receiver: " + text,
				text.contains("<div ^data.foreach[key;value]{"));
		assertTrue("Шаблон .foreach[] в реальном errors/1.p не должен удалять хвост тега: " + text,
				text.contains("}>text</div>"));
		assertFalse("Шаблон .foreach[] в реальном errors/1.p не должен оставлять набранный префикс: " + text,
				text.contains("^data.foforeach"));
	}

	public void testErrors1RealFileHtmlUserTemplateShortPrefixByTabKeepsTagTail() {
		String content =
				"@main[]\n" +
						"# errors/1.p: реальный файл HTML user template short prefix by Tab\n" +
						"$data[^hash::create[]]\n" +
						"$list[^table::sql{\n" +
						"\tSELECT\n" +
						"\t\tid, name\n" +
						"\tFROM\n" +
						"\t\titems\n" +
						"\tWHERE\n" +
						"\t\tid in (^data.de)\n" +
						"}]\n" +
						"\n" +
						"<div ^data.fo>text</div>\n" +
						"\n" +
						"после \"for\" нажать Ctrl + space - никаких вариантов нет, а должно быть \"foreach\". похоже что это только в injected, в остальных местах нормально\n" +
						"\n" +
						"уточнение: сразу после точки \"foreach\" появляется, но если написать \"for\" ничего не выбрать, убрать курсор, потом снова венуть на место и нажать Ctrl + space — тогда ничего не появляется\n";
		createParser3FileInDir("www/errors/1.p", content);
		VirtualFile vf = myFixture.findFileInTempDir("www/errors/1.p");
		myFixture.configureFromExistingVirtualFile(vf);
		int htmlOffset = content.indexOf("<div ^data.fo") + "<div ^data.fo".length();
		assertTrue("В реальном errors/1.p должен быть HTML-кейс ^data.fo", htmlOffset >= 0);
		myFixture.getEditor().getCaretModel().moveToOffset(htmlOffset);

		myFixture.complete(CompletionType.BASIC);
		selectCompletionByTab("foreach[]");
		com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents();

		com.intellij.openapi.editor.Document hostDocument =
				com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf);
		assertNotNull("Должен существовать document исходного Parser3-файла", hostDocument);
		String text = hostDocument.getText();
		assertTrue("Шаблон .foreach[] по Tab в реальном errors/1.p должен сохранить receiver: " + text,
				text.contains("<div ^data.foreach[key;value]{"));
		assertTrue("Шаблон .foreach[] по Tab в реальном errors/1.p не должен удалять хвост тега: " + text,
				text.contains("}>text</div>"));
		assertFalse("Шаблон .foreach[] по Tab в реальном errors/1.p не должен оставлять набранный префикс: " + text,
				text.contains("^data.foforeach"));
	}

	public void testRealMixedSqlAndHtmlInjectedUserTemplateInsert_htmlReplacesOnlyMethodPrefix() {
		createParser3FileInDir("www/errors_1_real_mixed_html_user_template_insert.p",
				"@main[]\n" +
						"# errors/1.p: реальный смешанный HTML user template insert\n" +
						"$data[^hash::create[]]\n" +
						"$list[^table::sql{\n" +
						"\tSELECT\n" +
						"\t\tid, name\n" +
						"\tFROM\n" +
						"\t\titems\n" +
						"\tWHERE\n" +
						"\t\tid in (^data.for)\n" +
						"}]\n" +
						"\n" +
						"<div ^data.for<caret>>text</div>\n"
		);
		VirtualFile vf = myFixture.findFileInTempDir("www/errors_1_real_mixed_html_user_template_insert.p");
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		selectCompletionByEnter("foreach[]");
		com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents();

		com.intellij.openapi.editor.Document hostDocument =
				com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf);
		assertNotNull("Должен существовать document исходного Parser3-файла", hostDocument);
		String text = hostDocument.getText();
		assertTrue("Шаблон .foreach[] в смешанном HTML должен сохранить receiver и surrounding HTML: " + text,
				text.contains("<div ^data.foreach[key;value]{"));
		assertTrue("Шаблон .foreach[] в смешанном HTML должен сохранить закрытие тега: " + text,
				text.contains("}>text</div>"));
		assertFalse("Шаблон .foreach[] в смешанном HTML не должен оставлять двойную точку: " + text,
				text.contains("^data..foreach"));
		assertFalse("Шаблон .foreach[] в смешанном HTML не должен оставлять временный lookup: " + text,
				text.contains("^data.foreach[]"));
	}

	public void testSqlInjectedUserTemplateInsert_replacesOnlyMethodPrefix() {
		createParser3FileInDir("www/errors_1_real_sql_injected_user_template_insert.p",
				"@main[]\n" +
						"# errors/1.p: реальный SQL user template insert\n" +
						"$data[^hash::create[]]\n" +
						"$list[^table::sql{\n" +
						"\tSELECT\n" +
						"\t\tid, name\n" +
						"\tFROM\n" +
						"\t\titems\n" +
						"\tWHERE\n" +
						"\t\tid in (^data.for<caret>)\n" +
						"}]\n"
		);
		VirtualFile vf = myFixture.findFileInTempDir("www/errors_1_real_sql_injected_user_template_insert.p");
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		selectCompletionByEnter("foreach[]");
		com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents();

		com.intellij.openapi.editor.Document hostDocument =
				com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf);
		assertNotNull("Должен существовать document исходного Parser3-файла", hostDocument);
		String text = hostDocument.getText();
		assertTrue("Шаблон .foreach[] в SQL должен сохранить receiver и SQL-обёртку: " + text,
				text.contains("\t\tid in (^data.foreach[key;value]{"));
		assertTrue("Шаблон .foreach[] в SQL должен сохранить закрывающую скобку SQL-условия: " + text,
				text.contains("})\n}]"));
		assertFalse("Шаблон .foreach[] в SQL не должен оставлять двойную точку: " + text,
				text.contains("^data..foreach"));
		assertFalse("Шаблон .foreach[] в SQL не должен оставлять временный lookup: " + text,
				text.contains("^data.foreach[]"));
	}

	public void testRealMixedSqlAndHtmlInjectedUserTemplateInsert_sqlReplacesOnlyMethodPrefix() {
		createParser3FileInDir("www/errors_1_real_mixed_sql_user_template_insert.p",
				"@main[]\n" +
						"# errors/1.p: реальный смешанный SQL user template insert\n" +
						"$data[^hash::create[]]\n" +
						"$list[^table::sql{\n" +
						"\tSELECT\n" +
						"\t\tid, name\n" +
						"\tFROM\n" +
						"\t\titems\n" +
						"\tWHERE\n" +
						"\t\tid in (^data.for<caret>)\n" +
						"}]\n" +
						"\n" +
						"<div ^data.de>text</div>\n"
		);
		VirtualFile vf = myFixture.findFileInTempDir("www/errors_1_real_mixed_sql_user_template_insert.p");
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		selectCompletion("foreach[]");
		com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents();

		com.intellij.openapi.editor.Document hostDocument =
				com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf);
		assertNotNull("Должен существовать document исходного Parser3-файла", hostDocument);
		String text = hostDocument.getText();
		assertTrue("Шаблон .foreach[] в смешанном SQL должен сохранить receiver и SQL-обёртку: " + text,
				text.contains("\t\tid in (^data.foreach[key;value]{"));
		assertTrue("Шаблон .foreach[] в смешанном SQL должен сохранить закрывающую скобку SQL-условия: " + text,
				text.contains("})\n}]\n\n<div ^data.de>text</div>"));
		assertFalse("Шаблон .foreach[] в смешанном SQL не должен оставлять двойную точку: " + text,
				text.contains("^data..foreach"));
		assertFalse("Шаблон .foreach[] в смешанном SQL не должен оставлять временный lookup: " + text,
				text.contains("^data.foreach[]"));
	}

	public void testCssInjectedMethodCompletion_savedPrefixKeepsObjectMethods() {
		createParser3FileInDir("www/errors_1_real_css_injected_saved_prefix.p",
				"@main[]\n" +
						"# errors/1.p: реальный минимальный CSS injected saved-prefix completion\n" +
						"$data[^hash::create[]]\n" +
						"<style>\n" +
						".item { color: ^data.for<caret>; }\n" +
						"</style>\n"
		);
		VirtualFile vf = myFixture.findFileInTempDir("www/errors_1_real_css_injected_saved_prefix.p");
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("В injected CSS completion после сохранённого префикса for должен показывать методы hash", elements);
		List<String> names = Arrays.stream(elements).map(LookupElement::getLookupString).collect(Collectors.toList());
		assertTrue("В injected CSS для ^data.for должен быть foreach, есть: " + names, names.contains("foreach"));
	}

	public void testDollarCompletion_methodResultHashEntryFromRealErrors1ShowsNestedMethodKeys() {
		List<String> completions = getCompletions(
				"errors_1_method_result_hash_entry_nested_method.p",
				"@main[]\n" +
						"# реальный кейс из errors/1.p\n" +
						"$res[^method[]]\n" +
						"$res.data.x.<caret>\n" +
						"\n" +
						"@method[]\n" +
						"$result[\n" +
						"\t$.data[\n" +
						"\t\t$.now[^date::now[]]\n" +
						"\t\t$.x[^method2[]]\n" +
						"\t]\n" +
						"]\n" +
						"\n" +
						"@method2[]\n" +
						"$result[\n" +
						"\t$.xxx[]\n" +
						"]\n"
		);

		assertTrue("У $res.data.x. должен быть ключ xxx из результата method2, есть: " + completions,
				completions.contains("xxx"));
		assertEquals("Ключ xxx не должен дублироваться: " + completions,
				1, java.util.Collections.frequency(completions, "xxx"));
	}

	public void testDollarCompletion_methodResultSelfSourceFromTelegramDoesNotOverflow() {
		List<String> completions;
		try {
			completions = getCompletions(
					"telegram_method_result_self_source_cycle.p",
					"@CLASS\n" +
							"telegram\n" +
							"\n" +
							"@send[]\n" +
							"$params[\n" +
							"\t$.url[]\n" +
							"]\n" +
							"$result[^json:parse[]]\n" +
							"^if($params.isDebug){\n" +
							"\t$result[\n" +
							"\t\t$.params[$params]\n" +
							"\t\t$.response[$result]\n" +
							"\t]\n" +
							"}\n" +
							"\n" +
							"@main[]\n" +
							"$res[^telegram:send[]]\n" +
							"$res.<caret>\n"
			);
		} catch (StackOverflowError e) {
			fail("Индекс результата метода не должен падать на $.response[$result] из telegram.p");
			return;
		}

		assertTrue("Результат send должен сохранить params, есть: " + completions,
				completions.contains("params."));
		assertTrue("Результат send должен сохранить response, есть: " + completions,
				completions.contains("response.") || completions.contains("response"));
		assertEquals("params не должен дублироваться: " + completions,
				1, java.util.Collections.frequency(completions, "params."));
		assertEquals("response не должен дублироваться: " + completions,
				1, java.util.Collections.frequency(completions, "response.")
						+ java.util.Collections.frequency(completions, "response"));
	}

	public void testDollarCompletion_realErrors2MethodResultResponseUsesPreviousResultShape() {
		String base =
				"@CLASS\n" +
						"tg\n" +
						"\n" +
						"@create[]\n" +
						"$res[^self.send[uri]]\n" +
						"%CARET%\n" +
						"\n" +
						"@send[]\n" +
						"$result[^json:parse[^taint[as-is][$f.text]]]\n" +
						"^result.add[\n" +
						"\t$.data[^date::now[]]\n" +
						"]\n" +
						"$result[\n" +
						"\t$.params[$params]\n" +
						"\t$.response[$result]\n" +
						"]\n";

		List<String> rootCompletions = getCompletions(
				"errors_2_method_result_response_root.p",
				base.replace("%CARET%", "$res.<caret>"));
		assertTrue("Корень результата send должен содержать response, есть: " + rootCompletions,
				rootCompletions.contains("response."));
		assertEquals("response в корне не должен дублироваться: " + rootCompletions,
				1, java.util.Collections.frequency(rootCompletions, "response."));

		List<String> responseCompletions = getCompletions(
				"errors_2_method_result_response_nested.p",
				base.replace("%CARET%", "$res.response.<caret>"));
		assertTrue("$res.response. должен видеть data из предыдущего $result, есть: " + responseCompletions,
				responseCompletions.contains("data."));
		assertEquals("$res.response. не должен снова показывать response: " + responseCompletions,
				0, java.util.Collections.frequency(responseCompletions, "response."));
	}

	public void testDollarCompletion_methodResultHashEntryNestedValueFromGetterShowsHashKeys() {
		List<String> completions = getCompletions(
				"errors_1_method_result_hash_entry_nested_getter_value.p",
				"@main[]\n" +
						"$res[^method[]]\n" +
						"$res.data.x.xxx.<caret>\n" +
						"\n" +
						"@method[]\n" +
						"$result[\n" +
						"\t$.data[\n" +
						"\t\t$.now[^date::now[]]\n" +
						"\t\t$.x[^method2[]]\n" +
						"\t]\n" +
						"]\n" +
						"\n" +
						"@method2[]\n" +
						"$2var[^inner::create[]]\n" +
						"$result[\n" +
						"\t$.xxx[$2var.n]\n" +
						"]\n" +
						"\n" +
						"@CLASS\n" +
						"inner\n" +
						"\n" +
						"@create[]\n" +
						"$result[$self]\n" +
						"\n" +
						"@GET_n[]\n" +
						"$result[\n" +
						"\t$.var[^date::now[]]\n" +
						"]\n"
		);

		assertSinglePropertyCompletion(
				"У $res.data.x.xxx. должен быть ключ var из @GET_n",
				completions,
				"var");
	}

	public void testDollarCompletion_realErrors1MethodResultBranchesKeepNestedShape() {
		String content =
				"@main[]\n" +
						"$res[^method[]]\n" +
						"$res.data.x.<caret>\n" +
						"\n" +
						"@method[]\n" +
						"$result[\n" +
						"\t$.data[\n" +
						"\t\t$.now[^date::now[]]\n" +
						"\t\t$.x[^method2[]]\n" +
						"\t]\n" +
						"]\n" +
						"\n" +
						"@method2[]\n" +
						"$2var[^inner::create[]]\n" +
						"$result[\n" +
						"\t$.aaa[^2var.xxx[]]\n" +
						"\t$.bbb[^2var:xxx[]]\n" +
						"\t$.ccc[$2var.n]\n" +
						"\t$.ddd[^2var.n[]]\n" +
						"]\n" +
						"\n" +
						"@CLASS\n" +
						"inner\n" +
						"\n" +
						"@create[]\n" +
						"$result[$self]\n" +
						"\n" +
						"@xxx[]\n" +
						"$result[\n" +
						"\t$.var2[^date::now[]]\n" +
						"]\n" +
						"\n" +
						"@GET_n[]\n" +
						"$result[\n" +
						"\t$.var[^date::now[]]\n" +
						"]\n";

		List<String> completions = getCompletions("errors_1_real_method_result_branches.p", content);

		assertSinglePropertyCompletion("aaa должен вставляться как структурный ключ", completions, "aaa");
		assertSinglePropertyCompletion("bbb должен вставляться как структурный ключ", completions, "bbb");
		assertSinglePropertyCompletion("ccc должен вставляться как структурный ключ", completions, "ccc");
		assertSinglePropertyCompletion("ddd должен вставляться как структурный ключ", completions, "ddd");
		assertTrue("aaa должен вставляться с точкой: " + completions, completions.contains("aaa."));
		assertTrue("bbb должен вставляться с точкой: " + completions, completions.contains("bbb."));
		assertTrue("ccc должен вставляться с точкой: " + completions, completions.contains("ccc."));
		assertTrue("ddd должен вставляться с точкой: " + completions, completions.contains("ddd."));
	}

	public void testDollarCompletion_realErrors1MethodResultBranchesShowNestedKeys() {
		String base =
				"@main[]\n" +
						"$res[^method[]]\n" +
						"%CARET%\n" +
						"\n" +
						"@method[]\n" +
						"$result[\n" +
						"\t$.data[\n" +
						"\t\t$.now[^date::now[]]\n" +
						"\t\t$.x[^method2[]]\n" +
						"\t]\n" +
						"]\n" +
						"\n" +
						"@method2[]\n" +
						"$2var[^inner::create[]]\n" +
						"$result[\n" +
						"\t$.aaa[^2var.xxx[]]\n" +
						"\t$.bbb[^2var:xxx[]]\n" +
						"\t$.ccc[$2var.n]\n" +
						"\t$.ddd[^2var.n[]]\n" +
						"]\n" +
						"\n" +
						"@CLASS\n" +
						"inner\n" +
						"\n" +
						"@create[]\n" +
						"$result[$self]\n" +
						"\n" +
						"@xxx[]\n" +
						"$result[\n" +
						"\t$.var2[^date::now[]]\n" +
						"]\n" +
						"\n" +
						"@GET_n[]\n" +
						"$result[\n" +
						"\t$.var[^date::now[]]\n" +
						"]\n";

		assertSinglePropertyCompletion(
				"aaa должен показывать ключ var2 из метода объекта",
				getCompletions("errors_1_real_aaa_nested_key.p",
						base.replace("%CARET%", "$res.data.x.aaa.<caret>")),
				"var2");
		assertSinglePropertyCompletion(
				"bbb должен показывать ключ var2 из class-style вызова",
				getCompletions("errors_1_real_bbb_nested_key.p",
						base.replace("%CARET%", "$res.data.x.bbb.<caret>")),
				"var2");
		assertSinglePropertyCompletion(
				"ccc должен показывать ключ var из getter-значения",
				getCompletions("errors_1_real_ccc_nested_key.p",
						base.replace("%CARET%", "$res.data.x.ccc.<caret>")),
				"var");
		assertSinglePropertyCompletion(
				"ddd должен показывать ключ var из getter-вызова",
				getCompletions("errors_1_real_ddd_nested_key.p",
						base.replace("%CARET%", "$res.data.x.ddd.<caret>")),
				"var");
	}

	public void testDollarCompletion_realErrors1NestedDateFieldsVisible() {
		String base =
				"@main[]\n" +
						"$res[^method[]]\n" +
						"%CARET%\n" +
						"\n" +
						"@method[]\n" +
						"$result[\n" +
						"\t$.data[\n" +
						"\t\t$.now[^date::now[]]\n" +
						"\t\t$.x[^method2[]]\n" +
						"\t]\n" +
						"]\n" +
						"\n" +
						"@method2[]\n" +
						"$2var[^inner::create[]]\n" +
						"$result[\n" +
						"\t$.aaa[^2var.xxx[]]\n" +
						"\t$.bbb[^2var:xxx[]]\n" +
						"\t$.ccc[$2var.n]\n" +
						"\t$.ddd[^2var.n[]]\n" +
						"]\n" +
						"\n" +
						"@CLASS\n" +
						"inner\n" +
						"\n" +
						"@create[]\n" +
						"$result[$self]\n" +
						"\n" +
						"@xxx[]\n" +
						"$result[\n" +
						"\t$.var2[^date::now[]]\n" +
						"]\n" +
						"\n" +
						"@GET_n[]\n" +
						"$result[\n" +
						"\t$.var[^date::now[]]\n" +
						"]\n";

		for (String path : java.util.List.of(
				"$res.data.x.aaa.var2.<caret>",
				"$res.data.x.bbb.var2.<caret>",
				"$res.data.x.ccc.var.<caret>",
				"$res.data.x.ddd.var.<caret>"
		)) {
			List<String> completions = getCompletions(
					"errors_1_real_nested_date_fields_" + path.replaceAll("[^a-zA-Z0-9]+", "_") + ".p",
					base.replace("%CARET%", path));
			assertTrue("У вложенного date значения должен быть day для " + path + ", есть: " + completions,
					completions.contains("day"));
			assertTrue("У вложенного date значения должен быть year для " + path + ", есть: " + completions,
					completions.contains("year"));
		}
	}

	public void testDollarCompletion_realErrors1NestedDateFieldManualPrefixVisible() {
		String base =
				"@main[]\n" +
						"$res[^method[]]\n" +
						"%CARET%\n" +
						"\n" +
						"@method[]\n" +
						"$result[\n" +
						"\t$.data[\n" +
						"\t\t$.now[^date::now[]]\n" +
						"\t\t$.x[^method2[]]\n" +
						"\t]\n" +
						"]\n" +
						"\n" +
						"@method2[]\n" +
						"$2var[^inner::create[]]\n" +
						"$result[\n" +
						"\t$.aaa[^2var.xxx[]]\n" +
						"\t$.bbb[^2var:xxx[]]\n" +
						"\t$.ccc[$2var.n]\n" +
						"\t$.ddd[^2var.n[]]\n" +
						"]\n" +
						"\n" +
						"@CLASS\n" +
						"inner\n" +
						"\n" +
						"@create[]\n" +
						"$result[$self]\n" +
						"\n" +
						"@xxx[]\n" +
						"$result[\n" +
						"\t$.var2[^date::now[]]\n" +
						"]\n" +
						"\n" +
						"@GET_n[]\n" +
						"$result[\n" +
						"\t$.var[^date::now[]]\n" +
						"]\n";

		for (String path : java.util.List.of(
				"$res.data.x.aaa.var2.d<caret>",
				"$res.data.x.aaa.var2.day<caret>"
		)) {
			List<String> completions = getCompletions(
					"errors_1_real_nested_date_field_manual_prefix_" + path.replaceAll("[^a-zA-Z0-9]+", "_") + ".p",
					base.replace("%CARET%", path));
			assertTrue("Manual Ctrl+Space должен видеть day для " + path + ", есть: " + completions,
					completions.contains("day"));
			assertEquals("day не должен дублироваться для " + path + ": " + completions,
					1, java.util.Collections.frequency(completions, "day"));
		}
	}

	public void testDollarCompletion_methodResultHashEntryDepthFiveShowsNestedMethodKeys() {
		List<String> completions = getCompletions(
				"method_result_hash_entry_depth_five.p",
				"@main[]\n" +
						"# цепочка method-result глубиной 5\n" +
						"$res[^method1[]]\n" +
						"$res.a.b.c.d.<caret>\n" +
						"\n" +
						"@method1[]\n" +
						"$result[$.a[^method2[]]]\n" +
						"\n" +
						"@method2[]\n" +
						"$result[$.b[^method3[]]]\n" +
						"\n" +
						"@method3[]\n" +
						"$result[$.c[^method4[]]]\n" +
						"\n" +
						"@method4[]\n" +
						"$result[$.d[^method5[]]]\n" +
						"\n" +
						"@method5[]\n" +
						"$result[$.final_key[]]\n"
		);

		assertTrue("Глубина 5 должна довести completion до final_key, есть: " + completions,
				completions.contains("final_key"));
	}

	public void testDollarCompletion_methodResultHashEntryCycleDoesNotHang() {
		List<String> completions = getCompletions(
				"method_result_hash_entry_cycle_no_hang.p",
				"@main[]\n" +
						"# циклический method-result не должен подвешивать completion\n" +
						"$res[^method1[]]\n" +
						"$res.loop.<caret>\n" +
						"\n" +
						"@method1[]\n" +
						"$result[$.loop[^method2[]]]\n" +
						"\n" +
						"@method2[]\n" +
						"$result[$.loop[^method1[]]]\n"
		);

		assertNotNull("Completion должен завершиться без исключения на цикле", completions);
	}

	public void testDollarCompletion_methodResultHashEntryFromAtUseFileShowsNestedMethodKeys() {
		createParser3FileInDir("www/lib_method_result_hash_entry_at_use.p",
				"@method[]\n" +
						"$result[$.data[$.x[^method2[]]]]\n" +
						"\n" +
						"@method2[]\n" +
						"$result[$.xxx[]]\n"
		);
		createParser3FileInDir("www/page_method_result_hash_entry_at_use.p",
				"@USE\n" +
						"lib_method_result_hash_entry_at_use.p\n" +
						"\n" +
						"@main[]\n" +
						"$res[^method[]]\n" +
						"$res.data.x.<caret>\n"
		);
		VirtualFile vf = myFixture.findFileInTempDir("www/page_method_result_hash_entry_at_use.p");
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("Для @USE должны быть completion-варианты", elements);
		List<String> completions = Arrays.stream(elements).map(LookupElement::getLookupString).collect(Collectors.toList());
		assertTrue("Через @USE должен быть ключ xxx, есть: " + completions, completions.contains("xxx"));
	}

	public void testDollarCompletion_methodResultHashEntryFromUseFunctionFileShowsNestedMethodKeys() {
		createParser3FileInDir("www/lib_method_result_hash_entry_use_function.p",
				"@method[]\n" +
						"$result[$.data[$.x[^method2[]]]]\n" +
						"\n" +
						"@method2[]\n" +
						"$result[$.xxx[]]\n"
		);
		createParser3FileInDir("www/page_method_result_hash_entry_use_function.p",
				"@main[]\n" +
						"^use[lib_method_result_hash_entry_use_function.p]\n" +
						"$res[^method[]]\n" +
						"$res.data.x.<caret>\n"
		);
		VirtualFile vf = myFixture.findFileInTempDir("www/page_method_result_hash_entry_use_function.p");
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("Для ^use[] должны быть completion-варианты", elements);
		List<String> completions = Arrays.stream(elements).map(LookupElement::getLookupString).collect(Collectors.toList());
		assertTrue("Через ^use[] должен быть ключ xxx, есть: " + completions, completions.contains("xxx"));
	}

	public void testDollarCompletion_methodResultHashEntryFromAutoFileShowsNestedMethodKeys() throws Exception {
		createParser3FileInDir("www/method_result_hash_entry_auto/lib.p",
				"@method[]\n" +
						"$result[$.data[$.x[^method2[]]]]\n" +
						"\n" +
						"@method2[]\n" +
						"$result[$.xxx[]]\n"
		);
		createAutoFile("www/method_result_hash_entry_auto",
				"@auto[]\n" +
						"^use[lib.p]\n"
		);
		createParser3FileInDir("www/method_result_hash_entry_auto/page.p",
				"@main[]\n" +
						"$res[^method[]]\n" +
						"$res.data.x.<caret>\n"
		);
		VirtualFile vf = myFixture.findFileInTempDir("www/method_result_hash_entry_auto/page.p");
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("Для auto.p должны быть completion-варианты", elements);
		List<String> completions = Arrays.stream(elements).map(LookupElement::getLookupString).collect(Collectors.toList());
		assertTrue("Через auto.p должен быть ключ xxx, есть: " + completions, completions.contains("xxx"));
	}

	public void testDollarCompletion_staticClassMethodResultHashEntryShowsNestedMethodKeys() {
		List<String> completions = getCompletions(
				"static_class_method_result_hash_entry_nested_method.p",
				"@CLASS\n" +
						"Api\n" +
						"\n" +
						"@get[]\n" +
						"$result[$.data[$.x[^Api:nested[]]]]\n" +
						"\n" +
						"@nested[]\n" +
						"$result[$.xxx[]]\n" +
						"\n" +
						"@main[]\n" +
						"$res[^Api:get[]]\n" +
						"$res.data.x.<caret>\n"
		);

		assertTrue("Через ^Api:get[] должен быть ключ xxx, есть: " + completions, completions.contains("xxx"));
	}

	public void testDollarCompletion_selfMethodResultHashEntryShowsNestedMethodKeys() {
		List<String> completions = getCompletions(
				"self_method_result_hash_entry_nested_method.p",
				"@CLASS\n" +
						"ApiSelfNested\n" +
						"\n" +
						"@create[]\n" +
						"$result[$self]\n" +
						"\n" +
						"@get[]\n" +
						"$result[$.data[$.x[^self.nested[]]]]\n" +
						"\n" +
						"@nested[]\n" +
						"$result[$.xxx[]]\n" +
						"\n" +
						"@main[]\n" +
						"$api[^ApiSelfNested::create[]]\n" +
						"$res[^api.get[]]\n" +
						"$res.data.x.<caret>\n"
		);

		assertTrue("Через ^self.nested[] должен быть ключ xxx, есть: " + completions, completions.contains("xxx"));
	}

	public void testDollarCompletion_partialClassMethodResultHashEntryShowsNestedMethodKeys() {
		List<String> completions = getCompletions(
				"partial_class_method_result_hash_entry_nested_method.p",
				"@CLASS\n" +
						"ApiPartialNested\n" +
						"\n" +
						"@create[]\n" +
						"$result[$self]\n" +
						"\n" +
						"@get[]\n" +
						"$result[$.data[$.x[^self.nested[]]]]\n" +
						"\n" +
						"@CLASS\n" +
						"ApiPartialNested\n" +
						"\n" +
						"@nested[]\n" +
						"$result[$.xxx[]]\n" +
						"\n" +
						"@main[]\n" +
						"$api[^ApiPartialNested::create[]]\n" +
						"$res[^api.get[]]\n" +
						"$res.data.x.<caret>\n"
		);

		assertTrue("В partial-классе должен быть ключ xxx, есть: " + completions, completions.contains("xxx"));
	}

	public void testDollarCompletion_baseClassMethodResultHashEntryShowsNestedMethodKeys() {
		List<String> completions = getCompletions(
				"base_class_method_result_hash_entry_nested_method.p",
				"@CLASS\n" +
						"BaseNestedResult\n" +
						"\n" +
						"@nested[]\n" +
						"$result[$.base_xxx[]]\n" +
						"\n" +
						"@CLASS\n" +
						"ChildNestedResult\n" +
						"\n" +
						"@BASE\n" +
						"BaseNestedResult\n" +
						"\n" +
						"@create[]\n" +
						"$result[$self]\n" +
						"\n" +
						"@get[]\n" +
						"$result[$.data[$.x[^self.nested[]]]]\n" +
						"\n" +
						"@main[]\n" +
						"$api[^ChildNestedResult::create[]]\n" +
						"$res[^api.get[]]\n" +
						"$res.data.x.<caret>\n"
		);

		assertTrue("Метод из base должен дать ключ base_xxx, есть: " + completions,
				completions.contains("base_xxx"));
	}

	public void testDollarCompletion_childOverrideMethodResultHashEntryShowsNestedMethodKeys() {
		List<String> completions = getCompletions(
				"child_override_method_result_hash_entry_nested_method.p",
				"@CLASS\n" +
						"BaseOverrideNestedResult\n" +
						"\n" +
						"@nested[]\n" +
						"$result[$.base_xxx[]]\n" +
						"\n" +
						"@CLASS\n" +
						"ChildOverrideNestedResult\n" +
						"\n" +
						"@BASE\n" +
						"BaseOverrideNestedResult\n" +
						"\n" +
						"@create[]\n" +
						"$result[$self]\n" +
						"\n" +
						"@get[]\n" +
						"$result[$.data[$.x[^self.nested[]]]]\n" +
						"\n" +
						"@nested[]\n" +
						"$result[$.child_xxx[]]\n" +
						"\n" +
						"@main[]\n" +
						"$api[^ChildOverrideNestedResult::create[]]\n" +
						"$res[^api.get[]]\n" +
						"$res.data.x.<caret>\n"
		);

		assertTrue("Override в child должен дать ключ child_xxx, есть: " + completions,
				completions.contains("child_xxx"));
		assertFalse("Base-ключ не должен побеждать override child, есть: " + completions,
				completions.contains("base_xxx"));
	}

	public void testDollarCompletion_explicitHashEntryAfterNestedMethodResultWinsAndDeduplicates() {
		List<String> completions = getCompletions(
				"explicit_hash_entry_after_nested_method_result_wins.p",
				"@main[]\n" +
						"# явное позднее значение должно переопределить method-result\n" +
						"$res[^method[]]\n" +
						"$res.data.x[$.explicit[]]\n" +
						"$res.data.x.<caret>\n" +
						"\n" +
						"@method[]\n" +
						"$result[$.data[$.x[^method2[]]]]\n" +
						"\n" +
						"@method2[]\n" +
						"$result[$.xxx[]]\n"
		);

		assertTrue("Явное позднее значение должно дать explicit, есть: " + completions,
				completions.contains("explicit"));
		assertFalse("Старый method-result ключ xxx не должен оставаться после override, есть: " + completions,
				completions.contains("xxx"));
		assertEquals("explicit не должен дублироваться: " + completions,
				1, java.util.Collections.frequency(completions, "explicit"));
	}

	public void testDollarCompletion_unknownNestedMethodResultKeepsReadChainInference() {
		List<String> completions = getCompletions(
				"unknown_nested_method_result_keeps_read_chain_inference.p",
				"@main[]\n" +
						"# неизвестный nested method-result не должен отключать read-chain inference\n" +
						"$res[^method[]]\n" +
						"$seen[$res.data.x.read_key]\n" +
						"$res.data.x.<caret>\n" +
						"\n" +
						"@method[]\n" +
						"$result[$.data[$.x[^unknown_nested_method[]]]]\n"
		);

		assertTrue("Read-chain inference должен сохранить read_key, есть: " + completions,
				completions.contains("read_key"));
	}

	public void testBuiltinDateFieldReadChain_doesNotRewriteHashEntryType() {
		createParser3FileInDir("www/test_date_field_read_chain_no_hash_rewrite.p",
				"@main[]\n" +
						"# реальный минимальный кейс date field read-chain не переписывает hash type\n" +
						"$self.options[\n" +
						"\t$.dt_end[^date::now[]]\n" +
						"]\n" +
						"^if($self.options.dt_end.hour < 7){^self.options.dt_end.ro}\n" +
						"^self.options.dt_end.r<caret>\n"
		);
		VirtualFile vf = myFixture.findFileInTempDir("www/test_date_field_read_chain_no_hash_rewrite.p");
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("После чтения системного поля date.hour должны быть методы date", elements);
		List<String> names = Arrays.stream(elements).map(LookupElement::getLookupString).collect(Collectors.toList());
		assertTrue("Должен быть метод date roll, есть: " + names, names.contains("roll"));
		assertFalse("Не должно быть hash.rename вместо date, есть: " + names, names.contains("rename"));
		assertFalse("Не должно быть hash.reverse вместо date, есть: " + names, names.contains("reverse"));
	}

	public void testBuiltinDateFieldReadChain_resolvesFieldType() {
		createParser3FileInDir("www/test_date_field_read_chain_resolves_field_type.p",
				"@main[]\n" +
						"# реальный минимальный кейс date field read-chain resolves field type\n" +
						"$self.options[\n" +
						"\t$.dt_end[^date::now[]]\n" +
						"]\n" +
						"^self.options.dt_end.hour.f<caret>\n"
		);
		VirtualFile vf = myFixture.findFileInTempDir("www/test_date_field_read_chain_resolves_field_type.p");
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("У системного поля date.hour должен резолвиться тип int", elements);
		List<String> names = Arrays.stream(elements).map(LookupElement::getLookupString).collect(Collectors.toList());
		assertTrue("Для int должен быть format, есть: " + names, names.contains("format"));
	}

	public void testBuiltinFileFieldReadChain_doesNotRewriteHashEntryType() {
		createParser3FileInDir("www/test_file_field_read_chain_no_hash_rewrite.p",
				"@main[]\n" +
						"# реальный минимальный кейс file field read-chain не переписывает hash type\n" +
						"$self.options[\n" +
						"\t$.src[^file::load[/tmp/test.txt]]\n" +
						"]\n" +
						"^if($self.options.src.text){^self.options.src.ba}\n" +
						"^self.options.src.b<caret>\n"
		);
		VirtualFile vf = myFixture.findFileInTempDir("www/test_file_field_read_chain_no_hash_rewrite.p");
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("После чтения системного поля file.text должны быть методы file", elements);
		List<String> names = Arrays.stream(elements).map(LookupElement::getLookupString).collect(Collectors.toList());
		assertTrue("Должен быть метод file base64, есть: " + names, names.contains("base64"));
		assertFalse("Не должно быть hash.rename вместо file, есть: " + names, names.contains("rename"));
		assertFalse("Не должно быть hash.reverse вместо file, есть: " + names, names.contains("reverse"));
	}

	public void testBuiltinFileFieldReadChain_resolvesFieldType() {
		createParser3FileInDir("www/test_file_field_read_chain_resolves_field_type.p",
				"@main[]\n" +
						"# реальный минимальный кейс file field read-chain resolves field type\n" +
						"$self.options[\n" +
						"\t$.src[^file::load[/tmp/test.txt]]\n" +
						"]\n" +
						"^self.options.src.text.tr<caret>\n"
		);
		VirtualFile vf = myFixture.findFileInTempDir("www/test_file_field_read_chain_resolves_field_type.p");
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("У системного поля file.text должен резолвиться тип string", elements);
		List<String> names = Arrays.stream(elements).map(LookupElement::getLookupString).collect(Collectors.toList());
		assertTrue("Для string должен быть trim, есть: " + names, names.contains("trim"));
	}

	public void testCaretVarMethodCompletion_localAliasToMainObjectFromAuto() {
		createParser3FileInDir("main_alias_object_completion/www/auto.p",
				"@USE\n" +
						"classes/query-service.p\n" +
						"\n" +
						"@auto[]\n" +
						"$MAIN:sql[^queryService::create[]]\n"
		);
		createParser3FileInDir("main_alias_object_completion/www/classes/query-service.p",
				"@CLASS\n" +
						"queryService\n" +
						"\n" +
						"@create[]\n" +
						"\n" +
						"@table[query;options]\n" +
						"$result[^table::sql{$query}[$options]]\n"
		);
		createParser3FileInDir("main_alias_object_completion/www/users/list.p",
				"@main[][oSql]\n" +
						"$oSql[$MAIN:sql]\n" +
						"^oSql.<caret>\n"
		);

		VirtualFile vf = myFixture.findFileInTempDir("main_alias_object_completion/www/users/list.p");
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("У локального алиаса на $MAIN:sql должны быть методы queryService", elements);
		List<String> names = Arrays.stream(elements).map(LookupElement::getLookupString).collect(Collectors.toList());
		assertTrue("У ^oSql. должен быть метод table из queryService, есть: " + names, names.contains("table"));
	}
}
