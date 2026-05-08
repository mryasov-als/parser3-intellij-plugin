package ru.artlebedev.parser3.highlight;

import com.intellij.lang.BracePair;
import com.intellij.lang.PairedBraceMatcher;
import com.intellij.psi.PsiFile;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.artlebedev.parser3.Parser3TokenTypes;

public class Parser3BraceMatcher implements PairedBraceMatcher {

	private static final BracePair[] PAIRS = new BracePair[]{
			new BracePair(Parser3TokenTypes.LBRACKET, Parser3TokenTypes.RBRACKET, false),
			new BracePair(Parser3TokenTypes.LPAREN, Parser3TokenTypes.RPAREN, false),
			new BracePair(Parser3TokenTypes.LBRACE, Parser3TokenTypes.RBRACE, false),
			new BracePair(Parser3TokenTypes.REM_LBRACKET, Parser3TokenTypes.REM_RBRACKET, false),
			new BracePair(Parser3TokenTypes.REM_LPAREN,   Parser3TokenTypes.REM_RPAREN,   false),
			new BracePair(Parser3TokenTypes.REM_LBRACE,   Parser3TokenTypes.REM_RBRACE,   false),
			new BracePair(Parser3TokenTypes.LSINGLE_QUOTE, Parser3TokenTypes.RSINGLE_QUOTE, false),
			new BracePair(Parser3TokenTypes.LDOUBLE_QUOTE, Parser3TokenTypes.RDOUBLE_QUOTE, false),
	};

	@Override
	public @NotNull BracePair @NotNull [] getPairs() {
		return PAIRS;
	}

	@Override
	public boolean isPairedBracesAllowedBeforeType(@NotNull IElementType lbraceType, @Nullable IElementType nextType) {
		return true;
	}

	@Override
	public int getCodeConstructStart(PsiFile file, int openingBraceOffset) {
		return openingBraceOffset;
	}
}