package ru.artlebedev.parser3;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.fileTypes.PlainTextFileType;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.tree.IElementType;
import com.intellij.testFramework.LightVirtualFile;
import com.intellij.testFramework.PsiTestUtil;
import com.intellij.util.indexing.FileBasedIndex;
import ru.artlebedev.parser3.index.P3ClassFileIndex;
import ru.artlebedev.parser3.index.P3CssClassFileIndex;
import ru.artlebedev.parser3.index.P3IndexExceptionUtil;
import ru.artlebedev.parser3.index.P3IndexableSetContributor;
import ru.artlebedev.parser3.index.P3IndexMaintenance;
import ru.artlebedev.parser3.index.P3MethodCallFileIndex;
import ru.artlebedev.parser3.index.P3MethodFileIndex;
import ru.artlebedev.parser3.index.P3UseFileIndex;
import ru.artlebedev.parser3.index.P3VariableFileIndex;
import ru.artlebedev.parser3.lexer.Parser3Lexer;
import ru.artlebedev.parser3.settings.Parser3SqlInjectionsService;
import ru.artlebedev.parser3.utils.Parser3FileUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Regression-тесты для устойчивости текстовых индексов Parser3.
 */
public class x16_IndexControlFlowTest extends Parser3TestCase {

	public void testIndexExceptionUtilRethrowsCannotReadException() {
		try {
			P3IndexExceptionUtil.rethrowIfControlFlow(new ReadAction.CannotReadException());
			fail("CannotReadException должен пробрасываться, а не логироваться индексатором");
		} catch (ReadAction.CannotReadException expected) {
			// Ожидаемое поведение: IntelliJ сама перезапустит read action.
		}
	}

	public void testIndexExceptionUtilRethrowsProcessCanceledException() {
		try {
			P3IndexExceptionUtil.rethrowIfControlFlow(new ProcessCanceledException());
			fail("ProcessCanceledException должен пробрасываться, а не логироваться индексатором");
		} catch (ProcessCanceledException expected) {
			// Ожидаемое поведение: отмена индексации не является ошибкой плагина.
		}
	}

	public void testIndexExceptionUtilKeepsRegularExceptionsForLogging() {
		P3IndexExceptionUtil.rethrowIfControlFlow(new IllegalArgumentException("real index error"));
	}

	public void testIndexFiltersAcceptParser3ExtensionsWhenFileTypeIsPlainText() {
		LightVirtualFile file = new LightVirtualFile("sample.p", PlainTextFileType.INSTANCE, "@main[]\n");

		assertTrue("Расширение .p должно считаться Parser3 даже при сбитой привязке file type",
				Parser3FileUtils.isParser3File(file));
		assertTrue("Индекс методов должен принимать .p по расширению",
				new P3MethodFileIndex().getInputFilter().acceptInput(file));
		assertTrue("Индекс классов должен принимать .p по расширению",
				new P3ClassFileIndex().getInputFilter().acceptInput(file));
		assertTrue("Индекс USE должен принимать .p по расширению",
				new P3UseFileIndex().getInputFilter().acceptInput(file));
		assertTrue("Индекс вызовов методов должен принимать .p по расширению",
				new P3MethodCallFileIndex().getInputFilter().acceptInput(file));
		assertTrue("Индекс переменных должен принимать .p по расширению",
				new P3VariableFileIndex().getInputFilter().acceptInput(file));
		assertTrue("Индекс CSS-классов должен принимать .p по расширению",
				new P3CssClassFileIndex().getInputFilter().acceptInput(file));
	}

	public void testIndexFiltersAcceptParser3P3ExtensionWhenFileTypeIsPlainText() {
		LightVirtualFile file = new LightVirtualFile("sample.p3", PlainTextFileType.INSTANCE, "@main[]\n");

		assertTrue("Расширение .p3 должно считаться Parser3 даже при сбитой привязке file type",
				Parser3FileUtils.isParser3File(file));
		assertTrue("Индекс методов должен принимать .p3 по расширению",
				new P3MethodFileIndex().getInputFilter().acceptInput(file));
	}

	public void testIndexFiltersRejectNonParser3PlainTextFile() {
		LightVirtualFile file = new LightVirtualFile("sample.txt", PlainTextFileType.INSTANCE, "@main[]\n");

		assertFalse("Обычный .txt не должен попадать в Parser3-индексы",
				Parser3FileUtils.isParser3File(file));
		assertFalse("Индекс методов не должен принимать .txt",
				new P3MethodFileIndex().getInputFilter().acceptInput(file));
	}

	public void testIndexableSetContributorAcceptsRegisteredParser3FilesOnly() {
		P3IndexableSetContributor contributor = new P3IndexableSetContributor();
		VirtualFile root = getProject().getBaseDir();
		VirtualFile parser3File = createProjectBaseFile("sample.p", "@main[]\n");
		VirtualFile textFile = createProjectBaseFile("sample.txt", "@main[]\n");

		assertTrue("Дополнительный корень должен принимать зарегистрированный Parser3 file type",
				contributor.acceptFile(parser3File, root, getProject()));
		assertFalse("Обычные текстовые файлы не должны попадать в Parser3-индексацию",
				contributor.acceptFile(textFile, root, getProject()));
		assertTrue("Открытая папка проекта всегда должна добавляться в Parser3-индексацию",
				contributor.getAdditionalProjectRootsToIndex(getProject()).contains(getProject().getBaseDir()));
		assertTrue("Глобальные дополнительные корни не должны добавляться без проекта",
				contributor.getAdditionalRootsToIndex().isEmpty());
	}

	public void testProjectParser3FilesSkipsIdeaExcludedDirectories() {
		P3IndexableSetContributor contributor = new P3IndexableSetContributor();
		VirtualFile visibleFile = createProjectBaseFile("www/visible.p", "@visible[]\n");
		VirtualFile excludedFile = createProjectBaseFile("excluded/hidden.p", "@hidden[]\n");
		VirtualFile excludedDir = excludedFile.getParent();

		PsiTestUtil.addContentRoot(getModule(), getProject().getBaseDir());
		PsiTestUtil.addExcludedRoot(getModule(), excludedDir);

		assertTrue("Обычный Parser3-файл внутри корня проекта должен индексироваться",
				Parser3FileUtils.isProjectIndexableParser3File(getProject(), visibleFile));
		assertFalse("Parser3-файл внутри excluded-директории не должен попадать в индексацию",
				Parser3FileUtils.isProjectIndexableParser3File(getProject(), excludedFile));
		assertFalse("Дополнительный корень индексации тоже должен уважать excluded-директории",
				contributor.acceptFile(excludedFile, getProject().getBaseDir(), getProject()));
		assertTrue("Обычный файл должен оставаться в общем списке Parser3-файлов",
				Parser3FileUtils.getProjectParser3Files(getProject()).contains(visibleFile));
		assertFalse("Excluded-файл не должен возвращаться прямым обходом project base",
				Parser3FileUtils.getProjectParser3Files(getProject()).contains(excludedFile));
	}

	public void testParser3LexerRefreshesSqlInjectionPrefixesWithoutTextChange() {
		Parser3SqlInjectionsService service = ApplicationManager.getApplication().getService(Parser3SqlInjectionsService.class);
		List<String> oldPrefixes = service.getNormalizedPrefixesCopy();
		String text = "$list[^self.oSql.table{\n" +
				"\tSELECT id FROM items\n" +
				"}]\n";
		Parser3Lexer lexer = new Parser3Lexer();

		try {
			service.setPrefixes(List.of());
			assertFalse("Без настройки пользовательский SQL-вызов не должен лекситься как SQL_BLOCK",
					collectLexerTokenTypes(lexer, text).contains("SQL_BLOCK"));

			service.setPrefixes(List.of("^self.oSql.table"));
			assertTrue("После изменения настройки тот же текст должен сразу перелекситься как SQL_BLOCK",
					collectLexerTokenTypes(lexer, text).contains("SQL_BLOCK"));
		} finally {
			service.setPrefixes(oldPrefixes);
		}
	}

	public void testDumpPartFileMethodsAreIndexed() {
		VirtualFile file = createParser3FileInDir(
				"www/app/api/actions/test_part.p",
				"@mod_code[][locals]\n" +
						"$data[^json:parse[^taint[as-is][$request:body]]]\n" +
						"$connectData[^get_connect_data[]]\n" +
						"\n" +
						"^connect[$connectData.string]{\n" +
						"\t^void:sql{use `${connectData.db_name}`}\n" +
						"\t$prefix[^math:md5[^math:uuid[]]]\n" +
						"\t$sql[]\n" +
						"\t$_data[^table::sql{select * from `${data.table_name}` limit 1}]\n" +
						"\t$columns[^_data.columns[]]\n" +
						"\t$list[^table::sql{\n" +
						"\t\tSELECT\n" +
						"\t\t\t^columns.menu{`$columns.column`,}\n" +
						"\t\t\t^columns.menu{\n" +
						"\t\t\t\tISNULL(`$columns.column`) as '${columns.column}_${prefix}_is_null'\n" +
						"\t\t\t}[,]\n" +
						"\t\t\tFROM `${data.table_name}`\n" +
						"\t\t\t^if(def $data.where){$data.where[$data.where]}\n" +
						"\t\t\t^if(def ^data.where.trim[]){WHERE ^taint[as-is][$data.where]}\n" +
						"\t\t\tLIMIT $data.offset, $data.limit\n" +
						"\t}]\n" +
						"\t^if($list){\n" +
						"\t\t$sql[^#0A^#0A${sql}INSERT INTO `${data.table_name}` (^columns.menu{`${columns.column}`}[,]) VALUES ^#0A^get_values_string[$columns;$list;$prefix]^;]\n" +
						"\t}\n" +
						"\t$fname[^get_dump_file[$data.dump_id]]\n" +
						"\t^file:lock[${fname}.lock]{\n" +
						"\t\t^sql.save[append;$fname]\n" +
						"\t}\n" +
						"}\n" +
						"\n" +
						"$result[\n" +
						"\t$.ok(true)\n" +
						"]\n" +
						"\n" +
						"@get_values_string[columns;data;prefix]\n" +
						"$s[^#0A]\n" +
						"$result[^data.menu{(^columns.menu{^if($data.[${columns.column}_${prefix}_is_null]){NULL;'^get_taint_value[$data.[$columns.column]]'}^if(^data.line[]%1000==0){^memory:compact[]}}[, ])}[,$s]]\n" +
						"\n" +
						"@get_taint_value[text]\n" +
						"$result[^apply-taint[sql][$text]]\n" +
						"\n" +
						"@get_dump_file[dump_id;file_id]\n" +
						"^if(!def $file_id){$file_id[all]}\n" +
						"$result[^get_dump_dir[]$dump_id/${file_id}.p]\n" +
						"\n" +
						"@get_dump_dir[]\n" +
						"^if(def $MAIN:dump_dir){\n" +
						"\t$result[$MAIN:dump_dir]\n" +
						"\t^if(^result.right(1) ne '/' && ^result.right(1) ne '\\'){$result[${result}/]}\n" +
						"}{\n" +
						"\t$result[_dump/]\n" +
						"}\n"
		);

		assertIndexedMethod(file, "mod_code");
		assertIndexedMethod(file, "get_values_string");
		assertIndexedMethod(file, "get_taint_value");
		assertIndexedMethod(file, "get_dump_file");
		assertIndexedMethod(file, "get_dump_dir");
	}

	public void testIndexedParser3FilesIncludesFilesWithIndexedMethods() {
		VirtualFile file = createParser3FileInDir(
				"www/index.p",
				"@main[]\n" +
						"$result[ok]\n"
		);

		com.intellij.openapi.project.DumbService.getInstance(getProject()).waitForSmartMode();

		assertTrue("Файл с индексированным методом должен попадать в общий список Parser3 файлов",
				P3IndexMaintenance.getIndexedParser3Files(getProject()).contains(file));
	}

	private void assertIndexedMethod(VirtualFile file, String methodName) {
		List<List<P3MethodFileIndex.MethodInfo>> values = FileBasedIndex.getInstance().getValues(
				P3MethodFileIndex.NAME,
				methodName,
				GlobalSearchScope.fileScope(getProject(), file)
		);
		assertFalse("Метод должен индексироваться: " + methodName, values.isEmpty());
		assertFalse("Список MethodInfo не должен быть пустым: " + methodName, values.get(0).isEmpty());
	}

	private VirtualFile createProjectBaseFile(String relativePath, String content) {
		VirtualFile[] result = new VirtualFile[1];
		ApplicationManager.getApplication().runWriteAction(() -> {
			try {
				VirtualFile baseDir = getProject().getBaseDir();
				assertNotNull("У тестового проекта должен быть baseDir", baseDir);
				int slash = relativePath.lastIndexOf('/');
				String dirPath = slash >= 0 ? relativePath.substring(0, slash) : "";
				String fileName = slash >= 0 ? relativePath.substring(slash + 1) : relativePath;
				VirtualFile dir = dirPath.isEmpty() ? baseDir : VfsUtil.createDirectoryIfMissing(baseDir, dirPath);
				VirtualFile file = dir.findChild(fileName);
				if (file == null) {
					file = dir.createChildData(this, fileName);
				}
				VfsUtil.saveText(file, content);
				result[0] = file;
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		});
		return result[0];
	}

	private List<String> collectLexerTokenTypes(Parser3Lexer lexer, String text) {
		List<String> result = new ArrayList<>();
		lexer.start(text, 0, text.length(), 0);
		while (lexer.getTokenType() != null) {
			IElementType type = lexer.getTokenType();
			result.add(type.toString());
			lexer.advance();
		}
		return result;
	}
}
