package ru.artlebedev.parser3.completion;

import com.intellij.codeInsight.completion.*;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.util.ProcessingContext;
import org.jetbrains.annotations.NotNull;
import ru.artlebedev.parser3.Parser3Language;
import ru.artlebedev.parser3.Parser3TokenTypes;
import ru.artlebedev.parser3.icons.Parser3Icons;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * CompletionContributor для автодополнения путей в файловых контекстах Parser3.
 */
public final class P3UseCompletionContributor extends CompletionContributor {

	public P3UseCompletionContributor() {
		extend(CompletionType.BASIC,
				PlatformPatterns.psiElement()
						.withLanguage(Parser3Language.INSTANCE),
				new PathCompletionProvider()
		);

		extend(CompletionType.BASIC,
				PlatformPatterns.psiElement()
						.withElementType(Parser3TokenTypes.STRING)
						.withLanguage(Parser3Language.INSTANCE),
				new PathCompletionProvider()
		);

		extend(CompletionType.BASIC,
				PlatformPatterns.psiElement()
						.withElementType(Parser3TokenTypes.USE_PATH)
						.withLanguage(Parser3Language.INSTANCE),
				new PathCompletionProvider()
		);

		extend(CompletionType.BASIC,
				PlatformPatterns.psiElement()
						.withElementType(Parser3TokenTypes.TEMPLATE_DATA)
						.withLanguage(Parser3Language.INSTANCE),
				new PathCompletionProvider()
		);
	}

	/**
	 * Универсальный провайдер для путей.
	 */
	private static class PathCompletionProvider extends CompletionProvider<CompletionParameters> {

		@Override
		protected void addCompletions(
				@NotNull CompletionParameters parameters,
				@NotNull ProcessingContext context,
				@NotNull CompletionResultSet result
		) {
			PsiElement position = parameters.getPosition();
			PsiFile file = parameters.getOriginalFile();

			if (file == null || file.getVirtualFile() == null) {
				return;
			}

			CharSequence text = parameters.getEditor().getDocument().getCharsSequence();
			P3PathCompletionContext contextInfo = P3PathCompletionSupport.detectContext(text, parameters.getOffset());
			if (contextInfo == null) {
				return;
			}

			VirtualFile currentFile = file.getVirtualFile();
			addPathCandidates(parameters, result, currentFile, contextInfo);
		}

		private void addPathCandidates(
				@NotNull CompletionParameters parameters,
				@NotNull CompletionResultSet result,
				@NotNull VirtualFile currentFile,
				@NotNull P3PathCompletionContext contextInfo
		) {
			PsiFile file = parameters.getOriginalFile();
			String originalPath = contextInfo.getOriginalPath();
			List<P3PathCompletionSupport.CompletionCandidate> candidates =
					P3PathCompletionSupport.getCompletionCandidates(
							file.getProject(),
							currentFile,
							originalPath,
							contextInfo.getProfile()
					);

			CompletionResultSet finalResult = result.withPrefixMatcher(P3PathCompletionSupport.normalizePath(originalPath));

			Set<String> addedPaths = new HashSet<>();
			for (P3PathCompletionSupport.CompletionCandidate candidate : candidates) {
				if (addedPaths.contains(candidate.getInsertText())) {
					continue;
				}
				addedPaths.add(candidate.getInsertText());

				String typeText = candidate.isDirectory()
						? "directory"
						: contextInfo.getProfile().getFileTypeText();
				LookupElementBuilder builder = LookupElementBuilder.create(candidate.getInsertText())
						.withPresentableText(candidate.getInsertText())
						.withTypeText(typeText, candidate.isDirectory());

				if (candidate.isDirectory()) {
					builder = builder.withIcon(com.intellij.icons.AllIcons.Nodes.Folder);
				} else {
					builder = builder.withIcon(Parser3Icons.File);
				}

				finalResult.addElement(builder.withInsertHandler(new UsePathInsertHandler(contextInfo.getInsertMode())));
			}
		}
	}

	/**
	 * InsertHandler который заменяет текущий path-фрагмент внутри нужного контекста.
	 */
	private static class UsePathInsertHandler implements InsertHandler<com.intellij.codeInsight.lookup.LookupElement> {
		private final @NotNull P3PathCompletionContext.InsertMode insertMode;

		private UsePathInsertHandler(@NotNull P3PathCompletionContext.InsertMode insertMode) {
			this.insertMode = insertMode;
		}

		@Override
		public void handleInsert(@NotNull InsertionContext context, @NotNull com.intellij.codeInsight.lookup.LookupElement item) {
			com.intellij.openapi.editor.Document doc = context.getDocument();
			int tailOffset = context.getTailOffset();
			CharSequence text = doc.getCharsSequence();

			int endOffset = tailOffset;
			while (endOffset < text.length()) {
				char c = text.charAt(endOffset);
				if (shouldStop(c)) {
					break;
				}
				endOffset++;
			}

			if (endOffset > tailOffset) {
				doc.deleteString(tailOffset, endOffset);
			}
		}

		private boolean shouldStop(char ch) {
			if (ch == '\n' || ch == '\r') {
				return true;
			}

			if (insertMode == P3PathCompletionContext.InsertMode.LINE) {
				return ch == ' ' || ch == '\t';
			}

			if (insertMode == P3PathCompletionContext.InsertMode.ARGUMENT) {
				return ch == ';' || ch == ']';
			}

			return ch == ']' || ch == '}';
		}
	}
}
