package com.github.squi2rel.mcng.fabric.client;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

import java.util.List;
import java.util.function.Supplier;

public final class GraphContextMenuComponent {
	private static final int PADDING = 4;
	private static final int ITEM_HEIGHT = 20;
	private static final int ITEM_PADDING_X = 10;
	private static final int MIN_WIDTH = 96;
	private static final int SCREEN_MARGIN = 8;

	private final Supplier<GraphEditorUiConfig> uiConfigSupplier;
	private final Supplier<GraphEditorI18n> i18nSupplier;

	private boolean open;
	private int x;
	private int y;
	private int width;
	private int height;
	private double anchorScreenX;
	private double anchorScreenY;
	private List<MenuItem> items = List.of();

	public GraphContextMenuComponent(Supplier<GraphEditorUiConfig> uiConfigSupplier, Supplier<GraphEditorI18n> i18nSupplier) {
		this.uiConfigSupplier = uiConfigSupplier;
		this.i18nSupplier = i18nSupplier;
	}

	public void openNodeMenu(double anchorScreenX, double anchorScreenY, GraphEditorBounds bounds, TextRenderer textRenderer) {
		open(anchorScreenX, anchorScreenY, bounds, textRenderer, List.of(
			new MenuItem(GraphEditorTranslations.ui(i18nSupplier.get(), "context_menu.copy", "Copy"), MenuAction.COPY, true),
			new MenuItem(GraphEditorTranslations.ui(i18nSupplier.get(), "context_menu.cut", "Cut"), MenuAction.CUT, true),
			new MenuItem(GraphEditorTranslations.ui(i18nSupplier.get(), "context_menu.delete", "Delete"), MenuAction.DELETE, true)
		));
	}

	public void openCanvasMenu(double anchorScreenX, double anchorScreenY, GraphEditorBounds bounds, TextRenderer textRenderer, boolean canPaste) {
		open(anchorScreenX, anchorScreenY, bounds, textRenderer, List.of(new MenuItem(GraphEditorTranslations.ui(i18nSupplier.get(), "context_menu.paste", "Paste"), MenuAction.PASTE, canPaste)));
	}

	public boolean isOpen() {
		return open;
	}

	public void close() {
		open = false;
		items = List.of();
	}

	public boolean blocksCanvasAt(double mouseX, double mouseY) {
		return open && mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
	}

	public MenuAction mouseClicked(double mouseX, double mouseY, int button) {
		if (!open || button != 0 || !blocksCanvasAt(mouseX, mouseY)) {
			return null;
		}
		MenuItem item = itemAt(mouseX, mouseY);
		if (item == null || !item.active()) {
			return null;
		}
		close();
		return item.action();
	}

	public void render(DrawContext context, TextRenderer textRenderer) {
		if (!open) {
			return;
		}
		GraphEditorUiConfig uiConfig = uiConfigSupplier.get();
		GraphEditorTheme theme = uiConfig.theme();
		EditorStyleRenderer.drawBox(context, x, y, width, height, theme.panelBackgroundColor(), theme.panelBorderColor(), uiConfig);

		for (int index = 0; index < items.size(); index++) {
			MenuItem item = items.get(index);
			int itemY = y + PADDING + (index * ITEM_HEIGHT);
			int fill = item.active()
				? theme.nodeBodyColor()
				: EditorStyleRenderer.darken(theme.panelBackgroundColor(), 0.08f);
			int border = item.active() ? theme.panelBorderColor() : EditorStyleRenderer.darken(theme.panelBorderColor(), 0.25f);
			EditorStyleRenderer.drawBox(context, x + PADDING, itemY, width - (PADDING * 2), ITEM_HEIGHT - 2, fill, border, uiConfig);
			context.drawText(textRenderer, item.label(), x + PADDING + ITEM_PADDING_X, itemY + 6, item.active() ? theme.primaryTextColor() : theme.secondaryTextColor(), false);
		}
	}

	double anchorScreenX() {
		return anchorScreenX;
	}

	double anchorScreenY() {
		return anchorScreenY;
	}

	private void open(double anchorScreenX, double anchorScreenY, GraphEditorBounds bounds, TextRenderer textRenderer, List<MenuItem> items) {
		this.anchorScreenX = anchorScreenX;
		this.anchorScreenY = anchorScreenY;
		this.items = List.copyOf(items);
		this.width = Math.max(MIN_WIDTH, this.items.stream().mapToInt(item -> textRenderer.getWidth(item.label()) + (ITEM_PADDING_X * 2) + (PADDING * 2)).max().orElse(MIN_WIDTH));
		this.height = (this.items.size() * ITEM_HEIGHT) + (PADDING * 2);
		int minX = bounds.x() + SCREEN_MARGIN;
		int maxX = Math.max(minX, bounds.right() - width - SCREEN_MARGIN);
		int minY = bounds.y() + SCREEN_MARGIN;
		int maxY = Math.max(minY, bounds.bottom() - height - SCREEN_MARGIN);
		this.x = clamp((int) Math.round(anchorScreenX), minX, maxX);
		this.y = clamp((int) Math.round(anchorScreenY), minY, maxY);
		this.open = true;
	}

	private MenuItem itemAt(double mouseX, double mouseY) {
		for (int index = 0; index < items.size(); index++) {
			int itemY = y + PADDING + (index * ITEM_HEIGHT);
			if (mouseX >= x + PADDING && mouseX <= x + width - PADDING && mouseY >= itemY && mouseY <= itemY + ITEM_HEIGHT - 2) {
				return items.get(index);
			}
		}
		return null;
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	public enum MenuAction {
		COPY,
		CUT,
		DELETE,
		PASTE
	}

	private record MenuItem(String label, MenuAction action, boolean active) {
	}
}
