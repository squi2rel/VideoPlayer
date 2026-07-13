package com.github.squi2rel.mcng.fabric.client;

import java.util.List;
import java.util.Objects;

public record NodePaletteDefinition(
	String nodeTypeId,
	String sectionTitle,
	int sectionOrder,
	int itemOrder,
	List<String> searchKeywords,
	String sectionTranslationKey
) {
	public NodePaletteDefinition {
		Objects.requireNonNull(nodeTypeId, "nodeTypeId");
		Objects.requireNonNull(sectionTitle, "sectionTitle");
		searchKeywords = List.copyOf(Objects.requireNonNull(searchKeywords, "searchKeywords"));
		if (nodeTypeId.isBlank()) {
			throw new IllegalArgumentException("nodeTypeId must not be blank");
		}
		if (sectionTitle.isBlank()) {
			throw new IllegalArgumentException("sectionTitle must not be blank");
		}
		if (sectionTranslationKey != null && sectionTranslationKey.isBlank()) {
			throw new IllegalArgumentException("sectionTranslationKey must not be blank");
		}
	}

	public NodePaletteDefinition(String nodeTypeId, String sectionTitle, int sectionOrder, int itemOrder) {
		this(nodeTypeId, sectionTitle, sectionOrder, itemOrder, List.of(), null);
	}

	public NodePaletteDefinition(String nodeTypeId, String sectionTitle, int sectionOrder, int itemOrder, List<String> searchKeywords) {
		this(nodeTypeId, sectionTitle, sectionOrder, itemOrder, searchKeywords, null);
	}

	public NodePaletteDefinition(String nodeTypeId, String sectionTitle, String sectionTranslationKey, int sectionOrder, int itemOrder, List<String> searchKeywords) {
		this(nodeTypeId, sectionTitle, sectionOrder, itemOrder, searchKeywords, sectionTranslationKey);
	}
}
