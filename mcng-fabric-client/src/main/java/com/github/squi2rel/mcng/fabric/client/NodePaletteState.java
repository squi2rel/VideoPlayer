package com.github.squi2rel.mcng.fabric.client;

final class NodePaletteState {
	private boolean open;
	private String query = "";
	private double scrollOffset;
	private NodePaletteEntry pressedEntry;
	private double pressedMouseX;
	private double pressedMouseY;
	private NodePaletteEntry dragEntry;
	private boolean dragging;
	private double dragMouseX;
	private double dragMouseY;

	boolean open() {
		return open;
	}

	void setOpen(boolean open) {
		this.open = open;
		if (!open) {
			clearInteraction();
		}
	}

	void toggle() {
		setOpen(!open);
	}

	String query() {
		return query;
	}

	void setQuery(String query) {
		this.query = query;
		scrollOffset = 0;
	}

	double scrollOffset() {
		return scrollOffset;
	}

	void setScrollOffset(double scrollOffset) {
		this.scrollOffset = Math.max(0, scrollOffset);
	}

	NodePaletteEntry pressedEntry() {
		return pressedEntry;
	}

	void setPressedEntry(NodePaletteEntry pressedEntry, double mouseX, double mouseY) {
		this.pressedEntry = pressedEntry;
		this.pressedMouseX = mouseX;
		this.pressedMouseY = mouseY;
	}

	double pressedMouseX() {
		return pressedMouseX;
	}

	double pressedMouseY() {
		return pressedMouseY;
	}

	NodePaletteEntry dragEntry() {
		return dragEntry;
	}

	void startDragging(NodePaletteEntry dragEntry) {
		this.dragEntry = dragEntry;
		this.dragging = true;
	}

	boolean dragging() {
		return dragging;
	}

	void updateDragMouse(double mouseX, double mouseY) {
		this.dragMouseX = mouseX;
		this.dragMouseY = mouseY;
	}

	double dragMouseX() {
		return dragMouseX;
	}

	double dragMouseY() {
		return dragMouseY;
	}

	void clearInteraction() {
		pressedEntry = null;
		dragEntry = null;
		dragging = false;
	}
}
