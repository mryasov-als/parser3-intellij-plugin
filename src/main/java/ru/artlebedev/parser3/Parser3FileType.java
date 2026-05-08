package ru.artlebedev.parser3;

import com.intellij.openapi.fileTypes.LanguageFileType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.artlebedev.parser3.icons.Parser3Icons;

import javax.swing.*;

public class Parser3FileType extends LanguageFileType {
	public static final Parser3FileType INSTANCE = new Parser3FileType();

	private Parser3FileType() {
		super(Parser3Language.INSTANCE);
	}

	@NotNull
	@Override
	public String getName() {
		return "Parser3";
	}

	@NotNull
	@Override
	public String getDescription() {
		return "Parser3 template/script file";
	}

	@NotNull
	@Override
	public String getDefaultExtension() {
		return "p"; // .p3 задаётся в plugin.xml
	}

	@Nullable
	@Override
	public Icon getIcon() {
		return Parser3Icons.File;
	}
}