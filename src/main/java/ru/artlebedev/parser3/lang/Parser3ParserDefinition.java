package ru.artlebedev.parser3.lang;

import com.intellij.lexer.Lexer;
import com.intellij.lang.ASTNode;
import com.intellij.lang.ParserDefinition;
import com.intellij.lang.PsiBuilder;
import com.intellij.lang.PsiParser;
import com.intellij.openapi.project.Project;
import com.intellij.psi.FileViewProvider;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.tree.IFileElementType;
import com.intellij.psi.tree.TokenSet;
import org.jetbrains.annotations.NotNull;
import ru.artlebedev.parser3.Parser3Language;
import ru.artlebedev.parser3.Parser3TokenTypes;
import ru.artlebedev.parser3.lexer.Parser3Lexer;
import ru.artlebedev.parser3.psi.P3MethodCallElement;
import ru.artlebedev.parser3.psi.Parser3PsiFile;

public class Parser3ParserDefinition implements ParserDefinition {

	// Обычный FILE node type для Parser3
	private static final IFileElementType FILE = new IFileElementType(Parser3Language.INSTANCE);

	@Override
	public @NotNull Lexer createLexer(Project project) {
		return new Parser3Lexer();
	}

	@Override
	public @NotNull PsiParser createParser(Project project) {
		return new PsiParser() {
			@Override
			public @NotNull ASTNode parse(@NotNull IElementType root, @NotNull PsiBuilder builder) {
				PsiBuilder.Marker fileMarker = builder.mark();

				while (!builder.eof()) {
					IElementType type = builder.getTokenType();

					if (type == Parser3TokenTypes.SQL_BLOCK) {
						PsiBuilder.Marker sqlMarker = builder.mark();
						builder.advanceLexer();
						sqlMarker.done(Parser3TokenTypes.SQL_BLOCK);
						continue;
					}

					if (type == Parser3TokenTypes.HTML_DATA) {
						PsiBuilder.Marker htmlMarker = builder.mark();
						builder.advanceLexer();
						htmlMarker.done(Parser3TokenTypes.HTML_DATA);
						continue;
					}

					if (type == Parser3TokenTypes.CSS_DATA) {
						PsiBuilder.Marker cssMarker = builder.mark();
						builder.advanceLexer();
						cssMarker.done(Parser3TokenTypes.CSS_DATA);
						continue;
					}

					if (type == Parser3TokenTypes.JS_DATA) {
						PsiBuilder.Marker jsMarker = builder.mark();
						builder.advanceLexer();
						jsMarker.done(Parser3TokenTypes.JS_DATA);
						continue;
					}

					if (type == Parser3TokenTypes.METHOD) {
						PsiBuilder.Marker methodMarker = builder.mark();
						builder.advanceLexer();
						methodMarker.done(Parser3TokenTypes.METHOD);
						continue;
					}

					builder.advanceLexer();
				}

				fileMarker.done(root);
				return builder.getTreeBuilt();
			}
		};
	}

	@Override
	public @NotNull IFileElementType getFileNodeType() {
		return FILE;
	}

	@Override
	public @NotNull TokenSet getWhitespaceTokens() {
		return TokenSet.create(TokenType.WHITE_SPACE);
	}

	@Override
	public @NotNull TokenSet getCommentTokens() {
		return TokenSet.create(Parser3TokenTypes.LINE_COMMENT, Parser3TokenTypes.BLOCK_COMMENT);
	}

	@Override
	public @NotNull TokenSet getStringLiteralElements() {
		return TokenSet.create(Parser3TokenTypes.STRING);
	}

	@Override
	public @NotNull PsiElement createElement(@NotNull ASTNode node) {
		IElementType type = node.getElementType();

		if (type == Parser3TokenTypes.SQL_BLOCK) {
			return new ru.artlebedev.parser3.psi.Parser3SqlBlock(node);
		}

		if (type == Parser3TokenTypes.HTML_DATA) {
			return new ru.artlebedev.parser3.psi.Parser3HtmlBlock(node);
		}

		if (type == Parser3TokenTypes.CSS_DATA) {
			return new ru.artlebedev.parser3.psi.Parser3CssBlock(node);
		}

		if (type == Parser3TokenTypes.JS_DATA) {
			return new ru.artlebedev.parser3.psi.Parser3JsBlock(node);
		}

		if (type == Parser3TokenTypes.METHOD) {
			return new P3MethodCallElement(node);
		}

		return node.getPsi();
	}

	@Override
	public PsiFile createFile(@NotNull FileViewProvider viewProvider) {
		return new Parser3PsiFile(viewProvider);
	}

	@Override
	public SpaceRequirements spaceExistenceTypeBetweenTokens(ASTNode left, ASTNode right) {
		return SpaceRequirements.MAY;
	}
}