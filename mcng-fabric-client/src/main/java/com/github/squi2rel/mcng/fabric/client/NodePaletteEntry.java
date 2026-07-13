package com.github.squi2rel.mcng.fabric.client;

import java.util.List;
import java.util.Objects;

record NodePaletteEntry(String nodeTypeId, String displayName, String subtitle, String groupName, int itemOrder, List<String> searchKeywords) {
	NodePaletteEntry {
		Objects.requireNonNull(nodeTypeId, "nodeTypeId");
		Objects.requireNonNull(displayName, "displayName");
		Objects.requireNonNull(subtitle, "subtitle");
		Objects.requireNonNull(groupName, "groupName");
		searchKeywords = List.copyOf(Objects.requireNonNull(searchKeywords, "searchKeywords"));
	}
}
