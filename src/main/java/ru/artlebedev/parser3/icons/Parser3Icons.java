package ru.artlebedev.parser3.icons;

import com.intellij.openapi.util.IconLoader;

import javax.swing.Icon;

public final class Parser3Icons {
	public static final Icon File = IconLoader.getIcon("/icons/parser3.svg", Parser3Icons.class);
	public static final Icon FileTemplate = IconLoader.getIcon("/icons/parser3.template.svg", Parser3Icons.class);
	public static final Icon FileMethod = IconLoader.getIcon("/icons/parser3.method.svg", Parser3Icons.class);
	public static final Icon FileClass = FileMethod;
	public static final Icon FileVariable = FileMethod;
	private Parser3Icons() {}
}