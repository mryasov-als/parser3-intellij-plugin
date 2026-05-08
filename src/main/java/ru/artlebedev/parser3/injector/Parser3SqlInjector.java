package ru.artlebedev.parser3.injector;

import com.intellij.lang.Language;
import com.intellij.lang.injection.MultiHostInjector;
import com.intellij.lang.injection.MultiHostRegistrar;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;
import ru.artlebedev.parser3.Parser3TokenTypes;
import ru.artlebedev.parser3.psi.Parser3SqlBlock;

import java.util.Collections;
import java.util.List;

/**
 * MultiHost-инжектор для SQL внутри Parser3-конструкторов вида:
 * ^table::sql{...}
 * ^string:sql{...}
 * ^void:sql{...}
 * ^hash::sql{...}
 * ^int:sql{...}
 * ^double:sql{...}
 * ^file::sql{...}
 * ^array::sql{...}
 *
 * Использует InjectorUtils.collectParts() — единую функцию очистки Parser3 конструкций.
 */
public class Parser3SqlInjector implements MultiHostInjector {

	// Включить для отладки инжекций
	private static final boolean DEBUG = false;

	@Override
	public void getLanguagesToInject(@NotNull MultiHostRegistrar registrar, @NotNull PsiElement context) {
		if (!(context instanceof Parser3SqlBlock)) {
			return;
		}

		Parser3SqlBlock host = (Parser3SqlBlock) context;

		PsiElement lbrace = findLeftBrace(host);
		if (lbrace == null) {
			return;
		}

		PsiElement rbrace = findRightBrace(lbrace);
		if (rbrace == null) {
			// Fallback: если баланс скобок не сошёлся, ищем последнюю RBRACE
			rbrace = findLastRbrace(lbrace);
		}

		if (rbrace == null) {
			return;
		}

		// Используем единую функцию из InjectorUtils
		List<InjectorUtils.InjectionPart<Parser3SqlBlock>> parts =
				InjectorUtils.collectParts(Parser3SqlBlock.class, lbrace, rbrace);

		if (parts.isEmpty()) {
			return;
		}

		// Проверяем что текущий host входит в список
		boolean hostInside = false;
		for (InjectorUtils.InjectionPart<Parser3SqlBlock> part : parts) {
			if (part.host == host) {
				hostInside = true;
				break;
			}
		}
		if (!hostInside) {
			return;
		}

		// Логирование
		if (DEBUG && parts.get(0).host == host) {
			String virtualSql = InjectorUtils.buildVirtualDocument(parts);
			System.out.println("\n========== final_SQL (lbrace at " + lbrace.getTextRange() + ") ==========\n");
			System.out.println(virtualSql);
			System.out.println("\n========== /final_SQL ==========\n");
		}

		// Ищем SQL язык
		Language sql = Language.findLanguageByID("SQL");
		if (sql == null) {
			sql = Language.findLanguageByID("TEXT");
			if (DEBUG) {
				System.out.println("[SQL Injector] SQL language not found, trying TEXT");
			}
		}
		if (sql == null) {
			if (DEBUG) {
				System.out.println("[SQL Injector] No language found for injection");
			}
			return;
		}

		if (DEBUG) {
			System.out.println("[SQL Injector] Starting injection with " + parts.size() + " parts, language: " + sql.getID());
		}

		registrar.startInjecting(sql);

		for (InjectorUtils.InjectionPart<Parser3SqlBlock> part : parts) {
			int length = part.host.getTextLength();
			if (length <= 0) {
				continue;
			}
			if (DEBUG) {
				System.out.println("[SQL Injector] addPlace: prefix='" + part.prefix.replace("\n", "\\n") + "', text='" + part.host.getText().replace("\n", "\\n") + "'");
			}
			registrar.addPlace(
					part.prefix,
					part.suffix,
					part.host,
					new TextRange(0, length)
			);
		}
		registrar.doneInjecting();

		if (DEBUG) {
			System.out.println("[SQL Injector] Injection done");
		}
	}

	@NotNull
	@Override
	public List<? extends Class<? extends PsiElement>> elementsToInjectIn() {
		return Collections.singletonList(Parser3SqlBlock.class);
	}

	private static PsiElement findLeftBrace(@NotNull Parser3SqlBlock host) {
		PsiElement e = host;
		while (e != null) {
			e = e.getPrevSibling();
			if (e == null) {
				break;
			}
			IElementType type = e.getNode().getElementType();
			if (type == Parser3TokenTypes.LBRACE) {
				if (isSqlLbrace(e)) {
					return e;
				}
			}
		}
		return null;
	}

	/**
	 * Проверяет, является ли LBRACE началом SQL-блока.
	 */
	private static boolean isSqlLbrace(@NotNull PsiElement lbrace) {
		StringBuilder beforeBrace = new StringBuilder();
		PsiElement e = lbrace.getPrevSibling();

		while (e != null) {
			IElementType type = e.getNode() != null ? e.getNode().getElementType() : null;

			if (type == Parser3TokenTypes.WHITE_SPACE || e instanceof com.intellij.psi.PsiWhiteSpace) {
				break;
			}

			if (e instanceof Parser3SqlBlock) {
				break;
			}

			String text = e.getText();
			if (text != null) {
				beforeBrace.insert(0, text);
			}
			e = e.getPrevSibling();
		}

		String before = beforeBrace.toString();

		// Стандартные конструкции: ^void:sql, ^table::sql и т.д.
		if (before.endsWith(":sql") || before.endsWith("::sql")) {
			return true;
		}

		// Пользовательские префиксы
		List<String> userPrefixes = ru.artlebedev.parser3.lexer.Parser3LexerCore.getUserSqlInjectionPrefixes();
		for (String prefix : userPrefixes) {
			if (prefix == null || prefix.trim().isEmpty()) {
				continue;
			}
			String p = prefix.trim();
			if (before.equals(p) || before.endsWith(p)) {
				return true;
			}
		}

		return false;
	}

	private static PsiElement findRightBrace(@NotNull PsiElement lbrace) {
		PsiElement e = lbrace;
		int depth = 1;

		while (e != null) {
			e = e.getNextSibling();
			if (e == null) {
				break;
			}

			IElementType type = e.getNode() != null ? e.getNode().getElementType() : null;

			if (type == Parser3TokenTypes.LBRACE) {
				depth++;
			} else if (type == Parser3TokenTypes.RBRACE) {
				depth--;
				if (depth == 0) {
					return e;
				}
			}
		}
		return null;
	}

	/**
	 * Fallback: ищем последнюю RBRACE после lbrace.
	 */
	private static PsiElement findLastRbrace(@NotNull PsiElement lbrace) {
		PsiElement e = lbrace;
		PsiElement lastRbrace = null;

		while (e != null) {
			e = e.getNextSibling();
			if (e == null) {
				break;
			}

			IElementType type = e.getNode() != null ? e.getNode().getElementType() : null;
			if (type == Parser3TokenTypes.RBRACE) {
				lastRbrace = e;
			}
		}

		return lastRbrace;
	}
}