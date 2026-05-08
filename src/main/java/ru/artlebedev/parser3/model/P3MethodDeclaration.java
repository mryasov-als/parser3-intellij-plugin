package ru.artlebedev.parser3.model;

import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.artlebedev.parser3.index.P3ResolvedValue;

import java.util.Collections;
import java.util.List;

/**
 * Represents a method declaration in Parser3 code.
 * Example: @methodName[params]
 */
public final class P3MethodDeclaration {

	private final @NotNull String name;
	private final @NotNull VirtualFile file;
	private final int offset;
	private final @Nullable PsiElement element;
	private final @Nullable String ownerClass;
	private final @NotNull List<String> parameterNames;
	private final @Nullable String docText;
	private final @NotNull List<P3MethodParameter> docParameters;
	private final @Nullable P3MethodParameter docResult;
	private final @Nullable P3ResolvedValue inferredResult;
	private final boolean isGetter;
	private final @NotNull P3MethodCallType callType;

	public P3MethodDeclaration(
			@NotNull String name,
			@NotNull VirtualFile file,
			int offset,
			@Nullable PsiElement element
	) {
		this(name, file, offset, element, null, List.of(), null, List.of(), null, null, false, P3MethodCallType.ANY);
	}

	public P3MethodDeclaration(
			@NotNull String name,
			@NotNull VirtualFile file,
			int offset,
			@Nullable PsiElement element,
			@NotNull List<String> parameterNames,
			@Nullable String docText,
			@NotNull List<P3MethodParameter> docParameters,
			@Nullable P3MethodParameter docResult
	) {
		this(name, file, offset, element, null, parameterNames, docText, docParameters, docResult, null, false, P3MethodCallType.ANY);
	}

	public P3MethodDeclaration(
			@NotNull String name,
			@NotNull VirtualFile file,
			int offset,
			@Nullable PsiElement element,
			@NotNull List<String> parameterNames,
			@Nullable String docText,
			@NotNull List<P3MethodParameter> docParameters,
			@Nullable P3MethodParameter docResult,
			@Nullable P3ResolvedValue inferredResult,
			boolean isGetter,
			@NotNull P3MethodCallType callType
	) {
		this(name, file, offset, element, null, parameterNames, docText, docParameters, docResult, inferredResult, isGetter, callType);
	}

	public P3MethodDeclaration(
			@NotNull String name,
			@NotNull VirtualFile file,
			int offset,
			@Nullable PsiElement element,
			@Nullable String ownerClass,
			@NotNull List<String> parameterNames,
			@Nullable String docText,
			@NotNull List<P3MethodParameter> docParameters,
			@Nullable P3MethodParameter docResult
	) {
		this(name, file, offset, element, ownerClass, parameterNames, docText, docParameters, docResult, null, false, P3MethodCallType.ANY);
	}

	public P3MethodDeclaration(
			@NotNull String name,
			@NotNull VirtualFile file,
			int offset,
			@Nullable PsiElement element,
			@Nullable String ownerClass,
			@NotNull List<String> parameterNames,
			@Nullable String docText,
			@NotNull List<P3MethodParameter> docParameters,
			@Nullable P3MethodParameter docResult,
			@Nullable P3ResolvedValue inferredResult,
			boolean isGetter
	) {
		this(name, file, offset, element, ownerClass, parameterNames, docText, docParameters, docResult, inferredResult, isGetter, P3MethodCallType.ANY);
	}

	public P3MethodDeclaration(
			@NotNull String name,
			@NotNull VirtualFile file,
			int offset,
			@Nullable PsiElement element,
			@Nullable String ownerClass,
			@NotNull List<String> parameterNames,
			@Nullable String docText,
			@NotNull List<P3MethodParameter> docParameters,
			@Nullable P3MethodParameter docResult,
			@Nullable P3ResolvedValue inferredResult,
			boolean isGetter,
			@NotNull P3MethodCallType callType
	) {
		this.name = name;
		this.file = file;
		this.offset = offset;
		this.element = element;
		this.ownerClass = ownerClass;
		this.parameterNames = Collections.unmodifiableList(parameterNames);
		this.docText = docText;
		this.docParameters = Collections.unmodifiableList(docParameters);
		this.docResult = docResult;
		this.inferredResult = inferredResult;
		this.isGetter = isGetter;
		this.callType = callType;
	}

	public @NotNull String getName() {
		return name;
	}

	public @NotNull VirtualFile getFile() {
		return file;
	}

	public int getOffset() {
		return offset;
	}

	public @Nullable PsiElement getElement() {
		return element;
	}

	public @Nullable String getOwnerClass() {
		return ownerClass;
	}

	public @NotNull List<String> getParameterNames() {
		return parameterNames;
	}

	public @Nullable String getDocText() {
		return docText;
	}

	public @NotNull List<P3MethodParameter> getDocParameters() {
		return docParameters;
	}

	public @Nullable P3MethodParameter getDocResult() {
		return docResult;
	}

	public @Nullable P3ResolvedValue getInferredResult() {
		return inferredResult;
	}

	public @Nullable String getDocResultType() {
		return docResult != null ? docResult.getType() : null;
	}

	public boolean isGetter() {
		return isGetter;
	}

	public @NotNull P3MethodCallType getCallType() {
		return callType;
	}

	/**
	 * Совместимость со старым кодом: возвращает doc-type, а не inferred type.
	 */
	public @Nullable String getResultType() {
		return getDocResultType();
	}

	public @Nullable String getParamType(@NotNull String paramName) {
		for (P3MethodParameter param : docParameters) {
			if (paramName.equals(param.getName())) {
				return param.getType();
			}
		}
		return null;
	}

	public boolean hasDocumentation() {
		return docText != null || !docParameters.isEmpty() || docResult != null;
	}

	public @Nullable String generateDocHtml() {
		if (docText == null || docText.isEmpty()) {
			return null;
		}

		StringBuilder sb = new StringBuilder();
		sb.append("<style>.title {font-size: 1.2em; font-weight: bold}</style>");
		sb.append("<code>@").append(escapeHtml(name));
		if (!parameterNames.isEmpty()) {
			sb.append("[").append(String.join(";", parameterNames)).append("]");
		}
		sb.append("</code>");

		String[] lines = docText.split("\n");
		if (lines.length > 0) {
			String title = lines[0].trim();
			if (!title.isEmpty()) {
				sb.append("<hr/>");
				sb.append("<span class=\"title\">").append(escapeHtml(title)).append("</span>");
				sb.append("<hr/>");
			}

			if (lines.length > 1) {
				sb.append("<p>");
				boolean first = true;
				for (int i = 1; i < lines.length; i++) {
					String line = lines[i];
					if (!first) {
						sb.append("<br/>");
					}
					first = false;
					sb.append(wrapVariablesInCode(escapeHtml(line)));
				}
				sb.append("</p>");
			}
		}

		return sb.toString();
	}

	private static @NotNull String wrapVariablesInCode(@NotNull String text) {
		StringBuilder result = new StringBuilder();
		int i = 0;
		while (i < text.length()) {
			if (text.charAt(i) == '$' && i + 1 < text.length()) {
				int start = i;
				i++;
				while (i < text.length() && isVarNameChar(text.charAt(i))) {
					i++;
				}

				if (i < text.length() && text.charAt(i) == '(') {
					i++;
					while (i < text.length() && text.charAt(i) != ')' && text.charAt(i) != '<') {
						i++;
					}
					if (i < text.length() && text.charAt(i) == ')') {
						i++;
					}
				}

				String varPart = text.substring(start, i);
				result.append("<code>").append(varPart).append("</code>");
			} else {
				result.append(text.charAt(i));
				i++;
			}
		}
		return result.toString();
	}

	private static boolean isVarNameChar(char ch) {
		return Character.isLetterOrDigit(ch) || ch == '_';
	}

	private static @NotNull String escapeHtml(@NotNull String text) {
		return text
				.replace("&", "&amp;")
				.replace("<", "&lt;")
				.replace(">", "&gt;")
				.replace("\"", "&quot;");
	}

	@Override
	public String toString() {
		return "P3MethodDeclaration{" +
				"name='" + name + '\'' +
				", file=" + file.getName() +
				", offset=" + offset +
				", params=" + parameterNames +
				", hasDoc=" + hasDocumentation() +
				'}';
	}
}
