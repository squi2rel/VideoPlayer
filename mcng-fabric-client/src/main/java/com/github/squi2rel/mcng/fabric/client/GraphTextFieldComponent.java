package com.github.squi2rel.mcng.fabric.client;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import org.lwjgl.glfw.GLFW;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

final class GraphTextFieldComponent {
	private final Supplier<String> clipboardReader;
	private final Consumer<String> clipboardWriter;
	private final Consumer<String> changedListener;
	private final Supplier<String> placeholderSupplier;

	private GraphTextInputState state = new GraphTextInputState("");
	private NodeWidget.Bounds bounds = new NodeWidget.Bounds(0, 0, 0, 0);
	private boolean focused;
	private boolean draggingPointer;
	private long lastPointerDownAt;
	private int lastPointerIndex;

	GraphTextFieldComponent(String placeholder, Supplier<String> clipboardReader, Consumer<String> clipboardWriter, Consumer<String> changedListener) {
		this(() -> placeholder, clipboardReader, clipboardWriter, changedListener);
	}

	GraphTextFieldComponent(Supplier<String> placeholderSupplier, Supplier<String> clipboardReader, Consumer<String> clipboardWriter, Consumer<String> changedListener) {
		this.placeholderSupplier = Objects.requireNonNull(placeholderSupplier, "placeholderSupplier");
		this.clipboardReader = Objects.requireNonNull(clipboardReader, "clipboardReader");
		this.clipboardWriter = Objects.requireNonNull(clipboardWriter, "clipboardWriter");
		this.changedListener = Objects.requireNonNull(changedListener, "changedListener");
	}

	void setBounds(NodeWidget.Bounds bounds) {
		this.bounds = Objects.requireNonNull(bounds, "bounds");
	}

	NodeWidget.Bounds bounds() {
		return bounds;
	}

	boolean contains(double mouseX, double mouseY) {
		return bounds.contains(mouseX, mouseY);
	}

	void setText(String text) {
		state = new GraphTextInputState(text);
		notifyChanged();
	}

	String text() {
		return state.text();
	}

	boolean focused() {
		return focused;
	}

	void setFocused(boolean focused) {
		this.focused = focused;
		if (!focused) {
			draggingPointer = false;
		}
	}

	void render(DrawContext context, TextRenderer textRenderer, GraphEditorTheme theme, GraphEditorUiConfig uiConfig) {
		GraphTextInputRenderer.renderFrame(context, bounds, theme, uiConfig, focused);
		String placeholder = placeholderSupplier.get();
		int scissorLeft = bounds.x() + GraphTextInputRenderer.CONTENT_PADDING_X;
		int scissorTop = bounds.y() + GraphTextInputRenderer.CONTENT_PADDING_Y;
		int scissorRight = bounds.x() + bounds.width() - GraphTextInputRenderer.CONTENT_PADDING_X;
		int scissorBottom = bounds.y() + bounds.height() - GraphTextInputRenderer.CONTENT_PADDING_Y;
		context.enableScissor(scissorLeft, scissorTop, scissorRight, scissorBottom);
		try {
			if (!state.text().isEmpty()) {
				GraphTextInputRenderer.renderContent(context, textRenderer, bounds, state, theme, focused);
			} else {
				int baselineY = bounds.y() + Math.max(2, (bounds.height() - textRenderer.fontHeight) / 2);
				context.drawText(textRenderer, placeholder, bounds.x() + GraphTextInputRenderer.CONTENT_PADDING_X, baselineY, theme.secondaryTextColor(), false);
				if (focused && (System.currentTimeMillis() / 530L) % 2L == 0L) {
					int cursorX = bounds.x() + GraphTextInputRenderer.CONTENT_PADDING_X;
					context.fill(cursorX, bounds.y() + GraphTextInputRenderer.CONTENT_PADDING_Y, cursorX + 1, bounds.y() + bounds.height() - GraphTextInputRenderer.CONTENT_PADDING_Y, theme.primaryTextColor());
				}
			}
		} finally {
			context.disableScissor();
		}
	}

	boolean mouseClicked(double mouseX, double mouseY, int button, TextRenderer textRenderer) {
		if (button != 0) {
			return false;
		}
		if (!bounds.contains(mouseX, mouseY)) {
			setFocused(false);
			return false;
		}
		setFocused(true);
		handlePointerDown(textRenderer, mouseX, System.currentTimeMillis());
		return true;
	}

	boolean mouseDragged(double mouseX, int button, TextRenderer textRenderer) {
		if (!focused || button != 0 || !draggingPointer) {
			return false;
		}
		state.setCursor(indexForScreenX(textRenderer, mouseX), true);
		ensureCursorVisible(textRenderer);
		return true;
	}

	boolean mouseReleased(int button) {
		if (button != 0) {
			return false;
		}
		draggingPointer = false;
		return focused;
	}

	boolean keyPressed(int keyCode, int scanCode, int modifiers, TextRenderer textRenderer) {
		if (!focused) {
			return false;
		}
		boolean controlDown = (modifiers & GLFW.GLFW_MOD_CONTROL) != 0;
		boolean shiftDown = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0;
		if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
			setFocused(false);
			return true;
		}
		if (controlDown) {
			switch (keyCode) {
				case GLFW.GLFW_KEY_Z -> {
					boolean changed = shiftDown ? state.redo() : state.undo();
					if (changed) {
						notifyChanged();
						ensureCursorVisible(textRenderer);
					}
					return true;
				}
				case GLFW.GLFW_KEY_A -> {
					state.selectAll();
					ensureCursorVisible(textRenderer);
					return true;
				}
				case GLFW.GLFW_KEY_C -> {
					clipboardWriter.accept(state.selectedText());
					return true;
				}
				case GLFW.GLFW_KEY_X -> {
					clipboardWriter.accept(state.selectedText());
					state.insert("");
					notifyChanged();
					ensureCursorVisible(textRenderer);
					return true;
				}
				case GLFW.GLFW_KEY_V -> {
					state.insert(clipboardReader.get());
					notifyChanged();
					ensureCursorVisible(textRenderer);
					return true;
				}
				default -> {
				}
			}
		}
		switch (keyCode) {
			case GLFW.GLFW_KEY_LEFT -> state.moveLeft(controlDown, shiftDown);
			case GLFW.GLFW_KEY_RIGHT -> state.moveRight(controlDown, shiftDown);
			case GLFW.GLFW_KEY_HOME -> state.moveHome(shiftDown);
			case GLFW.GLFW_KEY_END -> state.moveEnd(shiftDown);
			case GLFW.GLFW_KEY_BACKSPACE -> {
				state.backspace(controlDown);
				notifyChanged();
			}
			case GLFW.GLFW_KEY_DELETE -> {
				state.delete(controlDown);
				notifyChanged();
			}
			default -> {
				return false;
			}
		}
		ensureCursorVisible(textRenderer);
		return true;
	}

	boolean charTyped(char chr, int modifiers, TextRenderer textRenderer) {
		if (!focused) {
			return false;
		}
		if ((modifiers & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_ALT)) != 0 || Character.isISOControl(chr)) {
			return false;
		}
		state.insert(String.valueOf(chr));
		notifyChanged();
		ensureCursorVisible(textRenderer);
		return true;
	}

	private void handlePointerDown(TextRenderer textRenderer, double screenX, long timeMs) {
		int index = indexForScreenX(textRenderer, screenX);
		if ((timeMs - lastPointerDownAt) <= 250L && Math.abs(index - lastPointerIndex) <= 1) {
			state.selectWordAt(index);
			draggingPointer = false;
		} else {
			state.setCursor(index, false);
			draggingPointer = true;
		}
		lastPointerDownAt = timeMs;
		lastPointerIndex = index;
		ensureCursorVisible(textRenderer);
	}

	private int indexForScreenX(TextRenderer textRenderer, double screenX) {
		double localX = screenX - (bounds.x() + GraphTextInputRenderer.CONTENT_PADDING_X) + state.scrollX();
		return state.indexForX(textRenderer, localX);
	}

	private void ensureCursorVisible(TextRenderer textRenderer) {
		state.ensureCursorVisible(textRenderer, Math.max(1, bounds.width() - (GraphTextInputRenderer.CONTENT_PADDING_X * 2)));
	}

	private void notifyChanged() {
		changedListener.accept(state.text());
	}
}
