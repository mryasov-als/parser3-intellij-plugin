package ru.artlebedev.parser3.model;

import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a class declaration in Parser3 code.
 *
 * Examples:
 * - @CLASS User
 * - Implicit MAIN class (file without @CLASS)
 */
public final class P3ClassDeclaration {

	private final @NotNull String name;
	private final @NotNull VirtualFile file;
	private final int startOffset;  // Position of @CLASS (or 0 for MAIN)
	private final int endOffset;    // Position of next @CLASS or EOF
	private final @Nullable String baseClassName;  // From @BASE directive
	private final @NotNull List<String> options;  // From @OPTIONS: static, dynamic, locals, partial
	private final @NotNull List<P3MethodDeclaration> methods;
	private final boolean isMainClass;  // true if implicit MAIN (no @CLASS)
	private final @Nullable PsiElement element;  // PSI element for @CLASS token

	public P3ClassDeclaration(
			@NotNull String name,
			@NotNull VirtualFile file,
			int startOffset,
			int endOffset,
			@Nullable String baseClassName,
			@NotNull List<String> options,
			@NotNull List<P3MethodDeclaration> methods,
			boolean isMainClass,
			@Nullable PsiElement element
	) {
		this.name = name;
		this.file = file;
		this.startOffset = startOffset;
		this.endOffset = endOffset;
		this.baseClassName = baseClassName;
		this.options = new ArrayList<>(options);
		this.methods = new ArrayList<>(methods);
		this.isMainClass = isMainClass;
		this.element = element;
	}

	public @NotNull String getName() {
		return name;
	}

	public @NotNull VirtualFile getFile() {
		return file;
	}

	public int getStartOffset() {
		return startOffset;
	}

	public int getEndOffset() {
		return endOffset;
	}

	public @Nullable String getBaseClassName() {
		return baseClassName;
	}

	public @NotNull List<String> getOptions() {
		return new ArrayList<>(options);
	}

	public boolean hasOption(@NotNull String option) {
		return options.contains(option);
	}

	public boolean isStatic() {
		return options.contains("static");
	}

	public boolean isDynamic() {
		return options.contains("dynamic");
	}

	public boolean isLocals() {
		return options.contains("locals");
	}

	public boolean isPartial() {
		return options.contains("partial");
	}

	public @NotNull List<P3MethodDeclaration> getMethods() {
		return new ArrayList<>(methods);
	}

	public boolean isMainClass() {
		return isMainClass;
	}

	public @Nullable PsiElement getElement() {
		return element;
	}

	/**
	 * Check if offset is within this class range
	 * Uses <= for endOffset to include cursor at end of file/class
	 */
	public boolean containsOffset(int offset) {
		return offset >= startOffset && offset <= endOffset;
	}

	@Override
	public String toString() {
		return "P3ClassDeclaration{" +
				"name='" + name + '\'' +
				", file=" + file.getName() +
				", startOffset=" + startOffset +
				", endOffset=" + endOffset +
				", baseClass='" + baseClassName + '\'' +
				", options=" + options +
				", methodCount=" + methods.size() +
				", isMain=" + isMainClass +
				'}';
	}
}