package ru.artlebedev.parser3.references;

import com.intellij.openapi.util.TextRange;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.*;
import com.intellij.util.ProcessingContext;
import org.jetbrains.annotations.NotNull;
import ru.artlebedev.parser3.Parser3TokenTypes;
import ru.artlebedev.parser3.utils.Parser3IdentifierUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Contributes PsiReferences for Parser3 class and method calls.
 * This enables Ctrl+Click navigation with proper underlining of specific tokens.
 */
public class P3ClassReferenceContributor extends PsiReferenceContributor {

	private static final Pattern CLASS_METHOD_PATTERN = Pattern.compile(
			"^(" + Parser3IdentifierUtils.NAME_REGEX + ")(?:::?(" + Parser3IdentifierUtils.NAME_REGEX + "))?");

	@Override
	public void registerReferenceProviders(@NotNull PsiReferenceRegistrar registrar) {
		// Register for ALL elements, then filter for Parser3 CONSTRUCTOR tokens manually
		registrar.registerReferenceProvider(
				PlatformPatterns.psiElement(),
				new PsiReferenceProvider() {
					@Override
					public PsiReference @NotNull [] getReferencesByElement(@NotNull PsiElement element, @NotNull ProcessingContext context) {


						// Only process CONSTRUCTOR tokens from Parser3 language
						if (element.getNode() == null || element.getNode().getElementType() != Parser3TokenTypes.CONSTRUCTOR) {
							// If this is XML element, try to find Parser3 elements inside
							if (element.getLanguage().getID().equals("XML") || element.getLanguage().getID().equals("HTML")) {
								// Get Parser3 view
								PsiFile file = element.getContainingFile();
								if (file != null && file.getViewProvider() != null) {
									PsiFile p3File = file.getViewProvider().getPsi(ru.artlebedev.parser3.Parser3Language.INSTANCE);
									if (p3File != null) {
										// Find all Parser3 CONSTRUCTOR elements in this range
										int start = element.getTextRange().getStartOffset();
										int end = element.getTextRange().getEndOffset();

										List<PsiReference> refs = new ArrayList<>();

										for (int offset = start; offset < end; offset++) {
											PsiElement p3Element = p3File.findElementAt(offset);
											if (p3Element != null && p3Element.getNode() != null &&
													p3Element.getNode().getElementType() == Parser3TokenTypes.CONSTRUCTOR) {


												// Process this token
												PsiReference[] tokenRefs = createReferencesForConstructor(p3Element);
												refs.addAll(Arrays.asList(tokenRefs));

												// Skip to end of this token
												offset = p3Element.getTextRange().getEndOffset() - 1;
											}
										}

										if (!refs.isEmpty()) {
											return refs.toArray(new PsiReference[0]);
										}
									}
								}
							}
							return PsiReference.EMPTY_ARRAY;
						}

						if (!element.getLanguage().isKindOf(ru.artlebedev.parser3.Parser3Language.INSTANCE)) {
							return PsiReference.EMPTY_ARRAY;
						}

						return createReferencesForConstructor(element);
					}

					private PsiReference[] createReferencesForConstructor(PsiElement element) {
						String text = element.getText();

						// Remove leading ^
						if (text.startsWith("^")) {
							text = text.substring(1);
						}

						// Check if this is a class name (followed by :: or :)
						PsiElement next = element.getNextSibling();
						while (next != null && next instanceof PsiWhiteSpace) {
							next = next.getNextSibling();
						}


						// If followed by COLON, this is class name
						if (next != null && next.getNode() != null && next.getNode().getElementType() == Parser3TokenTypes.COLON) {
							// This is class name - create reference to class
							int startOffset = element.getText().startsWith("^") ? 1 : 0;
							TextRange range = new TextRange(startOffset, element.getTextLength());
							return new PsiReference[]{new P3ClassReference(element, range, text, null)};
						}

						// Check if this is method name after class (find previous :: or :)
						PsiElement prev = element.getPrevSibling();
						while (prev != null && prev instanceof PsiWhiteSpace) {
							prev = prev.getPrevSibling();
						}


						if (prev != null && prev.getNode() != null && prev.getNode().getElementType() == Parser3TokenTypes.COLON) {
							// Find class name before colons
							PsiElement beforeColon = prev.getPrevSibling();
							while (beforeColon != null && beforeColon instanceof PsiWhiteSpace) {
								beforeColon = beforeColon.getPrevSibling();
							}

							// Check if there's another COLON (for ::)
							if (beforeColon != null && beforeColon.getNode() != null && beforeColon.getNode().getElementType() == Parser3TokenTypes.COLON) {
								beforeColon = beforeColon.getPrevSibling();
								while (beforeColon != null && beforeColon instanceof PsiWhiteSpace) {
									beforeColon = beforeColon.getPrevSibling();
								}
							}


							if (beforeColon != null && beforeColon.getNode() != null && beforeColon.getNode().getElementType() == Parser3TokenTypes.CONSTRUCTOR) {
								String className = beforeColon.getText();
								if (className.startsWith("^")) {
									className = className.substring(1);
								}

								// Проверяем что после имени метода идёт скобка, а не точка
								// ^User::create.something[] — невалидно
								PsiElement afterMethod = element.getNextSibling();
								// НЕ пропускаем whitespace — DOT должен идти сразу после метода

								if (afterMethod != null && afterMethod.getNode() != null) {
									com.intellij.psi.tree.IElementType afterType = afterMethod.getNode().getElementType();
									// Если после имени метода идёт DOT — это невалидный вызов
									if (afterType == Parser3TokenTypes.DOT) {
										return PsiReference.EMPTY_ARRAY;
									}
									String afterText = afterMethod.getText();
									if (afterText != null && afterText.equals(".")) {
										return PsiReference.EMPTY_ARRAY;
									}
								}

								// This is method name - create reference to method
								TextRange range = new TextRange(0, element.getTextLength());
								return new PsiReference[]{new P3ClassReference(element, range, className, text)};
							}
						}

						return PsiReference.EMPTY_ARRAY;
					}
				}
		);
	}
}
