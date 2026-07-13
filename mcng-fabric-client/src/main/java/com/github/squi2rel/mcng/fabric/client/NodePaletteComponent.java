package com.github.squi2rel.mcng.fabric.client;

import com.github.squi2rel.mcng.core.NodeType;
import com.github.squi2rel.mcng.core.PortChannel;
import com.github.squi2rel.mcng.core.NodeTypeRegistry;
import com.github.squi2rel.mcng.core.PortTypeRegistry;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class NodePaletteComponent {
	private static final NodeComponentRegistry EMPTY_COMPONENT_REGISTRY = new NodeComponentRegistry();
	private static final int TOGGLE_MARGIN_X = 10;
	private static final int TOGGLE_MARGIN_Y = 6;
	private static final int TOGGLE_WIDTH = 90;
	private static final int TOGGLE_HEIGHT = 20;
	private static final int PANEL_MARGIN_X = 10;
	private static final int PANEL_TOP = 32;
	private static final int PANEL_WIDTH = 220;
	private static final int PANEL_PADDING = 10;
	private static final int SEARCH_HEIGHT = 18;
	private static final int SECTION_HEADER_HEIGHT = 16;
	private static final int ENTRY_HEIGHT = 24;
	private static final int LIST_TOP_GAP = 8;
	private static final int LIST_BOTTOM_PADDING = 10;
	private static final int SCROLL_STEP = 18;
	private static final double DRAG_THRESHOLD = 4.0;

	private final Supplier<List<NodePaletteSection>> sectionsSupplier;
	private final Supplier<NodeTypeRegistry> registrySupplier;
	private final Supplier<NodeComponentRegistry> componentRegistrySupplier;
	private final NodePaletteState state = new NodePaletteState();
	private final Supplier<GraphEditorUiConfig> uiConfigSupplier;
	private final Supplier<GraphEditorI18n> i18nSupplier;
	private final PortTypeRegistry portTypes;
	private final GraphTextFieldComponent searchField;

	private GraphEditorBounds bounds = new GraphEditorBounds(0, 0, 0, 0);
	private TextRenderer textRenderer;

	public NodePaletteComponent(
		Supplier<List<NodePaletteSection>> sectionsSupplier,
		Supplier<NodeTypeRegistry> registrySupplier,
		PortTypeRegistry portTypes,
		Supplier<GraphEditorUiConfig> uiConfigSupplier,
		Supplier<GraphEditorI18n> i18nSupplier,
		Supplier<String> clipboardReader,
		Consumer<String> clipboardWriter
	) {
		this(sectionsSupplier, registrySupplier, () -> EMPTY_COMPONENT_REGISTRY, portTypes, uiConfigSupplier, i18nSupplier, clipboardReader, clipboardWriter);
	}

	public NodePaletteComponent(
		Supplier<List<NodePaletteSection>> sectionsSupplier,
		Supplier<NodeTypeRegistry> registrySupplier,
		Supplier<NodeComponentRegistry> componentRegistrySupplier,
		PortTypeRegistry portTypes,
		Supplier<GraphEditorUiConfig> uiConfigSupplier,
		Supplier<GraphEditorI18n> i18nSupplier,
		Supplier<String> clipboardReader,
		Consumer<String> clipboardWriter
	) {
		this.sectionsSupplier = sectionsSupplier;
		this.registrySupplier = registrySupplier;
		this.componentRegistrySupplier = componentRegistrySupplier;
		this.portTypes = portTypes;
		this.uiConfigSupplier = uiConfigSupplier;
		this.i18nSupplier = i18nSupplier;
		this.searchField = new GraphTextFieldComponent(
			() -> GraphEditorTranslations.ui(i18nSupplier.get(), "palette.search_placeholder", "Search nodes"),
			clipboardReader,
			clipboardWriter,
			state::setQuery
		);
	}

	public void init(TextRenderer textRenderer, GraphEditorBounds bounds) {
		this.textRenderer = textRenderer;
		setBounds(bounds);
		searchField.setText(state.query());
	}

	public void setBounds(GraphEditorBounds bounds) {
		this.bounds = bounds;
		searchField.setBounds(searchFieldBounds());
	}

	public boolean isOpen() {
		return state.open();
	}

	public void toggle() {
		state.toggle();
		searchField.setFocused(false);
	}

	public void blurSearch() {
		searchField.setFocused(false);
	}

	public boolean isSearchFocused() {
		return state.open() && searchField.focused();
	}

	public int sidebarRight() {
		return panelX() + PANEL_WIDTH;
	}

	public boolean blocksCanvasAt(double mouseX, double mouseY) {
		return state.open() && mouseX >= panelX() && mouseX <= panelX() + PANEL_WIDTH && mouseY >= panelY() && mouseY <= panelBottom();
	}

	public GraphCursorManager.CursorKind cursorKindAt(double mouseX, double mouseY) {
		if (state.open()) {
			if (searchField.contains(mouseX, mouseY)) {
				return GraphCursorManager.CursorKind.TEXT;
			}
			Row row = rowAt(mouseX, mouseY);
			if (row != null && row.type() == RowType.ENTRY) {
				return GraphCursorManager.CursorKind.GRAB;
			}
		}
		return GraphCursorManager.CursorKind.DEFAULT;
	}

	public void render(DrawContext context, TextRenderer textRenderer, int mouseX, int mouseY, float delta) {
		this.textRenderer = textRenderer;
		GraphEditorUiConfig uiConfig = uiConfigSupplier.get();
		GraphEditorTheme theme = uiConfig.theme();
		GraphEditorI18n i18n = i18nSupplier.get();
		renderToggleButton(context, textRenderer, uiConfig, theme);
		if (!state.open()) {
			return;
		}

		EditorStyleRenderer.drawBox(context, panelX(), panelY(), PANEL_WIDTH, panelBottom() - panelY(), theme.panelBackgroundColor(), theme.panelBorderColor(), uiConfig);
		context.drawText(textRenderer, GraphEditorTranslations.ui(i18n, "palette.title", "Nodes"), panelX() + PANEL_PADDING, panelY() + 10, theme.primaryTextColor(), false);
		searchField.render(context, textRenderer, theme, uiConfig);

		int listTop = panelY() + 28 + SEARCH_HEIGHT + LIST_TOP_GAP;
		int listBottom = panelBottom() - LIST_BOTTOM_PADDING;
		List<Row> rows = visibleRows();
		double maxScroll = Math.max(0, totalContentHeight(rows) - (listBottom - listTop));
		state.setScrollOffset(Math.min(state.scrollOffset(), maxScroll));

		context.enableScissor(panelX() + 1, listTop, panelX() + PANEL_WIDTH - 1, listBottom);
		try {
			int y = listTop - (int) Math.round(state.scrollOffset());
			for (Row row : rows) {
				if (row.type() == RowType.SECTION) {
					if (y + SECTION_HEADER_HEIGHT >= listTop && y <= listBottom) {
						context.drawText(textRenderer, row.title(), panelX() + PANEL_PADDING, y + 4, theme.accentColor(), false);
					}
					y += SECTION_HEADER_HEIGHT;
					continue;
				}

				if (y + ENTRY_HEIGHT >= listTop && y <= listBottom) {
					boolean hovered = mouseX >= panelX() + PANEL_PADDING && mouseX <= panelX() + PANEL_WIDTH - PANEL_PADDING && mouseY >= y && mouseY <= y + ENTRY_HEIGHT;
					boolean pressed = row.entry().equals(state.pressedEntry()) && !state.dragging();
					int fill = pressed
						? EditorStyleRenderer.blend(theme.nodeBodyColor(), theme.accentColor(), 0.24f)
						: hovered ? EditorStyleRenderer.brighten(theme.nodeBodyColor(), 0.08f) : theme.nodeBodyColor();
					EditorStyleRenderer.drawBox(context, panelX() + PANEL_PADDING, y, PANEL_WIDTH - (PANEL_PADDING * 2), ENTRY_HEIGHT, fill, theme.panelBorderColor(), uiConfig);
					context.drawText(textRenderer, row.entry().displayName(), panelX() + PANEL_PADDING + 6, y + 5, theme.primaryTextColor(), false);
					context.drawText(textRenderer, row.entry().subtitle(), panelX() + PANEL_PADDING + 6, y + 14, theme.secondaryTextColor(), false);
				}
				y += ENTRY_HEIGHT;
			}
		} finally {
			context.disableScissor();
		}

		renderScrollbar(context, rows, listTop, listBottom, uiConfig, theme);
	}

	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (button == 0 && insideToggleButton(mouseX, mouseY)) {
			toggle();
			return true;
		}
		if (!state.open()) {
			return false;
		}
		if (!bounds.contains(mouseX, mouseY)) {
			searchField.setFocused(false);
			return false;
		}

		boolean searchHandled = searchField.mouseClicked(mouseX, mouseY, button, textRenderer);
		if (searchHandled) {
			return true;
		}
		if (!blocksCanvasAt(mouseX, mouseY)) {
			return false;
		}
		if (button != 0) {
			return true;
		}

		Row row = rowAt(mouseX, mouseY);
		if (row != null && row.type() == RowType.ENTRY) {
			state.setPressedEntry(row.entry(), mouseX, mouseY);
		}
		return true;
	}

	public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
		if (searchField.mouseDragged(mouseX, button, textRenderer)) {
			return true;
		}
		if (button != 0 || !state.open()) {
			return false;
		}

		if (state.pressedEntry() != null) {
			double dx = mouseX - state.pressedMouseX();
			double dy = mouseY - state.pressedMouseY();
			if (!state.dragging() && ((dx * dx) + (dy * dy)) >= (DRAG_THRESHOLD * DRAG_THRESHOLD)) {
				state.startDragging(state.pressedEntry());
			}
			if (state.dragging()) {
				state.updateDragMouse(mouseX, mouseY);
			}
			return true;
		}

		return blocksCanvasAt(mouseX, mouseY);
	}

	public NodePaletteInteractionResult mouseReleased(double mouseX, double mouseY, int button) {
		searchField.mouseReleased(button);
		if (button != 0) {
			return state.open() && blocksCanvasAt(mouseX, mouseY) ? NodePaletteInteractionResult.handledResult() : NodePaletteInteractionResult.ignoredResult();
		}
		if (!state.open()) {
			return NodePaletteInteractionResult.ignoredResult();
		}

		try {
			if (state.dragging() && state.dragEntry() != null) {
				if (!blocksCanvasAt(mouseX, mouseY) && bounds.contains(mouseX, mouseY)) {
					return NodePaletteInteractionResult.actionResult(new NodePaletteAction.CreateAtPointer(state.dragEntry().nodeTypeId(), mouseX, mouseY));
				}
				return NodePaletteInteractionResult.handledResult();
			}
			if (state.pressedEntry() != null) {
				Row row = rowAt(mouseX, mouseY);
				if (row != null && row.type() == RowType.ENTRY && row.entry().equals(state.pressedEntry())) {
					return NodePaletteInteractionResult.actionResult(new NodePaletteAction.CreateAtCenter(row.entry().nodeTypeId()));
				}
				return NodePaletteInteractionResult.handledResult();
			}
			return blocksCanvasAt(mouseX, mouseY) ? NodePaletteInteractionResult.handledResult() : NodePaletteInteractionResult.ignoredResult();
		} finally {
			state.clearInteraction();
		}
	}

	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		if (!state.open() || !blocksCanvasAt(mouseX, mouseY)) {
			return false;
		}
		double scrollAmount = resolveScrollAmount(horizontalAmount, verticalAmount);
		if (scrollAmount == 0.0) {
			return true;
		}
		List<Row> rows = visibleRows();
		int listTop = panelY() + 28 + SEARCH_HEIGHT + LIST_TOP_GAP;
		int listBottom = panelBottom() - LIST_BOTTOM_PADDING;
		double maxScroll = Math.max(0, totalContentHeight(rows) - (listBottom - listTop));
		double magnitude = Math.max(1.0, Math.abs(scrollAmount));
		double next = state.scrollOffset() - (Math.signum(scrollAmount) * magnitude * SCROLL_STEP);
		state.setScrollOffset(Math.max(0, Math.min(maxScroll, next)));
		return true;
	}

	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		return state.open() && searchField.keyPressed(keyCode, scanCode, modifiers, textRenderer);
	}

	public boolean charTyped(char chr, int modifiers) {
		return state.open() && searchField.charTyped(chr, modifiers, textRenderer);
	}

	public void renderDragPreview(DrawContext context, TextRenderer textRenderer) {
		if (!state.dragging() || state.dragEntry() == null) {
			return;
		}
		GraphEditorUiConfig uiConfig = uiConfigSupplier.get();
		GraphEditorTheme theme = uiConfig.theme();
		NodeType<?> nodeType = registrySupplier.get().getOrThrow(state.dragEntry().nodeTypeId());
		NodeComponentDefinition definition = componentRegistrySupplier.get().find(nodeType.id()).orElse(null);
		NodeBodyComponent component = definition == null ? null : definition.factory().create();
		ResizePolicy resizePolicy = definition == null ? ResizePolicy.none() : definition.resizePolicy();
		try {
			NodeWidget widget = NodeWidget.preview(nodeType, uiConfig, i18nSupplier.get(), component, resizePolicy, (int) Math.round(state.dragMouseX()), (int) Math.round(state.dragMouseY()));
			NodeWidgetRenderer.render(
				context,
				textRenderer,
				widget,
				uiConfig,
				theme,
				false,
				false,
				false,
				port -> port.definition().channel() == PortChannel.CONTROL ? theme.controlFlowColor() : portTypes.colorOf(port.definition().type()),
				port -> false,
				(nodeId, portId) -> false,
				(nodeId, key) -> false
			);
		} finally {
			if (component != null) {
				component.close();
			}
		}
	}

	private void renderToggleButton(DrawContext context, TextRenderer textRenderer, GraphEditorUiConfig uiConfig, GraphEditorTheme theme) {
		int fill = state.open() ? EditorStyleRenderer.blend(theme.panelBackgroundColor(), theme.accentColor(), 0.18f) : theme.panelBackgroundColor();
		EditorStyleRenderer.drawBox(context, toggleX(), toggleY(), TOGGLE_WIDTH, TOGGLE_HEIGHT, fill, theme.panelBorderColor(), uiConfig);
		context.drawText(
			textRenderer,
			state.open()
				? GraphEditorTranslations.ui(i18nSupplier.get(), "palette.toggle_open", "Nodes [Tab]")
				: GraphEditorTranslations.ui(i18nSupplier.get(), "palette.toggle_closed", "Open [Tab]"),
			toggleX() + 8,
			toggleY() + 6,
			theme.primaryTextColor(),
			false
		);
	}

	private void renderScrollbar(DrawContext context, List<Row> rows, int listTop, int listBottom, GraphEditorUiConfig uiConfig, GraphEditorTheme theme) {
		int viewportHeight = listBottom - listTop;
		int contentHeight = totalContentHeight(rows);
		if (contentHeight <= viewportHeight) {
			return;
		}
		int trackX = panelX() + PANEL_WIDTH - 8;
		EditorStyleRenderer.drawBox(context, trackX, listTop, 4, viewportHeight, EditorStyleRenderer.darken(theme.panelBackgroundColor(), 0.15f), theme.panelBorderColor(), uiConfig);
		double thumbRatio = viewportHeight / (double) contentHeight;
		int thumbHeight = Math.max(18, (int) Math.round(viewportHeight * thumbRatio));
		double maxScroll = contentHeight - viewportHeight;
		double scrollRatio = maxScroll <= 0 ? 0 : state.scrollOffset() / maxScroll;
		int thumbY = listTop + (int) Math.round((viewportHeight - thumbHeight) * scrollRatio);
		EditorStyleRenderer.drawBox(context, trackX - 1, thumbY, 6, thumbHeight, theme.accentColor(), theme.panelBorderColor(), uiConfig);
	}

	private boolean insideToggleButton(double mouseX, double mouseY) {
		return mouseX >= toggleX() && mouseX <= toggleX() + TOGGLE_WIDTH && mouseY >= toggleY() && mouseY <= toggleY() + TOGGLE_HEIGHT;
	}

	private List<NodePaletteSection> filteredSections() {
		return NodePaletteCatalog.filterSections(sectionsSupplier.get(), state.query());
	}

	private List<Row> visibleRows() {
		List<Row> rows = new ArrayList<>();
		for (NodePaletteSection section : filteredSections()) {
			rows.add(Row.section(section.title()));
			section.entries().forEach(entry -> rows.add(Row.entry(entry)));
		}
		return rows;
	}

	private int totalContentHeight(List<Row> rows) {
		int total = 0;
		for (Row row : rows) {
			total += row.type() == RowType.SECTION ? SECTION_HEADER_HEIGHT : ENTRY_HEIGHT;
		}
		return total;
	}

	private static double resolveScrollAmount(double horizontalAmount, double verticalAmount) {
		if (Math.abs(verticalAmount) >= Math.abs(horizontalAmount)) {
			return verticalAmount;
		}
		return horizontalAmount;
	}

	private Row rowAt(double mouseX, double mouseY) {
		if (!blocksCanvasAt(mouseX, mouseY)) {
			return null;
		}
		int listTop = panelY() + 28 + SEARCH_HEIGHT + LIST_TOP_GAP;
		int listBottom = panelBottom() - LIST_BOTTOM_PADDING;
		if (mouseY < listTop || mouseY > listBottom) {
			return null;
		}
		int y = listTop - (int) Math.round(state.scrollOffset());
		for (Row row : visibleRows()) {
			int rowHeight = row.type() == RowType.SECTION ? SECTION_HEADER_HEIGHT : ENTRY_HEIGHT;
			if (row.type() == RowType.ENTRY
				&& mouseX >= panelX() + PANEL_PADDING
				&& mouseX <= panelX() + PANEL_WIDTH - PANEL_PADDING
				&& mouseY >= y
				&& mouseY <= y + rowHeight) {
				return row;
			}
			y += rowHeight;
		}
		return null;
	}

	private int toggleX() {
		return bounds.x() + TOGGLE_MARGIN_X;
	}

	private int toggleY() {
		return bounds.y() + TOGGLE_MARGIN_Y;
	}

	private int panelX() {
		return bounds.x() + PANEL_MARGIN_X;
	}

	private int panelY() {
		return bounds.y() + PANEL_TOP;
	}

	private int panelBottom() {
		return bounds.bottom() - 10;
	}

	private NodeWidget.Bounds searchFieldBounds() {
		return new NodeWidget.Bounds(panelX() + PANEL_PADDING, panelY() + 28, PANEL_WIDTH - (PANEL_PADDING * 2), SEARCH_HEIGHT);
	}

	private enum RowType {
		SECTION,
		ENTRY
	}

	private record Row(RowType type, String title, NodePaletteEntry entry) {
		static Row section(String title) {
			return new Row(RowType.SECTION, title, null);
		}

		static Row entry(NodePaletteEntry entry) {
			return new Row(RowType.ENTRY, null, entry);
		}
	}
}
