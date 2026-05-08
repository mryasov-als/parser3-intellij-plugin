package ru.artlebedev.parser3;

import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import ru.artlebedev.parser3.index.P3MethodDocTypeResolver;
import ru.artlebedev.parser3.index.P3ResolvedValue;
import ru.artlebedev.parser3.index.P3VariableFileIndex;
import ru.artlebedev.parser3.index.P3VariableIndex;
import ru.artlebedev.parser3.settings.Parser3SqlInjectionsService;
import ru.artlebedev.parser3.visibility.P3VisibilityService;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class x12_var_HashArrayTest extends Parser3TestCase {

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		setSqlInjections("^oSql.hash", "^oSql.array", "^oSql.table");
		com.intellij.psi.PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
		com.intellij.openapi.project.DumbService.getInstance(getProject()).waitForSmartMode();
	}

	@Override
	protected void tearDown() throws Exception {
		try {
			setSqlInjections();
		} finally {
			super.tearDown();
		}
	}

	private void setSqlInjections(String... prefixes) {
		Parser3SqlInjectionsService service = ApplicationManager.getApplication().getService(Parser3SqlInjectionsService.class);
		assertNotNull(service);
		service.getPrefixes().clear();
		service.getPrefixes().addAll(Arrays.asList(prefixes));
	}

	private List<String> doComplete(String path, String content) {
		createParser3FileInDir(path, content);
		VirtualFile vf = myFixture.findFileInTempDir(path);
		myFixture.configureFromExistingVirtualFile(vf);
		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		if (elements == null) return List.of();
		return Arrays.stream(elements)
				.map(LookupElement::getLookupString)
				.collect(Collectors.toList());
	}

	private String parser010RealDynamicHashBase(String completionLine) {
		return "@main[]\n" +
				"# на основе parser3/tests/010.html\n" +
				"$a[ $.b[c] ]\n" +
				"$v[^hash::create[]]\n" +
				"$v.[$a.b][ $.x[y] ]\n" +
				completionLine;
	}

	private String parser013RealHashBase(String completionLine) {
		return "@main[]\n" +
				"# на основе parser3/tests/013.html\n" +
				"$h[^hash::create[$._default[123]]]\n" +
				"$h.paf[not kretin]\n" +
				"$h[\n" +
				"\t$.1[1]\n" +
				"\t$._default[default value]\n" +
				"]\n" +
				completionLine;
	}

	private String parser014RealHashBase(String completionLine) {
		return "@main[]\n" +
				"# на основе parser3/tests/014.html\n" +
				"$a[$.1[a1] $.2[a2] $.3[a3]]\n" +
				"$b[$.2[b2] $.3[b3] $.4[b4]]\n" +
				completionLine;
	}

	private String parser157RealFileDeleteBase(String completionLine) {
		return "@main[]\n" +
				"# на основе parser3/tests/157.html\n" +
				"$sSrc[157.html]\n" +
				"$sCopy[newdir1/157.copy]\n" +
				"$sMove[newdir2/157.move]\n" +
				"$a[$.1[a1] $.2[a2] $.3[a3]]\n" +
				completionLine;
	}

	private String parser281RealHashBase(String completionLine) {
		return "@main[]\n" +
				"# на основе parser3/tests/281.html\n" +
				"$h[ $.key[value] ]\n" +
				"$h.k[v]\n" +
				"$h.k2[v2]\n" +
				completionLine;
	}

	private String parser209RealHashCopyBase(String completionLine) {
		return "@main[]\n" +
				"# на основе parser3/tests/209.html\n" +
				"$a1[\n" +
				"\t$.1[a1]\n" +
				"\t$.2[a2]\n" +
				"]\n" +
				"$a2[$a1]\n" +
				"$a3[^hash::create[$a1]]\n" +
				"$a4[^hash::create[\n" +
				"\t$.3[a3]\n" +
				"\t$.4[a4]\n" +
				"]]\n" +
				"^a1.add[$a4]\n" +
				completionLine;
	}

	private String parser258RealObjectMethodBase(String mainBody) {
		return "@main[]\n" +
				"# на основе parser3/tests/258.html\n" +
				mainBody +
				"\n" +
				"@CLASS\n" +
				"test\n" +
				"\n" +
				"@create[kind;v]\n" +
				"^if($kind eq 'hash'){\n" +
				"\t$result[^hash::create[\n" +
				"\t\t$.type[hash]\n" +
				"\t\t$.value[$v]\n" +
				"\t]]\n" +
				"}{\n" +
				"\t$result[$self]\n" +
				"\t$type[object]\n" +
				"\t$value[$v]\n" +
				"}\n" +
				"\n" +
				"@payload[]\n" +
				"$result[^hash::create[\n" +
				"\t$.type[hash]\n" +
				"\t$.value[$value]\n" +
				"]]\n";
	}

	private String parser258RealObjectNestedMethodBase(String mainBody) {
		return "@main[]\n" +
				"# на основе parser3/tests/258.html\n" +
				mainBody +
				"\n" +
				"@CLASS\n" +
				"test\n" +
				"\n" +
				"@create[kind;v]\n" +
				"$result[$self]\n" +
				"$type[$kind]\n" +
				"$value[$v]\n" +
				"\n" +
				"@payload_inner[]\n" +
				"$result[\n" +
				"\t$.type[\n" +
				"\t\t$.value[$value]\n" +
				"\t]\n" +
				"]\n" +
				"\n" +
				"@payload[]\n" +
				"$result[^self.payload_inner[]]\n";
	}

	// === 1: Simple hash literals ===

	public void testHash_simple_nestedTable() {
		List<String> c = doComplete("h1.p",
				parser010RealDynamicHashBase("$v.c.<caret>"));
		assertTrue("Реальный Parser3 010: $v.c. должен содержать x: " + c, c.contains("x"));
	}

	public void testHash_simple_topLevel() {
		List<String> c = doComplete("h2.p",
				parser013RealHashBase("$h.<caret>"));
		assertTrue("Реальный Parser3 013: $h. должен содержать 1: " + c, c.contains("1"));
		assertTrue("Реальный Parser3 013: $h. должен содержать _default: " + c, c.contains("_default"));
		assertFalse("Реальный Parser3 013: поздний $h[...] переопределяет $h.paf: " + c, c.contains("paf"));
	}

	public void testHashDeleteCompletion_topLevelKeys() {
		List<String> c = doComplete("hash_delete_top_level_keys.p",
				parser014RealHashBase("^a.delete[<caret>]"));
		assertTrue("Реальный Parser3 014: ^a.delete[] должен предлагать 1: " + c, c.contains("1"));
		assertTrue("Реальный Parser3 014: ^a.delete[] должен предлагать 3: " + c, c.contains("3"));
		assertFalse("Реальный Parser3 014: ^a.delete[] не должен дописывать точку после ключа: " + c, c.contains("1."));
	}

	public void testHashDeleteCompletion_nestedKeys() {
		List<String> c = doComplete("hash_delete_nested_keys.p",
				parser010RealDynamicHashBase("^v.c.delete[x<caret>]"));
		assertTrue("Реальный Parser3 010: ^v.c.delete[x] должен предлагать x: " + c, c.contains("x"));
		assertFalse("Реальный Parser3 010: ^v.c.delete[x] не должен предлагать соседний key c: " + c, c.contains("c"));
	}

	public void testHashDeleteCompletion_ignoresStaticFileDelete() {
		List<String> c = doComplete("hash_delete_static_file_delete.p",
				parser157RealFileDeleteBase("^file:delete[$sMove<caret>]"));
		assertFalse("Реальный Parser3 157: ^file:delete[] не должен получать ключи hash из Parser3 014: " + c, c.contains("1"));
		assertFalse("Реальный Parser3 157: ^file:delete[] не должен получать ключи hash из Parser3 014: " + c, c.contains("2"));
	}

	public void testHashDeleteCompletion_contextDetectedForAutopopup() {
		String content = parser014RealHashBase("^a.delete[1<caret>]");
		createParser3File("hash_delete_context_autopopup.p", content);
		assertTrue("Контекст ^data.delete[x] должен разрешать auto-popup",
				ru.artlebedev.parser3.completion.P3CompletionUtils.isHashDeleteKeyContext(
						myFixture.getEditor().getDocument().getCharsSequence(),
						myFixture.getCaretOffset()
				));
	}

	public void testHashDeleteCompletion_noCompletionAfterArgumentDot() {
		List<String> c = doComplete("hash_delete_no_completion_after_argument_dot.p",
				parser014RealHashBase("^a.delete[1.<caret>]"));
		assertFalse("Реальный Parser3 014: ^a.delete[1.] не должен снова предлагать 1: " + c, c.contains("1"));
		assertFalse("Реальный Parser3 014: ^a.delete[1.] не должен снова предлагать 2: " + c, c.contains("2"));

		createParser3File("hash_delete_no_context_after_argument_dot.p",
				parser014RealHashBase("^a.delete[1.2<caret>]"));
		assertFalse("После точки в аргументе delete auto-popup ключей должен быть выключен",
				ru.artlebedev.parser3.completion.P3CompletionUtils.isHashDeleteKeyContext(
						myFixture.getEditor().getDocument().getCharsSequence(),
						myFixture.getCaretOffset()
				));
	}

	// === 2: ^hash::create[...] ===

	public void testHash_create_nestedKeys() {
		List<String> c = doComplete("hc1.p",
				parser281RealHashBase("$h.<caret>"));
		assertTrue("Реальный Parser3 281: $h. должен содержать key: " + c, c.contains("key"));
		assertTrue("Реальный Parser3 281: $h. должен содержать k: " + c, c.contains("k"));
		assertTrue("Реальный Parser3 281: $h. должен содержать k2: " + c, c.contains("k2"));
	}

	public void testMethodResultInference_hashLiteralViaResult() {
		List<String> root = doComplete("method_hash_result_root.p",
				"@makeHash[]\n" +
						"# на основе parser3/tests/258.html\n" +
						"$result[\n" +
						"    $.type[\n" +
						"        $.value[hash]\n" +
						"    ]\n" +
						"]\n" +
						"\n" +
						"@main[]\n" +
						"# на основе parser3/tests/258.html: минимальный вызов метода с hash-result\n" +
						"$res[^makeHash[]]\n" +
						"$res.<caret>");
		assertTrue("$res. должен содержать type из hash literal: " + root, root.contains("type."));

		List<String> nested = doComplete("method_hash_result_nested.p",
				"@makeHash[]\n" +
						"# на основе parser3/tests/258.html\n" +
						"$result[\n" +
						"    $.type[\n" +
						"        $.value[hash]\n" +
						"    ]\n" +
						"]\n" +
						"\n" +
						"@main[]\n" +
						"# на основе parser3/tests/258.html: минимальный вызов метода с вложенным hash-result\n" +
						"$res[^makeHash[]]\n" +
						"$res.type.<caret>");
		assertTrue("$res.type. должен содержать value из вложенного hash literal: " + nested, nested.contains("value"));
	}

	public void testMethodResultInference_resultDotChainMergesWithHashLiteral() {
		createParser3FileInDir("method_result_dot_chain/auto.p",
				"@get_connect_data[]\n" +
						"# на основе parser3/tests/258.html\n" +
						"$result[\n" +
						"\t$.type[\n" +
						"\t\t$.value[hash]\n" +
						"\t]\n" +
						"]\n" +
						"$result.type.extra[x]\n"
		);
		createParser3FileInDir("method_result_dot_chain/page.p",
				"@main[]\n" +
						"# на основе parser3/tests/258.html: минимальный вызов метода из другого файла\n" +
						"^use[auto.p]\n" +
						"$connectData[^get_connect_data[]]\n" +
						"$connectData.type.<caret>\n"
		);

		VirtualFile vf = myFixture.findFileInTempDir("method_result_dot_chain/page.p");
		myFixture.configureFromExistingVirtualFile(vf);
		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		List<String> c = elements == null ? List.of() : Arrays.stream(elements)
				.map(LookupElement::getLookupString)
				.collect(Collectors.toList());

		assertTrue("$connectData.type. должен видеть value из $result[...] метода: " + c, c.contains("value"));
		assertTrue("$connectData.type. должен видеть extra из $result.type.extra[...] метода: " + c, c.contains("extra"));
	}

	public void testMethodResultInference_resultDotChainCreatesHashWithoutLiteral() {
		List<String> c = doComplete("method_result_dot_chain_only.p",
				"@makeHash[]\n" +
						"# на основе parser3/tests/258.html\n" +
						"$result.type.value[hash]\n" +
						"\n" +
						"@main[]\n" +
						"$connectData[^makeHash[]]\n" +
						"$connectData.type.<caret>");

		assertTrue("$result.type.value[...] должен авто-создавать hash-форму результата метода: " + c,
				c.contains("value"));
	}

	public void testMethodResultInference_resultDotChainBeforeFullAssignmentIsReset() {
		List<String> c = doComplete("method_result_dot_chain_before_full_assignment_reset.p",
				"@makeHash[]\n" +
						"# на основе parser3/tests/258.html\n" +
						"$result.type.extra[x]\n" +
						"$result[\n" +
						"\t$.type[\n" +
						"\t\t$.value[hash]\n" +
						"\t]\n" +
						"]\n" +
						"\n" +
						"@main[]\n" +
						"# на основе parser3/tests/258.html: позднее полное присваивание $result\n" +
						"$connectData[^makeHash[]]\n" +
						"$connectData.type.<caret>");

		assertTrue("Позднее полное $result[...] должно оставить value: " + c, c.contains("value"));
		assertFalse("Позднее полное $result[...] должно сбросить ранний additive key extra: " + c, c.contains("extra"));
	}

	public void testMethodResultInference_resultAddMethodMergesHashLiteral() {
		List<String> c = doComplete("method_result_add_hash_literal.p",
				"@makeHash[]\n" +
						"# на основе parser3/tests/014.html и 258.html\n" +
						"$result[\n" +
						"\t$.type[\n" +
						"\t\t$.value[hash]\n" +
						"\t]\n" +
						"]\n" +
						"^result.add[\n" +
						"\t$.type[\n" +
						"\t\t$.extra[x]\n" +
						"\t]\n" +
						"]\n" +
						"\n" +
						"@main[]\n" +
						"# на основе parser3/tests/014.html и 258.html: result.add hash literal\n" +
						"$connectData[^makeHash[]]\n" +
						"$connectData.type.<caret>");

		assertTrue("^result.add[...] должен сохранить старый key value: " + c, c.contains("value"));
		assertTrue("^result.add[...] должен добавить key extra: " + c, c.contains("extra"));
	}

	public void testMethodResultInference_resultAddMethodMergesSourceVariable() {
		List<String> c = doComplete("method_result_add_source_variable.p",
				"@makeHash[]\n" +
						"# на основе parser3/tests/014.html и 258.html\n" +
						"$result[\n" +
						"\t$.type[\n" +
						"\t\t$.value[hash]\n" +
						"\t]\n" +
						"]\n" +
						"$extra[\n" +
						"\t$.type[\n" +
						"\t\t$.extra[x]\n" +
						"\t]\n" +
						"]\n" +
						"^result.add[$extra]\n" +
						"\n" +
						"@main[]\n" +
						"# на основе parser3/tests/014.html и 258.html: result.add source variable\n" +
						"$connectData[^makeHash[]]\n" +
						"$connectData.type.<caret>");

		assertTrue("^result.add[$extra] должен сохранить old key value: " + c, c.contains("value"));
		assertTrue("^result.add[$extra] должен добавить key extra из source variable: " + c, c.contains("extra"));
	}

	public void testMethodResultInference_resultSubMethodRemovesKey() {
		List<String> c = doComplete("method_result_sub_removes_key.p",
				"@makeHash[]\n" +
						"# на основе parser3/tests/014.html и 258.html\n" +
						"$result[\n" +
						"\t$.type[\n" +
						"\t\t$.value[hash]\n" +
						"\t\t$.extra[x]\n" +
						"\t]\n" +
						"]\n" +
						"^result.type.sub[$.extra[]]\n" +
						"\n" +
						"@main[]\n" +
						"# на основе parser3/tests/014.html и 258.html: result.sub удаляет ключ\n" +
						"$connectData[^makeHash[]]\n" +
						"$connectData.type.<caret>");

		assertTrue("^result.type.sub[...] должен оставить value: " + c, c.contains("value"));
		assertFalse("^result.type.sub[...] должен убрать extra: " + c, c.contains("extra"));
	}

	public void testMethodResultInference_hashResultWeakOverrideKeepsRicherStructureFromAnotherFile() {
		createParser3FileInDir("method_result_weak_override_use/auto.p",
				"@makeHash[]\n" +
						"# на основе parser3/tests/258.html\n" +
						"$result[\n" +
						"\t$.type[\n" +
						"\t\t$.value[hash]\n" +
						"\t\t$.extra[x]\n" +
						"\t]\n" +
						"]\n" +
						"^if(1){\n" +
						"\t$result(1)\n" +
						"}\n"
		);
		createParser3FileInDir("method_result_weak_override_use/page.p",
				"@main[]\n" +
						"# на основе parser3/tests/258.html: слабое переопределение $result из другого файла\n" +
						"^use[auto.p]\n" +
						"$connectData[^makeHash[]]\n" +
						"$connectData.type.<caret>\n"
		);

		VirtualFile vf = myFixture.findFileInTempDir("method_result_weak_override_use/page.p");
		myFixture.configureFromExistingVirtualFile(vf);
		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		List<String> c = elements == null ? List.of() : Arrays.stream(elements)
				.map(LookupElement::getLookupString)
				.collect(Collectors.toList());

		assertTrue("Слабое $result(1) в use-файле не должно затирать hash key value: " + c, c.contains("value"));
		assertTrue("Слабое $result(1) в use-файле не должно затирать hash key extra: " + c, c.contains("extra"));
	}

	public void testMethodResultInference_resultRenameMethodRenamesKey() {
		List<String> c = doComplete("method_result_rename_key.p",
				"@makeHash[]\n" +
						"# на основе parser3/tests/414.html\n" +
						"$result[\n" +
						"\t$.type[\n" +
						"\t\t$.value[hash]\n" +
						"\t]\n" +
						"]\n" +
						"^result.type.rename[$.value[renamed_value]]\n" +
						"\n" +
						"@main[]\n" +
						"# на основе parser3/tests/414.html: result.rename переименовывает ключ\n" +
						"$connectData[^makeHash[]]\n" +
						"$connectData.type.<caret>");

		assertTrue("^result.type.rename[...] должен показать renamed_value: " + c, c.contains("renamed_value"));
		assertFalse("^result.type.rename[...] должен скрыть value: " + c, c.contains("value"));
	}

	public void testMethodResultInference_resultDeepDotChainCreatesNestedHash() {
		List<String> c = doComplete("method_result_deep_dot_chain.p",
				"@makeHash[]\n" +
						"# на основе parser3/tests/258.html\n" +
						"$result.type.value.extra[hash]\n" +
						"\n" +
						"@main[]\n" +
						"$connectData[^makeHash[]]\n" +
						"$connectData.type.value.<caret>");

		assertTrue("Глубокая запись $result.type.value.extra[...] должна создать вложенный key extra: " + c,
				c.contains("extra"));
	}

	public void testMethodResultInference_resultDynamicKeyDotChainFallsBackToWildcard() {
		List<String> c = doComplete("method_result_dynamic_key_dot_chain.p",
				"@makeHash[]\n" +
						"# на основе parser3/tests/430.html\n" +
						"$key[type]\n" +
						"$result.[$key].value[hash]\n" +
						"\n" +
						"@main[]\n" +
						"$connectData[^makeHash[]]\n" +
						"$connectData.type.<caret>");

		assertTrue("Динамический key $result.[$key].value[...] должен дать wildcard-подсказку value: " + c,
				c.contains("value"));
	}

	public void testMethodResultInference_resultDynamicIndexCopiesLocalHashValueForForeach() {
		List<String> valueKeys = doComplete("method_result_dynamic_index_local_hash_foreach_value.p",
				"@main[]\n" +
						"# реальный кейс обезличенного fixture errors/4.p\n" +
						"$data[^get_data[]]\n" +
						"^data.foreach[key;value]{\n" +
						"\t$value.<caret>\n" +
						"}\n" +
						"\n" +
						"@get_data[]\n" +
						"$result[^hash::create[]]\n" +
						"$item[\n" +
						"\t$.x[]\n" +
						"\t$.y[\n" +
						"\t\t$.z[]\n" +
						"\t]\n" +
						"]\n" +
						"$result.[^result._count[]][$item]\n");

		assertTrue("$value. должен видеть x из $item, добавленного в $result динамическим индексом: " + valueKeys,
				valueKeys.contains("x"));
		assertTrue("$value. должен видеть y. из $item, добавленного в $result динамическим индексом: " + valueKeys,
				valueKeys.contains("y."));
		assertEquals("$value. не должен дублировать x из динамического элемента: " + valueKeys,
				1, Collections.frequency(valueKeys, "x"));
		assertEquals("$value. не должен дублировать y. из динамического элемента: " + valueKeys,
				1, Collections.frequency(valueKeys, "y."));

		List<String> nestedKeys = doComplete("method_result_dynamic_index_local_hash_foreach_nested.p",
				"@main[]\n" +
						"# реальный кейс обезличенного fixture errors/4.p\n" +
						"$data[^get_data[]]\n" +
						"^data.foreach[key;value]{\n" +
						"\t$value.y.<caret>\n" +
						"}\n" +
						"\n" +
						"@get_data[]\n" +
						"$result[^hash::create[]]\n" +
						"$item[\n" +
						"\t$.x[]\n" +
						"\t$.y[\n" +
						"\t\t$.z[]\n" +
						"\t]\n" +
						"]\n" +
						"$result.[^result._count[]][$item]\n");

		assertTrue("$value.y. должен видеть z из вложенного $item.y: " + nestedKeys,
				nestedKeys.contains("z"));
		assertEquals("$value.y. не должен дублировать z из вложенного $item.y: " + nestedKeys,
				1, Collections.frequency(nestedKeys, "z"));
	}

	public void testMethodResultInference_resultAddCopiesLocalHashValueForForeach() {
		List<String> valueKeys = doComplete("method_result_add_local_hash_foreach_value.p",
				"@main[]\n" +
						"# соседний кейс к обезличенного fixture errors/4.p: ^result.add[$.row[$item]]\n" +
						"$data[^get_data[]]\n" +
						"^data.foreach[key;value]{\n" +
						"\t$value.<caret>\n" +
						"}\n" +
						"\n" +
						"@get_data[]\n" +
						"$result[^hash::create[]]\n" +
						"$item[\n" +
						"\t$.x[]\n" +
						"\t$.y[\n" +
						"\t\t$.z[]\n" +
						"\t]\n" +
						"]\n" +
						"^result.add[\n" +
						"\t$.row[$item]\n" +
						"]\n");

		assertTrue("^result.add[$.row[$item]] должен раскрыть x в foreach value: " + valueKeys,
				valueKeys.contains("x"));
		assertTrue("^result.add[$.row[$item]] должен раскрыть y. в foreach value: " + valueKeys,
				valueKeys.contains("y."));
		assertEquals("^result.add[$.row[$item]] не должен дублировать x: " + valueKeys,
				1, Collections.frequency(valueKeys, "x"));
		assertEquals("^result.add[$.row[$item]] не должен дублировать y.: " + valueKeys,
				1, Collections.frequency(valueKeys, "y."));

		List<String> nestedKeys = doComplete("method_result_add_local_hash_foreach_nested.p",
				"@main[]\n" +
						"# соседний кейс к обезличенного fixture errors/4.p: ^result.add[$.row[$item]]\n" +
						"$data[^get_data[]]\n" +
						"^data.foreach[key;value]{\n" +
						"\t$value.y.<caret>\n" +
						"}\n" +
						"\n" +
						"@get_data[]\n" +
						"$result[^hash::create[]]\n" +
						"$item[\n" +
						"\t$.x[]\n" +
						"\t$.y[\n" +
						"\t\t$.z[]\n" +
						"\t]\n" +
						"]\n" +
						"^result.add[\n" +
						"\t$.row[$item]\n" +
						"]\n");

		assertTrue("^result.add[$.row[$item]] должен раскрыть z в $value.y.: " + nestedKeys,
				nestedKeys.contains("z"));
		assertEquals("^result.add[$.row[$item]] не должен дублировать z: " + nestedKeys,
				1, Collections.frequency(nestedKeys, "z"));
	}

	public void testMethodResultInference_resultDynamicIndexWithoutLocalValueKeepsWildcardForeach() {
		List<String> valueKeys = doComplete("method_result_dynamic_index_wildcard_foreach_value.p",
				"@main[]\n" +
						"# негативный кейс: нет локального $item, но универсальный ключ от динамического индекса должен продолжать работать\n" +
						"$data[^get_data[]]\n" +
						"^data.foreach[key;value]{\n" +
						"\t$value.<caret>\n" +
						"}\n" +
						"\n" +
						"@get_data[]\n" +
						"$result[^hash::create[]]\n" +
						"$result.[^result._count[]].auto[\n" +
						"\t$.inner[]\n" +
						"]\n");

		assertTrue("Динамический индекс без локального источника должен сохранить универсальный ключ auto: " + valueKeys,
				valueKeys.contains("auto."));
		assertEquals("Динамический индекс без локального источника не должен дублировать auto.: " + valueKeys,
				1, Collections.frequency(valueKeys, "auto."));

		List<String> nestedKeys = doComplete("method_result_dynamic_index_wildcard_foreach_nested.p",
				"@main[]\n" +
						"# негативный кейс: нет локального $item, но вложенный универсальный ключ должен продолжать работать\n" +
						"$data[^get_data[]]\n" +
						"^data.foreach[key;value]{\n" +
						"\t$value.auto.<caret>\n" +
						"}\n" +
						"\n" +
						"@get_data[]\n" +
						"$result[^hash::create[]]\n" +
						"$result.[^result._count[]].auto[\n" +
						"\t$.inner[]\n" +
						"]\n");

		assertTrue("Динамический индекс без локального источника должен сохранить вложенный key inner: " + nestedKeys,
				nestedKeys.contains("inner"));
		assertEquals("Динамический индекс без локального источника не должен дублировать inner: " + nestedKeys,
				1, Collections.frequency(nestedKeys, "inner"));
	}

	public void testMethodResultInference_resultDynamicIndexLocalHashForeachNavigationUsesRealValueKey() {
		createParser3FileInDir("method_result_dynamic_index_local_hash_foreach_navigation.p",
				"@main[]\n" +
						"# навигационный кейс к обезличенного fixture errors/4.p\n" +
						"$data[^get_data[]]\n" +
						"^data.foreach[key;value]{\n" +
						"\t$value.y.z\n" +
						"}\n" +
						"\n" +
						"@get_data[]\n" +
						"$result[^hash::create[]]\n" +
						"$item[\n" +
						"\t$.x[]\n" +
						"\t$.y[\n" +
						"\t\t$.z[]\n" +
						"\t]\n" +
						"]\n" +
						"$result.[^result._count[]][$item]\n");
		VirtualFile vFile = myFixture.findFileInTempDir("method_result_dynamic_index_local_hash_foreach_navigation.p");
		assertNotNull("Файл navigation-кейса должен быть создан", vFile);

		String content;
		try {
			content = new String(vFile.contentsToByteArray(), vFile.getCharset());
		} catch (Exception e) {
			fail("Не удалось прочитать navigation-кейс");
			return;
		}

		int usagePos = content.indexOf("$value.y.z");
		assertTrue("$value.y.z должен существовать", usagePos >= 0);
		int clickOffset = usagePos + "$value.y.".length();
		int expectedOffset = content.indexOf("$.z[]") + "$.".length();
		assertTrue("Исходный ключ $.z[] должен существовать", expectedOffset >= "$.".length());

		myFixture.configureFromExistingVirtualFile(vFile);
		myFixture.getEditor().getCaretModel().moveToOffset(clickOffset);

		PsiElement[] targets = GotoDeclarationAction.findAllTargetElements(
				getProject(), myFixture.getEditor(), clickOffset);

		assertNotNull("Клик по z в $value.y.z должен иметь цель навигации", targets);
		assertTrue("Клик по z в $value.y.z должен иметь непустую цель навигации", targets.length > 0);

		boolean foundRealKey = false;
		for (PsiElement target : targets) {
			int targetOffset = target.getTextOffset();
			if (targetOffset >= expectedOffset && targetOffset <= expectedOffset + "z".length()) {
				foundRealKey = true;
				break;
			}
		}
		assertTrue("Навигация $value.y.z должна вести к реальному $.z[] из $item", foundRealKey);
	}

	public void testMethodResultInference_resultCopiesLocalSourceAndThenAddsDotChain() {
		List<String> c = doComplete("method_result_local_source_then_dot_chain.p",
				"@makeHash[]\n" +
						"# на основе parser3/tests/258.html\n" +
						"$local[\n" +
						"\t$.type[\n" +
						"\t\t$.value[hash]\n" +
						"\t]\n" +
						"]\n" +
						"$result[$local]\n" +
						"$result.type.extra[x]\n" +
						"\n" +
						"@main[]\n" +
						"# на основе parser3/tests/258.html: $result[$local] плюс dot-chain\n" +
						"$connectData[^makeHash[]]\n" +
						"$connectData.type.<caret>");

		assertTrue("$result[$local] должен раскрыть source key value: " + c, c.contains("value"));
		assertTrue("$result.type.extra[...] после source copy должен добавить extra: " + c, c.contains("extra"));
	}

	public void testMethodResultInference_resultCopiesLocalHashPath() {
		List<String> c = doComplete("method_result_local_hash_path.p",
				"@makeHash[]\n" +
						"# на основе parser3/tests/258.html\n" +
						"$source[\n" +
						"\t$.type[\n" +
						"\t\t$.value[hash]\n" +
						"\t\t$.extra[x]\n" +
						"\t]\n" +
						"]\n" +
						"$result[$source.type]\n" +
						"\n" +
						"@main[]\n" +
						"# на основе parser3/tests/258.html: $result из вложенного hash path\n" +
						"$connectData[^makeHash[]]\n" +
						"$connectData.<caret>");

		assertTrue("$result[$source.type] должен раскрыть вложенный key value как root результата: " + c, c.contains("value"));
		assertTrue("$result[$source.type] должен раскрыть вложенный key extra как root результата: " + c, c.contains("extra"));
	}

	public void testObjectMethodResultInference_resultDotChainCreatesHash() {
		List<String> c = doComplete("object_method_result_dot_chain.p",
				"@CLASS\n" +
						"config\n" +
						"\n" +
						"@create[]\n" +
						"\n" +
						"@connect[]\n" +
						"# на основе parser3/tests/258.html\n" +
						"$result.type.value[hash]\n" +
						"\n" +
						"@main[]\n" +
						"$config[^config::create[]]\n" +
						"$connectData[^config.connect[]]\n" +
						"$connectData.type.<caret>");

		assertTrue("Object method result должен видеть additive key value: " + c, c.contains("value"));
	}

	public void testSelfMethodResultInference_resultDotChainCreatesHash() {
		List<String> c = doComplete("self_method_result_dot_chain.p",
				"@CLASS\n" +
						"config\n" +
						"\n" +
						"@create[]\n" +
						"$connectData[^self.connect[]]\n" +
						"$connectData.type.<caret>\n" +
						"\n" +
						"@connect[]\n" +
						"# на основе parser3/tests/258.html\n" +
						"$result.type.value[hash]\n");

		assertTrue("Self method result должен видеть additive key value: " + c, c.contains("value"));
	}

	public void testStaticMethodResultInference_resultDotChainCreatesHash() {
		List<String> c = doComplete("static_method_result_dot_chain.p",
				"@CLASS\n" +
						"config\n" +
						"\n" +
						"@connect[]\n" +
						"# на основе parser3/tests/258.html\n" +
						"$result.type.value[hash]\n" +
						"\n" +
						"@main[]\n" +
						"$connectData[^config:connect[]]\n" +
						"$connectData.type.<caret>");

		assertTrue("Static method result должен видеть additive key value: " + c, c.contains("value"));
	}

	public void testMethodResultInference_hashCreateDefaultAndAdditiveKeys() {
		List<String> c = doComplete("method_result_hash_create_default_and_additive_keys.p",
				"@makeHash[]\n" +
						"# на основе parser3/tests/152.html\n" +
						"$result[^hash::create[\n" +
						"\t$._default[default]\n" +
						"\t$.known[x]\n" +
						"]]\n" +
						"$result.extra[y]\n" +
						"\n" +
						"@main[]\n" +
						"# на основе parser3/tests/152.html: hash::create с _default и additive keys\n" +
						"$data[^makeHash[]]\n" +
						"$data.<caret>");

		assertTrue("^hash::create[$._default[]] должен сохранить _default в форме hash: " + c, c.contains("_default"));
		assertTrue("^hash::create[...] должен показать known: " + c, c.contains("known"));
		assertTrue("$result.extra[...] после ^hash::create[...] должен добавить extra: " + c, c.contains("extra"));
	}

	public void testMethodResultInference_hashLiteralVariableCompletionAppendsDot() {
		List<String> c = doComplete("method_hash_result_var_completion.p",
				"@makeHash[]\n" +
						"# на основе parser3/tests/258.html\n" +
						"$result[\n" +
						"    $.type[\n" +
						"        $.value[hash]\n" +
						"    ]\n" +
						"]\n" +
						"\n" +
						"@main[]\n" +
						"$res[^makeHash[]]\n" +
						"$re<caret>");
		assertTrue("$re<caret> должен предлагать res. для hash-результата метода: " + c, c.contains("res."));
	}

	public void testVariableCompletion_numericVariableAppendsDot() {
		List<String> c = doComplete("numeric_variable_completion_appends_dot.p",
				"@main[]\n" +
						"# на основе parser3/tests/010.html\n" +
						"$2var[\n" +
						"    $.b[\n" +
						"        $.c[value]\n" +
						"    ]\n" +
						"]\n" +
						"$2v<caret>");
		assertTrue("$2v<caret> должен предлагать 2var. для hash-переменной: " + c, c.contains("2var."));
	}

	public void testVariableCompletion_numericVariableHashKeys() {
		List<String> c = doComplete("numeric_variable_completion_hash_keys.p",
				"@main[]\n" +
						"# на основе parser3/tests/010.html\n" +
						"$2var[\n" +
						"    $.b[\n" +
						"        $.c[value]\n" +
						"    ]\n" +
						"]\n" +
						"$2var.b.<caret>");
		assertTrue("$2var.b.<caret> должен содержать c: " + c, c.contains("c"));
	}

	public void testMethodResultInference_nestedMethodHashLiteralVariableCompletionAppendsDot() {
		List<String> c = doComplete("method_hash_result_nested_var_completion.p",
				"@method2[]\n" +
						"# на основе parser3/tests/258.html\n" +
						"$result[\n" +
						"    $.type[\n" +
						"        $.value[hash]\n" +
						"    ]\n" +
						"]\n" +
						"\n" +
						"@method[]\n" +
						"$result[^method2[]]\n" +
						"\n" +
						"@main[]\n" +
						"# на основе parser3/tests/258.html: nested method hash-result variable completion\n" +
						"$res[^method[]]\n" +
						"$re<caret>");
		assertTrue("$re<caret> должен предлагать res. для nested method hash-результата: " + c, c.contains("res."));
	}

	public void testMethodResultInference_nestedMethodHashLiteralViaResult() {
		List<String> c = doComplete("method_hash_result_nested_method_root.p",
				"@method2[]\n" +
						"# на основе parser3/tests/258.html\n" +
						"$result[\n" +
						"    $.type[\n" +
						"        $.value[hash]\n" +
						"    ]\n" +
						"]\n" +
						"\n" +
						"@method[]\n" +
						"$result[^method2[]]\n" +
						"\n" +
						"@main[]\n" +
						"# на основе parser3/tests/258.html: nested method hash-result root\n" +
						"$res[^method[]]\n" +
						"$res.<caret>");
		assertTrue("$res. должен содержать type из nested method result: " + c, c.contains("type."));
	}

	public void testMethodResultInference_hashLiteralViaReturn() {
		List<String> c = doComplete("method_hash_return.p",
				"@makeHash[]\n" +
						"# на основе parser3/tests/258.html\n" +
						"^return[\n" +
						"    $.type[\n" +
						"        $.value[hash]\n" +
						"    ]\n" +
						"]\n" +
						"\n" +
						"@main[]\n" +
						"$res[^makeHash[]]\n" +
						"$res.type.<caret>");
		assertTrue("^return[...] должен строить ту же hash-структуру: " + c, c.contains("value"));
	}

	// === 3: Hash copy ===

	public void testObjectMethodResultInference_hashLiteralVariableCompletionAppendsDot() {
		List<String> c = doComplete("object_method_hash_result_var_completion.p",
				parser258RealObjectMethodBase(
						"$object[^test::create[;value for object]]\n" +
								"$payload[^object.payload[]]\n" +
								"$pay<caret>"));
		assertTrue("Реальный Parser3 258: $pay<caret> должен предлагать payload. для hash-результата object method: " + c,
				c.contains("payload."));
	}

	public void testObjectMethodResultInference_hashLiteralViaResult() {
		List<String> c = doComplete("object_method_hash_result_root.p",
				parser258RealObjectMethodBase(
						"$object[^test::create[;value for object]]\n" +
								"$payload[^object.payload[]]\n" +
								"$payload.<caret>"));
		assertTrue("Реальный Parser3 258: $payload. должен содержать type из object method result: " + c, c.contains("type"));
		assertTrue("Реальный Parser3 258: $payload. должен содержать value из object method result: " + c, c.contains("value"));
	}

	public void testObjectMethodResultInference_selfMethodHashLiteralViaResult() {
		String content =
				"@CLASS\n" +
						"test\n" +
						"# на основе parser3/tests/258.html: object method result через self\n" +
						"\n" +
						"@create[]\n" +
						"$tracked_data[^self._get_tracked_url_data[]]\n" +
						"$tracked_data.<caret>\n" +
						"\n" +
						"@_get_tracked_url_data[]\n" +
						"$result[\n" +
						"\t$.tracked_id(0)\n" +
						"\t$.work_id(0)\n" +
						"]\n";
		Map<String, List<P3VariableFileIndex.VariableTypeInfo>> parsed =
				P3VariableFileIndex.parseVariablesFromText(content);
		assertNotNull("tracked_data должен попасть в parsed variables", parsed.get("tracked_data"));
		P3VariableFileIndex.VariableTypeInfo trackedInfo = parsed.get("tracked_data").get(0);
		assertEquals("tracked_data должен быть method-call", P3VariableFileIndex.VariableTypeInfo.METHOD_CALL_MARKER, trackedInfo.className);
		assertEquals("tracked_data method", "_get_tracked_url_data", trackedInfo.methodName);
		assertEquals("tracked_data receiver", "self", trackedInfo.receiverVarKey);
		assertEquals("tracked_data target class", "test", trackedInfo.targetClassName);

		List<String> c = doComplete("object_method_self_hash_result_root.p", content);
		assertTrue("$tracked_data. должен содержать tracked_id из ^self._get_tracked_url_data[]: " + c, c.contains("tracked_id"));
		assertTrue("$tracked_data. должен содержать work_id из ^self._get_tracked_url_data[]: " + c, c.contains("work_id"));
	}

	public void testMethodResultInference_resultAdditiveFieldKeepsInitialHashKeys() {
		List<String> c = doComplete("method_result_additive_field_keeps_initial_hash_keys.p",
				"@CLASS\n" +
						"test\n" +
						"# на основе parser3/tests/258.html: additive $result.field сохраняет initial hash\n" +
						"\n" +
						"@create[]\n" +
						"$tracked_data[^_get_tracked_url_data[]]\n" +
						"$tracked_data.<caret>\n" +
						"\n" +
						"@_get_tracked_url_data[]\n" +
						"$result[\n" +
						"\t$.tracked_id(0)\n" +
						"\t$.work_id(0)\n" +
						"]\n" +
						"$result.tracked_id(1)\n");
		assertTrue("$tracked_data. должен сохранить tracked_id после $result.tracked_id(...): " + c, c.contains("tracked_id"));
		assertTrue("$tracked_data. должен сохранить work_id после $result.tracked_id(...): " + c, c.contains("work_id"));
	}

	public void testMethodResultInference_resultAdditiveFieldsMergeWithInitialHashKeys() {
		List<String> c = doComplete("method_result_additive_fields_merge_with_initial_hash_keys.p",
				"@CLASS\n" +
						"test\n" +
						"# на основе parser3/tests/258.html: несколько additive $result.field сохраняют initial hash\n" +
						"\n" +
						"@create[]\n" +
						"$tracked_data[^self._get_tracked_url_data[$urlData]]\n" +
						"$tracked_data.<caret> — не определился как хеш\n" +
						"\n" +
						"@_get_tracked_url_data[urlData]\n" +
						"$result[\n" +
						"\t$.tracked_id(0)\n" +
						"\t$.work_id(0)\n" +
						"]\n" +
						"$result.tracked_id(1)\n" +
						"$result.work_id(2)\n");
		assertTrue("$tracked_data. должен сохранить tracked_id после нескольких $result.field(...): " + c, c.contains("tracked_id"));
		assertTrue("$tracked_data. должен сохранить work_id после нескольких $result.field(...): " + c, c.contains("work_id"));
	}

	public void testMethodResultInference_realTrackedUrlDataCaseKeepsTrackedId() {
		List<String> c = doComplete("method_result_real_tracked_url_data_case.p",
				"@main[]\n" +
						"# реальный минимальный кейс tracked_url_data из обезличенного рабочего кейса\n" +
						"$x[]\n" +
						"\n" +
						"@CLASS\n" +
						"metrics\n" +
						"# реальный минимальный кейс tracked_url_data из обезличенного рабочего кейса\n" +
						"\n" +
						"@create[]\n" +
						"$tracked_data[^self._get_tracked_url_data[$urlData]]\n" +
						"$tracked_data.<caret> — не определился как хеш\n" +
						"\n" +
						"@_get_tracked_url_data[urlData]\n" +
						"$result[\n" +
						"\t$.tracked_id(0)\n" +
						"\t$.work_id(0)\n" +
						"]\n" +
						"$url[$urlData.url]\n" +
						"$uri[^url.trim[]]\n" +
						"$uri[^uri.replace[https://test.com/;/]]\n" +
						"$uri[^uri.replace[https://test.com/;/]]\n" +
						"^if(^uri.pos[?] > -1){\n" +
						"\t$uri[^uri.split[?;lh]]\n" +
						"\t$uri[$uri.0]\n" +
						"}\n" +
						"\n" +
						"^if(!$MAIN:tracked_urls_cache){\n" +
						"\t$MAIN:tracked_urls_cache[^hash::sql{select uri, id, title from tracked_urls}]\n" +
						"}\n" +
						"\n" +
						"^MAIN:trackedUrls.foreach[k;v]{\n" +
						"\t^if(^uri.pos[$v.uri] == 0){\n" +
						"\t\t$ok($v.deep || $uri eq $v)\n" +
						"\t\t^continue(!$ok)\n" +
						"\t\t$trackedData[$MAIN:tracked_urls_cache.[$uri]]\n" +
						"\t\t^if(!$trackedData){\n" +
						"\t\t\t^void:sql{INSERT INTO tracked_urls SET uri = '$uri'}\n" +
						"\t\t\t$result.tracked_id(^int:sql{SELECT LAST_INSERT_ID()})\n" +
						"\t\t\t$MAIN:tracked_urls_cache.[$uri][\n" +
						"\t\t\t\t$.id($result)\n" +
						"\t\t\t\t$.title[$urlData.title]\n" +
						"\t\t\t]\n" +
						"\t\t}{\n" +
						"\t\t\t$result.tracked_id($trackedData.id)\n" +
						"\t\t\t^if(def $urlData.title && $trackedData.title ne $urlData.title){\n" +
						"\t\t\t\t$MAIN:tracked_urls_cache.[$uri].title[$urlData.title]\n" +
						"\t\t\t}\n" +
						"\t\t}\n" +
						"\t}\n" +
						"}\n" +
						"^MAIN:selected_items_uri.foreach[;v]{\n" +
						"\t^if(^uri.pos[/$v.web_name/] == 0){\n" +
						"\t\t$result.work_id($v.id)\n" +
						"\t\t^return[$result]\n" +
						"\t}\n" +
						"}\n");
		assertTrue("$tracked_data. должен содержать tracked_id в полном кейсе: " + c, c.contains("tracked_id"));
		assertTrue("$tracked_data. должен содержать work_id в полном кейсе: " + c, c.contains("work_id"));
	}

	public void testJsonParseReadChainAddsRootHashKey() {
		// реальный минимальный кейс json:parse + read-chain foreach completion.
		String content =
				"$ids_src[$dir/ids.json]\n" +
						"$ids[^file::load[text;$ids_src]]\n" +
						"$ids[^json:parse[^taint[as-is][$ids.text]]]\n" +
						"^ids.list.foreach[k;v]{\n" +
						"\n" +
						"}\n" +
						"$ids.li<caret> — нет \"list\" в автокомплите";
		Map<String, List<P3VariableFileIndex.VariableTypeInfo>> parsed =
				P3VariableFileIndex.parseVariablesFromText(content.replace("<caret>", ""));
		assertTrue("Парсер должен добавить ключ list из ^ids.list.foreach: " + parsed.get("ids"),
				parsed.get("ids").stream().anyMatch(info -> info.hashKeys != null && info.hashKeys.containsKey("list")));
		createParser3FileInDir("json_parse_read_chain_adds_root_hash_key.p", content);
		VirtualFile vf = myFixture.findFileInTempDir("json_parse_read_chain_adds_root_hash_key.p");
		myFixture.configureFromExistingVirtualFile(vf);
		P3VariableIndex.VariableCompletionInfo info = P3VariableIndex.getInstance(getProject()).resolveEffectiveVariable(
				"ids", List.of(vf), vf, myFixture.getCaretOffset());
		assertNotNull("ids должен резолвиться", info);
		assertNotNull("ids должен иметь hashKeys", info.hashKeys);
		assertTrue("ids должен иметь list в hashKeys: " + info.hashKeys.keySet(), info.hashKeys.containsKey("list"));
		assertEquals("ids после json:parse и ^ids.list.foreach должен быть hash, а не старый file",
				"hash", info.variable.className);
		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		List<String> c = elements == null ? List.of() : Arrays.stream(elements)
				.map(LookupElement::getLookupString)
				.collect(Collectors.toList());
		assertTrue("$ids.li должен предлагать list из ^ids.list.foreach: " + c,
				c.contains("list") || c.contains("list."));
	}

	public void testObjectMethodResultInference_nestedMethodVariableCompletionAppendsDot() {
		List<String> c = doComplete("object_method_nested_result_var_completion.p",
				parser258RealObjectNestedMethodBase(
						"$object[^test::create[object;value for object]]\n" +
								"$payload[^object.payload[]]\n" +
								"$pay<caret>"));
		assertTrue("Реальный Parser3 258: $pay<caret> должен предлагать payload. для nested object method result: " + c,
				c.contains("payload."));
	}

	public void testObjectMethodResultInference_nestedMethodViaResult() {
		List<String> c = doComplete("object_method_nested_result_root.p",
				parser258RealObjectNestedMethodBase(
						"$object[^test::create[object;value for object]]\n" +
								"$payload[^object.payload[]]\n" +
								"$payload.type.<caret>"));
		assertTrue("Реальный Parser3 258: $payload.type. должен содержать value для nested object method result: " + c,
				c.contains("value"));
	}

	public void testStaticMethodResultInference_lowercaseClassNestedMethodVariableCompletionAppendsDot() {
		List<String> c = doComplete("static_method_lowercase_nested_var_completion.p",
				"@method2[]\n" +
						"# на основе parser3/tests/258.html\n" +
						"$result[\n" +
						"    $.type[\n" +
						"        $.value[hash]\n" +
						"    ]\n" +
						"]\n" +
						"\n" +
						"@CLASS test2\n" +
						"\n" +
						"@method[]\n" +
						"$result[^method2[]]\n" +
						"\n" +
						"@main[]\n" +
						"# на основе parser3/tests/258.html: static method нижнего регистра, variable completion\n" +
						"$mmm[^test2:method[]]\n" +
						"$mm<caret>");
		assertTrue("$mm<caret> должен предлагать mmm. для static method нижнего регистра: " + c, c.contains("mmm."));
	}

	public void testStaticMethodResultInference_lowercaseClassNestedMethodViaResult() {
		List<String> c = doComplete("static_method_lowercase_nested_root.p",
				"@method2[]\n" +
						"# на основе parser3/tests/258.html\n" +
						"$result[\n" +
						"    $.type[\n" +
						"        $.value[hash]\n" +
						"    ]\n" +
						"]\n" +
						"\n" +
						"@CLASS test2\n" +
						"\n" +
						"@method[]\n" +
						"$result[^method2[]]\n" +
						"\n" +
						"@main[]\n" +
						"# на основе parser3/tests/258.html: static method нижнего регистра, root completion\n" +
						"$mmm[^test2:method[]]\n" +
						"$mmm.type.<caret>");
		assertTrue("$mmm.type. должен содержать value для static method нижнего регистра: " + c, c.contains("value"));
	}

	public void testStaticMethodResultInference_numericClassNestedMethodVariableCompletionAppendsDot() {
		List<String> c = doComplete("static_method_numeric_nested_var_completion.p",
				"@method2[]\n" +
						"# на основе parser3/tests/258.html\n" +
						"$result[\n" +
						"    $.type[\n" +
						"        $.value[hash]\n" +
						"    ]\n" +
						"]\n" +
						"\n" +
						"@CLASS 222\n" +
						"\n" +
						"@method[]\n" +
						"$result[^method2[]]\n" +
						"\n" +
						"@main[]\n" +
						"# на основе parser3/tests/258.html: числовой класс, variable completion\n" +
						"$mmm[^222:method[]]\n" +
						"$mm<caret>");
		assertTrue("$mm<caret> должен предлагать mmm. для static method числового класса: " + c, c.contains("mmm."));
	}

	public void testStaticMethodResultInference_numericClassNestedMethodViaResult() {
		List<String> c = doComplete("static_method_numeric_nested_root.p",
				"@method2[]\n" +
						"# на основе parser3/tests/258.html\n" +
						"$result[\n" +
						"    $.type[\n" +
						"        $.value[hash]\n" +
						"    ]\n" +
						"]\n" +
						"\n" +
						"@CLASS 222\n" +
						"\n" +
						"@method[]\n" +
						"$result[^method2[]]\n" +
						"\n" +
						"@main[]\n" +
						"# на основе parser3/tests/258.html: числовой класс, root completion\n" +
						"$mmm[^222:method[]]\n" +
						"$mmm.type.<caret>");
		assertTrue("$mmm.type. должен содержать value для static method числового класса: " + c, c.contains("value"));
	}

	public void testObjectMethodResultInference_receiverChainResolvesType() {
		List<String> c = doComplete("object_method_hash_result_receiver_chain.p",
				"@CLASS test\n" +
						"\n" +
						"@create[]\n" +
						"\n" +
						"@method[]\n" +
						"# на основе parser3/tests/258.html\n" +
						"$result[\n" +
						"    $.type[\n" +
						"        $.value[hash]\n" +
						"    ]\n" +
						"]\n" +
						"\n" +
						"@main[]\n" +
						"$obj[\n" +
						"    $.child[^test::create[]]\n" +
						"]\n" +
						"$aaa[^obj.child.method[]]\n" +
						"$aaa.type.<caret>");
		assertTrue("^obj.child.method[] должен брать тип по receiver-цепочке: " + c, c.contains("value"));
	}

	public void testObjectMethodResultInference_resolverChain() {
		String content =
				"@CLASS test\n" +
						"\n" +
						"@create[]\n" +
						"\n" +
						"@method[]\n" +
						"# на основе parser3/tests/258.html\n" +
						"$result[\n" +
						"    $.type[\n" +
						"        $.value[hash]\n" +
						"    ]\n" +
						"]\n" +
						"\n" +
						"@main[]\n" +
						"$test[^test::create[]]\n" +
						"$aaa[^test.method[]]\n";
		VirtualFile vf = createParser3FileInDir("object_method_hash_result_resolver_chain.p", content);
		List<VirtualFile> visibleFiles = P3VisibilityService.getInstance(getProject()).getVisibleFiles(vf);
		P3VariableIndex variableIndex = P3VariableIndex.getInstance(getProject());
		P3MethodDocTypeResolver methodResolver = P3MethodDocTypeResolver.getInstance(getProject());
		Map<String, List<P3VariableFileIndex.VariableTypeInfo>> parsed = P3VariableFileIndex.parseVariablesFromText(content);

		assertEquals("receiver class", "test",
				variableIndex.findVariableClassInFiles("test", visibleFiles, vf, content.length()));
		assertNotNull("parsed aaa", parsed.get("aaa"));
		assertEquals("parsed aaa raw class", P3VariableFileIndex.VariableTypeInfo.METHOD_CALL_MARKER,
				parsed.get("aaa").get(parsed.get("aaa").size() - 1).className);

		P3ResolvedValue methodResult = methodResolver.getMethodResolvedResult("method", "test", visibleFiles);
		assertNotNull("class method inferred result", methodResult);
		assertEquals("class method result type", "hash", methodResult.className);
		assertNotNull("class method hash keys", methodResult.hashKeys);
		assertTrue("class method hash must contain type", methodResult.hashKeys.containsKey("type"));

		P3VariableIndex.VisibleVariable aaa = variableIndex.findVariable("aaa", visibleFiles, vf, content.length());
		assertNotNull("$aaa variable", aaa);
		assertEquals("$aaa raw class", P3VariableFileIndex.VariableTypeInfo.METHOD_CALL_MARKER, aaa.className);
		assertEquals("$aaa method name", "method", aaa.methodName);
		assertEquals("$aaa receiver", "test", aaa.receiverVarKey);
		P3ResolvedValue resolved = variableIndex.resolveValueRef(aaa, visibleFiles, vf, content.length());
		assertEquals("$aaa resolved type", "hash", resolved.className);
		assertNotNull("$aaa resolved hash keys", resolved.hashKeys);
		assertTrue("$aaa resolved hash must contain type", resolved.hashKeys.containsKey("type"));
	}

	public void testObjectMethodResultInference_cyclicReceiverChainDoesNotOverflowOnCompletion() {
		String content =
				"@main[]\n" +
						"# реальный минимальный регресс-кейс циклического receiver/source resolve\n" +
						"$a[$b]\n" +
						"$b[$a]\n" +
						"$res[^a.some[]]\n" +
						"$re<caret>";

		List<String> completions;
		try {
			completions = doComplete("object_method_cyclic_receiver_chain_completion.p", content);
		} catch (StackOverflowError e) {
			fail("Ctrl+Space не должен падать на циклическом receiver/source резолве");
			return;
		}

		assertTrue("$re<caret> должен предлагать res даже если тип не удалось дорезолвить: " + completions,
				completions.contains("res"));

		VirtualFile vf = myFixture.findFileInTempDir("object_method_cyclic_receiver_chain_completion.p");
		assertNotNull("Файл теста должен быть создан", vf);

		List<VirtualFile> visibleFiles = P3VisibilityService.getInstance(getProject()).getVisibleFiles(vf);
		P3VariableIndex variableIndex = P3VariableIndex.getInstance(getProject());

		P3VariableIndex.VariableCompletionInfo completionInfo;
		try {
			completionInfo = variableIndex.resolveEffectiveVariable("res", visibleFiles, vf, content.indexOf("$re<caret>"));
		} catch (StackOverflowError e) {
			fail("resolveEffectiveVariable не должен падать на циклическом receiver/source резолве");
			return;
		}

		assertNotNull("Переменная res должна находиться даже при циклическом резолве", completionInfo);
		assertEquals("Тип должен безопасно деградировать в UNKNOWN",
				P3VariableFileIndex.UNKNOWN_TYPE, completionInfo.variable.className);
	}

	public void testObjectMethodResultInference_mutualMethodReceiversDoNotOverflow() {
		String content =
				"@main[]\n" +
						"# прямой цикл receiver-цепочки через method-call marker\n" +
						"$a[^b.some[]]\n" +
						"$b[^a.some[]]\n" +
						"$res[^a.some[]]\n" +
						"$re<caret>";

		List<String> completions;
		try {
			completions = doComplete("object_method_mutual_receiver_cycle_completion.p", content);
		} catch (StackOverflowError e) {
			fail("Ctrl+Space не должен падать на взаимном цикле method-call receiver");
			return;
		}

		assertTrue("$re<caret> должен предлагать res даже при взаимном цикле receiver: " + completions,
				completions.contains("res"));

		VirtualFile vf = myFixture.findFileInTempDir("object_method_mutual_receiver_cycle_completion.p");
		assertNotNull("Файл теста должен быть создан", vf);

		List<VirtualFile> visibleFiles = P3VisibilityService.getInstance(getProject()).getVisibleFiles(vf);
		P3VariableIndex variableIndex = P3VariableIndex.getInstance(getProject());
		int cursorOffset = content.indexOf("$re<caret>");

		P3VariableIndex.VisibleVariable a = variableIndex.findVariable("a", visibleFiles, vf, cursorOffset);
		assertNotNull("Переменная a должна быть найдена", a);
		assertEquals("a должна остаться method-call marker до резолва",
				P3VariableFileIndex.VariableTypeInfo.METHOD_CALL_MARKER, a.className);

		try {
			P3ResolvedValue resolved = variableIndex.resolveValueRef(a, visibleFiles, vf, cursorOffset);
			assertEquals("Циклический receiver должен безопасно остаться method-call marker",
					P3VariableFileIndex.VariableTypeInfo.METHOD_CALL_MARKER, resolved.className);
		} catch (StackOverflowError e) {
			fail("resolveValueRef не должен падать на взаимном цикле method-call receiver");
			return;
		}

		P3VariableIndex.VariableCompletionInfo completionInfo;
		try {
			completionInfo = variableIndex.resolveEffectiveVariable("res", visibleFiles, vf, cursorOffset);
		} catch (StackOverflowError e) {
			fail("resolveEffectiveVariable не должен падать на взаимном цикле method-call receiver");
			return;
		}

		assertNotNull("Переменная res должна находиться даже при взаимном цикле receiver", completionInfo);
		assertEquals("Тип результата должен безопасно деградировать в UNKNOWN",
				P3VariableFileIndex.UNKNOWN_TYPE, completionInfo.variable.className);
	}

	public void testHash_copy_dollarRef() {
		List<String> c = doComplete("cp1.p",
				parser209RealHashCopyBase("$a2.<caret>"));
		assertTrue("Реальный Parser3 209: прямая ссылка $a2[$a1] должна видеть исходный ключ 1: " + c, c.contains("1"));
		assertTrue("Реальный Parser3 209: прямая ссылка $a2[$a1] должна видеть поздний ключ 3: " + c, c.contains("3"));
	}

	public void testHash_copy_hashCreate() {
		List<String> c = doComplete("cp2.p",
				"@main[]\n" +
						"# на основе parser3/tests/281.html\n" +
						"$h[ $.key[value] ]\n" +
						"$h2[^hash::create[$h]]\n" +
						"$h.k[v]\n" +
						"$h.k2[v2]\n" +
						"$h2.<caret>");
		assertTrue("Реальный Parser3 281: hash::create[$h] должен сохранить исходный key: " + c,
				c.contains("key"));
		assertFalse("Реальный Parser3 281: hash::create[$h] не должен видеть поздний k2: " + c,
				c.contains("k2"));
	}

	public void testHash_copy_pureHashCreate() {
		List<String> c = doComplete("cp3.p",
				parser209RealHashCopyBase("$a3.<caret>"));
		assertTrue("Реальный Parser3 209: hash::create[$a1] должен сохранить исходный ключ 1: " + c,
				c.contains("1"));
		assertFalse("Реальный Parser3 209: hash::create[$a1] не должен видеть поздний ключ 3: " + c,
				c.contains("3"));
	}

	// === 4: Additive ===

	public void testHash_additive_dotChain() {
		List<String> c = doComplete("add1.p",
				"@main[]\n# на основе parser3/tests/014.html\n$a[\n\t$.1[a1]\n]\n$a.2[a2]\n$a.<caret>");
		assertTrue("1: " + c, c.contains("1"));
		assertTrue("2: " + c, c.contains("2"));
	}

	public void testHash_additiveDotChainKeyWithDash() {
		List<String> c = doComplete("add_dash_key.p",
				"@conf[filespec]\n" +
						"$SQL[\n" +
						"\t$.drivers[^table::create{protocol\tdriver\tclient}]\n" +
						"]\n" +
						"\n" +
						"@auto[]\n" +
						"$SQL.connect-string[mysql://test_user:@localhost/test_db?charset=utf8]\n" +
						"$SQL.connect-accounts[mysql://test_user:@localhost/test_accounts?charset=utf8]\n" +
						"$SQL.connect-items[mysql://test_user:@localhost/items?charset=utf8]\n" +
						"\n" +
						"@main[]\n" +
						"# обезличенный минимальный кейс SQL config с ключами через дефис\n" +
						"$SQL.<caret>");
		assertTrue("drivers.: " + c, c.contains("drivers."));
		assertTrue("connect-string: " + c, c.contains("connect-string"));
		assertTrue("connect-accounts: " + c, c.contains("connect-accounts"));
		assertTrue("connect-items: " + c, c.contains("connect-items"));
	}

	public void testHash_additiveDotChainFromMainAutoMergesAllKeys() {
		createParser3FileInDir("test_auto/auto.p",
				"@conf[filespec][confdir;charsetsdir;sqldriversdir]\n" +
						"$SQL[\n" +
						"\t$.drivers[^table::create{protocol\tdriver\tclient\n" +
						"mysql\t$sqldriversdir/parser3mysql.dll\t$sqldriversdir/libmySQL.dll\n" +
						"sqlite\t$sqldriversdir/parser3sqlite.dll\t$sqldriversdir/sqlite3.dll\n" +
						"}]\n" +
						"]\n" +
						"\n" +
						"@auto[]\n" +
						"$SQL.connect-string[mysql://test_user:@localhost/test_db?charset=utf8]\n" +
						"$SQL.connect-accounts[mysql://test_user:@localhost/test_accounts?charset=utf8]\n" +
						"$SQL.connect-items[mysql://test_user:@localhost/items?charset=utf8]\n" +
						"$SQL.connet-items[$SQL.connect-items]\n" +
						"$SQL.connect-service[mysql://test_user:@localhost/service?charset=utf8]\n" +
						"$MAIN:SQL.connect-main[$SQL.connect-string]\n" +
						"$MAIN:SQL.connect-main-TestCharset[$SQL.connect-string]\n" +
						"$SQL.connect-tasks[mysql://test_user:@localhost/tasks?charset=utf8]\n" +
						"$SQL.connect-string-logs[mysql://test_user:@localhost/logs?charset=utf8mb4]\n" +
						"$SQL.connect-string-tools[mysql://test_user:@localhost/tools?charset=utf8]\n" +
						"#$SQL.connect-string[sqlite://db]\n" +
						"$SQL.connect-string-db-test[mysql://test_user:test_password@localhost/test_db]\n" +
						"$SQL.connect-string-stats[mysql://test_user:@localhost/stats]\n");
		setMainAuto("test_auto/auto.p");

		List<String> c = doComplete("test_auto/page.p",
				"@main[]\n# обезличенный минимальный кейс main auto SQL config\n$SQL.<caret>");
		assertTrue("drivers.: " + c, c.contains("drivers."));
		assertTrue("connect-string: " + c, c.contains("connect-string"));
		assertTrue("connect-accounts: " + c, c.contains("connect-accounts"));
		assertTrue("connect-items: " + c, c.contains("connect-items"));
		assertTrue("connect-service: " + c, c.contains("connect-service"));
		assertTrue("connect-tasks: " + c, c.contains("connect-tasks"));
		assertTrue("connect-string-logs: " + c, c.contains("connect-string-logs"));
		assertTrue("connect-string-tools: " + c, c.contains("connect-string-tools"));
		assertTrue("connect-string-db-test: " + c, c.contains("connect-string-db-test"));
		assertTrue("connect-string-stats: " + c, c.contains("connect-string-stats"));
		assertFalse("закомментированный sqlite не должен попадать: " + c, c.contains("sqlite://db"));
	}

	public void testHash_additive_deepDotChain() {
		List<String> c = doComplete("add2.p",
				"@main[]\n# на основе parser3/tests/010.html\n$a[$.b[]]\n$a.c.x.y[\n\t$.z[]\n]\n$a.<caret>");
		assertTrue("b: " + c, c.contains("b"));
		assertTrue("c.: " + c, c.contains("c."));
	}

	public void testHash_additive_addMethod() {
		List<String> c = doComplete("add3.p",
				"@main[]\n# на основе parser3/tests/014.html\n$a[$.1[a1]]\n^a.add[\n\t$.2[a2]\n]\n$a.<caret>");
		assertTrue("1: " + c, c.contains("1"));
		assertTrue("2: " + c, c.contains("2"));
	}

	public void testHash_keysReturnsTableWithDefaultColumn() {
		List<String> c = doComplete("hash_keys_default.p",
				"@main[]\n" +
						"# на основе parser3/tests/210.html\n" +
						"$a[a\"a]\n" +
						"$b[^taint[b\"b]]\n" +
						"$h[\n" +
						"\t$.[$a][$a]\n" +
						"\t$.[$b][$b]\n" +
						"]\n" +
						"$keys[^h._keys[]]\n" +
						"$keys.<caret>");
		assertTrue("key: " + c, c.contains("key"));
	}

	public void testHash_keysReturnsTableWithCustomColumn() {
		List<String> c = doComplete("hash_keys_custom.p",
				"@main[]\n" +
						"# на основе parser3/tests/203.html\n" +
						"$h[\n" +
						"\t$.1[paf]\n" +
						"\t$.2[misha]\n" +
						"]\n" +
						"$keys[^h._keys[k]]\n" +
						"$keys.<caret>");
		assertTrue("k: " + c, c.contains("k"));
		assertFalse("key NOT: " + c, c.contains("key"));
	}

	public void testHash_renamePairRenamesKeyAndKeepsNestedStructure() {
		List<String> c = doComplete("hash_rename_pair.p",
				"@main[]\n# на основе parser3/tests/414.html\n$demo[$.a[$.value[]] $.b[]]\n^demo.rename[a;c]\n$demo.c.<caret>");
		assertTrue("value: " + c, c.contains("value"));

		List<String> root = doComplete("hash_rename_pair_root.p",
				"@main[]\n# на основе parser3/tests/414.html\n$demo[$.a[$.value[]] $.b[]]\n^demo.rename[a;c]\n$demo.<caret>");
		assertTrue("c: " + root, root.contains("c."));
		assertTrue("b: " + root, root.contains("b"));
		assertFalse("a NOT: " + root, root.contains("a."));
	}

	public void testHash_renameHashRenamesKeyAndKeepsNestedStructure() {
		List<String> c = doComplete("hash_rename_hash.p",
				"@main[]\n# на основе parser3/tests/414.html\n$demo[$.a[$.value[]]]\n^demo.rename[$.a[c]]\n$demo.c.<caret>");
		assertTrue("value: " + c, c.contains("value"));
	}

	public void testHash_subLiteralRemovesKeys() {
		List<String> c = doComplete("hash_sub_literal.p",
				"@main[]\n# на основе parser3/tests/014.html\n$a[$.1[a1] $.2[a2] $.3[a3]]\n^a.sub[$.2[]]\n$a.<caret>");
		assertTrue("1: " + c, c.contains("1"));
		assertTrue("3: " + c, c.contains("3"));
		assertFalse("2 NOT: " + c, c.contains("2"));
	}

	public void testHash_subVariableRemovesKeysFromOtherHash() {
		List<String> c = doComplete("hash_sub_variable.p",
				"@main[]\n# на основе parser3/tests/014.html\n$a[$.1[a1] $.2[a2] $.3[a3]]\n$b[$.2[b2] $.3[b3]]\n^a.sub[$b]\n$a.<caret>");
		assertTrue("1: " + c, c.contains("1"));
		assertFalse("2 NOT: " + c, c.contains("2"));
		assertFalse("3 NOT: " + c, c.contains("3"));
	}

	public void testHash_selectKeepsHashStructure() {
		List<String> c = doComplete("hash_select_keeps_structure.p",
				"@main[]\n# на основе parser3/tests/388-sql.html\n$data[^hash::sql{select food as id, pet from pets}]\n$filtered[^data.select[k;v]($v.pet eq 'cat')]\n$filtered.x.<caret>");
		assertTrue("pet: " + c, c.contains("pet"));
		assertFalse("id NOT: " + c, c.contains("id"));
	}

	public void testHash_selectKeepsLiteralHashStructure() {
		List<String> c = doComplete("hash_select_literal_keeps_structure.p",
				"@main[]\n# на основе parser3/tests/014.html\n$a[$.1[$.value[a1]] $.2[$.value[a2]]]\n$filtered[^a.select[k;v]($v.value eq 'a1')]\n$filtered.1.<caret>");
		assertTrue("value: " + c, c.contains("value"));
	}

	public void testHash_selectParamsAreVariables() {
		List<String> valueCompletion = doComplete("hash_select_value_param.p",
				"@main[]\n# на основе parser3/tests/430.html\n$a[^array::copy[ $.1[v1] $.5[v5] ]]\n$res[^a.select[key;value]($v<caret> >= $limit)]");
		assertTrue("value должен быть виден как переменная select: " + valueCompletion,
				valueCompletion.contains("value"));

		List<String> keyCompletion = doComplete("hash_select_key_param.p",
				"@main[]\n# на основе parser3/tests/430.html\n$a[^array::copy[ $.1[v1] $.5[v5] ]]\n$res[^a.select[key;value]($k<caret> >= $limit)]");
		assertTrue("key должен быть виден как переменная select: " + keyCompletion,
				keyCompletion.contains("key"));
	}

	public void testHash_sortParamsAreVariables() {
		List<String> valueCompletion = doComplete("hash_sort_value_param.p",
				"@main[]\n# на основе parser3/tests/430.html\n$a[^array::copy[ $.1[v1] $.5[v5] ]]\n$res[^a.sort[key;value]($v<caret> >= $limit)]");
		assertTrue("value должен быть виден как переменная sort: " + valueCompletion,
				valueCompletion.contains("value"));

		List<String> keyCompletion = doComplete("hash_sort_key_param.p",
				"@main[]\n# на основе parser3/tests/430.html\n$a[^array::copy[ $.1[v1] $.5[v5] ]]\n$res[^a.sort[key;value]($k<caret> >= $limit)]");
		assertTrue("key должен быть виден как переменная sort: " + keyCompletion,
				keyCompletion.contains("key"));
	}

	public void testHash_selectAndSortParamsAreVariablesOnFieldChain() {
		List<String> selectCompletion = doComplete("hash_select_field_value_param.p",
				"@main[]\n# на основе parser3/tests/430.html\n$res.items[^array::copy[ $.1[v1] $.5[v5] ]]\n$res[^res.items.select[key;value]($v<caret> >= $limit)]");
		assertTrue("value должен быть виден как переменная select на поле: " + selectCompletion,
				selectCompletion.contains("value"));

		List<String> sortCompletion = doComplete("hash_sort_field_value_param.p",
				"@main[]\n# на основе parser3/tests/430.html\n$res.items[^array::copy[ $.1[v1] $.5[v5] ]]\n$res[^res.items.sort[key;value]($v<caret> >= $limit)]");
		assertTrue("value должен быть виден как переменная sort на поле: " + sortCompletion,
				sortCompletion.contains("value"));
	}

	public void testHash_selectAndSortValueParamKeepsSourceFields() {
		List<String> selectCompletion = doComplete("hash_select_value_fields.p",
				"@main[]\n# на основе parser3/tests/388-sql.html\n$data[^hash::sql{select food as id, pet from pets}]\n$filtered[^data.select[key;item]($item.<caret>)]");
		assertTrue("pet в value-параметре select: " + selectCompletion, selectCompletion.contains("pet"));
		assertFalse("id не должен быть value-полем select: " + selectCompletion, selectCompletion.contains("id"));

		List<String> sortCompletion = doComplete("hash_sort_value_fields.p",
				"@main[]\n# на основе parser3/tests/388-sql.html\n$data[^hash::sql{select food as id, pet from pets}]\n$sorted[^data.sort[key;item]($item.<caret>)]");
		assertTrue("pet в value-параметре sort: " + sortCompletion, sortCompletion.contains("pet"));
		assertFalse("id не должен быть value-полем sort: " + sortCompletion, sortCompletion.contains("id"));
	}

	public void testHash_unionMergesKnownKeys() {
		List<String> c = doComplete("hash_union_keys.p",
				"@main[]\n# на основе parser3/tests/014.html\n$a[$.1[a1] $.2[a2] $.3[a3]]\n$b[$.2[b2] $.3[b3] $.4[b4]]\n$u[^a.union[$b]]\n$u.<caret>");
		assertTrue("1: " + c, c.contains("1"));
		assertTrue("2: " + c, c.contains("2"));
		assertTrue("4: " + c, c.contains("4"));
	}

	public void testHash_unionKeepsNestedStructureFromBothHashes() {
		List<String> left = doComplete("hash_union_nested_left.p",
				"@main[]\n# на основе parser3/tests/014.html и 010.html\n$a[$.1[$.value[]]]\n$b[$.4[$.other[]]]\n$u[^a.union[$b]]\n$u.1.<caret>");
		assertTrue("value: " + left, left.contains("value"));

		List<String> right = doComplete("hash_union_nested_right.p",
				"@main[]\n# на основе parser3/tests/014.html и 010.html\n$a[$.1[$.value[]]]\n$b[$.4[$.other[]]]\n$u[^a.union[$b]]\n$u.4.<caret>");
		assertTrue("other: " + right, right.contains("other"));
	}

	public void testHash_intersectionKeepsCommonKeysFromSelf() {
		List<String> c = doComplete("hash_intersection_keys.p",
				"@main[]\n# на основе parser3/tests/014.html\n$a[$.1[$.value[]] $.2[a2]]\n$b[$.2[b2] $.1[b1] $.4[b4]]\n$i[^a.intersection[$b]]\n$i.<caret>");
		assertTrue("1.: " + c, c.contains("1."));
		assertTrue("2: " + c, c.contains("2"));
		assertFalse("4 NOT: " + c, c.contains("4"));

		List<String> nested = doComplete("hash_intersection_nested.p",
				"@main[]\n# на основе parser3/tests/014.html\n$a[$.1[$.value[]] $.2[a2]]\n$b[$.2[b2] $.1[b1]]\n$i[^a.intersection[$b]]\n$i.1.<caret>");
		assertTrue("value: " + nested, nested.contains("value"));
	}

	public void testHash_intersectionPrefersNestedStructureFromSelf() {
		List<String> c = doComplete("hash_intersection_prefers_self_nested.p",
				"@main[]\n# на основе parser3/tests/014.html\n$a[$.1[$.self_value[]]]\n$b[$.1[$.arg_value[]]]\n$i[^a.intersection[$b]]\n$i.1.<caret>");
		assertTrue("self_value: " + c, c.contains("self_value"));
		assertFalse("arg_value NOT: " + c, c.contains("arg_value"));
	}

	// === 5: hash::sql / array::sql ===

	public void testHashSql_wildcardKeys() {
		List<String> c = doComplete("hsql1.p",
				"@main[]\n# на основе parser3/tests/388-sql.html\n$list[^hash::sql{select food as id, pet from pets}]\n$list.x.<caret>");
		assertTrue("pet: " + c, c.contains("pet"));
		assertFalse("id NOT: " + c, c.contains("id"));
	}

	public void testHashSql_foreachValueKeepsSqlFields() {
		List<String> c = doComplete("hsql_foreach_value.p",
				"@main[]\n# на основе parser3/tests/388-sql.html\n$person[^hash::sql{select food as id, pet from pets}]\n^person.foreach[key;value]{\n\t$value.<caret>\n}");
		assertTrue("pet: " + c, c.contains("pet"));
		assertFalse("id NOT: " + c, c.contains("id"));
	}

	public void testHashSql_numericDotKeyKeepsSqlFields() {
		List<String> c = doComplete("hsql_numeric_dot_key.p",
				"@main[]\n# на основе parser3/tests/388-sql.html\n$person[^hash::sql{select food as id, pet from pets}]\n$person.0.<caret>");
		assertTrue("pet: " + c, c.contains("pet"));
		assertFalse("id NOT: " + c, c.contains("id"));
	}

	public void testHashSql_numericBracketKeyKeepsSqlFields() {
		List<String> c = doComplete("hsql_numeric_bracket_key.p",
				"@main[]\n# на основе parser3/tests/388-sql.html\n$person[^hash::sql{select food as id, pet from pets}]\n$person.[0].<caret>");
		assertTrue("pet: " + c, c.contains("pet"));
		assertFalse("id NOT: " + c, c.contains("id"));
	}

	public void testHashSql_dynamicBracketKeyKeepsSqlFields() {
		List<String> c = doComplete("hsql_dynamic_bracket_key.p",
				"@main[]\n# на основе parser3/tests/388-sql.html\n$key[0]\n$person[^hash::sql{select food as id, pet from pets}]\n$person.[$key].<caret>");
		assertTrue("pet: " + c, c.contains("pet"));
		assertFalse("id NOT: " + c, c.contains("id"));
	}

	public void testTableHashResultKeepsRowColumnsFromSqlTable() {
		List<String> c = doComplete("table_hash_result_rows_from_sql.p",
				"@main[]\n" +
						"# реальный кейс обезличенного fixture errors/5.p; на основе parser3/tests/341.html\n" +
						"$rowKey[123]\n" +
						"$list[^table::sql{\n" +
						"\tSELECT\n" +
						"\t\ti.user_id,\n" +
						"\t\tq.task_id,\n" +
						"\t\tq.user_id\n" +
						"\tFROM\n" +
						"\t\turl_stats AS i,\n" +
						"\t\ttasks AS q\n" +
						"}]\n" +
						"$list[^list.hash[user_id]]\n" +
						"$list.[$rowKey].<caret>");

		assertTrue("table.hash[key] должен сохранить user_id в значении строки: " + c, c.contains("user_id"));
		assertTrue("table.hash[key] должен сохранить task_id в значении строки: " + c, c.contains("task_id"));
		assertTrue("table.hash[key] должен сохранить user_id в значении строки: " + c, c.contains("user_id"));
	}

	public void testTableHashResultExplicitValueColumnIsScalar() {
		List<String> c = doComplete("table_hash_result_explicit_value_column_scalar.p",
				"@main[]\n" +
						"# на основе parser3/tests/341.html: ^t.hash[$id][$price]\n" +
						"$rowKey[123]\n" +
						"$list[^table::sql{SELECT user_id, task_id, user_id FROM tasks}]\n" +
						"$list[^list.hash[user_id][task_id]]\n" +
						"$list.[$rowKey].<caret>");

		assertFalse("table.hash[key][column] возвращает scalar-значение и не должен показывать user_id: " + c,
				c.contains("user_id"));
		assertFalse("table.hash[key][column] возвращает scalar-значение и не должен показывать task_id: " + c,
				c.contains("task_id"));
		assertFalse("table.hash[key][column] возвращает scalar-значение и не должен показывать user_id: " + c,
				c.contains("user_id"));
	}

	public void testTableArrayResultKeepsRowColumnsFromSqlTable() {
		List<String> c = doComplete("table_array_result_rows_from_sql.p",
				"@main[]\n" +
						"# реальный кейс обезличенного fixture errors/5.p; на основе parser3/tests/440.html\n" +
						"$rowIndex[0]\n" +
						"$list[^table::sql{SELECT user_id, task_id, user_id FROM tasks}]\n" +
						"$rows[^list.array[]]\n" +
						"$rows.[$rowIndex].<caret>");

		assertTrue("table.array[] должен сохранить user_id в элементе массива: " + c, c.contains("user_id"));
		assertTrue("table.array[] должен сохранить task_id в элементе массива: " + c, c.contains("task_id"));
		assertTrue("table.array[] должен сохранить user_id в элементе массива: " + c, c.contains("user_id"));
	}

	public void testTableArrayResultKeepsSingleValueColumn() {
		List<String> c = doComplete("table_array_result_single_value_column.p",
				"@main[]\n" +
						"# на основе parser3/tests/440.html: ^t.array[value2]\n" +
						"$rowIndex[0]\n" +
						"$list[^table::sql{SELECT user_id, task_id, user_id FROM tasks}]\n" +
						"$rows[^list.array[task_id]]\n" +
						"$rows.[$rowIndex].<caret>");

		assertFalse("table.array[column] возвращает scalar-значение и не должен показывать user_id: " + c,
				c.contains("user_id"));
		assertFalse("table.array[column] возвращает scalar-значение и не должен показывать task_id: " + c,
				c.contains("task_id"));
		assertFalse("table.array[column] возвращает scalar-значение и не должен показывать user_id: " + c,
				c.contains("user_id"));
	}

	public void testHashSql_atValueKeepsSqlFields() {
		List<String> c = doComplete("hsql_at_value.p",
				"@main[]\n# на основе parser3/tests/388-sql.html\n$person[^hash::sql{select food as id, pet from pets}]\n$row[^person.at[first]]\n$row.<caret>");
		assertTrue("pet: " + c, c.contains("pet"));
		assertFalse("id NOT: " + c, c.contains("id"));
	}

	public void testHashAtSelfReassignmentKeepsObjectMethodCompletion() {
		List<String> c = doComplete("hash_at_self_reassignment_object_methods.p",
				"@main[]\n" +
						"# реальный минимальный кейс самоприсваивания через hash.at[first]\n" +
						"$data[\n" +
						"\t$.var[]\n" +
						"]\n" +
						"$url[https://test.com]\n" +
						"$list[^test_api[$url]]\n" +
						"$d[^list.data.json.at[first]]\n" +
						"$data[$list.data.json]\n" +
						"^if(!$data){^return[]}\n" +
						"$data[^data.at[first]]\n" +
						"^if(!$data){^return[]}\n" +
						"$data[^data.at[first]]\n" +
						"^dshow[^data._<caret>]\n" +
						"^dstop[$data]\n");
		assertTrue("_count: " + c, c.contains("_count"));
	}

	public void testHashSql_atKeyDoesNotReturnSqlFields() {
		List<String> c = doComplete("hsql_at_key.p",
				"@main[]\n# на основе parser3/tests/388-sql.html\n$person[^hash::sql{select food as id, pet from pets}]\n$key[^person.at[first;key]]\n^key.<caret>");
		assertFalse("pet NOT: " + c, c.contains("pet"));
		assertTrue("string methods: " + c, c.contains("length"));
	}

	public void testHashSql_atHashKeepsHashValueStructure() {
		List<String> c = doComplete("hsql_at_hash.p",
				"@main[]\n# на основе parser3/tests/388-sql.html\n$person[^hash::sql{select food as id, pet from pets}]\n$one[^person.at[first;hash]]\n$one.0.<caret>");
		assertTrue("pet: " + c, c.contains("pet"));
		assertFalse("id NOT: " + c, c.contains("id"));
	}

	public void testArraySql_wildcardKeys() {
		List<String> c = doComplete("asql1.p",
				"@main[]\n# на основе parser3/tests/429-sql.html\n$list[^array::sql{select weigth as id, pet from pets}]\n$list.0.<caret>");
		assertTrue("id: " + c, c.contains("id"));
		assertTrue("pet: " + c, c.contains("pet"));
	}

	public void testHashSql_backtickAndAs() {
		List<String> c = doComplete("hsql_bt.p",
				"@main[]\n# минимальный SQL-кейс с quoted identifiers; в parser3/tests точного backtick-примера нет\n$hash[^hash::sql{SELECT id, pet, `s1 s2`, `s3 s4` AS 'column name' FROM pets}]\n$hash.x.<caret>");
		assertTrue("pet: " + c, c.contains("pet"));
		assertTrue("[s1 s2]: " + c, c.contains("[s1 s2]"));
		assertTrue("[column name]: " + c, c.contains("[column name]"));
	}

	// === 6: User SQL templates ===

	public void testUserSql_hash() {
		List<String> c = doComplete("usql_h.p",
				"@main[]\n# на основе parser3/tests/388-sql.html\n$oSql[x]\n$data[^oSql.hash{select food as id, pet from pets}]\n$data.x.<caret>");
		assertTrue("pet: " + c, c.contains("pet"));
		assertFalse("id NOT: " + c, c.contains("id"));
	}

	public void testUserSql_array() {
		List<String> c = doComplete("usql_a.p",
				"@main[]\n# на основе parser3/tests/429-sql.html\n$oSql[x]\n$data[^oSql.array{select weigth as id, pet from pets}]\n$data.0.<caret>");
		assertTrue("id: " + c, c.contains("id"));
		assertTrue("pet: " + c, c.contains("pet"));
	}

	public void testUserSql_table_dollarDot() {
		List<String> c = doComplete("usql_t1.p",
				"@main[]\n# на основе parser3/tests/388-sql.html\n$oSql[x]\n$data[^oSql.table{select pet, food from pets}]\n$data.<caret>");
		assertTrue("pet: " + c, c.contains("pet"));
		assertTrue("food: " + c, c.contains("food"));
	}

	public void testUserSql_table_caretDot() {
		List<String> c = doComplete("usql_t2.p",
				"@main[]\n# на основе parser3/tests/388-sql.html\n$oSql[x]\n$data[^oSql.table{select pet, food from pets}]\n^data.<caret>");
		assertTrue("pet.: " + c, c.contains("pet."));
		assertTrue("food.: " + c, c.contains("food."));
	}

	// === 7: Array literal ===

	public void testArray_literal_elements() {
		List<String> c = doComplete("arr1.p",
				"@main[]\n# реальный минимальный кейс array literal completion\n$arr[val1;$.val[x];$.list[^table::create{name\tvalue}]]\n$arr.1.<caret>");
		assertTrue("val: " + c, c.contains("val"));
	}

	// === 8: foreach / for ===

	public void testForeach_valueKeys() {
		List<String> c = doComplete("fe1.p",
				"@main[]\n# на основе parser3/tests/026.html\n$h[\n\t$.a[\n\t\t$.key[]\n\t\t$.value[]\n\t]\n\t$.b[\n\t\t$.key[]\n\t\t$.value[]\n\t\t$.extra[]\n\t]\n]\n^h.foreach[key;value]{\n\t$value.<caret>\n}");
		assertTrue("key: " + c, c.contains("key"));
		assertTrue("value: " + c, c.contains("value"));
		assertTrue("extra: " + c, c.contains("extra"));
	}

	public void testForeach_bodyAssignmentsMerge() {
		List<String> c = doComplete("fe2.p",
				"@main[]\n# на основе parser3/tests/026.html и 028.html\n$h[\n\t$.a[$.value[]]\n]\n^h.foreach[key;value]{\n\t$value.row[\n\t\t$.list[^table::create{o\n^h.foreach[key;value]{$value\n}}]\n\t]\n}\n$value.<caret>");
		assertTrue("value: " + c, c.contains("value"));
		assertTrue("row.: " + c, c.contains("row."));
	}

	public void testForeach_nestedTableColumns() {
		List<String> c = doComplete("fe3.p",
				"@main[]\n# на основе parser3/tests/026.html и 028.html\n$h[$.a[$.value[]]]\n^h.foreach[key;value]{\n\t$value.row[\n\t\t$.list[^table::create{o\n^h.foreach[key;value]{$value\n}}]\n\t]\n}\n$value.row.list.<caret>");
		assertTrue("o: " + c, c.contains("o"));
	}

	/**
	 * $h.a. должен содержать additive ключи из foreach-body ($value.row)
	 */
	public void testForeach_additiveVisibleInParent() {
		List<String> c = doComplete("fe4.p",
				"@main[]\n# на основе parser3/tests/026.html и 028.html\n$h[\n\t$.a[\n\t\t$.value[]\n\t]\n]\n^h.foreach[key;value]{\n\t$value.row[\n\t\t$.list[^table::create{o\n^h.foreach[key;value]{$value\n}}]\n\t]\n}\n$h.a.<caret>");
		assertTrue("value: " + c, c.contains("value"));
		assertTrue("row.: " + c, c.contains("row."));
	}

	/**
	 * $h.a.row. — deeper chain через foreach additive → list.
	 */
	public void testForeach_additiveDeepChain() {
		List<String> c = doComplete("fe5.p",
				"@main[]\n# на основе parser3/tests/026.html и 028.html\n$h[\n\t$.a[\n\t\t$.value[]\n\t]\n]\n^h.foreach[key;value]{\n\t$value.row[\n\t\t$.list[^table::create{o\n^h.foreach[key;value]{$value\n}}]\n\t]\n}\n$h.a.row.<caret>");
		assertTrue("list.: " + c, c.contains("list."));
	}

	public void testFor_valueKeys() {
		List<String> c = doComplete("for1.p",
				"@main[]\n# на основе parser3/tests/430.html\n$a[$.1[];$.5[];$.6[$.value[]]]\n^a.for[k;v]{\n\t$v.<caret>\n}");
		// v — элемент массива arr. Каждый элемент — хеш с одним ключом.
		// Объединение ключей всех элементов = 1, 5, 6.
		assertTrue("1: " + c, c.contains("1"));
		assertTrue("5: " + c, c.contains("5"));
		assertTrue("6.: " + c, c.contains("6."));
	}

	// === 9: Override ===

	public void testHash_override_onlyLatest() {
		List<String> c = doComplete("ovr1.p",
				"@main[]\n# на основе parser3/tests/010.html\n$a[\n\t$.b[]\n]\n\n$a[\n\t$.c[]\n]\n$a.<caret>");
		assertTrue("c: " + c, c.contains("c"));
		assertFalse("b NOT: " + c, c.contains("b"));
	}

	public void testMethodResultInference_emptyReturnKeepsLastNonEmptyHash() {
		List<String> c = doComplete("method_hash_empty_return.p",
				"@makeHash[]\n" +
						"# на основе parser3/tests/152.html: пустой ^return[] не стирает hash-result\n" +
						"$result[^hash::create[]]\n" +
						"^return[]\n" +
						"\n" +
						"@main[]\n" +
						"# на основе parser3/tests/152.html: вызов метода с пустым ^return[]\n" +
						"^makeHash[].<caret>");
		assertTrue("^return[] не должен стирать последний непустой hash: " + c, c.contains("foreach"));
	}

	public void testMethodResultInference_emptyResultKeepsLastNonEmptyHash() {
		List<String> c = doComplete("method_hash_empty_result.p",
				"@makeHash[]\n" +
						"# на основе parser3/tests/152.html: пустой $result[] не стирает hash-result\n" +
						"^return[^hash::create[]]\n" +
						"$result[]\n" +
						"\n" +
						"@main[]\n" +
						"# на основе parser3/tests/152.html: вызов метода с пустым $result[]\n" +
						"^makeHash[].<caret>");
		assertTrue("$result[] не должен стирать последний непустой hash: " + c, c.contains("foreach"));
	}

	public void testHash_selfReference_merge() {
		List<String> c = doComplete("sref1.p",
				"@main[]\n# на основе parser3/tests/014.html\n$a[\n\t$.1[a1]\n]\n\n$a[\n\t$a\n\t$.2[a2]\n]\n$a.<caret>");
		assertTrue("1: " + c, c.contains("1"));
		assertTrue("2: " + c, c.contains("2"));
	}

	public void testHash_override_thenAdditive() {
		List<String> c = doComplete("ovr2.p",
				"@main[]\n# на основе parser3/tests/010.html\n$a[\n\t$.b[]\n]\n\n$a[\n\t$.c[]\n]\n$a.d[value]\n$a.<caret>");
		assertTrue("c: " + c, c.contains("c"));
		assertTrue("d: " + c, c.contains("d"));
		assertFalse("b NOT: " + c, c.contains("b"));
	}

	// === РАЗДЕЛ 10: Комментарии внутри хешей ===

	/**
	 * # комментарий внутри хеша — $data НЕ должен распознаваться как sourceVar
	 */
	public void testHash_commentedSelfRef_hashComment() {
		List<String> c = doComplete("cmt1.p",
				"@main[]\n# на основе parser3/tests/014.html\n$a[\n\t$.1[a1]\n]\n\n$a[\n#\t$a\n\t$.2[a2]\n]\n$a.<caret>");
		assertTrue("2: " + c, c.contains("2"));
		assertFalse("1 NOT (commented out): " + c, c.contains("1"));
	}

	/**
	 * ^rem{} внутри хеша — содержимое должно игнорироваться
	 */
	public void testHash_commentedSelfRef_rem() {
		List<String> c = doComplete("cmt2.p",
				"@main[]\n# на основе parser3/tests/014.html\n$a[\n\t$.1[a1]\n]\n\n$a[\n\t^rem{$a}\n\t$.2[a2]\n]\n$a.<caret>");
		assertTrue("2: " + c, c.contains("2"));
		assertFalse("1 NOT (in ^rem): " + c, c.contains("1"));
	}

	/**
	 * Комментарий НЕ затрагивает настоящие ключи
	 */
	public void testHash_commentDoesNotAffectKeys() {
		List<String> c = doComplete("cmt3.p",
				"@main[]\n# на основе parser3/tests/014.html\n$a[\n\t# это комментарий\n\t$.1[a1]\n\t^rem{это тоже комментарий}\n\t$.2[a2]\n]\n$a.<caret>");
		assertTrue("1: " + c, c.contains("1"));
		assertTrue("2: " + c, c.contains("2"));
	}

	// === РАЗДЕЛ 11: Wildcard + explicit keys ===

	/**
	 * ^data.add[^hash::sql{...}] поверх явных ключей — wildcard $data.x. должен показать всё
	 */
	public void testHash_wildcardMergeExplicit() {
		List<String> c = doComplete("wc1.p",
				"@main[]\n# на основе parser3/tests/014.html и 388-sql.html\n$data[\n\t$.1[\n\t\t$.a[]\n\t]\n\t$.2[\n\t\t$.b[]\n\t]\n]\n^data.add[^hash::sql{select food as id, pet from pets}]\n$data.x.<caret>");
		assertTrue("pet: " + c, c.contains("pet"));
		assertFalse("id NOT: " + c, c.contains("id"));
		assertTrue("a: " + c, c.contains("a"));
		assertTrue("b: " + c, c.contains("b"));
	}

	/**
	 * Два hash::sql через add — wildcard nestedKeys мержатся
	 */
	public void testHash_wildcardMergeTwoSql() {
		List<String> c = doComplete("wc2.p",
				"@main[]\n# на основе parser3/tests/388-sql.html\n$data[^hash::sql{select food as id, pet from pets}]\n^data.add[^hash::sql{select food as id, aggressive from pets}]\n$data.x.<caret>");
		assertTrue("pet: " + c, c.contains("pet"));
		assertTrue("aggressive: " + c, c.contains("aggressive"));
		assertFalse("id NOT: " + c, c.contains("id"));
	}

	// === РАЗДЕЛ 12: Динамические ключи .[expr] ===

	/**
	 * $var.[$dynamic].key[] — динамический ключ как wildcard, key добавляется к значениям
	 */
	public void testHash_dynamicKey_dotChain() {
		List<String> c = doComplete("dyn1.p",
				"@main[]\n# на основе parser3/tests/010.html\n$v[^hash::create[]]\n$v.[$a.b].x[]\n$v.c.<caret>");
		assertTrue("x: " + c, c.contains("x"));
	}

	public void testHash_unknownAssignmentThenDotChainUpgradesRootVariableType() {
		List<String> c = doComplete("dyn_unknown_root_completion.p",
				"@main[]\n# на основе parser3/tests/010.html\n$v[value]\n$v.b[x]\n$v<caret>");
		assertTrue("$v должен предлагаться как hash-переменная с точкой: " + c, c.contains("v."));
	}

	public void testHash_unknownAssignmentThenDotChainExposesRootKeys() {
		List<String> c = doComplete("dyn_unknown_root_keys.p",
				"@main[]\n# на основе parser3/tests/010.html\n$v[value]\n$v.b[x]\n$v.<caret>");
		assertTrue("$v. должен содержать ключ b после $v.b[x]: " + c, c.contains("b"));
	}

	public void testHashSql_dynamicBracketKey_preservesValueStructure() {
		List<String> c = doComplete("dyn_hash_sql_bracket.p",
				"@main[]\n# на основе parser3/tests/388-sql.html\n$zoomData[^hash::sql{select food as id, pet from pets}]\n$zoomData.[x].<caret>");
		assertTrue("pet: " + c, c.contains("pet"));
		assertFalse("id NOT: " + c, c.contains("id"));
	}

	public void testHashSql_mainPrefixDynamicBracketKey_preservesValueStructure() {
		List<String> c = doComplete("dyn_hash_sql_main_bracket.p",
				"@main[]\n# на основе parser3/tests/388-sql.html\n$MAIN:tracked_urls_cache[^hash::sql{select food as id, pet from pets}]\n$MAIN:tracked_urls_cache.[x].<caret>");
		assertTrue("pet: " + c, c.contains("pet"));
		assertFalse("id NOT: " + c, c.contains("id"));
	}

	public void testHashSql_mainPrefixDynamicBracketKeyInsideTopLevelIf_preservesValueStructure() {
		List<String> c = doComplete("dyn_hash_sql_main_if_bracket.p",
				"^if(!$MAIN:tracked_urls_cache){\n" +
						"\t# на основе parser3/tests/388-sql.html\n" +
						"\t$MAIN:tracked_urls_cache[^hash::sql{select food as id, pet from pets}]\n" +
						"}\n" +
						"$MAIN:tracked_urls_cache.[x].<caret>");
		assertTrue("pet: " + c, c.contains("pet"));
		assertFalse("id NOT: " + c, c.contains("id"));
	}

	public void testHashSql_mainPrefixDynamicBracketKeyBeforePlainText_preservesValueStructure() {
		List<String> c = doComplete("dyn_hash_sql_main_if_bracket_text.p",
				"^if(!$MAIN:tracked_urls_cache){\n" +
						"\t# на основе parser3/tests/388-sql.html\n" +
						"\t$MAIN:tracked_urls_cache[^hash::sql{select food as id, pet from pets}]\n" +
						"\t$tracked_urls_cache2[^hash::sql{select food as id, aggressive from pets}]\n" +
						"}\n" +
						"$MAIN:tracked_urls_cache.[x].<caret> — нет полей");
		assertTrue("pet: " + c, c.contains("pet"));
		assertFalse("id NOT: " + c, c.contains("id"));
		assertFalse("aggressive NOT: " + c, c.contains("aggressive"));
	}

	public void testHashSql_mainPrefixDynamicBracketReference_preservesValueStructure() {
		List<String> c = doComplete("dyn_hash_sql_main_ref.p",
				"@main[]\n# на основе parser3/tests/388-sql.html\n$uri[/]\n$MAIN:tracked_urls_cache[^hash::sql{select food as id, pet from pets}]\n$trackedData[$MAIN:tracked_urls_cache.[$uri]]\n$trackedData.<caret>");
		assertTrue("pet: " + c, c.contains("pet"));
		assertFalse("id NOT: " + c, c.contains("id"));
	}

	public void testHash_dynamicBracketReferenceToKnownElements_preservesNestedKeys() {
		List<String> c = doComplete("dyn_hash_literal_main_ref.p",
				"@main[]\n" +
						"# обезличенный минимальный кейс dynamic bracket reference к known hash elements\n" +
						"$test_app_config[\n" +
						"\t$.connects[\n" +
						"\t\t$.0[\n" +
						"\t\t\t$.string[mysql://test_user:test_password@localhost/test_db?charset=utf8mb4]\n" +
						"\t\t]\n" +
						"\t\t$.1[\n" +
						"\t\t\t$.string[mysql://test_user:test_password@localhost/test_db?charset=utf8mb4]\n" +
						"\t\t]\n" +
						"\t]\n" +
						"]\n" +
						"$connect[$test_app_config.connects.[$key]]\n" +
						"$connect.<caret>");
		assertTrue("string: " + c, c.contains("string"));
	}

	public void testHashSql_dynamicDollarKey_preservesValueStructure() {
		List<String> c = doComplete("dyn_hash_sql_dollar.p",
				"@main[]\n# на основе parser3/tests/388-sql.html\n$scoreCnt[^hash::sql{select food as id, pet from pets}]\n$scoreCnt.$x.<caret>");
		assertTrue("pet: " + c, c.contains("pet"));
		assertFalse("id NOT: " + c, c.contains("id"));
	}

	public void testHashSql_dynamicComplexBracketKey_preservesValueStructure() {
		List<String> c = doComplete("dyn_hash_sql_complex_expr.p",
				"@main[]\n# на основе parser3/tests/388-sql.html\n$scoreCnt[^hash::sql{select food as id, pet from pets}]\n$scoreCnt.[$v.id].<caret>");
		assertTrue("pet: " + c, c.contains("pet"));
		assertFalse("id NOT: " + c, c.contains("id"));
	}

	public void testHashSql_dynamicMethodBracketKey_preservesValueStructure() {
		List<String> c = doComplete("dyn_hash_sql_method_expr.p",
				"@main[]\n# на основе parser3/tests/388-sql.html\n$scoreCnt[^hash::sql{select food as id, pet from pets}]\n$scoreCnt.[^class:method[]].<caret>");
		assertTrue("pet: " + c, c.contains("pet"));
		assertFalse("id NOT: " + c, c.contains("id"));
	}

	public void testHash_dynamicBracketKey_manualStructurePreserved() {
		List<String> c = doComplete("dyn_hash_manual_bracket.p",
				"@main[]\n# на основе parser3/tests/439.html\n^if(!^h.contains[$i]){\n" +
						"\t$h.[$i][\n" +
						"\t\t$.value[v$i]\n" +
						"\t\t$.marker[$i]\n" +
						"\t]\n" +
						"}\n" +
						"^h.[$i].<caret>");
		assertTrue("value.: " + c, c.contains("value."));
		assertTrue("marker.: " + c, c.contains("marker."));
	}

	public void testHash_dynamicBracketKey_withDotInsideExpression_preservedForCaretChain() {
		List<String> c = doComplete("dyn_hash_manual_bracket_nested_expr.p",
				"@main[]\n# на основе parser3/tests/430.html\n$a[^array::copy[ $.1[v1] $.5[v5] ]]\n$res[^hash::create[]]\n" +
						"$res.[$a.1][\n" +
						"\t$.value[v1]\n" +
						"]\n" +
						"^res.[$a.1].va<caret>");
		assertTrue("value.: " + c, c.contains("value."));
	}

	/**
	 * foreach additive видны через явный ключ: $data.var. → xxx
	 */
	public void testForeach_additiveVisibleViaExplicitKey() {
		List<String> c = doComplete("fe6.p",
				"@main[]\n# на основе parser3/tests/026.html\n$h[\n\t$.a[]\n\t$.b[]\n]\n^h.foreach[key;value]{\n\t$value.printed[1]\n}\n$h.a.<caret>");
		assertTrue("printed: " + c, c.contains("printed"));
	}

	/**
	 * Пустой hash::create[] + foreach additive → $data.some. показывает xxx
	 */
	public void testForeach_additiveVisibleViaWildcard() {
		List<String> c = doComplete("fe7.p",
				"@main[]\n# на основе parser3/tests/014.html\n$h[^hash::create[]]\n^h.foreach[key;value]{\n\t$value.printed[1]\n}\n$h.some.<caret>");
		assertTrue("printed: " + c, c.contains("printed"));
	}

	/**
	 * Неизвестный тип + foreach → $data.some. показывает xxx
	 */
	public void testForeach_additiveUnknownType() {
		List<String> c = doComplete("fe8.p",
				"@main[]\n# на основе parser3/tests/026.html; источник оставлен неизвестным для проверки wildcard fallback\n$h[^some_method[]]\n^h.foreach[key;value]{\n\t$value.printed[1]\n}\n$h.some.<caret>");
		assertTrue("printed: " + c, c.contains("printed"));
	}

	public void testForeach_additiveVisibleViaParenAssignment() {
		List<String> c = doComplete("fe9.p",
				"@main[]\n# на основе parser3/tests/026.html\n$h[\n" +
						"\t$.a[\n" +
						"\t\t$.key[1]\n" +
						"\t\t$.line[2]\n" +
						"\t]\n" +
						"]\n" +
						"^h.foreach[key;value]{\n" +
						"\t$value.printed(1)\n" +
						"}\n" +
						"$value.<caret>");
		assertTrue("key: " + c, c.contains("key"));
		assertTrue("line: " + c, c.contains("line"));
		assertTrue("printed: " + c, c.contains("printed"));
	}

	public void testForeach_additiveVisibleViaBraceAssignment() {
		List<String> c = doComplete("fe10.p",
				"@main[]\n# на основе parser3/tests/026.html\n$h[\n" +
						"\t$.a[\n" +
						"\t\t$.key[1]\n" +
						"\t\t$.line[2]\n" +
						"\t]\n" +
						"]\n" +
						"^h.foreach[key;value]{\n" +
						"\t$value.printed{1}\n" +
						"}\n" +
						"$value.<caret>");
		assertTrue("key: " + c, c.contains("key"));
		assertTrue("line: " + c, c.contains("line"));
		assertTrue("printed: " + c, c.contains("printed"));
	}

	// === РАЗДЕЛ 13: SQL с Parser3-кодом внутри ===

	/**
	 * ^if($var){,name} внутри SQL — колонка name должна парситься
	 */
	public void testTableSql_parser3InsideSql() {
		List<String> c = doComplete("sql_p3.p",
				"@main[]\n# на основе parser3/tests/388-sql.html: минимальный SQL с Parser3-кодом\n$list[^table::sql{SELECT\n    id\n    ^if($var){\n       ,name\n    }\n    FROM list}]\n$list.<caret>");
		assertTrue("id: " + c, c.contains("id"));
		assertTrue("name: " + c, c.contains("name"));
	}

	// === РАЗДЕЛ 14: split с опциями ===

	/**
	 * ^str.split[,] → table с колонкой "piece"
	 */
	public void testSplit_default() {
		List<String> c = doComplete("sp1.p",
				"@main[][sText;tVertical]\n" +
						"# на основе parser3/tests/169.html\n" +
						"$sText[/a/b/c/]\n" +
						"$sChar[/]\n" +
						"$parts[^sText.split[$sChar;v]]\n" +
						"$parts.<caret>");
		assertTrue("piece: " + c, c.contains("piece"));
	}

	/**
	 * ^str.split[,;lh] → опция h: колонки неизвестны, не показывать "piece"
	 */
	public void testSplit_optionH() {
		List<String> c = doComplete("sp2.p",
				"@main[]\n" +
						"# на основе parser3/tests/389.html\n" +
						"$s[a,,b,c]\n" +
						"$path[^s.split[,,;rh]]\n" +
						"$path.<caret>");
		assertFalse("piece NOT: " + c, c.contains("piece"));
	}

	/**
	 * ^str.split[,;;myCol] → кастомное имя колонки
	 */
	public void testSplit_customColumn() {
		List<String> c = doComplete("sp3.p",
				"@main[][sText;tVertical]\n" +
						"# на основе parser3/tests/169.html\n" +
						"$sText[/a/b/c/]\n" +
						"$sChar[/]\n" +
						"$sColumnName[zigi]\n" +
						"$parts[^sText.split[$sChar;v;$sColumnName]]\n" +
						"$parts.<caret>");
		assertTrue("zigi: " + c, c.contains("zigi"));
	}

	// === РАЗДЕЛ 15: ^table.columns[] ===

	/**
	 * ^list.columns[] → table с колонкой "column"
	 */
	public void testColumns_default() {
		List<String> c = doComplete("col1.p",
				"@main[][tData;tColumn]\n" +
						"# на основе parser3/tests/168.html\n" +
						"$tData[^table::create{a\tb1\tzigi}]\n" +
						"$col[^tData.columns[]]\n" +
						"$col.<caret>");
		assertTrue("column: " + c, c.contains("column"));
	}

	/**
	 * ^list.columns[xxx] → table с колонкой "xxx"
	 */
	public void testColumns_custom() {
		List<String> c = doComplete("col2.p",
				"@main[][tData;tColumn]\n" +
						"# на основе parser3/tests/168.html\n" +
						"$tData[^table::create{a\tb1\tzigi}]\n" +
						"$col2[^tData.columns[zzz]]\n" +
						"$col2.<caret>");
		assertTrue("zzz: " + c, c.contains("zzz"));
	}
	// === РАЗДЕЛ 16: foreach на вложенных полях ===

	/**
	 * ^hash.meets.foreach[;v] → поле "meets" появляется у "hash"
	 */
	public void testForeachField_fieldIsVisible() {
		List<String> c = doComplete("ff1.p",
				"@main[]\n# на основе parser3/tests/026.html\n$h[^hash::create[]]\n^h.items.foreach[key;value]{\n\t$value.printed[]\n}\n$h.<caret>");
		assertTrue("items: " + c, c.contains("items."));
	}

	/**
	 * ^hash.meets.foreach[;v]{$v.xxx[]} → $hash.me должен предложить meets.
	 */
	public void testForeachField_fieldCompletesFromPrefix() {
		List<String> c = doComplete("ff2.p",
				"@main[]\n# на основе parser3/tests/026.html\n$h[^hash::create[]]\n^h.items.foreach[key;value]{\n\t$value.printed[]\n}\n$h.it<caret>");
		assertTrue("items.: " + c, c.contains("items."));
	}

	/**
	 * ^hash.meets.foreach[;v]{$v.xxx[]} → $hash.meets.some.x показывает xxx
	 */
	public void testForeachField_nestedFieldHasKeys() {
		List<String> c = doComplete("ff3.p",
				"@main[]\n# на основе parser3/tests/026.html\n$h[^hash::create[]]\n^h.items.foreach[key;value]{\n\t$value.printed[]\n}\n$h.items.some.<caret>");
		assertTrue("printed: " + c, c.contains("printed"));
	}

	/**
	 * ^hash.meets.pos[5] → поле "meets" появляется у "hash" (без точки в $hash. контексте — нет ключей)
	 */
	public void testForeachField_anyMethodAddsField() {
		List<String> c = doComplete("ff4.p",
				"@main[]\n# на основе parser3/tests/014.html\n$h[^hash::create[]]\n^h.items.contains[5]\n$h.<caret>");
		assertTrue("items: " + c, c.contains("items"));
	}

	/**
	 * ^hash.a.b.foreach[;v]{$v.xxx[]} → $hash.a.b.some. показывает xxx (глубокая вложенность)
	 */
	public void testForeachField_deepNesting() {
		List<String> c = doComplete("ff5.p",
				"@main[]\n# на основе parser3/tests/026.html\n$h[^hash::create[]]\n^h.items.rows.foreach[key;value]{\n\t$value.printed[]\n}\n$h.items.rows.some.<caret>");
		assertTrue("printed: " + c, c.contains("printed"));
	}

	// === РАЗДЕЛ 17: @OPTIONS locals — параметр метода затеняет $self.field ===

	/**
	 * $one_1[^hash::create[]] в методе с параметром one_1 и @OPTIONS locals:
	 * переменная переопределена локально, нет полей → вставляется БЕЗ точки
	 */
	public void testLocals_paramShadowsSelf_noTrailingDot() {
		List<String> c = doComplete("loc1.p",
				"@CLASS\na\n\n@OPTIONS\nlocals\n\n@auto[]\n# на основе parser3/tests/176_dir/a.p\n$self.one_1[$.one_3[]]\n\n@one[one_1]\n$one_1[^hash::create[]]\n$one_<caret>");
		// Переменная one_1 должна быть в списке, но БЕЗ точки
		assertTrue("one_1: " + c, c.contains("one_1"));
		assertFalse("one_1. NOT: " + c, c.contains("one_1."));
	}

	/**
	 * $one_1.one_3 в методе с параметром one_1:
	 * параметр затеняет $self.one_1 → one_3 НЕ должен предлагаться
	 */
	public void testLocals_paramShadowsSelf_noSelfKeys() {
		List<String> c = doComplete("loc2.p",
				"@CLASS\na\n\n@OPTIONS\nlocals\n\n@auto[]\n# на основе parser3/tests/176_dir/a.p\n$self.one_1[$.one_3[]]\n\n@one[one_1]\n$one_1.<caret>");
		assertFalse("one_3 NOT: " + c, c.contains("one_3"));
	}

	/**
	 * $self.one_1 в том же методе — должен показывать one_3
	 */
	public void testLocals_selfSettingStillVisible() {
		List<String> c = doComplete("loc3.p",
				"@CLASS\na\n\n@OPTIONS\nlocals\n\n@auto[]\n# на основе parser3/tests/176_dir/a.p\n$self.one_1[$.one_3[]]\n\n@one[one_1]\n$self.one_1.<caret>");
		assertTrue("one_3: " + c, c.contains("one_3"));
	}

	// === РАЗДЕЛ 18: Два foreach с одинаковым именем v — ключи не смешиваются ===

	/**
	 * ^h.foreach[;value]{$value.printed[]} и ^h2.foreach[;value]{$value.exported[]} →
	 * $h.0. показывает только printed, не exported
	 */
	public void testForeach_twoVarsNoMix_data() {
		List<String> c = doComplete("fmix1.p",
				"@main[]\n# на основе parser3/tests/026.html\n$h[^hash::create[]]\n$h2[^hash::create[]]\n"
						+ "^h.foreach[key;value]{\n\t$value.printed[]\n}\n"
						+ "^h2.foreach[key;value]{\n\t$value.exported[]\n}\n"
						+ "$h.0.<caret>");
		assertTrue("printed: " + c, c.contains("printed"));
		assertFalse("exported NOT: " + c, c.contains("exported"));
	}

	/**
	 * ^h.foreach[;value]{$value.printed[]} и ^h2.foreach[;value]{$value.exported[]} →
	 * $h2.0. показывает только exported, не printed
	 */
	public void testForeach_twoVarsNoMix_data2() {
		List<String> c = doComplete("fmix2.p",
				"@main[]\n# на основе parser3/tests/026.html\n$h[^hash::create[]]\n$h2[^hash::create[]]\n"
						+ "^h.foreach[key;value]{\n\t$value.printed[]\n}\n"
						+ "^h2.foreach[key;value]{\n\t$value.exported[]\n}\n"
						+ "$h2.0.<caret>");
		assertTrue("exported: " + c, c.contains("exported"));
		assertFalse("printed NOT: " + c, c.contains("printed"));
	}

	/**
	 * ^h.foreach[;value]{$value.printed[]} → $value. внутри тела foreach показывает printed
	 */
	public void testForeach_vDotInsideBody() {
		List<String> c = doComplete("fmix3.p",
				"@main[]\n# на основе parser3/tests/026.html\n$h[^hash::create[]]\n"
						+ "^h.foreach[key;value]{\n\t$value.printed[]\n\t$value.<caret>\n}");
		assertTrue("printed: " + c, c.contains("printed"));
	}

	// === РАЗДЕЛ 19: Псевдонимы ($v[$a.b]) — ключи видны через оригинал ===

	/**
	 * Тест 1: $v[$a.b] + $v.c[] → $a.b. показывает c
	 */
	public void testAlias_basicKey() {
		List<String> c = doComplete("al1.p",
				"@main[]\n# на основе parser3/tests/010.html\n$a[\n\t$.b[value]\n]\n$v[$a.b]\n$v.c[]\n$a.b.<caret>");
		assertTrue("c: " + c, c.contains("c"));
	}

	/**
	 * Тест 2: $v[$a.b] + $v.c[^table::create{name\tage}] → $a.b.c. показывает name, age
	 */
	public void testAlias_nestedTableColumns() {
		List<String> c = doComplete("al2.p",
				"@main[]\n# на основе parser3/tests/010.html и 009.html\n$a[\n\t$.b[value]\n]\n$v[$a.b]\n$v.c[^table::create{name\tage}]\n$a.b.c.<caret>");
		assertTrue("name: " + c, c.contains("name"));
		assertTrue("age: " + c, c.contains("age"));
	}

	/**
	 * Тест 3: Два псевдонима → $a.b. показывает ключи обоих
	 */
	public void testAlias_twoAliasesMerged() {
		List<String> c = doComplete("al3.p",
				"@main[]\n# на основе parser3/tests/010.html\n$a[\n\t$.b[value]\n]\n$v[$a.b]\n$vv[$a.b]\n$v.c[]\n$vv.e[]\n$a.b.<caret>");
		assertTrue("c: " + c, c.contains("c"));
		assertTrue("e: " + c, c.contains("e"));
	}

	/**
	 * Тест 4: $v[$a.b] без добавления ключей → $a.b. не показывает ключи,
	 * но явный Ctrl+Space всё равно показывает пользовательские шаблоны.
	 */
	public void testAlias_noKeys_empty() {
		List<String> c = doComplete("al4.p",
				"@main[]\n# на основе parser3/tests/010.html\n$a[\n\t$.b[value]\n]\n$v[$a.b]\n$a.b.<caret>");
		assertTrue("Ctrl+Space должен показывать пользовательские шаблоны: " + c, c.contains("curl:load[]"));
		assertFalse("Не должно быть ключей из alias без добавления полей: " + c, c.contains("c"));
		assertFalse("Не должно быть ключей из alias без добавления полей: " + c, c.contains("e"));
	}

	/**
	 * Тест 5: $v[^hash::create[$a.b]] — копия, не ссылка → ключи $v НЕ видны через $a.b
	 */
	public void testAlias_copyNotReference() {
		List<String> c = doComplete("al5.p",
				"@main[]\n# на основе parser3/tests/010.html\n$a[\n\t$.b[value]\n]\n$v[^hash::create[$a.b]]\n$v.c[]\n$a.b.<caret>");
		assertFalse("c NOT via copy: " + c, c.contains("c"));
	}

	/**
	 * Тест 6: Через use — псевдоним и ключи в lib.p, cursor в main.p
	 */
	public void testAlias_viaUse() {
		createParser3FileInDir("lib.p",
				"@main[]\n# на основе parser3/tests/010.html\n$a[\n\t$.b[value]\n]\n$v[$a.b]\n$v.c[]\n");
		List<String> c = doComplete("main.p",
				"@main[]\n^use[lib.p]\n$a.b.<caret>");
		assertTrue("c via use: " + c, c.contains("c"));
	}

	/**
	 * Тест 7: $v.c[] объявлен ДО $v[$a.b] → ключ НЕ виден (offset > cursor)
	 */
	public void testAlias_keyBeforeAlias_notVisible() {
		List<String> c = doComplete("al7.p",
				"@main[]\n# на основе parser3/tests/010.html\n$a[\n\t$.b[value]\n]\n$v.c[]\n$v[$a.b]\n$a.b.<caret>");
		assertFalse("c NOT before alias: " + c, c.contains("c"));
	}

	/**
	 * Тест 8: $a.b вставляется С точкой когда есть псевдоним с ключами
	 */
	public void testAlias_topLevel_dotAppended() {
		List<String> c = doComplete("al8.p",
				"@main[]\n# на основе parser3/tests/010.html\n$a[\n\t$.b[value]\n]\n$v[$a.b]\n$v.c[]\n$a.<caret>");
		assertTrue("b. with dot: " + c, c.contains("b."));
	}

	/**
	 * Тест 9: Пустой ^hash::create[] (параметр метода) вставляется БЕЗ точки
	 */
	public void testAlias_emptyHashParam_noDot() {
		List<String> c = doComplete("al9.p",
				"@CLASS\nFromParser152\n\n@method[h]\n# на основе parser3/tests/152.html\n$h[^hash::create[]]\n$h<caret>");
		assertTrue("h without dot: " + c, c.contains("h"));
		assertFalse("h. NOT: " + c, c.contains("h."));
	}


	// =============================================================================
	// РАЗДЕЛ: ${var} — автокомплит переменных в фигурных скобках
	// =============================================================================

	/**
	 * ${<caret>} → показывает переменные (как обычный $<caret>)
	 */
	public void testBraceVar_completionInBraces() {
		List<String> c = doComplete("brace1.p",
				"@main[]\n# на основе parser3/tests/001.html\n$var[value]\n${<caret>}");
		assertTrue("var должна быть в автокомплите ${: " + c, c.contains("var"));
	}

	/**
	 * ${da<caret>} → автокомплит по префиксу
	 */
	public void testBraceVar_completionWithPrefix() {
		List<String> c = doComplete("brace2.p",
				"@main[]\n# на основе parser3/tests/001.html\n$var[value]\n$v2[world]\n${va<caret>}");
		assertTrue("var должна быть в автокомплите ${va: " + c, c.contains("var"));
	}

	/**
	 * ${data.<caret>} → ключи хеша data
	 */
	public void testBraceVar_hashKeysInBraces() {
		List<String> c = doComplete("brace3.p",
				"@main[]\n# на основе parser3/tests/010.html\n$a[\n\t$.b[value]\n\t$.c[other]\n]\n${a.<caret>}");
		assertTrue("b должен быть в автокомплите ${a.: " + c, c.contains("b"));
		assertTrue("c должен быть в автокомплите ${a.: " + c, c.contains("c"));
	}

	/**
	 * ${a.b.<caret>} → вложенные ключи
	 */
	public void testBraceVar_nestedHashKeysInBraces() {
		List<String> c = doComplete("brace4.p",
				"@main[]\n# на основе parser3/tests/010.html\n$a[\n\t$.b[\n\t\t$.c[value]\n\t]\n]\n${a.b.<caret>}");
		assertTrue("c должен быть в автокомплите ${a.b.: " + c, c.contains("c"));
	}

	/**
	 * ${<caret> (без закрывающей скобки) → показывает переменные
	 */
	public void testBraceVar_completionNoBrace() {
		List<String> c = doComplete("brace5.p",
				"@main[]\n# на основе parser3/tests/001.html\n$var[value]\n${<caret>");
		assertTrue("var должна быть в автокомплите ${ без скобки: " + c, c.contains("var"));
	}

	/**
	 * ${data.<caret> (без закрывающей скобки) → ключи хеша
	 */
	public void testBraceVar_hashKeysNoBrace() {
		List<String> c = doComplete("brace6.p",
				"@main[]\n# на основе parser3/tests/010.html\n$a[\n\t$.b[value]\n\t$.c[other]\n]\n${a.<caret>");
		assertTrue("b должен быть в автокомплите ${a. без скобки: " + c, c.contains("b"));
		assertTrue("c должен быть в автокомплите ${a. без скобки: " + c, c.contains("c"));
	}

	/**
	 * ${a.b.<caret> (без закрывающей скобки) → вложенные ключи
	 */
	public void testBraceVar_nestedHashKeysNoBrace() {
		List<String> c = doComplete("brace7.p",
				"@main[]\n# на основе parser3/tests/010.html\n$a[\n\t$.b[\n\t\t$.c[value]\n\t]\n]\n${a.b.<caret>");
		assertTrue("c должен быть в автокомплите ${a.b. без скобки: " + c, c.contains("c"));
	}

	// =============================================================================
	// РАЗДЕЛ 20: параметр метода затеняет глобальную переменную с тем же именем
	// =============================================================================

	/**
	 * Глобальная $data[^table::create{prompt\tresult}] в wish.p,
	 * параметр метода data — в автокомплите $data не должно быть типа table.
	 */
	public void testParam_shadowsGlobalTable_noType() {
		List<String> c = doComplete("shadow_global1.p",
				"@main[]\n# на основе parser3/tests/047.html\n$person[^table::create{name\theight}]\n\n@getResult[person]\n$pers<caret>");
		assertTrue("person должна быть в автокомплите: " + c, c.contains("person"));
		// тип не должен быть table (параметр без явного типа → "param")
		// проверяем через отсутствие колонок в автокомплите $data.
		List<String> c2 = doComplete("shadow_global2.p",
				"@main[]\n# на основе parser3/tests/047.html\n$person[^table::create{name\theight}]\n\n@getResult[person]\n$person.<caret>");
		assertFalse("name НЕ должен быть в $person. (параметр, не таблица): " + c2, c2.contains("name"));
		assertFalse("height НЕ должен быть в $person. (параметр, не таблица): " + c2, c2.contains("height"));
	}

	/**
	 * $self.setting — с параметром setting в том же методе:
	 * $self.setting. ДОЛЖЕН показывать land_id (explicit self не затеняется параметром).
	 */
	public void testParam_selfExplicit_showsKeys() {
		List<String> c = doComplete("shadow_self1.p",
				"@CLASS\na\n\n@OPTIONS\nlocals\n\n@auto[]\n# на основе parser3/tests/176_dir/a.p\n$self.one_1[$.one_3[]]\n\n@one[one_1]\n$self.one_1.<caret>");
		assertTrue("one_3 должен быть в $self.one_1.: " + c, c.contains("one_3"));
	}

	/**
	 * $setting в методе с параметром setting + переопределением $setting[y]:
	 * автокомплит не должен предлагать колонки глобальной таблицы с тем же именем.
	 */
	public void testParam_withLocalOverride_noGlobalKeys() {
		List<String> c = doComplete("shadow_override1.p",
				"@main[]\n# на основе parser3/tests/047.html\n$person[^table::create{name\theight}]\n\n@m[person]\n$person[override]\n$person.<caret>");
		assertFalse("name НЕ должен быть в $person. (локальное переопределение): " + c, c.contains("name"));
	}}
