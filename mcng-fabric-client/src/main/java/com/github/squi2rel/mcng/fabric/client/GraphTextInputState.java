package com.github.squi2rel.mcng.fabric.client;

import net.minecraft.client.font.TextRenderer;

import java.util.ArrayDeque;
import java.util.Deque;

final class GraphTextInputState {
	private static final int MAX_LENGTH = 128;

	private String text;
	private int cursor;
	private int anchor;
	private int scrollX;
	private final Deque<Snapshot> undoStack = new ArrayDeque<>();
	private final Deque<Snapshot> redoStack = new ArrayDeque<>();

	GraphTextInputState(String initialText) {
		this.text = initialText == null ? "" : sanitize(initialText);
		this.cursor = this.text.length();
		this.anchor = this.cursor;
	}

	String text() {
		return text;
	}

	int cursor() {
		return cursor;
	}

	int scrollX() {
		return scrollX;
	}

	boolean hasSelection() {
		return cursor != anchor;
	}

	int selectionStart() {
		return Math.min(cursor, anchor);
	}

	int selectionEnd() {
		return Math.max(cursor, anchor);
	}

	String selectedText() {
		return hasSelection() ? text.substring(selectionStart(), selectionEnd()) : "";
	}

	boolean canUndo() {
		return !undoStack.isEmpty();
	}

	boolean canRedo() {
		return !redoStack.isEmpty();
	}

	boolean undo() {
		Snapshot snapshot = undoStack.pollLast();
		if (snapshot == null) {
			return false;
		}
		redoStack.addLast(snapshot());
		restore(snapshot);
		return true;
	}

	boolean redo() {
		Snapshot snapshot = redoStack.pollLast();
		if (snapshot == null) {
			return false;
		}
		undoStack.addLast(snapshot());
		restore(snapshot);
		return true;
	}

	void setCursor(int index, boolean extendSelection) {
		cursor = clampIndex(index);
		if (!extendSelection) {
			anchor = cursor;
		}
	}

	void selectRange(int start, int end) {
		anchor = clampIndex(start);
		cursor = clampIndex(end);
	}

	void selectAll() {
		anchor = 0;
		cursor = text.length();
	}

	void insert(String value) {
		replaceSelection(value);
	}

	void backspace(boolean byWord) {
		if (hasSelection()) {
			replaceSelection("");
			return;
		}
		if (cursor <= 0) {
			return;
		}
		int start = byWord ? previousWordBoundary(cursor) : cursor - 1;
		replaceRange(start, cursor, "");
	}

	void delete(boolean byWord) {
		if (hasSelection()) {
			replaceSelection("");
			return;
		}
		if (cursor >= text.length()) {
			return;
		}
		int end = byWord ? nextWordBoundary(cursor) : cursor + 1;
		replaceRange(cursor, end, "");
	}

	void moveLeft(boolean byWord, boolean extendSelection) {
		if (!extendSelection && hasSelection()) {
			setCursor(selectionStart(), false);
			return;
		}
		setCursor(byWord ? previousWordBoundary(cursor) : Math.max(0, cursor - 1), extendSelection);
	}

	void moveRight(boolean byWord, boolean extendSelection) {
		if (!extendSelection && hasSelection()) {
			setCursor(selectionEnd(), false);
			return;
		}
		setCursor(byWord ? nextWordBoundary(cursor) : Math.min(text.length(), cursor + 1), extendSelection);
	}

	void moveHome(boolean extendSelection) {
		setCursor(0, extendSelection);
	}

	void moveEnd(boolean extendSelection) {
		setCursor(text.length(), extendSelection);
	}

	void selectWordAt(int index) {
		if (text.isEmpty()) {
			setCursor(0, false);
			return;
		}
		int clamped = Math.max(0, Math.min(index, text.length() - 1));
		char current = text.charAt(clamped);
		int start = clamped;
		int end = clamped + 1;
		if (Character.isWhitespace(current)) {
			while (start > 0 && Character.isWhitespace(text.charAt(start - 1))) {
				start--;
			}
			while (end < text.length() && Character.isWhitespace(text.charAt(end))) {
				end++;
			}
		} else if (isWordCharacter(current)) {
			while (start > 0 && isWordCharacter(text.charAt(start - 1))) {
				start--;
			}
			while (end < text.length() && isWordCharacter(text.charAt(end))) {
				end++;
			}
		}
		selectRange(start, end);
	}

	int indexForX(TextRenderer textRenderer, double x) {
		if (x <= 0) {
			return 0;
		}
		int previousWidth = 0;
		for (int index = 1; index <= text.length(); index++) {
			int width = textRenderer.getWidth(text.substring(0, index));
			if (x < width) {
				return x - previousWidth < width - x ? index - 1 : index;
			}
			previousWidth = width;
		}
		return text.length();
	}

	void ensureCursorVisible(TextRenderer textRenderer, int innerWidth) {
		int clampedInnerWidth = Math.max(1, innerWidth);
		int cursorX = textRenderer.getWidth(text.substring(0, cursor));
		int maxScroll = Math.max(0, textRenderer.getWidth(text) - clampedInnerWidth);
		if (cursorX < scrollX) {
			scrollX = cursorX;
		} else if (cursorX > scrollX + clampedInnerWidth - 1) {
			scrollX = cursorX - (clampedInnerWidth - 1);
		}
		scrollX = Math.max(0, Math.min(scrollX, maxScroll));
	}

	private void replaceSelection(String replacement) {
		replaceRange(selectionStart(), selectionEnd(), replacement);
	}

	private void replaceRange(int start, int end, String replacement) {
		Snapshot before = snapshot();
		int safeStart = clampIndex(start);
		int safeEnd = clampIndex(end);
		String sanitized = sanitize(replacement);
		int available = MAX_LENGTH - (text.length() - (safeEnd - safeStart));
		if (available < sanitized.length()) {
			sanitized = sanitized.substring(0, Math.max(0, available));
		}
		String updated = text.substring(0, safeStart) + sanitized + text.substring(safeEnd);
		if (text.equals(updated)) {
			cursor = safeStart + sanitized.length();
			anchor = cursor;
			return;
		}
		pushUndo(before);
		redoStack.clear();
		text = updated;
		cursor = safeStart + sanitized.length();
		anchor = cursor;
	}

	private int previousWordBoundary(int index) {
		int cursorIndex = clampIndex(index);
		while (cursorIndex > 0 && Character.isWhitespace(text.charAt(cursorIndex - 1))) {
			cursorIndex--;
		}
		if (cursorIndex > 0 && isWordCharacter(text.charAt(cursorIndex - 1))) {
			while (cursorIndex > 0 && isWordCharacter(text.charAt(cursorIndex - 1))) {
				cursorIndex--;
			}
			return cursorIndex;
		}
		while (cursorIndex > 0 && !Character.isWhitespace(text.charAt(cursorIndex - 1)) && !isWordCharacter(text.charAt(cursorIndex - 1))) {
			cursorIndex--;
		}
		return cursorIndex;
	}

	private int nextWordBoundary(int index) {
		int cursorIndex = clampIndex(index);
		while (cursorIndex < text.length() && Character.isWhitespace(text.charAt(cursorIndex))) {
			cursorIndex++;
		}
		if (cursorIndex < text.length() && isWordCharacter(text.charAt(cursorIndex))) {
			while (cursorIndex < text.length() && isWordCharacter(text.charAt(cursorIndex))) {
				cursorIndex++;
			}
			return cursorIndex;
		}
		while (cursorIndex < text.length() && !Character.isWhitespace(text.charAt(cursorIndex)) && !isWordCharacter(text.charAt(cursorIndex))) {
			cursorIndex++;
		}
		return cursorIndex;
	}

	private int clampIndex(int index) {
		return Math.max(0, Math.min(index, text.length()));
	}

	private static boolean isWordCharacter(char character) {
		return Character.isLetterOrDigit(character) || character == '_';
	}

	private static String sanitize(String value) {
		return value.replace("\r", "").replace("\n", "");
	}

	private Snapshot snapshot() {
		return new Snapshot(text, cursor, anchor, scrollX);
	}

	private void restore(Snapshot snapshot) {
		text = snapshot.text();
		cursor = snapshot.cursor();
		anchor = snapshot.anchor();
		scrollX = snapshot.scrollX();
	}

	private void pushUndo(Snapshot snapshot) {
		undoStack.addLast(snapshot);
		while (undoStack.size() > 128) {
			undoStack.removeFirst();
		}
	}

	private record Snapshot(String text, int cursor, int anchor, int scrollX) {
	}
}
