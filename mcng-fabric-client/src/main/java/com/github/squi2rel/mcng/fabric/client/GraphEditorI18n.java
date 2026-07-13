package com.github.squi2rel.mcng.fabric.client;

import java.util.IllegalFormatException;
import java.util.Locale;

@FunctionalInterface
public interface GraphEditorI18n {
	String translate(String key, String fallback, Object... args);

	static GraphEditorI18n identity() {
		return (key, fallback, args) -> formatFallback(fallback, key, args);
	}

	static String formatFallback(String fallback, String key, Object... args) {
		String template = fallback != null && !fallback.isBlank() ? fallback : key;
		if (args == null || args.length == 0) {
			return template;
		}
		try {
			return String.format(Locale.ROOT, template, args);
		} catch (IllegalFormatException exception) {
			return template;
		}
	}
}
