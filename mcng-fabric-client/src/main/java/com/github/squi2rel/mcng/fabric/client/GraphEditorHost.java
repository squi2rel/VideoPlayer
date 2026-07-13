package com.github.squi2rel.mcng.fabric.client;

import com.github.squi2rel.mcng.core.GraphDocument;

import java.util.Optional;

public interface GraphEditorHost {
	void onDocumentChanged(GraphDocument document);

	void copyToClipboard(String value);

	String readClipboard();

	void showMessage(String message);

	default GraphEditorI18n i18n() {
		return GraphEditorI18n.identity();
	}

	default boolean supportsFileDialogs() {
		return false;
	}

	default Optional<String> chooseFile(GraphFileDialogRequest request) {
		return Optional.empty();
	}
}
