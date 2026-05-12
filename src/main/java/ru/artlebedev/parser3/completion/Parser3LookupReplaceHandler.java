package ru.artlebedev.parser3.completion;

import com.intellij.codeInsight.lookup.Lookup;
import com.intellij.codeInsight.lookup.LookupManager;
import com.intellij.codeInsight.lookup.impl.LookupImpl;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.IdeActions;
import com.intellij.openapi.editor.Caret;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.actionSystem.EditorActionManager;
import com.intellij.openapi.editor.actionSystem.EditorActionHandler;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.artlebedev.parser3.utils.Parser3PsiUtils;

/**
 * Запрещает Tab подтверждать активный completion в Parser3.
 */
public final class Parser3LookupReplaceHandler extends EditorActionHandler {

	private final EditorActionHandler originalHandler;

	public Parser3LookupReplaceHandler(@NotNull EditorActionHandler originalHandler) {
		this.originalHandler = originalHandler;
	}

	@Override
	protected void doExecute(
			@NotNull Editor editor,
			@Nullable Caret caret,
			@NotNull DataContext dataContext
	) {
		Project project = editor.getProject();
		if (project == null || !isParser3File(project, editor)) {
			executeOriginal(editor, caret, dataContext);
			return;
		}

		Lookup activeLookup = LookupManager.getActiveLookup(editor);
		if (activeLookup instanceof LookupImpl) {
			LookupImpl lookup = (LookupImpl) activeLookup;
			if (!lookup.isLookupDisposed()) {
				lookup.hideLookup(true);
				executeEditorTab(editor, caret, dataContext);
				return;
			}
		}

		executeEditorTab(editor, caret, dataContext);
	}

	private static boolean isParser3File(@NotNull Project project, @NotNull Editor editor) {
		PsiFile psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());
		return Parser3PsiUtils.isParser3File(psiFile);
	}

	private void executeOriginal(
			@NotNull Editor editor,
			@Nullable Caret caret,
			@NotNull DataContext dataContext
	) {
		if (originalHandler != null) {
			originalHandler.execute(editor, caret, dataContext);
		}
	}

	private static void executeEditorTab(
			@NotNull Editor editor,
			@Nullable Caret caret,
			@NotNull DataContext dataContext
	) {
		EditorActionHandler tabHandler = EditorActionManager.getInstance()
				.getActionHandler(IdeActions.ACTION_EDITOR_TAB);
		if (tabHandler != null) {
			tabHandler.execute(editor, caret, dataContext);
		}
	}
}
