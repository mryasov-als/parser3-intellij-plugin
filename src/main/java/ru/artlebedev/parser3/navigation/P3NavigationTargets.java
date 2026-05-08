package ru.artlebedev.parser3.navigation;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.pom.Navigatable;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.impl.FakePsiElement;
import ru.artlebedev.parser3.icons.Parser3Icons;
import ru.artlebedev.parser3.index.P3MethodCallFileIndex;
import ru.artlebedev.parser3.index.P3MethodCallIndex;
import ru.artlebedev.parser3.model.P3ClassDeclaration;
import ru.artlebedev.parser3.model.P3MethodDeclaration;
import ru.artlebedev.parser3.utils.Parser3PathFormatter;

import javax.swing.*;

/**
 * Классы-обёртки для отображения результатов навигации в popup.
 * Используются в P3GotoDeclarationHandler и P3UseNavigationHandler.
 */
final class P3NavigationTargets {

	private P3NavigationTargets() {}

	static PsiElement createVariableTarget(
			PsiElement target,
			VirtualFile file,
			int offset
	) {
		return new VariableTarget(target, file, offset);
	}

	/**
	 * Обёртка для методов в popup с правильным форматированием.
	 */
	static class MethodTarget extends FakePsiElement implements Navigatable {
		private final PsiElement target;
		private final P3MethodDeclaration declaration;
		private final String methodName;

		MethodTarget(PsiElement target, P3MethodDeclaration decl, String methodName) {
			this.target = target;
			this.declaration = decl;
			this.methodName = methodName;
		}

		@Override
		public void navigate(boolean requestFocus) {
			new OpenFileDescriptor(target.getProject(), declaration.getFile(), declaration.getOffset())
					.navigate(requestFocus);
		}

		@Override
		public boolean canNavigate() { return true; }

		@Override
		public boolean canNavigateToSource() { return true; }

		@Override
		public PsiElement getParent() { return target.getContainingFile(); }

		@Override
		public PsiFile getContainingFile() { return target.getContainingFile(); }

		@Override
		public String getName() { return methodName; }

		@Override
		public String getPresentableText() { return "@" + methodName; }

		@Override
		public String getLocationString() {
			return Parser3PathFormatter.formatFilePathWithLineForUI(
					declaration.getFile(), target.getProject(), declaration.getOffset());
		}

		@Override
		public Icon getIcon(boolean unused) { return Parser3Icons.FileMethod; }
	}

	/**
	 * Обёртка для классов в popup.
	 */
	static class ClassTarget extends FakePsiElement implements Navigatable {
		private final PsiElement target;
		private final P3ClassDeclaration declaration;

		ClassTarget(PsiElement target, P3ClassDeclaration decl) {
			this.target = target;
			this.declaration = decl;
		}

		@Override
		public void navigate(boolean requestFocus) {
			new OpenFileDescriptor(target.getProject(), declaration.getFile(), declaration.getStartOffset())
					.navigate(requestFocus);
		}

		@Override
		public boolean canNavigate() { return true; }

		@Override
		public boolean canNavigateToSource() { return true; }

		@Override
		public PsiElement getParent() {
			return PsiManager.getInstance(target.getProject()).findFile(declaration.getFile());
		}

		@Override
		public PsiFile getContainingFile() {
			return PsiManager.getInstance(target.getProject()).findFile(declaration.getFile());
		}

		@Override
		public String getName() { return declaration.getName(); }

		@Override
		public String getPresentableText() { return declaration.getName(); }

		@Override
		public String getLocationString() {
			return Parser3PathFormatter.formatFilePathWithLineForUI(
					declaration.getFile(), target.getProject(), declaration.getStartOffset());
		}

		@Override
		public Icon getIcon(boolean unused) { return Parser3Icons.FileClass; }
	}

	/**
	 * Обёртка для классов-наследников в popup навигации.
	 */
	static class ChildClassTarget extends FakePsiElement implements Navigatable {
		private final PsiElement target;
		private final P3ClassDeclaration childClass;
		private final String parentClassName;

		ChildClassTarget(PsiElement target, P3ClassDeclaration childClass, String parentClassName) {
			this.target = target;
			this.childClass = childClass;
			this.parentClassName = parentClassName;
		}

		@Override
		public void navigate(boolean requestFocus) {
			new OpenFileDescriptor(target.getProject(), childClass.getFile(), childClass.getStartOffset())
					.navigate(requestFocus);
		}

		@Override
		public boolean canNavigate() { return true; }

		@Override
		public boolean canNavigateToSource() { return true; }

		@Override
		public PsiElement getParent() { return target.getContainingFile(); }

		@Override
		public PsiFile getContainingFile() { return target.getContainingFile(); }

		@Override
		public String getName() { return childClass.getName(); }

		@Override
		public String getPresentableText() {
			return childClass.getName() + " @BASE " + parentClassName;
		}

		@Override
		public String getLocationString() {
			return Parser3PathFormatter.formatFilePathWithLineForUI(
					childClass.getFile(), target.getProject(), childClass.getStartOffset());
		}

		@Override
		public Icon getIcon(boolean unused) { return Parser3Icons.FileClass; }
	}

	/**
	 * Обёртка для использований класса в popup навигации.
	 * Показывает вызовы типа ^ClassName::method[] или ^ClassName:method[]
	 */
	static class ClassUsageTarget extends FakePsiElement implements Navigatable {
		private final PsiElement target;
		private final P3MethodCallIndex.ClassUsageResult usageResult;

		ClassUsageTarget(PsiElement target, P3MethodCallIndex.ClassUsageResult usageResult) {
			this.target = target;
			this.usageResult = usageResult;
		}

		@Override
		public void navigate(boolean requestFocus) {
			new OpenFileDescriptor(target.getProject(), usageResult.file, usageResult.callInfo.offset)
					.navigate(requestFocus);
		}

		@Override
		public boolean canNavigate() { return true; }

		@Override
		public boolean canNavigateToSource() { return true; }

		@Override
		public PsiElement getParent() { return target.getContainingFile(); }

		@Override
		public PsiFile getContainingFile() { return target.getContainingFile(); }

		@Override
		public String getName() { return usageResult.callInfo.targetClassName; }

		@Override
		public String getPresentableText() {
			P3MethodCallFileIndex.CallType callType = usageResult.callInfo.callType;
			P3MethodCallFileIndex.BracketType bracket = usageResult.callInfo.bracketType;
			String open = bracket.getOpen();
			String close = bracket.getClose();
			String methodName = usageResult.methodName;
			String prefix = usageResult.callInfo.isInComment ? "# " : "";

			if (callType == P3MethodCallFileIndex.CallType.BASE) {
				return prefix + "^BASE:" + methodName + open + close;
			}
			if (callType == P3MethodCallFileIndex.CallType.SELF) {
				return prefix + "^self." + methodName + open + close;
			}

			String className = usageResult.callInfo.targetClassName;
			String separator = (callType == P3MethodCallFileIndex.CallType.CLASS_CONSTRUCTOR) ? "::" : ":";
			return prefix + "^" + className + separator + methodName + open + close;
		}

		@Override
		public String getLocationString() {
			return Parser3PathFormatter.formatFilePathWithLineForUI(
					usageResult.file, target.getProject(), usageResult.callInfo.offset);
		}

		@Override
		public Icon getIcon(boolean unused) { return Parser3Icons.FileMethod; }
	}

	/**
	 * Обёртка для вызовов методов в popup навигации.
	 * Показывает информацию о вызове: тип вызова, файл, позицию.
	 */
	static class MethodCallTarget extends FakePsiElement implements Navigatable {
		private final PsiElement target;
		private final P3MethodCallIndex.MethodCallResult callResult;
		private final String methodName;

		MethodCallTarget(PsiElement target, P3MethodCallIndex.MethodCallResult callResult, String methodName) {
			this.target = target;
			this.callResult = callResult;
			this.methodName = methodName;
		}

		@Override
		public void navigate(boolean requestFocus) {
			new OpenFileDescriptor(target.getProject(), callResult.file, callResult.callInfo.offset)
					.navigate(requestFocus);
		}

		@Override
		public boolean canNavigate() { return true; }

		@Override
		public boolean canNavigateToSource() { return true; }

		@Override
		public PsiElement getParent() { return target.getContainingFile(); }

		@Override
		public PsiFile getContainingFile() { return target.getContainingFile(); }

		@Override
		public String getName() { return methodName; }

		@Override
		public String getPresentableText() {
			P3MethodCallFileIndex.CallType callType = callResult.callInfo.callType;
			P3MethodCallFileIndex.BracketType bracket = callResult.callInfo.bracketType;
			String targetClass = callResult.callInfo.targetClassName;
			String open = bracket.getOpen();
			String close = bracket.getClose();
			String prefix = callResult.callInfo.isInComment ? "# " : "";

			switch (callType) {
				case SELF:
					return prefix + "^self." + methodName + open + close;
				case MAIN:
					return prefix + "^MAIN:" + methodName + open + close;
				case BASE:
					return prefix + "^BASE:" + methodName + open + close;
				case CLASS_STATIC:
					return prefix + "^" + targetClass + ":" + methodName + open + close;
				case CLASS_CONSTRUCTOR:
					return prefix + "^" + targetClass + "::" + methodName + open + close;
				case SIMPLE:
				default:
					return prefix + "^" + methodName + open + close;
			}
		}

		@Override
		public String getLocationString() {
			return Parser3PathFormatter.formatFilePathWithLineForUI(
					callResult.file, target.getProject(), callResult.callInfo.offset);
		}

		@Override
		public Icon getIcon(boolean unused) { return Parser3Icons.FileMethod; }
	}

	/**
	 * Обёртка для навигации по переменным.
	 * Если target уже виден в текущем редакторе — не меняет скролл (только ставит каретку).
	 */
	static class VariableTarget extends FakePsiElement implements Navigatable {
		private final PsiElement target;
		private final VirtualFile file;
		private final int offset;

		VariableTarget(PsiElement target, VirtualFile file, int offset) {
			this.target = target;
			this.file = file;
			this.offset = offset;
		}

		@Override
		public void navigate(boolean requestFocus) {
			if (tryNavigateWithoutScrollJump()) {
				return;
			}
			new OpenFileDescriptor(target.getProject(), file, offset).navigate(requestFocus);
		}

		private boolean tryNavigateWithoutScrollJump() {
			Editor editor = FileEditorManager.getInstance(target.getProject()).getSelectedTextEditor();
			if (editor == null) {
				return false;
			}
			VirtualFile editorFile = FileDocumentManager.getInstance().getFile(editor.getDocument());
			if (editorFile == null || !editorFile.equals(file)) {
				return false;
			}

			int clampedOffset = Math.max(0, Math.min(offset, editor.getDocument().getTextLength()));
			if (!isOffsetVisible(editor, clampedOffset)) {
				return false;
			}

			int oldVert = editor.getScrollingModel().getVerticalScrollOffset();
			int oldHor = editor.getScrollingModel().getHorizontalScrollOffset();
			editor.getCaretModel().moveToOffset(clampedOffset);
			editor.getScrollingModel().scrollToCaret(ScrollType.MAKE_VISIBLE);
			editor.getScrollingModel().scrollVertically(oldVert);
			editor.getScrollingModel().scrollHorizontally(oldHor);
			return true;
		}

		private boolean isOffsetVisible(Editor editor, int targetOffset) {
			java.awt.Rectangle visible = editor.getScrollingModel().getVisibleArea();
			java.awt.Point point = editor.visualPositionToXY(editor.offsetToVisualPosition(targetOffset));
			int lineHeight = editor.getLineHeight();
			return point.y >= visible.y && (point.y + lineHeight) <= (visible.y + visible.height);
		}

		@Override
		public boolean canNavigate() { return true; }

		@Override
		public boolean canNavigateToSource() { return true; }

		@Override
		public PsiElement getParent() { return target.getContainingFile(); }

		@Override
		public PsiFile getContainingFile() { return target.getContainingFile(); }

		@Override
		public String getText() { return target.getText(); }

		@Override
		public int getTextOffset() { return offset; }

		@Override
		public PsiElement getNavigationElement() { return target; }

		@Override
		public String getName() { return target.getText(); }

		@Override
		public String getPresentableText() { return target.getText(); }

		@Override
		public Icon getIcon(boolean unused) { return Parser3Icons.FileVariable; }
	}
}
