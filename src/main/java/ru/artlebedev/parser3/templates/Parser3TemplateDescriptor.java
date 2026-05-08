package ru.artlebedev.parser3.templates;

import org.jetbrains.annotations.NotNull;

public final class Parser3TemplateDescriptor {

	public final String name;
	public final String prefix;
	public final String suffix;
	public final String comment;
	public final Parser3UserTemplate template;

	public Parser3TemplateDescriptor(
			@NotNull String name,
			@NotNull String prefix,
			@NotNull String suffix,
			@NotNull String comment,
			@NotNull Parser3UserTemplate template
	) {
		this.name = name;
		this.prefix = prefix;
		this.suffix = suffix;
		this.comment = comment;
		this.template = template;
	}
}