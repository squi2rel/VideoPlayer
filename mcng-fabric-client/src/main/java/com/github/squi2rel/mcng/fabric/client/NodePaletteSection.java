package com.github.squi2rel.mcng.fabric.client;

import java.util.List;
import java.util.Objects;

record NodePaletteSection(String title, int order, List<NodePaletteEntry> entries) {
	NodePaletteSection {
		Objects.requireNonNull(title, "title");
		Objects.requireNonNull(entries, "entries");
		entries = List.copyOf(entries);
	}
}
