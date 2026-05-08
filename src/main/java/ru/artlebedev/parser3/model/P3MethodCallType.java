package ru.artlebedev.parser3.model;

/**
 * Допустимый тип вызова метода Parser3.
 */
public enum P3MethodCallType {
	ANY,
	STATIC,
	DYNAMIC;

	public boolean allowsStaticCall() {
		return this == ANY || this == STATIC;
	}

	public boolean allowsDynamicCall() {
		return this == ANY || this == DYNAMIC;
	}

	public static P3MethodCallType fromDirective(@org.jetbrains.annotations.Nullable String directive) {
		if ("static".equals(directive)) return STATIC;
		if ("dynamic".equals(directive)) return DYNAMIC;
		return ANY;
	}
}
