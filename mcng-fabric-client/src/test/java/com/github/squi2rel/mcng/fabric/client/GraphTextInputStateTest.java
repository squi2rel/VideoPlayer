package com.github.squi2rel.mcng.fabric.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphTextInputStateTest {
	@Test
	void undoRedoRestoresTextEdits() {
		GraphTextInputState state = new GraphTextInputState("ab");

		state.insert("c");
		assertEquals("abc", state.text());
		assertTrue(state.canUndo());

		assertTrue(state.undo());
		assertEquals("ab", state.text());
		assertTrue(state.canRedo());

		assertTrue(state.redo());
		assertEquals("abc", state.text());
	}

	@Test
	void cursorMovementDoesNotCreateUndoEntries() {
		GraphTextInputState state = new GraphTextInputState("abc");

		state.moveLeft(false, false);
		state.moveLeft(false, true);

		assertFalse(state.canUndo());
	}
}
