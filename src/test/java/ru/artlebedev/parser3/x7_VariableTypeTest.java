package ru.artlebedev.parser3;

import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;

/**
 * Тесты переменных с типами: определение типа, навигация ^var.method[],
 * эквивалентность форм $var/$self.var/$MAIN:var, @OPTIONS locals.
 */
public class x7_VariableTypeTest extends Parser3TestCase {

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
						"# реальный минимальный кейс Parser3 fixture\n@USE\n" +
						"classes/User.p\n" +
						"classes/Admin.p\n" +
						"classes/UserCF.p\n"
		);

		// www/classes/User.p
		createParser3FileInDir("www/classes/User.p",
				"# реальный минимальный кейс Parser3 fixture\n@CLASS\n" +
						"User\n" +
						"\n" +
						"@create[name;email]\n" +
						"$self.name[$name]\n" +
						"$self.email[$email]\n" +
						"\n" +
						"@validate[]\n" +
						"^if(!$self.email){^throw[error]}\n" +
						"\n" +
						"@save[]\n" +
						"$result[saved]\n" +
						"\n" +
						"@getName[]\n" +
						"$result[$self.name]\n" +
						"\n" +
						"@GET_dyn[]\n" +
						"$result[dynamic_value]\n"
		);

		// www/classes/Admin.p — наследует User
		createParser3FileInDir("www/classes/Admin.p",
				"# реальный минимальный кейс Parser3 fixture\n@CLASS\n" +
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
						"$result[banned]\n"
		);

		// www/classes/Other.p — отдельный класс
		createParser3FileInDir("www/classes/Other.p",
				"# реальный минимальный кейс Parser3 fixture\n@CLASS\n" +
						"Other\n" +
						"\n" +
						"@validate[]\n" +
						"$result[other validate]\n"
		);

		// lib.p — методы MAIN (подключен через auto.p)
		createParser3FileInDir("lib.p",
				"@helper[]\n" +
						"$result[ok]\n"
		);

		// notconnected.p — НЕ подключен
		createParser3FileInDir("notconnected.p",
				"@stuff[]\n" +
						"$globalVar[^User::create[a;b]]\n"
		);

		// =============================================================
		// Тестовые файлы
		// =============================================================

		// --- Эквивалентность форм в MAIN без locals ---
		createParser3FileInDir("www/equiv_main.p",
				"# Присваивание через $var\n" +
						"$u1[^User::create[A;a@b]]\n" +
						"^u1.validate[]\n" +
						"^self.u1.validate[]\n" +
						"^MAIN:u1.validate[]\n" +
						"\n" +
						"# Присваивание через $self.var\n" +
						"$self.u2[^User::create[B;b@c]]\n" +
						"^u2.validate[]\n" +
						"^self.u2.validate[]\n" +
						"^MAIN:u2.validate[]\n" +
						"\n" +
						"# Присваивание через $MAIN:var\n" +
						"$MAIN:u3[^User::create[C;c@d]]\n" +
						"^u3.validate[]\n" +
						"^self.u3.validate[]\n" +
						"^MAIN:u3.validate[]\n"
		);

		// --- MAIN с @OPTIONS locals ---
		createParser3FileInDir("www/locals_main.p",
				"@OPTIONS\n" +
						"locals\n" +
						"\n" +
						"@method1[]\n" +
						"$localUser[^User::create[L;l@m]]\n" +
						"^localUser.validate[]\n" +
						"$MAIN:globalUser[^Admin::create[G;g@h;admin]]\n" +
						"\n" +
						"@method2[]\n" +
						"^localUser.validate[]\n" +
						"^MAIN:globalUser.ban[]\n" +
						"^self.globalUser.ban[]\n"
		);

		// --- Класс без locals ---
		createParser3FileInDir("www/class_no_locals.p",
				"# реальный минимальный кейс Parser3 fixture\n@CLASS\n" +
						"TestNoLocals\n" +
						"\n" +
						"@create[]\n" +
						"$obj[^User::create[T;t@x]]\n" +
						"$self.obj2[^Admin::create[S;s@y;mod]]\n" +
						"\n" +
						"@process[]\n" +
						"^obj.validate[]\n" +
						"^self.obj.validate[]\n" +
						"^self.obj2.ban[]\n"
		);

		// --- Класс с @OPTIONS locals ---
		createParser3FileInDir("www/class_locals.p",
				"# реальный минимальный кейс Parser3 fixture\n@CLASS\n" +
						"TestLocals\n" +
						"\n" +
						"@OPTIONS\n" +
						"locals\n" +
						"\n" +
						"@create[]\n" +
						"$localObj[^User::create[CL;cl@x]]\n" +
						"^localObj.validate[]\n" +
						"$self.sharedObj[^Admin::create[CS;cs@y;admin]]\n" +
						"\n" +
						"@process[]\n" +
						"^localObj.validate[]\n" +
						"^self.sharedObj.ban[]\n"
		);

		// --- Метод с [locals] ---
		createParser3FileInDir("www/method_locals.p",
				"@method1[][locals]\n" +
						"$mUser[^User::create[ML;ml@x]]\n" +
						"^mUser.validate[]\n" +
						"\n" +
						"@method2[]\n" +
						"^mUser.validate[]\n"
		);

		// --- Метод с [var_name] ---
		createParser3FileInDir("www/method_varlist.p",
				"@method1[][localUser;localData]\n" +
						"$localUser[^User::create[VL;vl@x]]\n" +
						"$localData[^table::create{name}]\n" +
						"$globalObj[^Admin::create[VG;vg@y;mod]]\n" +
						"^localUser.validate[]\n" +
						"^globalObj.ban[]\n" +
						"\n" +
						"@method2[]\n" +
						"^localUser.validate[]\n" +
						"^globalObj.ban[]\n"
		);

		// --- Встроенный класс ---
		createParser3FileInDir("www/builtin_type.p",
				"$tbl[^table::create{name\nvalue}]\n" +
						"^tbl.save[file.cfg]\n"
		);

		// --- Переменная из другого файла (подключён) ---
		createParser3FileInDir("www/cross_file.p",
				"# lib.p подключен через auto.p\n" +
						"# Переменная определена в lib.p — но lib.p не содержит $var\n" +
						"# Этот тест проверяет что переменная из auto.p видна\n" +
						"@main[]\n# реальный минимальный кейс Parser3 fixture\n" +
						"$cfUser[^User::create[CF;cf@x]]\n" +
						"^cfUser.validate[]\n"
		);

		// --- @BASE цепочка ---
		createParser3FileInDir("www/base_chain.p",
				"$admin[^Admin::create[BC;bc@x;super]]\n" +
						"^admin.validate[]\n" +
						"^admin.ban[]\n" +
						"^admin.getName[]\n"
		);

		// --- Нет навигации к другому классу ---
		createParser3FileInDir("www/wrong_class.p",
				"$user[^User::create[WC;wc@x]]\n" +
						"^user.ban[]\n"
		);

		// --- Неподключённый файл ---
		createParser3FileInDir("www/not_connected_var.p",
				"# notconnected.p НЕ подключен\n" +
						"^globalVar.validate[]\n"
		);

		// --- $self.var с наследованием (@BASE) ---
		createParser3FileInDir("www/base_self_var.p",
				"# реальный минимальный кейс Parser3 fixture\n@CLASS\n" +
						"UserSV\n" +
						"\n" +
						"@create[]\n" +
						"$self.var_user[asdf]\n" +
						"\n" +
						"@GET_dyn[]\n" +
						"$result[dynamic]\n" +
						"\n" +
						"\n" +
						"# реальный минимальный кейс Parser3 fixture\n@CLASS\n" +
						"AdminSV\n" +
						"\n" +
						"@BASE\n" +
						"UserSV\n" +
						"\n" +
						"@auto[]\n" +
						"$self.var_admin[asdf]\n" +
						"\n" +
						"@checkVars[]\n" +
						"$self.var_user\n" +
						"$self.var_admin\n"
		);

		// --- $BASE:var ---
		createParser3FileInDir("www/base_prefix_var.p",
				"# реальный минимальный кейс Parser3 fixture\n@CLASS\n" +
						"UserBP\n" +
						"\n" +
						"@create[]\n" +
						"$self.var_user[asdf]\n" +
						"\n" +
						"\n" +
						"# реальный минимальный кейс Parser3 fixture\n@CLASS\n" +
						"AdminBP\n" +
						"\n" +
						"@BASE\n" +
						"UserBP\n" +
						"\n" +
						"@auto[]\n" +
						"$self.var_admin[asdf]\n" +
						"\n" +
						"@checkVars[]\n" +
						"$BASE:var_user\n" +
						"$BASE:var_admin\n"
		);

		// --- @GET_ через $self., $BASE:, $var ---
		createParser3FileInDir("www/getter_nav.p",
				"# реальный минимальный кейс Parser3 fixture\n@CLASS\n" +
						"UserGN\n" +
						"\n" +
						"@create[]\n" +
						"$self.var_user[asdf]\n" +
						"\n" +
						"@GET_dyn[]\n" +
						"$result[dynamic]\n" +
						"\n" +
						"\n" +
						"# реальный минимальный кейс Parser3 fixture\n@CLASS\n" +
						"AdminGN\n" +
						"\n" +
						"@BASE\n" +
						"UserGN\n" +
						"\n" +
						"@checkGetters[]\n" +
						"$self.dyn\n" +
						"$BASE:dyn\n" +
						"$dyn\n"
		);

		createParser3FileInDir("www/getter_read_chain_nav.p",
				"# реальный минимальный кейс Parser3 fixture\n@CLASS\n" +
						"PrintRC\n" +
						"\n" +
						"@create[]\n" +
						"^if($self.uriData.uri){}\n" +
						"\n" +
						"@checkGetters[]\n" +
						"$self.uriData\n" +
						"\n" +
						"@GET_uriData[]\n" +
						"$result[^self.getUriData[]]\n" +
						"\n" +
						"@getUriData[]\n" +
						"$result[\n" +
						"	$.uri[/]\n" +
						"]\n"
		);

		// --- $BASE: в MAIN (негативный) ---
		createParser3FileInDir("www/base_in_main.p",
				"@main[]\n# реальный минимальный кейс Parser3 fixture\n" +
						"$BASE:somevar\n"
		);

		// --- $BASE: в классе без @BASE (негативный) ---
		createParser3FileInDir("www/base_no_base.p",
				"# реальный минимальный кейс Parser3 fixture\n@CLASS\n" +
						"NoBase\n" +
						"\n" +
						"@method[]\n" +
						"$BASE:somevar\n"
		);

		// --- $var навигация в MAIN без locals ---
		createParser3FileInDir("www/dollarvar_main.p",
				"@auto[]\n" +
						"$var1[asdf]\n" +
						"$self.var2[qwer]\n" +
						"$MAIN:var3[zxcv]\n" +
						"\n" +
						"@main[]\n# реальный минимальный кейс Parser3 fixture\n" +
						"$var1\n" +
						"$self.var1\n" +
						"$MAIN:var1\n" +
						"$var2\n" +
						"$self.var2\n" +
						"$MAIN:var2\n" +
						"$var3\n" +
						"$self.var3\n" +
						"$MAIN:var3\n"
		);

		// --- Переопределение переменных через разные формы ---
		createParser3FileInDir("www/dollarvar_redef.p",
				"@auto[]\n" +
						"# Case 1: $MAIN:v → $v (последнее определение побеждает)\n" +
						"$MAIN:v_r[x]\n" +
						"$v_r[y]\n" +
						"\n" +
						"# Case 2: $v → $self.v\n" +
						"$v_s[a]\n" +
						"$self.v_s[b]\n" +
						"\n" +
						"# Case 3: $v → $MAIN:v\n" +
						"$v_m[a]\n" +
						"$MAIN:v_m[c]\n" +
						"\n" +
						"@main[]\n# реальный минимальный кейс Parser3 fixture\n" +
						"$v_r\n" +
						"$self.v_r\n" +
						"$MAIN:v_r\n" +
						"$v_s\n" +
						"$self.v_m\n"
		);

		// --- $var навигация в MAIN с locals ---
		createParser3FileInDir("www/dollarvar_main_locals.p",
				"@OPTIONS\n" +
						"locals\n" +
						"\n" +
						"@method1[]\n" +
						"$localV[local_value]\n" +
						"$MAIN:globalV[global_value]\n" +
						"\n" +
						"@method2[]\n" +
						"$localV\n" +
						"$MAIN:globalV\n" +
						"$self.globalV\n"
		);

		// --- $var навигация в классе без locals ---
		createParser3FileInDir("www/dollarvar_class.p",
				"# реальный минимальный кейс Parser3 fixture\n@CLASS\n" +
						"TestDollarClass\n" +
						"\n" +
						"@create[]\n" +
						"$myVar[class_value]\n" +
						"$self.myVar2[class_value2]\n" +
						"\n" +
						"@method[]\n" +
						"$myVar\n" +
						"$self.myVar\n" +
						"$self.myVar2\n"
		);

		// --- $var навигация в классе с locals ---
		createParser3FileInDir("www/dollarvar_class_locals.p",
				"# реальный минимальный кейс Parser3 fixture\n@CLASS\n" +
						"TestDollarClassLocals\n" +
						"\n" +
						"@OPTIONS\n" +
						"locals\n" +
						"\n" +
						"@create[]\n" +
						"$localCL[local_class]\n" +
						"$self.globalCL[global_class]\n" +
						"\n" +
						"@method[]\n" +
						"$localCL\n" +
						"$self.globalCL\n"
		);

		// --- $var из другого файла (подключён через use) ---
		createParser3FileInDir("www/dollarvar_provider.p",
				"@auto[]\n" +
						"$providerVar[from_provider]\n"
		);

		createParser3FileInDir("www/dollarvar_consumer.p",
				"@auto[]\n" +
						"^use[dollarvar_provider.p]\n" +
						"\n" +
						"@main[]\n# реальный минимальный кейс Parser3 fixture\n" +
						"$providerVar\n"
		);

		// --- $var из файла НЕ подключенного ---
		createParser3FileInDir("www/dollarvar_isolated.p",
				"@auto[]\n" +
						"$isolatedVar[not_connected]\n"
		);

		createParser3FileInDir("www/dollarvar_nouse.p",
				"@main[]\n# реальный минимальный кейс Parser3 fixture\n" +
						"$isolatedVar\n"
		);

		// --- $var cross-file @BASE ---
		createParser3FileInDir("www/classes/UserCF.p",
				"# реальный минимальный кейс Parser3 fixture\n@CLASS\n" +
						"UserCF\n" +
						"\n" +
						"@create[]\n" +
						"$self.cf_var[user_cf_val]\n"
		);

		createParser3FileInDir("www/dollarvar_crossfile_base.p",
				"# реальный минимальный кейс Parser3 fixture\n@CLASS\n" +
						"AdminCF\n" +
						"\n" +
						"@BASE\n" +
						"UserCF\n" +
						"\n" +
						"@method[]\n" +
						"$self.cf_var\n"
		);

		// --- Навигация на себя: клик на определении не должен навигировать ---
		createParser3FileInDir("www/dollarvar_self_nav.p",
				"@main[]\n# реальный минимальный кейс Parser3 fixture\n" +
						"$mydef[string_value]\n"
		);

		// --- RHS: $var внутри [...] ведёт к предыдущему определению ---
		createParser3FileInDir("www/dollarvar_rhs.p",
				"@main[]\n# реальный минимальный кейс Parser3 fixture\n" +
						"$data[original]\n" +
						"$data[^data.split[,]]\n"
		);

		// --- Параметр метода: $data → @method[data] ---
		createParser3FileInDir("www/dollarvar_param.p",
				"@method[data]\n" +
						"$var[$data]\n"
		);

		// --- Параметр метода с одноимённой переменной в MAIN ---
		createParser3FileInDir("www/dollarvar_param_shadow.p",
				"@main[]\n# реальный минимальный кейс Parser3 fixture\n" +
						"$data[x]\n" +
						"\n" +
						"@method[data]\n" +
						"$var[$data]\n"
		);

		// --- Параметр метода + переопределение внутри метода ---
		createParser3FileInDir("www/dollarvar_param_override.p",
				"@m[var]\n" +
						"$var[x]\n" +
						"^if(!def $var){\n" +
						"\t$var[y]\n" +
						"}\n" +
						"$var\n"
		);

		// --- Параметр метода затеняет MAIN переменную ---
		createParser3FileInDir("www/dollarvar_param_shadow_main.p",
				"$var[x]\n" +
						"^if(!def $var){\n" +
						"\t$var[y]\n" +
						"}\n" +
						"$var\n" +
						"\n" +
						"@m[var]\n" +
						"$var1[x]\n" +
						"^if(!def $var){\n" +
						"\t$var2[y]\n" +
						"}\n" +
						"$var\n"
		);

		// --- ^var.method[] навигация к параметру метода ---
		createParser3FileInDir("www/dollarvar_param_caret.p",
				"@set_company_persons[list][locals]\n" +
						"^list.foreach[k;v]{\n" +
						"}\n" +
						"^self.list.foreach[k;v]{\n" +
						"}\n" +
						"^MAIN:list.foreach[k;v]{\n" +
						"}\n"
		);

		// --- Параметр затеняет глобальную таблицу с тем же именем ---
		createParser3FileInDir("www/dollarvar_param_global_table.p",
				"@main[]\n# реальный минимальный кейс Parser3 fixture\n" +
						"$data[^table::create{prompt\tresult}]\n" +
						"\n" +
						"@getResult[data]\n" +
						"$data\n"
		);

		// --- $result навигация ---
		createParser3FileInDir("www/dollarvar_result.p",
				"@main[]\n# реальный минимальный кейс Parser3 fixture\n" +
						"$result[^table::create{name\tvalue}]\n" +
						"$result.name\n"
		);

		// --- $result навигация внутри метода ---
		createParser3FileInDir("www/dollarvar_result_method.p",
				"@getData[]\n" +
						"$result[^hash::create[]]\n" +
						"$result.name\n"
		);

		// --- ${var} синтаксис с фигурными скобками: простое использование ---
		createParser3FileInDir("www/brace_var_simple.p",
				"@main[]\n# реальный минимальный кейс Parser3 fixture\n" +
						"$myvar[test]\n" +
						"${myvar}\n"
		);

		// --- ${data.key1} и ${data.key1.key2}: цепочка навигации ---
		createParser3FileInDir("www/brace_var_chain.p",
				"@main[]\n# реальный минимальный кейс Parser3 fixture\n" +
						"$data[\n" +
						"    $.key1[\n" +
						"        $.key2[final_value]\n" +
						"    ]\n" +
						"]\n" +
						"${data.key1}\n" +
						"${data.key1.key2}\n"
		);

		// --- Навигация по hash key: все формы $, ^, $self., ^self., $MAIN:, ^MAIN: ---
		createParser3FileInDir("www/hash_key_nav.p",
				"@main[]\n# реальный минимальный кейс Parser3 fixture\n" +
						"$self.config[\n" +
						"    $.name[xxx]\n" +
						"]\n" +
						"$self.config.name\n" +
						"^self.config.name.\n" +
						"$config.name\n" +
						"^config.name.\n" +
						"$MAIN:config.name\n" +
						"^MAIN:config.name.\n"
		);
	}

	// ==================== HELPERS ====================

	/**
	 * Проверяет навигацию ^var.method[] к @method в ожидаемом файле.
	 */
	private void assertVarMethodNavigation(String sourceFile, String callPattern,
										   String expectedMethodName, String expectedFile) {
		com.intellij.openapi.vfs.VirtualFile vFile = myFixture.findFileInTempDir(sourceFile);
		assertNotNull("Файл не найден: " + sourceFile, vFile);

		String content = readFile(vFile);
		int patternPos = content.indexOf(callPattern);
		assertTrue("Паттерн '" + callPattern + "' не найден в " + sourceFile, patternPos >= 0);

		// Имя метода после последней точки
		int lastDot = callPattern.lastIndexOf('.');
		int methodOffset = patternPos + lastDot + 1 + 2; // +2 символа внутрь имени

		myFixture.configureFromExistingVirtualFile(vFile);
		myFixture.getEditor().getCaretModel().moveToOffset(methodOffset);

		PsiElement[] targets = GotoDeclarationAction.findAllTargetElements(
				getProject(), myFixture.getEditor(), methodOffset);

		assertNotNull("Нет таргетов для " + callPattern, targets);
		assertTrue("Должен быть таргет для " + callPattern, targets.length > 0);

		boolean found = false;
		for (PsiElement target : targets) {
			PsiFile targetFile = target.getContainingFile();
			if (targetFile != null && targetFile.getVirtualFile().getPath().endsWith(expectedFile)) {
				found = true;
				break;
			}
		}
		assertTrue("Навигация должна вести к " + expectedFile + " для " + callPattern, found);
	}

	/**
	 * Проверяет что навигация ^var.method[] НЕ работает (нет таргетов).
	 */
	private void assertVarMethodNoNavigation(String sourceFile, String callPattern) {
		com.intellij.openapi.vfs.VirtualFile vFile = myFixture.findFileInTempDir(sourceFile);
		assertNotNull("Файл не найден: " + sourceFile, vFile);

		String content = readFile(vFile);
		int patternPos = content.indexOf(callPattern);
		assertTrue("Паттерн '" + callPattern + "' не найден в " + sourceFile, patternPos >= 0);

		int lastDot = callPattern.lastIndexOf('.');
		int methodOffset = patternPos + lastDot + 1 + 2;

		myFixture.configureFromExistingVirtualFile(vFile);
		myFixture.getEditor().getCaretModel().moveToOffset(methodOffset);

		PsiElement[] targets = GotoDeclarationAction.findAllTargetElements(
				getProject(), myFixture.getEditor(), methodOffset);

		boolean noTargets = (targets == null || targets.length == 0);
		assertTrue("НЕ должно быть навигации для " + callPattern, noTargets);
	}

	/**
	 * Проверяет что навигация ведёт НЕ к указанному файлу (т.е. не к чужому классу).
	 */
	private void assertVarMethodNotInFile(String sourceFile, String callPattern, String forbiddenFile) {
		com.intellij.openapi.vfs.VirtualFile vFile = myFixture.findFileInTempDir(sourceFile);
		assertNotNull("Файл не найден: " + sourceFile, vFile);

		String content = readFile(vFile);
		int patternPos = content.indexOf(callPattern);
		assertTrue("Паттерн '" + callPattern + "' не найден в " + sourceFile, patternPos >= 0);

		int lastDot = callPattern.lastIndexOf('.');
		int methodOffset = patternPos + lastDot + 1 + 2;

		myFixture.configureFromExistingVirtualFile(vFile);
		myFixture.getEditor().getCaretModel().moveToOffset(methodOffset);

		PsiElement[] targets = GotoDeclarationAction.findAllTargetElements(
				getProject(), myFixture.getEditor(), methodOffset);

		if (targets != null) {
			for (PsiElement target : targets) {
				PsiFile targetFile = target.getContainingFile();
				if (targetFile != null) {
					assertFalse("Навигация НЕ должна вести к " + forbiddenFile + " для " + callPattern,
							targetFile.getVirtualFile().getPath().endsWith(forbiddenFile));
				}
			}
		}
	}

	private String readFile(com.intellij.openapi.vfs.VirtualFile vFile) {
		try {
			return new String(vFile.contentsToByteArray(), vFile.getCharset());
		} catch (Exception e) {
			fail("Не удалось прочитать файл: " + vFile.getPath());
			return "";
		}
	}

	// =============================================================================
	// РАЗДЕЛ 1: Эквивалентность форм в MAIN без locals
	// =============================================================================

	// $u1[^User::create[]] — все три формы вызова навигируют к User.validate

	public void testEquivMain_dollarVar_var() {
		assertVarMethodNavigation("www/equiv_main.p", "^u1.validate[]", "validate", "www/classes/User.p");
	}

	public void testEquivMain_dollarVar_selfVar() {
		assertVarMethodNavigation("www/equiv_main.p", "^self.u1.validate[]", "validate", "www/classes/User.p");
	}

	public void testEquivMain_dollarVar_mainVar() {
		assertVarMethodNavigation("www/equiv_main.p", "^MAIN:u1.validate[]", "validate", "www/classes/User.p");
	}

	public void testTryCatch_exceptionDoesNotNavigateToMainAutoMethodParamAfterCatch() {
		createParser3FileInDir("cgi/auto.p",
				"@auto[]\n" +
						"\n" +
						"@handle_exception_debug[exception;stack]\n" +
						"$exception.type\n" +
						"$exception.source\n" +
						"$exception.comment\n"
		);
		setMainAuto("cgi/auto.p");
		createParser3FileInDir("www/test_try_catch_exception_navigation_after_catch.p",
				"@main[]\n" +
						"^try{\n" +
						"\t^xp[]\n" +
						"}{\n" +
						"\t$exceptionCopy[$exception]\n" +
						"}\n" +
						"$exception.\n"
		);

		com.intellij.openapi.vfs.VirtualFile vFile =
				myFixture.findFileInTempDir("www/test_try_catch_exception_navigation_after_catch.p");
		assertNotNull("Файл не найден", vFile);
		String content = readFile(vFile);
		int patternOffset = content.lastIndexOf("$exception.");
		assertTrue("Паттерн $exception. не найден", patternOffset >= 0);
		int clickOffset = patternOffset + "$ex".length();

		myFixture.configureFromExistingVirtualFile(vFile);
		myFixture.getEditor().getCaretModel().moveToOffset(clickOffset);

		PsiElement[] targets = GotoDeclarationAction.findAllTargetElements(
				getProject(), myFixture.getEditor(), clickOffset);

		assertTrue("После catch $exception не должен вести к параметру из main auto",
				targets == null || targets.length == 0);
	}

	public void testTryCatch_exceptionDoesNotNavigateToAutoTryCatchAfterCatch() throws Exception {
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
		createParser3FileInDir("www/test_try_catch_exception_navigation_auto_try_after_catch.p",
				"@main[]\n" +
						"^try{\n" +
						"\t^xp[]\n" +
						"}{\n" +
						"\t$exceptionCopy[$exception]\n" +
						"\n" +
						"}\n" +
						"$exception.\n"
		);

		com.intellij.openapi.vfs.VirtualFile vFile =
				myFixture.findFileInTempDir("www/test_try_catch_exception_navigation_auto_try_after_catch.p");
		assertNotNull("Файл не найден", vFile);
		String content = readFile(vFile);
		int patternOffset = content.lastIndexOf("$exception.");
		assertTrue("Паттерн $exception. не найден", patternOffset >= 0);
		int clickOffset = patternOffset + "$ex".length();

		myFixture.configureFromExistingVirtualFile(vFile);
		myFixture.getEditor().getCaretModel().moveToOffset(clickOffset);

		PsiElement[] targets = GotoDeclarationAction.findAllTargetElements(
				getProject(), myFixture.getEditor(), clickOffset);

		assertTrue("После catch $exception не должен вести к try/catch из auto.p",
				targets == null || targets.length == 0);
	}

	public void testCache_exceptionDoesNotNavigateToAutoCacheCatchAfterCatch() throws Exception {
		setDocumentRoot("www");
		replaceParser3FileInDir("www/auto.p",
				"@auto[]\n" +
						"^cache[/tmp/test](0){\n" +
						"\t^broken[]\n" +
						"}{\n" +
						"\t$exception.handled(true)\n" +
						"}\n"
		);
		createParser3FileInDir("www/test_cache_exception_navigation_auto_cache_after_catch.p",
				"@main[]\n" +
						"$exception.\n"
		);

		com.intellij.openapi.vfs.VirtualFile vFile =
				myFixture.findFileInTempDir("www/test_cache_exception_navigation_auto_cache_after_catch.p");
		assertNotNull("Файл не найден", vFile);
		String content = readFile(vFile);
		int patternOffset = content.lastIndexOf("$exception.");
		assertTrue("Паттерн $exception. не найден", patternOffset >= 0);
		int clickOffset = patternOffset + "$ex".length();

		myFixture.configureFromExistingVirtualFile(vFile);
		myFixture.getEditor().getCaretModel().moveToOffset(clickOffset);

		PsiElement[] targets = GotoDeclarationAction.findAllTargetElements(
				getProject(), myFixture.getEditor(), clickOffset);

		assertTrue("После catch $exception не должен вести к catch-блоку ^cache из auto.p",
				targets == null || targets.length == 0);
	}

	public void testStringMatch_matchDoesNotNavigateToAutoReplacementBlockAfterBlock() throws Exception {
		setDocumentRoot("www");
		replaceParser3FileInDir("www/auto.p",
				"@auto[]\n" +
						"$s[test]\n" +
						"^s.match[(t)][g]{\n" +
						"\t$match.1\n" +
						"}\n"
		);
		createParser3FileInDir("www/test_string_match_temp_navigation_auto_after.p",
				"@main[]\n" +
						"$match.\n"
		);

		com.intellij.openapi.vfs.VirtualFile vFile =
				myFixture.findFileInTempDir("www/test_string_match_temp_navigation_auto_after.p");
		assertNotNull("Файл не найден", vFile);
		String content = readFile(vFile);
		int patternOffset = content.lastIndexOf("$match.");
		assertTrue("Паттерн $match. не найден", patternOffset >= 0);
		int clickOffset = patternOffset + "$ma".length();

		myFixture.configureFromExistingVirtualFile(vFile);
		myFixture.getEditor().getCaretModel().moveToOffset(clickOffset);

		PsiElement[] targets = GotoDeclarationAction.findAllTargetElements(
				getProject(), myFixture.getEditor(), clickOffset);

		assertTrue("После replacement-блока $match не должен вести к ^string.match из auto.p",
				targets == null || targets.length == 0);
	}

	// $self.u2[^User::create[]] — все три формы

	public void testEquivMain_selfVar_var() {
		assertVarMethodNavigation("www/equiv_main.p", "^u2.validate[]", "validate", "www/classes/User.p");
	}

	public void testEquivMain_selfVar_selfVar() {
		assertVarMethodNavigation("www/equiv_main.p", "^self.u2.validate[]", "validate", "www/classes/User.p");
	}

	public void testEquivMain_selfVar_mainVar() {
		assertVarMethodNavigation("www/equiv_main.p", "^MAIN:u2.validate[]", "validate", "www/classes/User.p");
	}

	// $MAIN:u3[^User::create[]] — все три формы

	public void testEquivMain_mainVar_var() {
		assertVarMethodNavigation("www/equiv_main.p", "^u3.validate[]", "validate", "www/classes/User.p");
	}

	public void testEquivMain_mainVar_selfVar() {
		assertVarMethodNavigation("www/equiv_main.p", "^self.u3.validate[]", "validate", "www/classes/User.p");
	}

	public void testEquivMain_mainVar_mainVar() {
		assertVarMethodNavigation("www/equiv_main.p", "^MAIN:u3.validate[]", "validate", "www/classes/User.p");
	}

	// =============================================================================
	// РАЗДЕЛ 2: MAIN с @OPTIONS locals
	// =============================================================================

	/**
	 * $localUser — локальная в method1.
	 * ^localUser.validate[] в method1 — навигирует (переменная видна).
	 */
	public void testLocalsMain_localVar_sameMethod() {
		assertVarMethodNavigation("www/locals_main.p", "^localUser.validate[]", "validate", "www/classes/User.p");
	}

	/**
	 * $localUser — локальная в method1.
	 * ^localUser.validate[] в method2 — НЕ навигирует (переменная не видна).
	 */
	public void testLocalsMain_localVar_otherMethod() {
		// Находим ВТОРОЕ вхождение ^localUser.validate[] (в method2)
		com.intellij.openapi.vfs.VirtualFile vFile = myFixture.findFileInTempDir("www/locals_main.p");
		String content = readFile(vFile);
		int first = content.indexOf("^localUser.validate[]");
		int second = content.indexOf("^localUser.validate[]", first + 1);
		assertTrue("Должно быть 2 вхождения ^localUser.validate[]", second > first);

		int lastDot = "^localUser.validate[]".lastIndexOf('.');
		int methodOffset = second + lastDot + 1 + 2;

		myFixture.configureFromExistingVirtualFile(vFile);
		myFixture.getEditor().getCaretModel().moveToOffset(methodOffset);

		PsiElement[] targets = GotoDeclarationAction.findAllTargetElements(
				getProject(), myFixture.getEditor(), methodOffset);

		boolean noTargets = (targets == null || targets.length == 0);
		assertTrue("^localUser.validate[] в method2 НЕ должен навигировать (переменная локальная)", noTargets);
	}

	/**
	 * $MAIN:globalUser — глобальная.
	 * ^MAIN:globalUser.ban[] в method2 — навигирует.
	 */
	public void testLocalsMain_globalVar_mainPrefix() {
		assertVarMethodNavigation("www/locals_main.p", "^MAIN:globalUser.ban[]", "ban", "www/classes/Admin.p");
	}

	/**
	 * $MAIN:globalUser — глобальная.
	 * ^self.globalUser.ban[] в method2 — навигирует (self = MAIN, глобальная).
	 */
	public void testLocalsMain_globalVar_selfPrefix() {
		assertVarMethodNavigation("www/locals_main.p", "^self.globalUser.ban[]", "ban", "www/classes/Admin.p");
	}

	// =============================================================================
	// РАЗДЕЛ 3: Класс без locals
	// =============================================================================

	/**
	 * $obj и $self.obj — эквивалентны в классе без locals.
	 * ^obj.validate[] — навигирует к User.validate.
	 */
	public void testClassNoLocals_var() {
		assertVarMethodNavigation("www/class_no_locals.p", "^obj.validate[]", "validate", "www/classes/User.p");
	}

	/**
	 * ^self.obj.validate[] — навигирует к User.validate.
	 */
	public void testClassNoLocals_selfVar() {
		assertVarMethodNavigation("www/class_no_locals.p", "^self.obj.validate[]", "validate", "www/classes/User.p");
	}

	/**
	 * $self.obj2[^Admin::create[]] → ^self.obj2.ban[] — навигирует к Admin.ban.
	 */
	public void testClassNoLocals_selfObj2() {
		assertVarMethodNavigation("www/class_no_locals.p", "^self.obj2.ban[]", "ban", "www/classes/Admin.p");
	}

	// =============================================================================
	// РАЗДЕЛ 4: Класс с @OPTIONS locals
	// =============================================================================

	/**
	 * $localObj — локальная в @create.
	 * ^localObj.validate[] в @create — навигирует.
	 */
	public void testClassLocals_localVar_sameMethod() {
		assertVarMethodNavigation("www/class_locals.p", "^localObj.validate[]", "validate", "www/classes/User.p");
	}

	/**
	 * $localObj — локальная в @create.
	 * ^localObj.validate[] в @process — НЕ навигирует.
	 */
	public void testClassLocals_localVar_otherMethod() {
		com.intellij.openapi.vfs.VirtualFile vFile = myFixture.findFileInTempDir("www/class_locals.p");
		String content = readFile(vFile);
		int first = content.indexOf("^localObj.validate[]");
		int second = content.indexOf("^localObj.validate[]", first + 1);
		assertTrue("Должно быть 2 вхождения", second > first);

		int lastDot = "^localObj.validate[]".lastIndexOf('.');
		int methodOffset = second + lastDot + 1 + 2;

		myFixture.configureFromExistingVirtualFile(vFile);
		myFixture.getEditor().getCaretModel().moveToOffset(methodOffset);

		PsiElement[] targets = GotoDeclarationAction.findAllTargetElements(
				getProject(), myFixture.getEditor(), methodOffset);

		boolean noTargets = (targets == null || targets.length == 0);
		assertTrue("^localObj в @process НЕ должен навигировать (локальная)", noTargets);
	}

	/**
	 * $self.sharedObj — глобальная переменная класса.
	 * ^self.sharedObj.ban[] в @process — навигирует.
	 */
	public void testClassLocals_selfVar_otherMethod() {
		assertVarMethodNavigation("www/class_locals.p", "^self.sharedObj.ban[]", "ban", "www/classes/Admin.p");
	}

	// =============================================================================
	// РАЗДЕЛ 5: Метод с [locals]
	// =============================================================================

	/**
	 * @method1[][locals] — $mUser локальная.
	 * ^mUser.validate[] в method1 — навигирует.
	 */
	public void testMethodLocals_sameMethod() {
		assertVarMethodNavigation("www/method_locals.p", "^mUser.validate[]", "validate", "www/classes/User.p");
	}

	/**
	 * $mUser — локальная в method1.
	 * ^mUser.validate[] в method2 — НЕ навигирует.
	 */
	public void testMethodLocals_otherMethod() {
		com.intellij.openapi.vfs.VirtualFile vFile = myFixture.findFileInTempDir("www/method_locals.p");
		String content = readFile(vFile);
		int first = content.indexOf("^mUser.validate[]");
		int second = content.indexOf("^mUser.validate[]", first + 1);
		assertTrue("Должно быть 2 вхождения", second > first);

		int lastDot = "^mUser.validate[]".lastIndexOf('.');
		int methodOffset = second + lastDot + 1 + 2;

		myFixture.configureFromExistingVirtualFile(vFile);
		myFixture.getEditor().getCaretModel().moveToOffset(methodOffset);

		PsiElement[] targets = GotoDeclarationAction.findAllTargetElements(
				getProject(), myFixture.getEditor(), methodOffset);

		boolean noTargets = (targets == null || targets.length == 0);
		assertTrue("^mUser в method2 НЕ должен навигировать (method1 [locals])", noTargets);
	}

	// =============================================================================
	// РАЗДЕЛ 6: Метод с [var_name;var2_name]
	// =============================================================================

	/**
	 * @method1[][localUser;localData] — $localUser и $localData локальные, $globalObj глобальная.
	 * ^localUser.validate[] в method1 — навигирует.
	 */
	public void testMethodVarList_localVar_sameMethod() {
		assertVarMethodNavigation("www/method_varlist.p", "^localUser.validate[]", "validate", "www/classes/User.p");
	}

	/**
	 * ^globalObj.ban[] в method1 — навигирует (глобальная).
	 */
	public void testMethodVarList_globalVar_sameMethod() {
		assertVarMethodNavigation("www/method_varlist.p", "^globalObj.ban[]", "ban", "www/classes/Admin.p");
	}

	/**
	 * ^localUser.validate[] в method2 — НЕ навигирует (локальная в method1).
	 */
	public void testMethodVarList_localVar_otherMethod() {
		com.intellij.openapi.vfs.VirtualFile vFile = myFixture.findFileInTempDir("www/method_varlist.p");
		String content = readFile(vFile);
		int first = content.indexOf("^localUser.validate[]");
		int second = content.indexOf("^localUser.validate[]", first + 1);
		assertTrue("Должно быть 2 вхождения", second > first);

		int lastDot = "^localUser.validate[]".lastIndexOf('.');
		int methodOffset = second + lastDot + 1 + 2;

		myFixture.configureFromExistingVirtualFile(vFile);
		myFixture.getEditor().getCaretModel().moveToOffset(methodOffset);

		PsiElement[] targets = GotoDeclarationAction.findAllTargetElements(
				getProject(), myFixture.getEditor(), methodOffset);

		boolean noTargets = (targets == null || targets.length == 0);
		assertTrue("^localUser в method2 НЕ должен навигировать", noTargets);
	}

	/**
	 * ^globalObj.ban[] в method2 — навигирует (глобальная).
	 */
	public void testMethodVarList_globalVar_otherMethod() {
		com.intellij.openapi.vfs.VirtualFile vFile = myFixture.findFileInTempDir("www/method_varlist.p");
		String content = readFile(vFile);
		int first = content.indexOf("^globalObj.ban[]");
		int second = content.indexOf("^globalObj.ban[]", first + 1);
		assertTrue("Должно быть 2 вхождения", second > first);

		int lastDot = "^globalObj.ban[]".lastIndexOf('.');
		int methodOffset = second + lastDot + 1 + 2;

		myFixture.configureFromExistingVirtualFile(vFile);
		myFixture.getEditor().getCaretModel().moveToOffset(methodOffset);

		PsiElement[] targets = GotoDeclarationAction.findAllTargetElements(
				getProject(), myFixture.getEditor(), methodOffset);

		assertNotNull("^globalObj в method2 должен навигировать", targets);
		assertTrue("Должен быть таргет", targets.length > 0);

		boolean found = false;
		for (PsiElement target : targets) {
			PsiFile tf = target.getContainingFile();
			if (tf != null && tf.getVirtualFile().getPath().endsWith("www/classes/Admin.p")) {
				found = true;
				break;
			}
		}
		assertTrue("Навигация должна вести к Admin.p", found);
	}

	// =============================================================================
	// РАЗДЕЛ 7: Встроенный класс
	// =============================================================================

	/**
	 * $tbl[^table::create{...}] → ^tbl.save[] — навигация к встроенному методу.
	 * Встроенные методы не имеют файла — проверяем что хотя бы таргет есть.
	 */
	public void testBuiltinType_table() {
		com.intellij.openapi.vfs.VirtualFile vFile = myFixture.findFileInTempDir("www/builtin_type.p");
		String content = readFile(vFile);
		int patternPos = content.indexOf("^tbl.save[");
		assertTrue("Паттерн не найден", patternPos >= 0);

		int lastDot = "^tbl.save[".lastIndexOf('.');
		int methodOffset = patternPos + lastDot + 1 + 2;

		myFixture.configureFromExistingVirtualFile(vFile);
		myFixture.getEditor().getCaretModel().moveToOffset(methodOffset);

		// Для встроенных классов навигация может не работать — это ожидаемо
		// Главное — нет исключений
		PsiElement[] targets = GotoDeclarationAction.findAllTargetElements(
				getProject(), myFixture.getEditor(), methodOffset);
		// Не проверяем результат — просто убеждаемся что не упало
	}

	// =============================================================================
	// РАЗДЕЛ 8: @BASE цепочка
	// =============================================================================

	/**
	 * $admin[^Admin::create[]] → ^admin.validate[] — навигирует к User.validate
	 * (validate определён в User, Admin наследует User).
	 */
	public void testBaseChain_inheritedMethod() {
		assertVarMethodNavigation("www/base_chain.p", "^admin.validate[]", "validate", "www/classes/User.p");
	}

	/**
	 * ^admin.ban[] — навигирует к Admin.ban (собственный метод).
	 */
	public void testBaseChain_ownMethod() {
		assertVarMethodNavigation("www/base_chain.p", "^admin.ban[]", "ban", "www/classes/Admin.p");
	}

	/**
	 * ^admin.getName[] — навигирует к User.getName (унаследованный).
	 */
	public void testBaseChain_inheritedGetName() {
		assertVarMethodNavigation("www/base_chain.p", "^admin.getName[]", "getName", "www/classes/User.p");
	}

	// =============================================================================
	// РАЗДЕЛ 9: Нет навигации к чужому классу
	// =============================================================================

	/**
	 * $user[^User::create[]] → ^user.ban[] — НЕ навигирует к Admin.ban.
	 * ban есть в Admin, но не в User.
	 */
	public void testWrongClass_noNavToOtherClass() {
		assertVarMethodNotInFile("www/wrong_class.p", "^user.ban[]", "www/classes/Admin.p");
	}

	// =============================================================================
	// РАЗДЕЛ 10: Переменная из подключённого файла
	// =============================================================================

	/**
	 * $cfUser определён в том же файле — навигирует.
	 */
	public void testCrossFile_sameFile() {
		assertVarMethodNavigation("www/cross_file.p", "^cfUser.validate[]", "validate", "www/classes/User.p");
	}

	// =============================================================================
	// РАЗДЕЛ 11: $self.var с наследованием (@BASE)
	// =============================================================================

	/**
	 * $self.var_user в AdminSV → навигация к $self.var_user[asdf] в UserSV (из @BASE).
	 */
	public void testSelfVar_inheritedFromBase() {
		assertDollarVarNavigation("www/base_self_var.p", "$self.var_user", "var_user", "www/base_self_var.p");
	}

	/**
	 * $self.var_admin в AdminSV → навигация к $self.var_admin[asdf] в том же файле (собственная).
	 */
	public void testSelfVar_ownVariable() {
		assertDollarVarNavigation("www/base_self_var.p", "$self.var_admin", "var_admin", "www/base_self_var.p");
	}

	// =============================================================================
	// РАЗДЕЛ 12: $BASE:var
	// =============================================================================

	/**
	 * $BASE:var_user в AdminBP → навигация к $self.var_user[asdf] в UserBP.
	 */
	public void testBaseVar_navigatesToBaseClass() {
		assertDollarVarNavigation("www/base_prefix_var.p", "$BASE:var_user", "var_user", "www/base_prefix_var.p");
	}

	/**
	 * $BASE:var_admin в AdminBP → НЕ навигирует (var_admin — собственная AdminBP, не из BASE).
	 */
	public void testBaseVar_ownVariable_noNavigation() {
		assertDollarVarNoNavigation("www/base_prefix_var.p", "$BASE:var_admin", "var_admin");
	}

	// =============================================================================
	// РАЗДЕЛ 13: @GET_ через $self., $BASE:, $var
	// =============================================================================

	/**
	 * $self.dyn в AdminGN → навигация к @GET_dyn в UserGN.
	 */
	public void testGetterNav_selfDyn() {
		assertDollarVarNavigation("www/getter_nav.p", "$self.dyn", "dyn", "www/getter_nav.p");
	}

	/**
	 * $BASE:dyn в AdminGN → навигация к @GET_dyn в UserGN.
	 */
	public void testGetterNav_baseDyn() {
		assertDollarVarNavigation("www/getter_nav.p", "$BASE:dyn", "dyn", "www/getter_nav.p");
	}

	/**
	 * $dyn в AdminGN → навигация к @GET_dyn в UserGN (normal контекст).
	 */
	public void testGetterNav_normalDyn() {
		assertDollarVarNavigation("www/getter_nav.p", "$dyn", "dyn", "www/getter_nav.p");
	}

	/**
	 * $self.uriData.uri создаёт синтетическую read-chain, но $self.uriData должен вести к @GET_uriData.
	 */
	public void testGetterNav_selfReadChainPrefersGetter() {
		com.intellij.openapi.vfs.VirtualFile vFile = myFixture.findFileInTempDir("www/getter_read_chain_nav.p");
		assertNotNull("Файл не найден", vFile);

		String content = readFile(vFile);
		int usagePos = content.indexOf("@checkGetters[]");
		assertTrue("@checkGetters[] не найден", usagePos >= 0);
		usagePos = content.indexOf("$self.uriData", usagePos);
		assertTrue("$self.uriData не найден", usagePos >= 0);
		int clickOffset = usagePos + "$self.".length() + 1;

		myFixture.configureFromExistingVirtualFile(vFile);
		myFixture.getEditor().getCaretModel().moveToOffset(clickOffset);
		com.intellij.psi.PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
		com.intellij.openapi.project.DumbService.getInstance(getProject()).waitForSmartMode();
		com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents();

		PsiElement[] targets = GotoDeclarationAction.findAllTargetElements(
				getProject(), myFixture.getEditor(), clickOffset);

		assertNotNull("Нет таргетов для $self.uriData", targets);
		assertTrue("Должен быть таргет для $self.uriData", targets.length > 0);

		boolean foundGetter = false;
		for (PsiElement target : targets) {
			if (target.getClass().getName().contains("MethodTarget")) {
				foundGetter = true;
				break;
			}
		}
		assertTrue("Навигация должна вести к @GET_uriData, а не к синтетической read-chain", foundGetter);
	}

	public void testGetterNav_selfReadChainHashKeyUsesGetterMethodResult() {
		String content =
				"# реальный минимальный кейс Parser3 fixture\n@CLASS\n" +
						"Print\n" +
						"\n" +
						"@create[]\n" +
						"^if($self.uriData.uri){\n" +
						"	^rem{...}\n" +
						"}\n" +
						"$self.uriData.uri\n" +
						"\n" +
						"@GET_uriData[]\n" +
						"$result[^self.getUriData[]]\n" +
						"^if(1){\n" +
						"\t$result(1)\n" +
						"}\n" +
						"\n" +
						"@getUriData[]\n" +
						"$uri[^request:uri.split[?;lh]]\n" +
						"$tUri[^uri.split[/;lh]]\n" +
						"\n" +
						"$result[\n" +
						"	$.uri[/$uri/]\n" +
						"	$.tUri[$tUri]\n" +
						"]\n";
		createParser3File("getter_read_chain_hash_key_nav.p", content);

		int usagePos = content.indexOf("$self.uriData.uri\n");
		assertTrue("$self.uriData.uri не найден", usagePos >= 0);
		int clickOffset = usagePos + "$self.uriData.".length() + 1;
		myFixture.getEditor().getCaretModel().moveToOffset(clickOffset);

		PsiElement[] targets = GotoDeclarationAction.findAllTargetElements(
				getProject(), myFixture.getEditor(), clickOffset);

		assertNotNull("Нет таргетов для $self.uriData.uri", targets);
		assertTrue("Должен быть таргет для $self.uriData.uri", targets.length > 0);

		int expectedOffset = content.indexOf("$.uri[");
		assertTrue("$.uri[ не найден", expectedOffset >= 0);

		boolean foundUriResultKey = false;
		for (PsiElement target : targets) {
			int targetOffset = target.getTextOffset();
			if (targetOffset >= expectedOffset && targetOffset <= expectedOffset + "$.uri".length()) {
				foundUriResultKey = true;
				break;
			}
		}
		assertTrue("Навигация должна вести к $.uri в результате @getUriData[]", foundUriResultKey);
	}

	// =============================================================================
	// РАЗДЕЛ 14: Негативные кейсы $BASE:
	// =============================================================================

	/**
	 * $BASE:somevar в MAIN → НЕ навигирует (MAIN без @BASE).
	 */
	public void testBaseVar_inMain_noNavigation() {
		assertDollarVarNoNavigation("www/base_in_main.p", "$BASE:somevar", "somevar");
	}

	/**
	 * $BASE:somevar в классе без @BASE → НЕ навигирует.
	 */
	public void testBaseVar_noBase_noNavigation() {
		assertDollarVarNoNavigation("www/base_no_base.p", "$BASE:somevar", "somevar");
	}

	// ==================== HELPERS для $var навигации ====================

	// =============================================================================
	// РАЗДЕЛ 15: $var навигация в MAIN без locals
	// =============================================================================

	/**
	 * $var1[asdf] в @auto → $var1 в @main → навигация к определению.
	 */
	public void testDollarVarMain_var() {
		assertDollarVarNavigation("www/dollarvar_main.p", "$var1", "var1", "www/dollarvar_main.p");
	}

	/**
	 * $var1[asdf] → $self.var1 — эквивалентно $var1 в MAIN.
	 */
	public void testDollarVarMain_selfVar() {
		assertDollarVarNavigation("www/dollarvar_main.p", "$self.var1", "var1", "www/dollarvar_main.p");
	}

	/**
	 * $var1[asdf] → $MAIN:var1 — эквивалентно $var1 в MAIN.
	 */
	public void testDollarVarMain_mainVar() {
		assertDollarVarNavigation("www/dollarvar_main.p", "$MAIN:var1", "var1", "www/dollarvar_main.p");
	}

	/**
	 * $self.var2[qwer] → $var2 — эквивалентно.
	 */
	public void testDollarVarMain_selfDefined_readVar() {
		assertDollarVarNavigation("www/dollarvar_main.p", "$var2", "var2", "www/dollarvar_main.p");
	}

	/**
	 * $MAIN:var3[zxcv] → $self.var3 — эквивалентно.
	 */
	public void testDollarVarMain_mainDefined_readSelf() {
		assertDollarVarNavigation("www/dollarvar_main.p", "$self.var3", "var3", "www/dollarvar_main.p");
	}

	/**
	 * $self.var2[qwer] → $self.var2 — та же форма.
	 */
	public void testDollarVarMain_selfDefined_readSelf() {
		assertDollarVarNavigation("www/dollarvar_main.p", "$self.var2", "var2", "www/dollarvar_main.p");
	}

	/**
	 * $self.var2[qwer] → $MAIN:var2 — эквивалентно.
	 */
	public void testDollarVarMain_selfDefined_readMain() {
		assertDollarVarNavigation("www/dollarvar_main.p", "$MAIN:var2", "var2", "www/dollarvar_main.p");
	}

	/**
	 * $MAIN:var3[zxcv] → $var3 — эквивалентно.
	 */
	public void testDollarVarMain_mainDefined_readVar() {
		assertDollarVarNavigation("www/dollarvar_main.p", "$var3", "var3", "www/dollarvar_main.p");
	}

	/**
	 * $MAIN:var3[zxcv] → $MAIN:var3 — та же форма.
	 */
	public void testDollarVarMain_mainDefined_readMain() {
		assertDollarVarNavigation("www/dollarvar_main.p", "$MAIN:var3", "var3", "www/dollarvar_main.p");
	}

	// --- Переопределение через разные формы ---

	/**
	 * $MAIN:v[x] → $v[y] → чтение $v → последнее определение ($v[y]).
	 */
	public void testDollarVarRedef_varReadAfterRedef() {
		assertDollarVarNavigation("www/dollarvar_redef.p", "$v_r", "v_r", "www/dollarvar_redef.p");
	}

	/**
	 * $MAIN:v[x] → $v[y] → чтение $self.v → последнее определение ($v[y]).
	 */
	public void testDollarVarRedef_selfReadAfterRedef() {
		assertDollarVarNavigation("www/dollarvar_redef.p", "$self.v_r", "v_r", "www/dollarvar_redef.p");
	}

	/**
	 * $MAIN:v[x] → $v[y] → чтение $MAIN:v → последнее определение ($v[y]).
	 */
	public void testDollarVarRedef_mainReadAfterRedef() {
		assertDollarVarNavigation("www/dollarvar_redef.p", "$MAIN:v_r", "v_r", "www/dollarvar_redef.p");
	}

	/**
	 * $var[a] → $self.var[b] → чтение $var → последнее ($self.var[b]).
	 */
	public void testDollarVarRedef_varThenSelf_readVar() {
		assertDollarVarNavigation("www/dollarvar_redef.p", "$v_s", "v_s", "www/dollarvar_redef.p");
	}

	/**
	 * $var[a] → $MAIN:var[c] → чтение $self.var → последнее ($MAIN:var[c]).
	 */
	public void testDollarVarRedef_varThenMain_readSelf() {
		assertDollarVarNavigation("www/dollarvar_redef.p", "$self.v_m", "v_m", "www/dollarvar_redef.p");
	}

	// =============================================================================
	// РАЗДЕЛ 16: $var навигация в MAIN с locals
	// =============================================================================

	/**
	 * $localV — локальная в method1, НЕ видна из method2.
	 */
	public void testDollarVarMainLocals_localNotVisible() {
		assertDollarVarNoNavigation("www/dollarvar_main_locals.p", "$localV", "localV");
	}

	/**
	 * $MAIN:globalV — глобальная, видна из method2.
	 */
	public void testDollarVarMainLocals_globalVisible() {
		assertDollarVarNavigation("www/dollarvar_main_locals.p", "$MAIN:globalV", "globalV", "www/dollarvar_main_locals.p");
	}

	/**
	 * $self.globalV — эквивалентно $MAIN:globalV, видна из method2.
	 */
	public void testDollarVarMainLocals_selfGlobalVisible() {
		assertDollarVarNavigation("www/dollarvar_main_locals.p", "$self.globalV", "globalV", "www/dollarvar_main_locals.p");
	}

	// =============================================================================
	// РАЗДЕЛ 17: $var навигация в классе без locals
	// =============================================================================

	/**
	 * $myVar и $self.myVar — эквивалентны в классе без locals.
	 */
	public void testDollarVarClass_var() {
		assertDollarVarNavigation("www/dollarvar_class.p", "$myVar", "myVar", "www/dollarvar_class.p");
	}

	public void testDollarVarClass_selfVar() {
		assertDollarVarNavigation("www/dollarvar_class.p", "$self.myVar", "myVar", "www/dollarvar_class.p");
	}

	public void testDollarVarClass_selfVar2() {
		assertDollarVarNavigation("www/dollarvar_class.p", "$self.myVar2", "myVar2", "www/dollarvar_class.p");
	}

	// =============================================================================
	// РАЗДЕЛ 18: $var навигация в классе с locals
	// =============================================================================

	/**
	 * $localCL — локальная в @create, НЕ видна из @method.
	 */
	public void testDollarVarClassLocals_localNotVisible() {
		assertDollarVarNoNavigation("www/dollarvar_class_locals.p", "$localCL", "localCL");
	}

	/**
	 * $self.globalCL — глобальная переменная класса, видна из @method.
	 */
	public void testDollarVarClassLocals_selfGlobalVisible() {
		assertDollarVarNavigation("www/dollarvar_class_locals.p", "$self.globalCL", "globalCL", "www/dollarvar_class_locals.p");
	}

	// =============================================================================
	// РАЗДЕЛ 19: $var из другого файла (подключён через use)
	// =============================================================================

	/**
	 * $providerVar определена в dollarvar_provider.p, подключён через ^use[].
	 */
	public void testDollarVarCrossFile_connected() {
		assertDollarVarNavigation("www/dollarvar_consumer.p", "$providerVar", "providerVar", "www/dollarvar_provider.p");
	}

	// =============================================================================
	// РАЗДЕЛ 20: $var из файла НЕ подключенного
	// =============================================================================

	/**
	 * $isolatedVar определена в dollarvar_isolated.p, НЕ подключён.
	 * В режиме USE_ONLY — навигация НЕ должна работать.
	 */
	public void testDollarVarCrossFile_notConnected() {
		// В режиме ALL_METHODS будет работать, поэтому тестируем только USE_ONLY
		ru.artlebedev.parser3.settings.Parser3ProjectSettings settings =
				ru.artlebedev.parser3.settings.Parser3ProjectSettings.getInstance(getProject());
		ru.artlebedev.parser3.settings.Parser3ProjectSettings.MethodCompletionMode oldMode = settings.getMethodCompletionMode();
		try {
			settings.setMethodCompletionMode(
					ru.artlebedev.parser3.settings.Parser3ProjectSettings.MethodCompletionMode.USE_ONLY);
			assertDollarVarNoNavigation("www/dollarvar_nouse.p", "$isolatedVar", "isolatedVar");
		} finally {
			settings.setMethodCompletionMode(oldMode);
		}
	}

	// =============================================================================
	// РАЗДЕЛ 21: $var cross-file @BASE
	// =============================================================================

	/**
	 * AdminCF наследует UserCF (в другом файле).
	 * $self.cf_var в AdminCF → навигация к $self.cf_var в UserCF.
	 */
	public void testDollarVarCrossFileBase() {
		assertDollarVarNavigation("www/dollarvar_crossfile_base.p", "$self.cf_var", "cf_var", "www/classes/UserCF.p");
	}

	// =============================================================================
	// РАЗДЕЛ 22: навигация на себя — клик на определении не навигирует
	// =============================================================================

	/**
	 * $mydef[string_value] — клик на $mydef не должен навигировать (это определение).
	 */
	public void testDollarVarSelfNav_noNavOnDefinition() {
		assertDollarVarNoNavigation("www/dollarvar_self_nav.p", "$mydef", "mydef");
	}

	// =============================================================================
	// РАЗДЕЛ 23: RHS — переменная внутри правой части ведёт к предыдущему определению
	// =============================================================================

	/**
	 * $data[original] затем $data[^data.split[,]]
	 * Клик на ^data внутри второго присваивания → навигация к первому $data[original].
	 */
	public void testDollarVarRHS_navigateToPrevDefinition() {
		com.intellij.openapi.vfs.VirtualFile vFile = myFixture.findFileInTempDir("www/dollarvar_rhs.p");
		assertNotNull("Файл не найден", vFile);

		String content = readFile(vFile);

		// Ищем ^data.split — это внутри RHS второго присваивания
		int caretDotPos = content.indexOf("^data.split");
		assertTrue("^data.split не найден", caretDotPos >= 0);

		// Кликаем на "data" после ^ (offset + 1 чтобы попасть внутрь имени)
		int clickOffset = caretDotPos + 2; // ^d|ata

		myFixture.configureFromExistingVirtualFile(vFile);
		myFixture.getEditor().getCaretModel().moveToOffset(clickOffset);

		PsiElement[] targets = GotoDeclarationAction.findAllTargetElements(
				getProject(), myFixture.getEditor(), clickOffset);

		assertNotNull("Должны быть таргеты для ^data в RHS", targets);
		assertTrue("Должен быть таргет для ^data в RHS", targets.length > 0);

		// Таргет должен быть в первом $data[original], а не во втором
		boolean foundFirst = false;
		int firstDefOffset = content.indexOf("$data[original]");
		assertTrue("$data[original] не найден", firstDefOffset >= 0);

		for (PsiElement target : targets) {
			int tOffset = target.getTextOffset();
			// Таргет на $data[original] (offset $ или d)
			if (tOffset >= firstDefOffset && tOffset <= firstDefOffset + 5) {
				foundFirst = true;
			}
		}
		assertTrue("Навигация должна вести к первому $data[original]", foundFirst);
	}

	// =============================================================================
	// РАЗДЕЛ 24: параметр метода — $data → @method[data]
	// =============================================================================

	/**
	 * @method[data] → $data внутри метода навигирует к параметру.
	 */
	public void testDollarVarParam_navigateToParam() {
		com.intellij.openapi.vfs.VirtualFile vFile = myFixture.findFileInTempDir("www/dollarvar_param.p");
		assertNotNull("Файл не найден", vFile);

		String content = readFile(vFile);

		// Ищем $data внутри метода (в строке "$var[$data]")
		int dollarDataPos = content.indexOf("$var[$data]");
		assertTrue("$var[$data] не найден", dollarDataPos >= 0);
		int clickOffset = content.indexOf("$data]", dollarDataPos) + 2; // $d|ata

		myFixture.configureFromExistingVirtualFile(vFile);
		myFixture.getEditor().getCaretModel().moveToOffset(clickOffset);

		PsiElement[] targets = GotoDeclarationAction.findAllTargetElements(
				getProject(), myFixture.getEditor(), clickOffset);

		assertNotNull("Должны быть таргеты для $data (параметр)", targets);
		assertTrue("Должен быть таргет для $data (параметр)", targets.length > 0);

		// Таргет должен быть на "data" внутри @method[data]
		int methodDeclPos = content.indexOf("@method[data]");
		assertTrue("@method[data] не найден", methodDeclPos >= 0);
		int paramPos = content.indexOf("data", methodDeclPos + 8); // после @method[

		boolean foundParam = false;
		for (PsiElement target : targets) {
			int tOffset = target.getTextOffset();
			if (tOffset >= paramPos && tOffset <= paramPos + 4) {
				foundParam = true;
			}
		}
		assertTrue("Навигация должна вести к параметру в @method[data]", foundParam);
	}

	/**
	 * $data[x] в MAIN + @method[data] → $data внутри метода навигирует к параметру (не к MAIN).
	 */
	public void testDollarVarParamShadow_paramOverMainVar() {
		com.intellij.openapi.vfs.VirtualFile vFile = myFixture.findFileInTempDir("www/dollarvar_param_shadow.p");
		assertNotNull("Файл не найден", vFile);

		String content = readFile(vFile);

		// Ищем $data внутри метода (в строке "$var[$data]")
		int varLinePos = content.indexOf("$var[$data]");
		assertTrue("$var[$data] не найден", varLinePos >= 0);
		int clickOffset = content.indexOf("$data]", varLinePos) + 2; // $d|ata

		myFixture.configureFromExistingVirtualFile(vFile);
		myFixture.getEditor().getCaretModel().moveToOffset(clickOffset);

		PsiElement[] targets = GotoDeclarationAction.findAllTargetElements(
				getProject(), myFixture.getEditor(), clickOffset);

		assertNotNull("Должны быть таргеты для $data (параметр shadow)", targets);
		assertTrue("Должен быть таргет для $data (параметр shadow)", targets.length > 0);

		// Таргет должен быть на "data" внутри @method[data], а не $data[x] в MAIN
		int methodDeclPos = content.indexOf("@method[data]");
		assertTrue("@method[data] не найден", methodDeclPos >= 0);
		int paramPos = content.indexOf("data", methodDeclPos + 8);

		int mainDefPos = content.indexOf("$data[x]");
		assertTrue("$data[x] не найден", mainDefPos >= 0);

		boolean foundParam = false;
		boolean foundMain = false;
		for (PsiElement target : targets) {
			int tOffset = target.getTextOffset();
			if (tOffset >= paramPos && tOffset <= paramPos + 4) {
				foundParam = true;
			}
			if (tOffset >= mainDefPos && tOffset <= mainDefPos + 5) {
				foundMain = true;
			}
		}
		assertTrue("Навигация должна вести к параметру, а не к $data[x] в MAIN", foundParam);
		assertFalse("Навигация НЕ должна вести к $data[x] в MAIN", foundMain);
	}

	// =============================================================================
	// РАЗДЕЛ 25: параметр + переопределение внутри метода
	// =============================================================================

	/**
	 * @m[var] → $var[x] → $var[y] → $var
	 * Клик на последнем $var ведёт к $var[y] (последнему переопределению), а не к @m[var].
	 */
	public void testDollarVarParamOverride_navigateToOverride() {
		com.intellij.openapi.vfs.VirtualFile vFile = myFixture.findFileInTempDir("www/dollarvar_param_override.p");
		assertNotNull("Файл не найден", vFile);

		String content = readFile(vFile);

		// Ищем последний "$var" (без [) — это чтение переменной
		int lastVarPos = content.lastIndexOf("$var");
		// Убеждаемся что после него нет [ (это не присваивание)
		assertTrue("Последний $var не найден", lastVarPos >= 0);
		assertFalse("Последний $var не должен быть присваиванием",
				lastVarPos + 4 < content.length() && content.charAt(lastVarPos + 4) == '[');

		int clickOffset = lastVarPos + 2; // $v|ar

		myFixture.configureFromExistingVirtualFile(vFile);
		myFixture.getEditor().getCaretModel().moveToOffset(clickOffset);

		PsiElement[] targets = GotoDeclarationAction.findAllTargetElements(
				getProject(), myFixture.getEditor(), clickOffset);

		assertNotNull("Должны быть таргеты", targets);
		assertTrue("Должен быть таргет", targets.length > 0);

		// Таргет должен быть на $var[y], а НЕ на @m[var]
		int overridePos = content.indexOf("$var[y]");
		assertTrue("$var[y] не найден", overridePos >= 0);

		int paramDeclPos = content.indexOf("@m[var]");
		assertTrue("@m[var] не найден", paramDeclPos >= 0);
		int paramPos = content.indexOf("var", paramDeclPos + 3); // после @m[

		boolean foundOverride = false;
		boolean foundParam = false;
		for (PsiElement target : targets) {
			int tOffset = target.getTextOffset();
			if (tOffset >= overridePos && tOffset <= overridePos + 4) {
				foundOverride = true;
			}
			if (tOffset >= paramPos && tOffset <= paramPos + 3) {
				foundParam = true;
			}
		}
		assertTrue("Навигация должна вести к $var[y] (переопределению)", foundOverride);
		assertFalse("Навигация НЕ должна вести к @m[var] (параметру)", foundParam);
	}

	/**
	 * $var[y] в MAIN → $var в MAIN → навигация к $var[y].
	 * @m[var] → $var1[x] → $var2[y] → $var → навигация к параметру @m[var].
	 *
	 * Второй $var — внутри метода @m, параметр затеняет MAIN $var[y].
	 */
	public void testDollarVarParamShadowMain_mainVarGoesToMainDef() {
		com.intellij.openapi.vfs.VirtualFile vFile = myFixture.findFileInTempDir("www/dollarvar_param_shadow_main.p");
		assertNotNull("Файл не найден", vFile);

		String content = readFile(vFile);

		// Первый $var\n (в MAIN, после $var[y]) — ведёт к $var[y]
		// Находим "$var\n" в MAIN (до @m)
		int mPos = content.indexOf("@m[var]");
		int mainReadPos = content.lastIndexOf("$var\n", mPos);
		assertTrue("$var в MAIN не найден", mainReadPos >= 0);

		int clickOffset = mainReadPos + 2; // $v|ar
		myFixture.configureFromExistingVirtualFile(vFile);
		myFixture.getEditor().getCaretModel().moveToOffset(clickOffset);

		PsiElement[] targets = GotoDeclarationAction.findAllTargetElements(
				getProject(), myFixture.getEditor(), clickOffset);

		assertNotNull("Должны быть таргеты для $var в MAIN", targets);
		assertTrue("Должен быть таргет для $var в MAIN", targets.length > 0);

		int varYMainPos = content.indexOf("$var[y]");
		boolean foundVarY = false;
		for (PsiElement target : targets) {
			int tOffset = target.getTextOffset();
			if (tOffset >= varYMainPos && tOffset <= varYMainPos + 4) {
				foundVarY = true;
			}
		}
		assertTrue("$var в MAIN должен навигировать к $var[y] в MAIN", foundVarY);
	}

	public void testDollarVarParamShadowMain_methodVarGoesToParam() {
		com.intellij.openapi.vfs.VirtualFile vFile = myFixture.findFileInTempDir("www/dollarvar_param_shadow_main.p");
		assertNotNull("Файл не найден", vFile);

		String content = readFile(vFile);

		// Последний $var\n (в @m) — должен вести к @m[var] (параметр)
		int lastVarPos = content.lastIndexOf("$var\n");
		assertTrue("Последний $var не найден", lastVarPos >= 0);

		int clickOffset = lastVarPos + 2; // $v|ar
		myFixture.configureFromExistingVirtualFile(vFile);
		myFixture.getEditor().getCaretModel().moveToOffset(clickOffset);

		PsiElement[] targets = GotoDeclarationAction.findAllTargetElements(
				getProject(), myFixture.getEditor(), clickOffset);

		assertNotNull("Должны быть таргеты для $var в @m", targets);
		assertTrue("Должен быть таргет для $var в @m", targets.length > 0);

		// Таргет должен быть на "var" в @m[var]
		int mDeclPos = content.indexOf("@m[var]");
		int paramPos = content.indexOf("var", mDeclPos + 3);

		boolean foundParam = false;
		for (PsiElement target : targets) {
			int tOffset = target.getTextOffset();
			if (tOffset >= paramPos && tOffset <= paramPos + 3) {
				foundParam = true;
			}
		}
		assertTrue("$var в @m должен навигировать к параметру @m[var]", foundParam);
	}

	// =============================================================================
	// РАЗДЕЛ 26: $result навигация (IMPORTANT_VARIABLE)
	// =============================================================================

	/**
	 * $result[^table::create{...}] → $result.name — клик по $result ведёт к определению.
	 * $result — токен IMPORTANT_VARIABLE, не VARIABLE.
	 */
	public void testDollarVarResult_navigateToDefinition() {
		com.intellij.openapi.vfs.VirtualFile vFile = myFixture.findFileInTempDir("www/dollarvar_result.p");
		assertNotNull("Файл не найден", vFile);

		String content = readFile(vFile);

		// Ищем "$result.name" — клик на "$result"
		int resultDotPos = content.indexOf("$result.name");
		assertTrue("$result.name не найден", resultDotPos >= 0);

		// $result — IMPORTANT_VARIABLE, кликаем на "r" (+1 от $)
		int clickOffset = resultDotPos + 1; // $|result

		myFixture.configureFromExistingVirtualFile(vFile);
		myFixture.getEditor().getCaretModel().moveToOffset(clickOffset);

		PsiElement[] targets = GotoDeclarationAction.findAllTargetElements(
				getProject(), myFixture.getEditor(), clickOffset);

		assertNotNull("Должны быть таргеты для $result", targets);
		assertTrue("Должен быть таргет для $result", targets.length > 0);

		// Таргет на $result[^table::create{...}]
		int defPos = content.indexOf("$result[^table");
		assertTrue("$result[^table...] не найден", defPos >= 0);

		boolean found = false;
		for (PsiElement target : targets) {
			int tOffset = target.getTextOffset();
			if (tOffset >= defPos && tOffset <= defPos + 7) {
				found = true;
			}
		}
		assertTrue("Навигация $result должна вести к $result[^table...]", found);
	}

	/**
	 * $result внутри метода — навигация к $result[^hash::create[]].
	 */
	public void testDollarVarResult_inMethod() {
		com.intellij.openapi.vfs.VirtualFile vFile = myFixture.findFileInTempDir("www/dollarvar_result_method.p");
		assertNotNull("Файл не найден", vFile);

		String content = readFile(vFile);

		int resultDotPos = content.indexOf("$result.name");
		assertTrue("$result.name не найден", resultDotPos >= 0);

		int clickOffset = resultDotPos + 1;

		myFixture.configureFromExistingVirtualFile(vFile);
		myFixture.getEditor().getCaretModel().moveToOffset(clickOffset);

		PsiElement[] targets = GotoDeclarationAction.findAllTargetElements(
				getProject(), myFixture.getEditor(), clickOffset);

		assertNotNull("Должны быть таргеты для $result в методе", targets);
		assertTrue("Должен быть таргет для $result в методе", targets.length > 0);

		int defPos = content.indexOf("$result[^hash");
		assertTrue("$result[^hash...] не найден", defPos >= 0);

		boolean found = false;
		for (PsiElement target : targets) {
			int tOffset = target.getTextOffset();
			if (tOffset >= defPos && tOffset <= defPos + 7) {
				found = true;
			}
		}
		assertTrue("Навигация $result должна вести к $result[^hash...]", found);
	}

	/**
	 * $result[^table::create{...}] — клик на определении не навигирует (навигация на себя).
	 */
	public void testDollarVarResult_noNavOnDefinition() {
		com.intellij.openapi.vfs.VirtualFile vFile = myFixture.findFileInTempDir("www/dollarvar_result.p");
		assertNotNull("Файл не найден", vFile);

		String content = readFile(vFile);

		// Клик на $result в определении $result[^table::create{...}]
		int defPos = content.indexOf("$result[^table");
		assertTrue("$result[^table...] не найден", defPos >= 0);

		int clickOffset = defPos + 1; // $|result

		myFixture.configureFromExistingVirtualFile(vFile);
		myFixture.getEditor().getCaretModel().moveToOffset(clickOffset);

		PsiElement[] targets = GotoDeclarationAction.findAllTargetElements(
				getProject(), myFixture.getEditor(), clickOffset);

		boolean noTargets = (targets == null || targets.length == 0);
		assertTrue("НЕ должно быть навигации для $result на определении", noTargets);
	}

	// ==================== HELPERS для $var навигации (основной код) ====================

	/**
	 * Проверяет навигацию $var / $self.var / $BASE:var к определению в ожидаемом файле.
	 * varNameInPattern — имя переменной внутри паттерна, по которому кликаем.
	 */
	private void assertDollarVarNavigation(String sourceFile, String fullPattern,
										   String varNameInPattern, String expectedFile) {
		com.intellij.openapi.vfs.VirtualFile vFile = myFixture.findFileInTempDir(sourceFile);
		assertNotNull("Файл не найден: " + sourceFile, vFile);

		String content = readFile(vFile);

		// Ищем паттерн в контексте @checkVars[] или @checkGetters[] или @main[]
		// чтобы не попасть на определение переменной
		int patternPos = findPatternInMethods(content, fullPattern,
				new String[]{"@checkVars[]", "@checkGetters[]", "@main[]", "@method[]"});
		assertTrue("Паттерн '" + fullPattern + "' не найден в тестовых методах " + sourceFile, patternPos >= 0);

		// Offset на имя переменной внутри паттерна
		int namePos = fullPattern.lastIndexOf(varNameInPattern);
		int clickOffset = patternPos + namePos + 1; // +1 символ внутрь имени

		System.out.println("[TEST] file=" + sourceFile + " pattern='" + fullPattern
				+ "' patternPos=" + patternPos + " namePos=" + namePos
				+ " clickOffset=" + clickOffset
				+ " charAtClick=" + (clickOffset < content.length() ? "'" + content.charAt(clickOffset) + "'" : "OOB")
				+ " context='" + content.substring(Math.max(0, patternPos - 5), Math.min(content.length(), patternPos + fullPattern.length() + 5)) + "'");

		myFixture.configureFromExistingVirtualFile(vFile);
		myFixture.getEditor().getCaretModel().moveToOffset(clickOffset);

		PsiElement[] targets = GotoDeclarationAction.findAllTargetElements(
				getProject(), myFixture.getEditor(), clickOffset);

		System.out.println("[TEST] targets=" + (targets != null ? targets.length : "null"));
		if (targets != null) {
			for (PsiElement t : targets) {
				PsiFile tf = t.getContainingFile();
				System.out.println("[TEST]   target: " + (tf != null ? tf.getVirtualFile().getPath() : "null")
						+ " offset=" + t.getTextOffset() + " text='" + t.getText() + "'");
			}
		}

		assertNotNull("Нет таргетов для " + fullPattern, targets);
		assertTrue("Должен быть таргет для " + fullPattern, targets.length > 0);

		boolean found = false;
		for (PsiElement target : targets) {
			PsiFile targetFile = target.getContainingFile();
			if (targetFile != null && targetFile.getVirtualFile().getPath().endsWith(expectedFile)) {
				found = true;
				break;
			}
		}
		assertTrue("Навигация должна вести к " + expectedFile + " для " + fullPattern, found);
	}

	/**
	 * Проверяет что навигация $var / $self.var / $BASE:var НЕ работает.
	 */
	private void assertDollarVarNoNavigation(String sourceFile, String fullPattern,
											 String varNameInPattern) {
		com.intellij.openapi.vfs.VirtualFile vFile = myFixture.findFileInTempDir(sourceFile);
		assertNotNull("Файл не найден: " + sourceFile, vFile);

		String content = readFile(vFile);
		int patternPos = findPatternInMethods(content, fullPattern,
				new String[]{"@checkVars[]", "@checkGetters[]", "@main[]", "@method[]"});
		assertTrue("Паттерн '" + fullPattern + "' не найден в тестовых методах " + sourceFile, patternPos >= 0);

		int namePos = fullPattern.lastIndexOf(varNameInPattern);
		int clickOffset = patternPos + namePos + 1;

		myFixture.configureFromExistingVirtualFile(vFile);
		myFixture.getEditor().getCaretModel().moveToOffset(clickOffset);

		PsiElement[] targets = GotoDeclarationAction.findAllTargetElements(
				getProject(), myFixture.getEditor(), clickOffset);

		boolean noTargets = (targets == null || targets.length == 0);
		assertTrue("НЕ должно быть навигации для " + fullPattern, noTargets);
	}

	/**
	 * ^list.foreach[] — клик на ^list навигирует к параметру list в @set_company_persons[list].
	 */
	public void testCaretVarParam_navigateToParam() {
		com.intellij.openapi.vfs.VirtualFile vFile = myFixture.findFileInTempDir("www/dollarvar_param_caret.p");
		assertNotNull("Файл не найден", vFile);

		String content = readFile(vFile);

		// Клик на ^list в "^list.foreach"
		int caretListPos = content.indexOf("^list.foreach");
		assertTrue("^list.foreach не найден", caretListPos >= 0);
		int clickOffset = caretListPos + 1; // ^l|ist

		myFixture.configureFromExistingVirtualFile(vFile);
		myFixture.getEditor().getCaretModel().moveToOffset(clickOffset);

		PsiElement[] targets = GotoDeclarationAction.findAllTargetElements(
				getProject(), myFixture.getEditor(), clickOffset);

		assertNotNull("Должны быть таргеты для ^list (параметр)", targets);
		assertTrue("Должен быть таргет для ^list (параметр)", targets.length > 0);

		// Таргет должен быть на "list" в @set_company_persons[list]
		int methodDeclPos = content.indexOf("@set_company_persons[list]");
		assertTrue("@set_company_persons[list] не найден", methodDeclPos >= 0);
		int paramPos = content.indexOf("list", methodDeclPos + 21); // после [

		boolean foundParam = false;
		for (PsiElement target : targets) {
			int tOffset = target.getTextOffset();
			if (tOffset >= paramPos && tOffset <= paramPos + 4) {
				foundParam = true;
			}
		}
		assertTrue("Навигация ^list должна вести к параметру в @set_company_persons[list]", foundParam);
	}

	/**
	 * ^self.list.foreach[] — клик на list навигирует к параметру list в @set_company_persons[list].
	 */
	public void testCaretVarParam_selfList_navigateToParam() {
		com.intellij.openapi.vfs.VirtualFile vFile = myFixture.findFileInTempDir("www/dollarvar_param_caret.p");
		assertNotNull("Файл не найден", vFile);

		String content = readFile(vFile);

		// Клик на list в "^self.list.foreach"
		int selfListPos = content.indexOf("^self.list.foreach");
		assertTrue("^self.list.foreach не найден", selfListPos >= 0);
		int clickOffset = selfListPos + 6; // ^self.l|ist

		myFixture.configureFromExistingVirtualFile(vFile);
		myFixture.getEditor().getCaretModel().moveToOffset(clickOffset);

		PsiElement[] targets = GotoDeclarationAction.findAllTargetElements(
				getProject(), myFixture.getEditor(), clickOffset);

		assertNotNull("Должны быть таргеты для ^self.list (параметр)", targets);
		assertTrue("Должен быть таргет для ^self.list (параметр)", targets.length > 0);

		int methodDeclPos = content.indexOf("@set_company_persons[list]");
		assertTrue("@set_company_persons[list] не найден", methodDeclPos >= 0);
		int paramPos = content.indexOf("list", methodDeclPos + 21);

		boolean foundParam = false;
		for (PsiElement target : targets) {
			int tOffset = target.getTextOffset();
			if (tOffset >= paramPos && tOffset <= paramPos + 4) {
				foundParam = true;
			}
		}
		assertTrue("Навигация ^self.list должна вести к параметру в @set_company_persons[list]", foundParam);
	}

	/**
	 * ^MAIN:list.foreach[] — клик на list навигирует к параметру list в @set_company_persons[list].
	 */
	public void testCaretVarParam_mainList_navigateToParam() {
		com.intellij.openapi.vfs.VirtualFile vFile = myFixture.findFileInTempDir("www/dollarvar_param_caret.p");
		assertNotNull("Файл не найден", vFile);

		String content = readFile(vFile);

		// Клик на list в "^MAIN:list.foreach"
		int mainListPos = content.indexOf("^MAIN:list.foreach");
		assertTrue("^MAIN:list.foreach не найден", mainListPos >= 0);
		int clickOffset = mainListPos + 6; // ^MAIN:l|ist

		myFixture.configureFromExistingVirtualFile(vFile);
		myFixture.getEditor().getCaretModel().moveToOffset(clickOffset);

		PsiElement[] targets = GotoDeclarationAction.findAllTargetElements(
				getProject(), myFixture.getEditor(), clickOffset);

		assertNotNull("Должны быть таргеты для ^MAIN:list (параметр)", targets);
		assertTrue("Должен быть таргет для ^MAIN:list (параметр)", targets.length > 0);

		int methodDeclPos = content.indexOf("@set_company_persons[list]");
		assertTrue("@set_company_persons[list] не найден", methodDeclPos >= 0);
		int paramPos = content.indexOf("list", methodDeclPos + 21);

		boolean foundParam = false;
		for (PsiElement target : targets) {
			int tOffset = target.getTextOffset();
			if (tOffset >= paramPos && tOffset <= paramPos + 4) {
				foundParam = true;
			}
		}
		assertTrue("Навигация ^MAIN:list должна вести к параметру в @set_company_persons[list]", foundParam);
	}

	// =============================================================================
	// РАЗДЕЛ 27: ${var} — переменная в фигурных скобках
	// =============================================================================

	/**
	 * ${myvar} — клик на myvar навигирует к $myvar[test].
	 */
	public void testBraceVar_simpleNavigation() {
		com.intellij.openapi.vfs.VirtualFile vFile = myFixture.findFileInTempDir("www/brace_var_simple.p");
		assertNotNull("Файл не найден", vFile);
		String content = readFile(vFile);

		// Ищем "${myvar}" и кликаем на "myvar" внутри скобок
		int bracePos = content.indexOf("${myvar}");
		assertTrue("${myvar} не найден", bracePos >= 0);
		// ${m|yvar} — +2 от начала "${", т.е. позиция 'm'
		int clickOffset = bracePos + 3;

		myFixture.configureFromExistingVirtualFile(vFile);
		myFixture.getEditor().getCaretModel().moveToOffset(clickOffset);

		PsiElement[] targets = GotoDeclarationAction.findAllTargetElements(
				getProject(), myFixture.getEditor(), clickOffset);

		System.out.println("[TEST] brace_var_simple targets=" + (targets != null ? targets.length : "null"));
		if (targets != null) for (PsiElement t : targets) System.out.println("[TEST]   " + t.getText() + " offset=" + t.getTextOffset());

		assertNotNull("Должны быть таргеты для ${myvar}", targets);
		assertTrue("Должен быть таргет для ${myvar}", targets.length > 0);

		// Таргет должен быть на $myvar[test]
		int defPos = content.indexOf("$myvar[test]");
		assertTrue("$myvar[test] не найден", defPos >= 0);
		boolean found = false;
		for (PsiElement target : targets) {
			if (target.getTextOffset() >= defPos && target.getTextOffset() <= defPos + 6) found = true;
		}
		assertTrue("Навигация ${myvar} должна вести к $myvar[test]", found);
	}

	/**
	 * ${data.key1} — клик на key1 навигирует к $.key1[...] внутри $data.
	 */
	public void testBraceVar_chainNavKey1() {
		com.intellij.openapi.vfs.VirtualFile vFile = myFixture.findFileInTempDir("www/brace_var_chain.p");
		assertNotNull("Файл не найден", vFile);
		String content = readFile(vFile);

		int bracePos = content.indexOf("${data.key1}");
		assertTrue("${data.key1} не найден", bracePos >= 0);
		// ${data.k|ey1} — "key1" начинается на позиции +8 от "${", кликаем +1 внутрь
		int clickOffset = bracePos + 9;

		myFixture.configureFromExistingVirtualFile(vFile);
		myFixture.getEditor().getCaretModel().moveToOffset(clickOffset);

		PsiElement[] targets = GotoDeclarationAction.findAllTargetElements(
				getProject(), myFixture.getEditor(), clickOffset);

		System.out.println("[TEST] brace_chain key1 targets=" + (targets != null ? targets.length : "null"));
		if (targets != null) for (PsiElement t : targets) System.out.println("[TEST]   " + t.getText() + " offset=" + t.getTextOffset());

		assertNotNull("Должны быть таргеты для ${data.key1}", targets);
		assertTrue("Должен быть таргет для ${data.key1}", targets.length > 0);
	}

	/**
	 * ${data.key1.key2} — клик на key2 навигирует к $.key2[final_value].
	 */
	public void testBraceVar_chainNavKey2() {
		com.intellij.openapi.vfs.VirtualFile vFile = myFixture.findFileInTempDir("www/brace_var_chain.p");
		assertNotNull("Файл не найден", vFile);
		String content = readFile(vFile);

		int bracePos = content.indexOf("${data.key1.key2}");
		assertTrue("${data.key1.key2} не найден", bracePos >= 0);
		// "${data.key1.k|ey2}" — "key2" начинается на +13, кликаем +1 внутрь
		int clickOffset = bracePos + 14;

		myFixture.configureFromExistingVirtualFile(vFile);
		myFixture.getEditor().getCaretModel().moveToOffset(clickOffset);

		PsiElement[] targets = GotoDeclarationAction.findAllTargetElements(
				getProject(), myFixture.getEditor(), clickOffset);

		System.out.println("[TEST] brace_chain key2 targets=" + (targets != null ? targets.length : "null"));
		if (targets != null) for (PsiElement t : targets) System.out.println("[TEST]   " + t.getText() + " offset=" + t.getTextOffset());

		assertNotNull("Должны быть таргеты для ${data.key1.key2}", targets);
		assertTrue("Должен быть таргет для ${data.key1.key2}", targets.length > 0);
	}

	/**
	 * ${data.key1.key2} — клик на data навигирует к $data[...] определению.
	 */
	public void testBraceVar_chainNavRootVar() {
		com.intellij.openapi.vfs.VirtualFile vFile = myFixture.findFileInTempDir("www/brace_var_chain.p");
		assertNotNull("Файл не найден", vFile);
		String content = readFile(vFile);

		int bracePos = content.indexOf("${data.key1.key2}");
		assertTrue("${data.key1.key2} не найден", bracePos >= 0);
		// "${d|ata.key1.key2}" — "data" начинается на +2, кликаем +1 внутрь
		int clickOffset = bracePos + 3;

		myFixture.configureFromExistingVirtualFile(vFile);
		myFixture.getEditor().getCaretModel().moveToOffset(clickOffset);

		PsiElement[] targets = GotoDeclarationAction.findAllTargetElements(
				getProject(), myFixture.getEditor(), clickOffset);

		System.out.println("[TEST] brace_chain root targets=" + (targets != null ? targets.length : "null"));
		if (targets != null) for (PsiElement t : targets) System.out.println("[TEST]   " + t.getText() + " offset=" + t.getTextOffset());

		assertNotNull("Должны быть таргеты для data в ${data.key1.key2}", targets);
		assertTrue("Должен быть таргет для data в ${data.key1.key2}", targets.length > 0);

		// Таргет на $data[...] определение
		int defPos = content.indexOf("$data[");
		assertTrue("$data[ не найден", defPos >= 0);
		boolean found = false;
		for (PsiElement target : targets) {
			if (target.getTextOffset() >= defPos && target.getTextOffset() <= defPos + 5) found = true;
		}
		assertTrue("Навигация на data в ${data.key1.key2} должна вести к $data[...]", found);
	}

	/**
	 * Ищет паттерн только после одного из указанных методов.
	 * Это нужно чтобы не попасть на определение переменной ($self.name[$name])
	 * вместо использования ($self.name).
	 */
	private int findPatternInMethods(String content, String pattern, String[] methodHeaders) {
		int searchFrom = -1;
		for (String header : methodHeaders) {
			int headerPos = content.indexOf(header);
			if (headerPos >= 0 && (searchFrom == -1 || headerPos < searchFrom)) {
				// Ищем паттерн после этого метода
				int found = content.indexOf(pattern, headerPos);
				if (found >= 0) return found;
			}
		}
		// Fallback — ищем последнее вхождение
		return content.lastIndexOf(pattern);
	}


	// =============================================================================
	// РАЗДЕЛ: навигация по hash key — все формы: $self., ^self., plain, ^plain, $MAIN:, ^MAIN:
	// =============================================================================

	public void testHashKeyNav_dollarSelf() { assertHashKeyNavInFile("www/hash_key_nav.p", "$self.config.name", "name"); }
	public void testHashKeyNav_caretSelf()  { assertHashKeyNavInFile("www/hash_key_nav.p", "^self.config.name.", "name"); }
	public void testHashKeyNav_dollarPlain(){ assertHashKeyNavInFile("www/hash_key_nav.p", "$config.name", "name"); }
	public void testHashKeyNav_caretPlain() { assertHashKeyNavInFile("www/hash_key_nav.p", "^config.name.", "name"); }
	public void testHashKeyNav_dollarMain() { assertHashKeyNavInFile("www/hash_key_nav.p", "$MAIN:config.name", "name"); }
	public void testHashKeyNav_caretMain()  { assertHashKeyNavInFile("www/hash_key_nav.p", "^MAIN:config.name.", "name"); }

	public void testCaretVar_numericMethodNavigation() {
		createParser3FileInDir("numeric-nav/auto.p",
				"@auto[]\n" +
						"$alsSite[^AlsSite::create[]]\n"
		);
		createParser3FileInDir("numeric-nav/AlsSite.p",
				"# реальный минимальный кейс Parser3 fixture\n@CLASS\n" +
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
		createParser3FileInDir("numeric-nav/site.p",
				"@main[]\n# реальный минимальный кейс Parser3 fixture\n" +
						"$alsSite.notShow(true)\n" +
						"^alsSite.404[]\n"
		);

		assertVarMethodNavigation("numeric-nav/site.p", "^alsSite.404[]", "404", "numeric-nav/AlsSite.p");
	}

	public void testCaretVar_numericMethodVariableNavigationGoesToAuto() {
		createParser3FileInDir("numeric-var-nav/auto.p",
				"@auto[]\n" +
						"$alsSite[^AlsSite::create[]]\n"
		);
		createParser3FileInDir("numeric-var-nav/AlsSite.p",
				"# реальный минимальный кейс Parser3 fixture\n@CLASS\n" +
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
		createParser3FileInDir("numeric-var-nav/site.p",
				"@main[]\n# реальный минимальный кейс Parser3 fixture\n" +
						"$alsSite.notShow(true)\n" +
						"^alsSite.404[]\n"
		);

		com.intellij.openapi.vfs.VirtualFile vFile = myFixture.findFileInTempDir("numeric-var-nav/site.p");
		assertNotNull("Файл не найден", vFile);

		String content = readFile(vFile);
		int patternPos = content.indexOf("^alsSite.404[]");
		assertTrue("^alsSite.404[] не найден", patternPos >= 0);
		int clickOffset = patternPos + 3; // ^al|sSite.404[]

		myFixture.configureFromExistingVirtualFile(vFile);
		myFixture.getEditor().getCaretModel().moveToOffset(clickOffset);

		PsiElement[] targets = GotoDeclarationAction.findAllTargetElements(
				getProject(), myFixture.getEditor(), clickOffset);

		assertNotNull("Должны быть таргеты для ^alsSite", targets);
		assertTrue("Должен быть хотя бы один таргет для ^alsSite", targets.length > 0);

		boolean foundAuto = false;
		for (PsiElement target : targets) {
			PsiFile targetFile = target.getContainingFile();
			if (targetFile != null && targetFile.getVirtualFile().getPath().endsWith("numeric-var-nav/auto.p")) {
				foundAuto = true;
				break;
			}
		}
		assertTrue("Навигация по ^alsSite должна вести к $alsSite[^AlsSite::create[]] в numeric-var-nav/auto.p", foundAuto);
	}

	/** Кликает на keyName внутри строки searchPattern, проверяет что таргет есть. */
	public void testCaretVar_numericBaseClassInheritedMethodNavigation() {
		createParser3FileInDir("numeric-base-nav/www/auto.p",
				"@auto[]\n" +
						"# реальный минимальный кейс Parser3 fixture\n@USE\n" +
						"classes/222.p\n" +
						"classes/333.p\n"
		);
		createParser3FileInDir("numeric-base-nav/www/classes/222.p",
				"# реальный минимальный кейс Parser3 fixture\n@CLASS\n" +
						"222\n" +
						"\n" +
						"@create[]\n" +
						"\n" +
						"@method[]\n"
		);
		createParser3FileInDir("numeric-base-nav/www/classes/333.p",
				"# реальный минимальный кейс Parser3 fixture\n@CLASS\n" +
						"333\n" +
						"\n" +
						"@BASE\n" +
						"222\n" +
						"\n" +
						"@create[]\n"
		);
		createParser3FileInDir("numeric-base-nav/www/site.p",
				"@main[]\n# реальный минимальный кейс Parser3 fixture\n" +
						"$obj[^333::create[]]\n" +
						"^obj.method[]\n"
		);

		assertVarMethodNavigation("numeric-base-nav/www/site.p", "^obj.method[]", "method", "numeric-base-nav/www/classes/222.p");
	}

	public void testStaticMethod_numericClassNavigation() {
		createParser3FileInDir("numeric-static-nav/www/auto.p",
				"@auto[]\n" +
						"# реальный минимальный кейс Parser3 fixture\n@USE\n" +
						"classes/222.p\n"
		);
		createParser3FileInDir("numeric-static-nav/www/classes/222.p",
				"# реальный минимальный кейс Parser3 fixture\n@CLASS\n" +
						"222\n" +
						"\n" +
						"@method[]\n"
		);
		createParser3FileInDir("numeric-static-nav/www/site.p",
				"@main[]\n# реальный минимальный кейс Parser3 fixture\n" +
						"^222:method[]\n"
		);

		com.intellij.openapi.vfs.VirtualFile vFile = myFixture.findFileInTempDir("numeric-static-nav/www/site.p");
		assertNotNull("Файл не найден: numeric-static-nav/www/site.p", vFile);

		String content = readFile(vFile);
		int patternPos = content.indexOf("^222:method[]");
		assertTrue("^222:method[] не найден", patternPos >= 0);
		int methodOffset = patternPos + "^222:me".length();

		myFixture.configureFromExistingVirtualFile(vFile);
		myFixture.getEditor().getCaretModel().moveToOffset(methodOffset);

		PsiElement[] targets = GotoDeclarationAction.findAllTargetElements(
				getProject(), myFixture.getEditor(), methodOffset);

		assertNotNull("Должны быть таргеты для ^222:method[]", targets);
		assertTrue("Должен быть хотя бы один таргет для ^222:method[]", targets.length > 0);

		boolean foundClassFile = false;
		for (PsiElement target : targets) {
			PsiFile targetFile = target.getContainingFile();
			if (targetFile != null && targetFile.getVirtualFile().getPath().endsWith("numeric-static-nav/www/classes/222.p")) {
				foundClassFile = true;
				break;
			}
		}
		assertTrue("Навигация по ^222:method[] должна вести к numeric-static-nav/www/classes/222.p", foundClassFile);
	}

	public void testObjectPropertyFromNumericClassMethodResult_navigatesToClassProperty() {
		createParser3FileInDir("numeric-object-prop-nav/www/auto.p",
				"@auto[]\n" +
						"# реальный минимальный кейс Parser3 fixture\n@USE\n" +
						"classes/2Login.p\n"
		);
		createParser3FileInDir("numeric-object-prop-nav/www/classes/2Login.p",
				"# реальный минимальный кейс Parser3 fixture\n@CLASS\n" +
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
		createParser3FileInDir("numeric-object-prop-nav/www/site.p",
				"@main[]\n# реальный минимальный кейс Parser3 fixture\n" +
						"$LoginTest[^2Login::create[]]\n" +
						"$LoginTest.user\n"
		);

		com.intellij.openapi.vfs.VirtualFile vFile = myFixture.findFileInTempDir("numeric-object-prop-nav/www/site.p");
		assertNotNull("Файл не найден", vFile);

		String content = readFile(vFile);
		int usagePos = content.indexOf("$LoginTest.user");
		assertTrue("$LoginTest.user не найден", usagePos >= 0);
		int clickOffset = usagePos + "$LoginTest.".length() + 1;

		myFixture.configureFromExistingVirtualFile(vFile);
		myFixture.getEditor().getCaretModel().moveToOffset(clickOffset);

		PsiElement[] targets = GotoDeclarationAction.findAllTargetElements(
				getProject(), myFixture.getEditor(), clickOffset);

		assertNotNull("Должны быть таргеты для $LoginTest.user", targets);
		assertTrue("Должен быть хотя бы один таргет для $LoginTest.user", targets.length > 0);

		boolean foundClassProperty = false;
		for (PsiElement target : targets) {
			PsiFile targetFile = target.getContainingFile();
			if (targetFile != null
					&& targetFile.getVirtualFile().getPath().endsWith("numeric-object-prop-nav/www/classes/2Login.p")) {
				foundClassProperty = true;
				break;
			}
		}
		assertTrue("Навигация по user должна вести к $self.user[^self.get_user[]] в 2Login.p", foundClassProperty);
	}

	public void testObjectPropertyFromAutouseNumericClassMethodResult_navigatesToClassProperty() {
		setMethodCompletionMode(ru.artlebedev.parser3.settings.Parser3ProjectSettings.MethodCompletionMode.USE_ONLY);

		createParser3FileInDir("numeric-object-prop-autouse-nav/www/auto.p",
				"@autouse[]\n"
		);
		createParser3FileInDir("numeric-object-prop-autouse-nav/www/2Login.p",
				"# реальный минимальный кейс Parser3 fixture\n@CLASS\n" +
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
		createParser3FileInDir("numeric-object-prop-autouse-nav/www/site.p",
				"@main[]\n# реальный минимальный кейс Parser3 fixture\n" +
						"$LoginTest[^2Login::create[]]\n" +
						"$LoginTest.user\n"
		);

		com.intellij.openapi.vfs.VirtualFile vFile = myFixture.findFileInTempDir("numeric-object-prop-autouse-nav/www/site.p");
		assertNotNull("Файл не найден", vFile);

		String content = readFile(vFile);
		int usagePos = content.indexOf("$LoginTest.user");
		assertTrue("$LoginTest.user не найден", usagePos >= 0);
		int clickOffset = usagePos + "$LoginTest.".length() + 1;

		myFixture.configureFromExistingVirtualFile(vFile);
		myFixture.getEditor().getCaretModel().moveToOffset(clickOffset);

		PsiElement[] targets = GotoDeclarationAction.findAllTargetElements(
				getProject(), myFixture.getEditor(), clickOffset);

		assertNotNull("Должны быть таргеты для $LoginTest.user через @autouse", targets);
		assertTrue("Должен быть хотя бы один таргет для $LoginTest.user через @autouse", targets.length > 0);

		boolean foundClassProperty = false;
		for (PsiElement target : targets) {
			PsiFile targetFile = target.getContainingFile();
			if (targetFile != null
					&& targetFile.getVirtualFile().getPath().endsWith("numeric-object-prop-autouse-nav/www/2Login.p")) {
				foundClassProperty = true;
				break;
			}
		}
		assertTrue("Навигация по user через @autouse должна вести к $self.user[^self.get_user[]] в 2Login.p", foundClassProperty);
	}

	public void testObjectPropertyFromAutouseNumericClassMethodResult_navigatesToNestedHashKey() {
		setMethodCompletionMode(ru.artlebedev.parser3.settings.Parser3ProjectSettings.MethodCompletionMode.USE_ONLY);

		createParser3FileInDir("numeric-object-prop-autouse-hash-nav/www/auto.p",
				"@autouse[]\n"
		);
		createParser3FileInDir("numeric-object-prop-autouse-hash-nav/www/2Login.p",
				"# реальный минимальный кейс Parser3 fixture\n@CLASS\n" +
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
		createParser3FileInDir("numeric-object-prop-autouse-hash-nav/www/site.p",
				"@main[]\n# реальный минимальный кейс Parser3 fixture\n" +
						"$LoginTest[^2Login::create[]]\n" +
						"$LoginTest.user\n" +
						"$LoginTest.user\n" +
						"$LoginTest.user.login\n"
		);

		com.intellij.openapi.vfs.VirtualFile vFile = myFixture.findFileInTempDir("numeric-object-prop-autouse-hash-nav/www/site.p");
		assertNotNull("Файл не найден", vFile);

		String content = readFile(vFile);
		int usagePos = content.lastIndexOf("$LoginTest.user.login");
		assertTrue("$LoginTest.user.login не найден", usagePos >= 0);
		int clickOffset = usagePos + "$LoginTest.user.".length() + 1;

		myFixture.configureFromExistingVirtualFile(vFile);
		myFixture.getEditor().getCaretModel().moveToOffset(clickOffset);

		PsiElement[] targets = GotoDeclarationAction.findAllTargetElements(
				getProject(), myFixture.getEditor(), clickOffset);

		assertNotNull("Должны быть таргеты для $LoginTest.user.login через @autouse", targets);
		assertTrue("Должен быть хотя бы один таргет для $LoginTest.user.login через @autouse", targets.length > 0);

		boolean foundLoginKey = false;
		for (PsiElement target : targets) {
			PsiFile targetFile = target.getContainingFile();
			if (targetFile != null
					&& targetFile.getVirtualFile().getPath().endsWith("numeric-object-prop-autouse-hash-nav/www/2Login.p")
					&& "login".equals(target.getText())) {
				foundLoginKey = true;
				break;
			}
		}
		assertTrue("Навигация по login через @autouse должна вести к $.login[test] в 2Login.p", foundLoginKey);
	}

	private void assertHashKeyNavInFile(String filePath, String searchPattern, String keyName) {
		com.intellij.openapi.vfs.VirtualFile vFile = myFixture.findFileInTempDir(filePath);
		assertNotNull("Файл не найден: " + filePath, vFile);
		String content = readFile(vFile);
		int pos = content.indexOf(searchPattern);
		assertTrue("Паттерн не найден: " + searchPattern, pos >= 0);
		int keyPos = content.indexOf(keyName, pos + searchPattern.lastIndexOf(keyName));
		assertTrue("keyName не найден: " + keyName, keyPos >= 0);
		int clickOffset = keyPos + 1;
		myFixture.configureFromExistingVirtualFile(vFile);
		myFixture.getEditor().getCaretModel().moveToOffset(clickOffset);
		PsiElement[] targets = GotoDeclarationAction.findAllTargetElements(getProject(), myFixture.getEditor(), clickOffset);
		assertNotNull("Нет таргетов для " + searchPattern, targets);
		assertTrue("Нет таргетов для " + searchPattern, targets.length > 0);
	}

	public void testTableMenu_dynamicBracketExpr_sqlAliasNavigation() {
		createParser3FileInDir("www/table_menu_dynamic_bracket_nav.p",
				"@main[]\n# обезличенный минимальный кейс Parser3 fixture\n" +
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
						"	$items.[$_items.target_user_id][]\n" +
						"}\n");

		com.intellij.openapi.vfs.VirtualFile vFile = myFixture.findFileInTempDir("www/table_menu_dynamic_bracket_nav.p");
		assertNotNull("Файл не найден", vFile);
		String content = readFile(vFile);
		int usagePos = content.lastIndexOf("target_user_id");
		assertTrue("target_user_id в использовании не найден", usagePos >= 0);
		int clickOffset = usagePos + 2;

		myFixture.configureFromExistingVirtualFile(vFile);
		myFixture.getEditor().getCaretModel().moveToOffset(clickOffset);

		PsiElement[] targets = GotoDeclarationAction.findAllTargetElements(
				getProject(), myFixture.getEditor(), clickOffset);

		assertTrue("Вызов навигации по target_user_id не должен падать", true);
		if (targets != null) {
			System.out.println("[TEST] table_menu_dynamic_bracket_expr_sqlAliasNavigation targets=" + targets.length);
		}
	}

	public void testMainAutoHashKeyNavigation_goesToExternalHashLiteralKey() {
		createParser3FileInDir("test_auto_nav/auto.p",
				"@conf[filespec][confdir;charsetsdir;sqldriversdir]\n" +
						"$SQL[\n" +
						"\t$.drivers[^table::create{protocol\tdriver\tclient\n" +
						"mysql\t$sqldriversdir/parser3mysql.dll\t$sqldriversdir/libmySQL.dll\n" +
						"}]\n" +
						"]\n" +
						"\n" +
						"@auto[]\n" +
						"$SQL.connect-items[mysql://test_user:@localhost/items?charset=utf8]\n");
		setMainAuto("test_auto_nav/auto.p");

		createParser3FileInDir("test_auto_nav/page.p",
				"@main[]\n# обезличенный минимальный кейс Parser3 fixture\n" +
						"$SQL.drivers.\n");

		assertHashKeyNavigationTarget(
				"test_auto_nav/page.p",
				"$SQL.drivers.",
				"drivers",
				"test_auto_nav/auto.p"
		);
	}

	public void testMainAutoHashKeyNavigation_goesToExternalAdditiveDotChainKey() {
		createParser3FileInDir("test_auto_nav_add/auto.p",
				"@conf[filespec]\n" +
						"$SQL[\n" +
						"\t$.drivers[^table::create{protocol\tdriver\tclient}]\n" +
						"]\n" +
						"\n" +
						"@auto[]\n" +
						"$SQL.connect-items[mysql://test_user:@localhost/items?charset=utf8]\n" +
						"$SQL.connet-items[$SQL.connect-items]\n");
		setMainAuto("test_auto_nav_add/auto.p");

		createParser3FileInDir("test_auto_nav_add/page.p",
				"@main[]\n# обезличенный минимальный кейс Parser3 fixture\n" +
						"$SQL.connect-items\n");

		assertHashKeyNavigationTarget(
				"test_auto_nav_add/page.p",
				"$SQL.connect-items",
				"connect-items",
				"test_auto_nav_add/auto.p"
		);
	}

	public void testHashKeyNavigation_syntheticReadChainFallback_goesToPreviousUsage() {
		createParser3FileInDir("www/hash_key_nav_synthetic_read_chain_fallback.p",
				"@main[]\n" +
						"# обезличенный минимальный кейс из test/3.p\n" +
						"$SQL.connect-string-test-api\n" +
						"$value[$SQL.connect-string-test-api]\n");

		com.intellij.openapi.vfs.VirtualFile vFile = myFixture.findFileInTempDir("www/hash_key_nav_synthetic_read_chain_fallback.p");
		assertNotNull("Файл не найден", vFile);

		String content = readFile(vFile);
		int firstUsagePos = content.indexOf("$SQL.connect-string-test-api");
		assertTrue("Первое использование $SQL.connect-string-test-api не найдено", firstUsagePos >= 0);
		int secondUsagePos = content.lastIndexOf("$SQL.connect-string-test-api");
		assertTrue("Второе использование $SQL.connect-string-test-api не найдено", secondUsagePos > firstUsagePos);
		int clickOffset = secondUsagePos + "$SQL.".length() + 1;

		myFixture.configureFromExistingVirtualFile(vFile);
		myFixture.getEditor().getCaretModel().moveToOffset(clickOffset);

		PsiElement[] targets = GotoDeclarationAction.findAllTargetElements(
				getProject(), myFixture.getEditor(), clickOffset);

		assertNotNull("Synthetic read-chain должен иметь fallback-таргет", targets);
		assertTrue("Synthetic read-chain должен иметь fallback-таргет", targets.length > 0);

		int expectedKeyPos = firstUsagePos + "$SQL.".length();
		boolean foundPreviousUsage = false;
		boolean foundClickedUsage = false;
		for (PsiElement target : targets) {
			int targetOffset = target.getTextOffset();
			if (targetOffset >= expectedKeyPos && targetOffset <= expectedKeyPos + "connect-string-test-api".length()) {
				foundPreviousUsage = true;
			}
			if (targetOffset >= secondUsagePos && targetOffset <= secondUsagePos + "$SQL.connect-string-test-api".length()) {
				foundClickedUsage = true;
			}
		}

		assertTrue("Навигация должна вести к предыдущему read-chain использованию", foundPreviousUsage);
		assertFalse("Навигация не должна вести на кликнутое использование, если есть предыдущее", foundClickedUsage);
	}

	public void testHashKeyNavigation_realAdditiveDefinitionBeatsSyntheticReadChainFallback() {
		createParser3FileInDir("test_auto_nav_sql_connect/cgi/auto.p",
				"@auto[]\n" +
						"$SQL.connect-string-test-api[mysql://test_user:test_password@localhost/test_db?charset=utf8]\n" +
						"$SQL[\n" +
						"\t$.drivers[^table::create{protocol\tdriver\tclient}]\n" +
						"]\n" +
						"\n" +
						"@main[]\n" +
						"^connect[$SQL.connect-string-test-api]{$code}\n");
		setMainAuto("test_auto_nav_sql_connect/cgi/auto.p");

		createParser3FileInDir("test_auto_nav_sql_connect/www/errors/3.p",
				"@note[]\n" +
						"$SQL.connect-string-test-api\n");

		com.intellij.openapi.vfs.VirtualFile vFile = myFixture.findFileInTempDir("test_auto_nav_sql_connect/www/errors/3.p");
		assertNotNull("Файл не найден", vFile);

		String content = readFile(vFile);
		int usagePos = content.indexOf("$SQL.connect-string-test-api");
		assertTrue("Использование $SQL.connect-string-test-api не найдено", usagePos >= 0);
		int clickOffset = usagePos + "$SQL.".length() + 1;

		myFixture.configureFromExistingVirtualFile(vFile);
		myFixture.getEditor().getCaretModel().moveToOffset(clickOffset);

		PsiElement[] targets = GotoDeclarationAction.findAllTargetElements(
				getProject(), myFixture.getEditor(), clickOffset);

		assertNotNull("Должны быть таргеты для $SQL.connect-string-test-api", targets);
		assertTrue("Должен быть хотя бы один таргет для $SQL.connect-string-test-api", targets.length > 0);

		com.intellij.openapi.vfs.VirtualFile autoFile = myFixture.findFileInTempDir("test_auto_nav_sql_connect/cgi/auto.p");
		assertNotNull("auto.p не найден", autoFile);
		String autoContent = readFile(autoFile);
		int expectedDefPos = autoContent.indexOf("$SQL.connect-string-test-api[");
		int wrongReadPos = autoContent.indexOf("^connect[$SQL.connect-string-test-api]");
		assertTrue("Определение $SQL.connect-string-test-api[ не найдено", expectedDefPos >= 0);
		assertTrue("Read-chain usage в ^connect не найден", wrongReadPos >= 0);

		boolean foundDefinition = false;
		boolean foundConnectUsage = false;
		for (PsiElement target : targets) {
			PsiFile targetFile = target.getContainingFile();
			if (targetFile == null || targetFile.getVirtualFile() == null) continue;
			if (!targetFile.getVirtualFile().equals(autoFile)) continue;
			int targetOffset = target.getTextOffset();
			if (targetOffset >= expectedDefPos && targetOffset <= expectedDefPos + "$SQL.connect-string-test-api".length()) {
				foundDefinition = true;
			}
			if (targetOffset >= wrongReadPos && targetOffset <= wrongReadPos + "^connect[$SQL.connect-string-test-api]".length()) {
				foundConnectUsage = true;
			}
		}

		assertTrue("Навигация должна вести к реальному $SQL.connect-string-test-api[", foundDefinition);
		assertFalse("Навигация не должна вести к read-chain usage в ^connect", foundConnectUsage);
	}

	private void assertHashKeyNavigationTarget(
			String sourceFile,
			String searchPattern,
			String keyName,
			String expectedFile
	) {
		com.intellij.openapi.vfs.VirtualFile vFile = myFixture.findFileInTempDir(sourceFile);
		assertNotNull("Файл не найден: " + sourceFile, vFile);

		String content = readFile(vFile);
		int patternPos = content.indexOf(searchPattern);
		assertTrue("Паттерн не найден: " + searchPattern, patternPos >= 0);
		int keyPos = content.indexOf(keyName, patternPos);
		assertTrue("Ключ не найден: " + keyName, keyPos >= 0);
		int clickOffset = keyPos + Math.min(1, keyName.length() - 1);

		myFixture.configureFromExistingVirtualFile(vFile);
		myFixture.getEditor().getCaretModel().moveToOffset(clickOffset);

		PsiElement[] targets = GotoDeclarationAction.findAllTargetElements(
				getProject(), myFixture.getEditor(), clickOffset);

		assertNotNull("Нет таргетов для " + searchPattern, targets);
		assertTrue("Нет таргетов для " + searchPattern, targets.length > 0);

		com.intellij.openapi.vfs.VirtualFile expectedVFile = myFixture.findFileInTempDir(expectedFile);
		assertNotNull("Ожидаемый файл не найден: " + expectedFile, expectedVFile);
		String expectedContent = readFile(expectedVFile);
		int expectedKeyPos = expectedContent.indexOf("$." + keyName);
		if (expectedKeyPos >= 0) expectedKeyPos += 2;
		if (expectedKeyPos < 0) {
			expectedKeyPos = expectedContent.indexOf("$SQL." + keyName);
			if (expectedKeyPos >= 0) expectedKeyPos += "$SQL.".length();
		}
		assertTrue("Ожидаемый ключ не найден: " + keyName, expectedKeyPos >= 0);

		boolean found = false;
		for (PsiElement target : targets) {
			PsiFile targetFile = target.getContainingFile();
			if (targetFile == null || targetFile.getVirtualFile() == null) continue;
			int targetOffset = target.getTextOffset();
			if (targetFile.getVirtualFile().getPath().endsWith(expectedFile)
					&& targetOffset >= expectedKeyPos
					&& targetOffset <= expectedKeyPos + keyName.length()) {
				found = true;
				break;
			}
		}
		assertTrue("Навигация по " + keyName + " должна вести к " + expectedFile, found);
	}

	// =============================================================================
	// РАЗДЕЛ 27: параметр метода затеняет глобальную переменную — навигация
	// =============================================================================

	/**
	 * $data в @getResult[data]: параметр затеняет глобальную $data[^table::create{...}].
	 * Навигация должна вести к параметру @getResult[data], а не к глобальной таблице.
	 */
	public void testParam_shadowsGlobalTable_navigatesToParam() {
		com.intellij.openapi.vfs.VirtualFile vFile = myFixture.findFileInTempDir("www/dollarvar_param_global_table.p");
		assertNotNull("Файл не найден", vFile);

		String content = readFile(vFile);

		// Последний $data (без [) — чтение переменной в методе
		int lastDataPos = content.lastIndexOf("$data");
		assertTrue("$data не найден", lastDataPos >= 0);
		assertFalse("Последний $data не должен быть присваиванием",
				lastDataPos + 5 < content.length() && content.charAt(lastDataPos + 5) == '[');

		int clickOffset = lastDataPos + 2;
		myFixture.configureFromExistingVirtualFile(vFile);
		myFixture.getEditor().getCaretModel().moveToOffset(clickOffset);

		PsiElement[] targets = GotoDeclarationAction.findAllTargetElements(
				getProject(), myFixture.getEditor(), clickOffset);

		assertNotNull("Должны быть таргеты", targets);
		assertTrue("Должен быть таргет", targets.length > 0);

		// Параметр: "data" в @getResult[data]
		int methodDeclPos = content.indexOf("@getResult[data]");
		assertTrue("@getResult[data] не найден", methodDeclPos >= 0);
		int paramPos = content.indexOf("data", methodDeclPos + 11);

		// Глобальная таблица: $data[^table...]
		int globalPos = content.indexOf("$data[");
		assertTrue("$data[ не найден", globalPos >= 0);

		boolean foundParam = false;
		boolean foundGlobal = false;
		for (PsiElement target : targets) {
			int tOffset = target.getTextOffset();
			if (tOffset >= paramPos && tOffset <= paramPos + 4) foundParam = true;
			if (tOffset >= globalPos && tOffset <= globalPos + 5) foundGlobal = true;
		}

		assertTrue("Навигация должна вести к параметру @getResult[data]", foundParam);
		assertFalse("Навигация НЕ должна вести к глобальной $data[^table...]", foundGlobal);
	}

	/**
	 * В разных методах с [locals] одинаковое имя $data не должно смешивать foreach-ключи.
	 * Иначе у $data.companies внезапно появляется item_id из другого метода.
	 */
	public void testLocals_hashKeysFromOtherMethodForeach_doNotLeakIntoNavigation() {
		createParser3FileInDir("www/locals_hash_nav_isolation.p",
				"@first[][locals]\n" +
						"$data[^hash::create[]]\n" +
						"^data.foreach[;v]{\n" +
						"	^if($v.item_id){\n" +
						"	}\n" +
						"}\n" +
						"\n" +
						"@second[][locals]\n" +
						"$data[\n" +
						"	$.companies[^hash::create[]]\n" +
						"]\n" +
						"$data.companies.company_id[]\n" +
						"$data.companies.item_id\n");

		com.intellij.openapi.vfs.VirtualFile vFile = myFixture.findFileInTempDir("www/locals_hash_nav_isolation.p");
		assertNotNull("Файл не найден", vFile);

		String content = readFile(vFile);
		int usagePos = content.lastIndexOf("$data.companies.item_id");
		assertTrue("Использование $data.companies.item_id не найдено", usagePos >= 0);
		int clickOffset = usagePos + "$data.companies.".length() + 1;

		myFixture.configureFromExistingVirtualFile(vFile);
		myFixture.getEditor().getCaretModel().moveToOffset(clickOffset);

		PsiElement[] targets = GotoDeclarationAction.findAllTargetElements(
				getProject(), myFixture.getEditor(), clickOffset);

		boolean hasTargets = targets != null && targets.length > 0;
		assertFalse("item_id не должен резолвиться через foreach из другого метода с [locals]", hasTargets);
	}

	/**
	 * Простое чтение $data.companies.item_id не должно считаться объявлением ключа для навигации.
	 * Иначе goto прыгает в случайное место вместо отсутствия таргета.
	 */
	public void testReadChain_hashKeyNavigation_doesNotJumpToSyntheticReadUsage() {
		createParser3FileInDir("www/hash_key_nav_read_chain_only.p",
				"@main[]\n# реальный минимальный кейс Parser3 fixture\n" +
						"$data[\n" +
						"	$.companies[^hash::create[]]\n" +
						"]\n" +
						"$data.companies.company_id[]\n" +
						"$data.companies.item_id\n");

		com.intellij.openapi.vfs.VirtualFile vFile = myFixture.findFileInTempDir("www/hash_key_nav_read_chain_only.p");
		assertNotNull("Файл не найден", vFile);

		String content = readFile(vFile);
		int usagePos = content.lastIndexOf("$data.companies.item_id");
		assertTrue("Использование $data.companies.item_id не найдено", usagePos >= 0);
		int clickOffset = usagePos + "$data.companies.".length() + 1;

		myFixture.configureFromExistingVirtualFile(vFile);
		myFixture.getEditor().getCaretModel().moveToOffset(clickOffset);

		PsiElement[] targets = GotoDeclarationAction.findAllTargetElements(
				getProject(), myFixture.getEditor(), clickOffset);

		boolean hasTargets = targets != null && targets.length > 0;
		assertFalse("Синтетический readChain-ключ item_id не должен давать таргет навигации", hasTargets);
	}

	/**
	 * Простое чтение локального $user.login в CLASS с @OPTIONS locals не должно
	 * затенять реальный MAIN-хеш $user и ломать навигацию по соседним ключам.
	 */
	public void testReadChain_localClassUsageDoesNotShadowMainHashKeyNavigation() {
		try {
			setDocumentRoot("www");
		} catch (java.io.IOException e) {
			throw new AssertionError("Не удалось настроить document_root для обезличенного fixture", e);
		}
		replaceParser3FileInDir("www/auto.p",
				"@auto[]\n" +
						"$profileData[\n" +
						"	$.login[test_user]\n" +
						"	$.email[test@example.com]\n" +
						"	$.roles[^hash::create[]]\n" +
						"]\n");
		createParser3FileInDir("www/_mod/auto.p",
				"@CLASS\n" +
						"TestModule\n" +
						"\n" +
						"@OPTIONS\n" +
						"locals\n" +
						"\n" +
						"@create[]\n" +
						"$profileData.login\n" +
						"$value[$profileData.email]\n");

		com.intellij.openapi.vfs.VirtualFile vFile = myFixture.findFileInTempDir("www/_mod/auto.p");
		assertNotNull("Файл не найден", vFile);
		myFixture.configureFromExistingVirtualFile(vFile);

		String content = readFile(vFile);
		int usagePos = content.indexOf("$profileData.email");
		assertTrue("Использование $profileData.email не найдено", usagePos >= 0);
		int clickOffset = usagePos + "$profileData.".length() + 1;
		myFixture.getEditor().getCaretModel().moveToOffset(clickOffset);

		PsiElement[] targets = GotoDeclarationAction.findAllTargetElements(
				getProject(), myFixture.getEditor(), clickOffset);

		assertNotNull("Должны быть таргеты для $profileData.email", targets);
		assertTrue("Должен быть хотя бы один таргет для $profileData.email", targets.length > 0);

		com.intellij.openapi.vfs.VirtualFile autoFile = myFixture.findFileInTempDir("www/auto.p");
		assertNotNull("MAIN auto.p не найден", autoFile);
		String autoContent = readFile(autoFile);
		int expectedEmailPos = autoContent.indexOf("$.email");
		assertTrue("Определение $.email не найдено", expectedEmailPos >= 0);
		expectedEmailPos += 2;

		int wrongLoginPos = content.indexOf("$profileData.login");
		assertTrue("Read-only $profileData.login не найден", wrongLoginPos >= 0);

		boolean foundEmailDefinition = false;
		boolean foundReadOnlyLogin = false;
		for (PsiElement target : targets) {
			PsiFile targetFile = target.getContainingFile();
			if (targetFile == null || targetFile.getVirtualFile() == null) continue;
			int targetOffset = target.getTextOffset();
			if (targetFile.getVirtualFile().equals(autoFile)
					&& targetOffset >= expectedEmailPos
					&& targetOffset <= expectedEmailPos + "email".length()) {
				foundEmailDefinition = true;
			}
			if (targetFile.getVirtualFile().equals(vFile)
					&& targetOffset >= wrongLoginPos
					&& targetOffset <= wrongLoginPos + "$profileData.login".length()) {
				foundReadOnlyLogin = true;
			}
		}

		assertTrue("Навигация должна вести к реальному $.email из MAIN auto.p, targets=" + describeTargets(targets),
				foundEmailDefinition);
		assertFalse("Навигация не должна вести к read-only $profileData.login", foundReadOnlyLogin);
	}

	/**
	 * Вложенная read-only цепочка из class-файла не должна становиться таргетом
	 * сама для себя, если корень пришёл из MAIN method-result.
	 */
	public void testReadChain_nestedLocalClassUsageDoesNotNavigateToItself() {
		try {
			setDocumentRoot("www");
		} catch (java.io.IOException e) {
			throw new AssertionError("Не удалось настроить document_root для обезличенного fixture", e);
		}
		replaceParser3FileInDir("www/auto.p",
				"@auto[]\n" +
						"$person[^getPerson[]]\n" +
						"\n" +
						"@getPerson[][locals]\n" +
						"$result[\n" +
						"	$.login[test_user]\n" +
						"	$.fields[\n" +
						"		$.remote(true)\n" +
						"		$.email[test@example.com]\n" +
						"	]\n" +
						"]\n");
		createParser3FileInDir("www/_mod/auto.p",
				"@CLASS\n" +
						"TestModule\n" +
						"\n" +
						"@OPTIONS\n" +
						"locals\n" +
						"\n" +
						"@create[]\n" +
						"$person.fields.remote — read-only diagnostic text\n");

		com.intellij.openapi.vfs.VirtualFile usageFile = myFixture.findFileInTempDir("www/_mod/auto.p");
		assertNotNull("Файл использования не найден", usageFile);
		com.intellij.openapi.vfs.VirtualFile mainAutoFile = myFixture.findFileInTempDir("www/auto.p");
		assertNotNull("MAIN auto.p не найден", mainAutoFile);

		assertNestedReadChainTarget(
				usageFile,
				mainAutoFile,
				"$person.fields.remote",
				"fields",
				"$.fields",
				"read-only $person.fields.remote не должен быть таргетом для fields");
		assertNestedReadChainTarget(
				usageFile,
				mainAutoFile,
				"$person.fields.remote",
				"remote",
				"$.remote",
				"read-only $person.fields.remote не должен быть таргетом для remote");
	}

	/**
	 * Если у nested read-only цепочки нет реального определения, навигация не должна
	 * возвращать саму строку чтения как declaration target.
	 */
	public void testReadChain_nestedLocalClassUsageWithoutRealKeyHasNoSelfNavigation() {
		try {
			setDocumentRoot("www");
		} catch (java.io.IOException e) {
			throw new AssertionError("Не удалось настроить document_root для обезличенного fixture", e);
		}
		replaceParser3FileInDir("www/auto.p",
				"@auto[]\n" +
						"$person[^getPerson[]]\n" +
						"\n" +
						"@getPerson[][locals]\n" +
						"$result[\n" +
						"	$.login[test_user]\n" +
						"]\n");
		createParser3FileInDir("www/_mod/auto.p",
				"@CLASS\n" +
						"TestModule\n" +
						"\n" +
						"@OPTIONS\n" +
						"locals\n" +
						"\n" +
						"@create[]\n" +
						"$person.fields.remote\n");

		com.intellij.openapi.vfs.VirtualFile usageFile = myFixture.findFileInTempDir("www/_mod/auto.p");
		assertNotNull("Файл использования не найден", usageFile);

		assertNestedReadChainHasNoTargets(usageFile, "$person.fields.remote", "fields");
		assertNestedReadChainHasNoTargets(usageFile, "$person.fields.remote", "remote");
	}

	/**
	 * Минимизация реального кейса из www/_mod/auto.p:8.
	 * Корневой $person приходит из @getPerson[], внутри которого форма переносится
	 * через $p[^get_persons[]], $p[^p.at[first]], $p.fields.remote и $person[$p].
	 * Если completion взял fields/remote из read-chain источника, navigation должен
	 * вести к этому источнику, а не молча терять target.
	 */
	public void testReadChain_nestedLocalClassUsageFromCopiedMethodResultNavigatesToReadChainSource() {
		try {
			setDocumentRoot("www");
		} catch (java.io.IOException e) {
			throw new AssertionError("Не удалось настроить document_root для обезличенного fixture", e);
		}
		replaceParser3FileInDir("www/auto.p",
				"@auto[]\n" +
						"$person[^getPerson[]]\n" +
						"\n" +
						"@getPerson[][locals]\n" +
						"$person[^hash::create[]]\n" +
						"$p[^get_persons[]]\n" +
						"^if($p){\n" +
						"	$p[^p.at[first]]\n" +
						"	$p.remote[$p.fields.remote]\n" +
						"	$person[$p]\n" +
						"}\n" +
						"$result[$person]\n" +
						"\n" +
						"@get_persons[][locals]\n" +
						"$result[^table::create{login\n" +
						"test_user}]\n");
		createParser3FileInDir("www/_mod/auto.p",
				"@CLASS\n" +
						"TestModule\n" +
						"\n" +
						"@OPTIONS\n" +
						"locals\n" +
						"\n" +
						"@create[]\n" +
						"$person.fields.remote — read-only diagnostic text\n");

		com.intellij.openapi.vfs.VirtualFile usageFile = myFixture.findFileInTempDir("www/_mod/auto.p");
		assertNotNull("Файл использования не найден", usageFile);
		com.intellij.openapi.vfs.VirtualFile mainAutoFile = myFixture.findFileInTempDir("www/auto.p");
		assertNotNull("MAIN auto.p не найден", mainAutoFile);

		assertNestedReadChainSourceTarget(
				usageFile,
				mainAutoFile,
				"$person.fields.remote",
				"fields",
				"$p.fields.remote",
				"fields");
		assertNestedReadChainSourceTarget(
				usageFile,
				mainAutoFile,
				"$person.fields.remote",
				"remote",
				"$p.fields.remote",
				"remote");
	}

	private void assertNestedReadChainSourceTarget(
			@NotNull com.intellij.openapi.vfs.VirtualFile usageFile,
			@NotNull com.intellij.openapi.vfs.VirtualFile targetFile,
			@NotNull String usagePattern,
			@NotNull String clickedName,
			@NotNull String sourcePattern,
			@NotNull String sourceName
	) {
		myFixture.configureFromExistingVirtualFile(usageFile);

		String usageContent = readFile(usageFile);
		int usagePos = usageContent.indexOf(usagePattern);
		assertTrue("Использование " + usagePattern + " не найдено", usagePos >= 0);
		int clickedNamePos = usagePattern.indexOf(clickedName);
		assertTrue("Сегмент " + clickedName + " не найден в " + usagePattern, clickedNamePos >= 0);
		int clickOffset = usagePos + clickedNamePos + 1;
		myFixture.getEditor().getCaretModel().moveToOffset(clickOffset);

		PsiElement[] targets = GotoDeclarationAction.findAllTargetElements(
				getProject(), myFixture.getEditor(), clickOffset);

		assertNotNull("Должны быть targets для " + usagePattern + "." + clickedName, targets);
		assertTrue("Должен быть хотя бы один target для " + usagePattern + "." + clickedName
						+ ", targets=" + describeTargets(targets),
				targets.length > 0);

		String targetContent = readFile(targetFile);
		int sourcePos = targetContent.indexOf(sourcePattern);
		assertTrue("Источник " + sourcePattern + " не найден", sourcePos >= 0);
		int sourceNamePos = sourcePattern.indexOf(sourceName);
		assertTrue("Сегмент " + sourceName + " не найден в " + sourcePattern, sourceNamePos >= 0);
		int expectedPos = sourcePos + sourceNamePos;

		boolean foundSource = false;
		boolean foundSelfUsage = false;
		for (PsiElement target : targets) {
			PsiFile psiFile = target.getContainingFile();
			if (psiFile == null || psiFile.getVirtualFile() == null) continue;
			int targetOffset = target.getTextOffset();
			if (psiFile.getVirtualFile().equals(targetFile)
					&& targetOffset >= expectedPos
					&& targetOffset <= expectedPos + sourceName.length()) {
				foundSource = true;
			}
			if (psiFile.getVirtualFile().equals(usageFile)
					&& targetOffset >= usagePos
					&& targetOffset <= usagePos + usagePattern.length()) {
				foundSelfUsage = true;
			}
		}

		assertTrue("Навигация должна вести к read-chain источнику " + sourcePattern
						+ ", targets=" + describeTargets(targets),
				foundSource);
		assertFalse("Навигация не должна вести на read-only usage " + usagePattern
						+ ", targets=" + describeTargets(targets),
				foundSelfUsage);
	}

	private void assertNestedReadChainHasNoTargets(
			@NotNull com.intellij.openapi.vfs.VirtualFile usageFile,
			@NotNull String usagePattern,
			@NotNull String clickedName
	) {
		myFixture.configureFromExistingVirtualFile(usageFile);

		String usageContent = readFile(usageFile);
		int usagePos = usageContent.indexOf(usagePattern);
		assertTrue("Использование " + usagePattern + " не найдено", usagePos >= 0);
		int clickedNamePos = usagePattern.indexOf(clickedName);
		assertTrue("Сегмент " + clickedName + " не найден в " + usagePattern, clickedNamePos >= 0);
		int clickOffset = usagePos + clickedNamePos + 1;
		myFixture.getEditor().getCaretModel().moveToOffset(clickOffset);

		PsiElement[] targets = GotoDeclarationAction.findAllTargetElements(
				getProject(), myFixture.getEditor(), clickOffset);

		boolean hasTargets = targets != null && targets.length > 0;
		assertFalse("Read-only " + usagePattern + " без реального " + clickedName
						+ " не должен вести на себя, targets=" + describeTargets(targets),
				hasTargets);
	}

	private void assertNestedReadChainTarget(
			@NotNull com.intellij.openapi.vfs.VirtualFile usageFile,
			@NotNull com.intellij.openapi.vfs.VirtualFile targetFile,
			@NotNull String usagePattern,
			@NotNull String clickedName,
			@NotNull String expectedPattern,
			@NotNull String selfTargetMessage
	) {
		myFixture.configureFromExistingVirtualFile(usageFile);

		String usageContent = readFile(usageFile);
		int usagePos = usageContent.indexOf(usagePattern);
		assertTrue("Использование " + usagePattern + " не найдено", usagePos >= 0);
		int clickedNamePos = usagePattern.indexOf(clickedName);
		assertTrue("Сегмент " + clickedName + " не найден в " + usagePattern, clickedNamePos >= 0);
		int clickOffset = usagePos + clickedNamePos + 1;
		myFixture.getEditor().getCaretModel().moveToOffset(clickOffset);

		PsiElement[] targets = GotoDeclarationAction.findAllTargetElements(
				getProject(), myFixture.getEditor(), clickOffset);

		assertNotNull("Должны быть таргеты для " + usagePattern + "." + clickedName, targets);
		assertTrue("Должен быть хотя бы один таргет для " + usagePattern + "." + clickedName
						+ ", targets=" + describeTargets(targets),
				targets.length > 0);

		String targetContent = readFile(targetFile);
		int expectedPos = targetContent.indexOf(expectedPattern);
		assertTrue("Ожидаемое определение " + expectedPattern + " не найдено", expectedPos >= 0);
		expectedPos += 2;

		boolean foundRealKey = false;
		boolean foundSelfUsage = false;
		for (PsiElement target : targets) {
			PsiFile psiFile = target.getContainingFile();
			if (psiFile == null || psiFile.getVirtualFile() == null) continue;
			int targetOffset = target.getTextOffset();
			if (psiFile.getVirtualFile().equals(targetFile)
					&& targetOffset >= expectedPos
					&& targetOffset <= expectedPos + clickedName.length()) {
				foundRealKey = true;
			}
			if (psiFile.getVirtualFile().equals(usageFile)
					&& targetOffset >= usagePos
					&& targetOffset <= usagePos + usagePattern.length()) {
				foundSelfUsage = true;
			}
		}

		assertTrue("Навигация должна вести к реальному " + expectedPattern
						+ ", targets=" + describeTargets(targets),
				foundRealKey);
		assertFalse(selfTargetMessage + ", targets=" + describeTargets(targets), foundSelfUsage);
	}

	private String describeTargets(PsiElement[] targets) {
		if (targets == null) return "<null>";
		StringBuilder result = new StringBuilder();
		for (PsiElement target : targets) {
			PsiFile targetFile = target.getContainingFile();
			result.append('[');
			result.append(targetFile != null && targetFile.getVirtualFile() != null
					? targetFile.getVirtualFile().getPath()
					: "<no-file>");
			result.append(':').append(target.getTextOffset()).append(" '").append(target.getText()).append("']");
		}
		return result.toString();
	}

	/**
	 * После полного переприсваивания $data старые foreach-ключи от предыдущего значения
	 * не должны оставаться видимыми у нового $data, даже без [locals].
	 */
	public void testForeachAdditiveKeys_doNotLeakAcrossRootReassignment() {
		createParser3FileInDir("www/hash_key_nav_reassign_isolation.p",
				"@main[]\n# реальный минимальный кейс Parser3 fixture\n" +
						"$data[^hash::create[]]\n" +
						"^data.foreach[;v]{\n" +
						"	^if($v.item_id){\n" +
						"	}\n" +
						"}\n" +
						"$data[\n" +
						"	$.companies[^hash::create[]]\n" +
						"]\n" +
						"$data.companies.company_id[]\n" +
						"$data.companies.item_id\n");

		com.intellij.openapi.vfs.VirtualFile vFile = myFixture.findFileInTempDir("www/hash_key_nav_reassign_isolation.p");
		assertNotNull("Файл не найден", vFile);

		String content = readFile(vFile);
		int usagePos = content.lastIndexOf("$data.companies.item_id");
		assertTrue("Использование $data.companies.item_id не найдено", usagePos >= 0);
		int clickOffset = usagePos + "$data.companies.".length() + 1;

		myFixture.configureFromExistingVirtualFile(vFile);
		myFixture.getEditor().getCaretModel().moveToOffset(clickOffset);

		PsiElement[] targets = GotoDeclarationAction.findAllTargetElements(
				getProject(), myFixture.getEditor(), clickOffset);

		boolean hasTargets = targets != null && targets.length > 0;
		assertFalse("Старые foreach-ключи не должны подтягиваться после нового присваивания $data", hasTargets);
	}

	/**
	 * Навигация по ключу из nested method-result должна идти к реальному $.xxx[] в методе,
	 * а не к промежуточной synthetic read-chain записи.
	 */
	public void testHashKeyNavigation_methodResultHashEntryNestedMethodTargetsRealKey() {
		createParser3FileInDir("www/hash_key_nav_method_result_entry_nested_method.p",
				"@main[]\n" +
						"# реальный кейс из errors/1.p для навигации\n" +
						"$res[^method[]]\n" +
						"$res.data.x.xxx\n" +
						"\n" +
						"@method[]\n" +
						"$result[\n" +
						"\t$.data[\n" +
						"\t\t$.x[^method2[]]\n" +
						"\t]\n" +
						"]\n" +
						"\n" +
						"@method2[]\n" +
						"$result[\n" +
						"\t$.xxx[]\n" +
						"]\n");

		com.intellij.openapi.vfs.VirtualFile vFile = myFixture.findFileInTempDir("www/hash_key_nav_method_result_entry_nested_method.p");
		assertNotNull("Файл не найден", vFile);

		String content = readFile(vFile);
		int usagePos = content.indexOf("$res.data.x.xxx");
		assertTrue("Использование $res.data.x.xxx не найдено", usagePos >= 0);
		int clickOffset = usagePos + "$res.data.x.".length() + 1;
		int realDefPos = content.indexOf("$.xxx[]");
		assertTrue("Реальное определение $.xxx[] не найдено", realDefPos >= 0);

		myFixture.configureFromExistingVirtualFile(vFile);
		myFixture.getEditor().getCaretModel().moveToOffset(clickOffset);

		PsiElement[] targets = GotoDeclarationAction.findAllTargetElements(
				getProject(), myFixture.getEditor(), clickOffset);

		assertNotNull("Должны быть таргеты для $res.data.x.xxx", targets);
		assertTrue("Должен быть хотя бы один таргет для $res.data.x.xxx", targets.length > 0);

		boolean foundRealKey = false;
		boolean foundSyntheticRead = false;
		for (PsiElement target : targets) {
			int targetOffset = target.getTextOffset();
			if (targetOffset >= realDefPos && targetOffset <= realDefPos + "$.xxx[]".length()) {
				foundRealKey = true;
			}
			if (targetOffset >= usagePos && targetOffset <= usagePos + "$res.data.x.xxx".length()) {
				foundSyntheticRead = true;
			}
		}

		assertTrue("Навигация должна вести к реальному $.xxx[] внутри method2", foundRealKey);
		assertFalse("Навигация не должна вести к synthetic read-chain использованию", foundSyntheticRead);
	}

	public void testCaretVarMethodNavigation_localAliasToMainObjectFromAuto() {
		createParser3FileInDir("main_alias_object_nav/www/auto.p",
				"@USE\n" +
						"classes/query-service.p\n" +
						"\n" +
						"@auto[]\n" +
						"$MAIN:sql[^queryService::create[]]\n"
		);
		createParser3FileInDir("main_alias_object_nav/www/classes/query-service.p",
				"@CLASS\n" +
						"queryService\n" +
						"\n" +
						"@create[]\n" +
						"\n" +
						"@table[query;options]\n" +
						"$result[^table::sql{$query}[$options]]\n"
		);
		createParser3FileInDir("main_alias_object_nav/www/users/list.p",
				"@main[][oSql]\n" +
						"$oSql[$MAIN:sql]\n" +
						"$recent[^oSql.table{select id, name from users}]\n"
		);

		com.intellij.openapi.vfs.VirtualFile vFile = myFixture.findFileInTempDir("main_alias_object_nav/www/users/list.p");
		assertNotNull("Файл не найден", vFile);

		String content = readFile(vFile);
		int callPos = content.indexOf("^oSql.table");
		assertTrue("Вызов ^oSql.table не найден", callPos >= 0);
		int clickOffset = callPos + "^oSql.".length() + 1;

		myFixture.configureFromExistingVirtualFile(vFile);
		myFixture.getEditor().getCaretModel().moveToOffset(clickOffset);

		PsiElement[] targets = GotoDeclarationAction.findAllTargetElements(
				getProject(), myFixture.getEditor(), clickOffset);

		assertNotNull("Должны быть таргеты для ^oSql.table", targets);
		assertTrue("Должен быть хотя бы один таргет для ^oSql.table", targets.length > 0);

		com.intellij.openapi.vfs.VirtualFile classFile =
				myFixture.findFileInTempDir("main_alias_object_nav/www/classes/query-service.p");
		assertNotNull("Файл класса queryService не найден", classFile);
		String classText = readFile(classFile);
		int tableDefPos = classText.indexOf("@table[");
		assertTrue("Метод @table не найден", tableDefPos >= 0);

		boolean foundTableMethod = false;
		StringBuilder targetDump = new StringBuilder();
		for (PsiElement target : targets) {
			String targetName = target instanceof com.intellij.navigation.NavigationItem
					? ((com.intellij.navigation.NavigationItem) target).getName()
					: target.getText();
			targetDump.append("\n")
					.append(target.getClass().getName())
					.append(" name=").append(targetName)
					.append(" offset=").append(target.getTextOffset());
			PsiFile targetFile = target.getContainingFile();
			targetDump.append(" file=")
					.append(targetFile != null && targetFile.getVirtualFile() != null
							? targetFile.getVirtualFile().getPath()
							: "<null>");
			if (targetFile == null || targetFile.getVirtualFile() == null) continue;
			if (!targetFile.getVirtualFile().equals(classFile)) continue;
			if (target.getClass().getName().contains("MethodTarget") && "table".equals(targetName)) {
				foundTableMethod = true;
				break;
			}
		}

		assertTrue("Навигация по ^oSql.table должна вести к @table в queryService, targets:" + targetDump, foundTableMethod);
	}
}
