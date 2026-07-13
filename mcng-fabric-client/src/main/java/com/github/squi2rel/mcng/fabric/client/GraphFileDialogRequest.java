package com.github.squi2rel.mcng.fabric.client;

import java.util.List;
import java.util.Objects;

public record GraphFileDialogRequest(
	String title,
	List<String> extensions,
	String initialPath
) {
	public GraphFileDialogRequest {
		if (title == null || title.isBlank()) {
			throw new IllegalArgumentException("title must not be blank");
		}
		Objects.requireNonNull(extensions, "extensions");
		if (extensions.isEmpty()) {
			throw new IllegalArgumentException("extensions must not be empty");
		}
		extensions = List.copyOf(extensions);
		for (String extension : extensions) {
			if (extension == null || extension.isBlank()) {
				throw new IllegalArgumentException("extensions must not contain blanks");
			}
		}
		initialPath = initialPath == null ? "" : initialPath;
	}
}
