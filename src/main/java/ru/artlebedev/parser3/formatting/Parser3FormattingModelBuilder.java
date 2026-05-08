package ru.artlebedev.parser3.formatting;

import com.intellij.formatting.FormattingContext;
import com.intellij.formatting.FormattingModel;
import com.intellij.formatting.FormattingModelBuilder;
import com.intellij.formatting.FormattingModelProvider;
import com.intellij.formatting.SpacingBuilder;
import com.intellij.lang.ASTNode;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiFile;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.artlebedev.parser3.Parser3Language;

public final class Parser3FormattingModelBuilder implements FormattingModelBuilder {

	private static final ThreadLocal<TextRange> OVERRIDE_RANGE = new ThreadLocal<>();

	public static void withTemporaryRange(@NotNull TextRange range, @NotNull Runnable runnable) {
		OVERRIDE_RANGE.set(range);
		try {
			runnable.run();
		}
		finally {
			OVERRIDE_RANGE.remove();
		}
	}

	@Nullable
	static TextRange getOverrideRange() {
		return OVERRIDE_RANGE.get();
	}

	@Override
	public @NotNull FormattingModel createModel(@NotNull FormattingContext context) {
		PsiFile file = context.getContainingFile();
		ASTNode rootNode = file.getNode();
		CodeStyleSettings settings = context.getCodeStyleSettings();
		CommonCodeStyleSettings commonSettings = settings.getCommonSettings(Parser3Language.INSTANCE);
		CommonCodeStyleSettings.IndentOptions indentOptions = commonSettings.getIndentOptions();
		SpacingBuilder spacingBuilder = Parser3SpacingBuilder.create(settings);

		TextRange formattingRange = context.getFormattingRange();

		Parser3Block rootBlock = new Parser3Block(
				rootNode,
				spacingBuilder,
				indentOptions,
				true,
				0,
				false,
				false,
				false,
				formattingRange
		);

		return FormattingModelProvider.createFormattingModelForPsiFile(file, rootBlock, settings);
	}
}