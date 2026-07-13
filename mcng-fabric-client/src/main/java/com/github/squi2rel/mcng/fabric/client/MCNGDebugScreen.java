package com.github.squi2rel.mcng.fabric.client;

import com.github.squi2rel.mcng.core.DocumentNodeDefinitionKind;
import com.github.squi2rel.mcng.core.GraphDocument;
import com.github.squi2rel.mcng.core.GraphError;
import com.github.squi2rel.mcng.core.GraphJsonCodec;
import com.github.squi2rel.mcng.core.GraphVariableDefinition;
import com.github.squi2rel.mcng.core.NodeTypeRegistry;
import com.github.squi2rel.mcng.core.PortTypeRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.resource.language.I18n;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

public final class MCNGDebugScreen extends Screen implements GraphEditorHost {
	private static final int EXECUTION_STEP_BUDGET = 8;
	private static final int DEBUG_PANEL_WIDTH = 280;
	private static final int DEBUG_PANEL_PADDING = 8;
	private static final int DEBUG_BUTTON_WIDTH = 128;
	private static final int DEBUG_BUTTON_HEIGHT = 18;
	private static final int DEBUG_BUTTON_GAP = 8;
	private static final int DEBUG_BUTTON_ROW_GAP = 6;
	private static final int DEBUG_SETTINGS_TITLE_OFFSET = 78;
	private static final int DEBUG_SECTION_TITLE_GAP = 14;
	private static final int DEBUG_SECTION_GAP = 10;
	private static final int DEBUG_VARIABLE_ROW_HEIGHT = 16;
	private static final int DEBUG_VARIABLE_ROW_STEP = 18;
	private static final int DEBUG_VARIABLE_ROWS_VISIBLE = 5;
	private static final GraphEditorI18n MINECRAFT_I18N = (key, fallback, args) ->
		I18n.hasTranslation(key) ? I18n.translate(key, args) : GraphEditorI18n.formatFallback(fallback, key, args);
	private static final List<ThemeOption> THEME_OPTIONS = List.of(
		new ThemeOption("classic", "Classic", GraphEditorTheme.classic()),
		new ThemeOption("light", "Light", GraphEditorTheme.light()),
		new ThemeOption("high_contrast", "High Contrast", GraphEditorTheme.highContrast())
	);

	private final NodeTypeRegistry registry;
	private final PortTypeRegistry portTypes;
	private final NodePaletteRegistry paletteRegistry;
	private final NodeComponentRegistry componentRegistry;
	private final GraphJsonCodec codec;
	private final Consumer<GraphDocument> onPersist;
	private final Consumer<String> statusSink;
	private final GraphEditorSession session;
	private final GraphEditorComponent editor;

	private int themePresetIndex;
	private String statusMessage = "";
	private boolean debugPanelVisible = true;
	private String selectedVariableId;

	public MCNGDebugScreen(
		NodeTypeRegistry registry,
		PortTypeRegistry portTypes,
		NodePaletteRegistry paletteRegistry,
		GraphJsonCodec codec,
		GraphEditorUiConfig uiConfig,
		GraphDocument initialDocument,
		Consumer<GraphDocument> onPersist,
		Consumer<String> statusSink
	) {
		this(registry, portTypes, paletteRegistry, new NodeComponentRegistry(), codec, uiConfig, initialDocument, onPersist, statusSink);
	}

	public MCNGDebugScreen(
		NodeTypeRegistry registry,
		PortTypeRegistry portTypes,
		NodePaletteRegistry paletteRegistry,
		NodeComponentRegistry componentRegistry,
		GraphJsonCodec codec,
		GraphEditorUiConfig uiConfig,
		GraphDocument initialDocument,
		Consumer<GraphDocument> onPersist,
		Consumer<String> statusSink
	) {
		super(minecraftClient(), minecraftTextRenderer(), Text.translatable("mcng.ui.debug.screen_title"));
		this.registry = registry;
		this.portTypes = portTypes;
		this.paletteRegistry = paletteRegistry;
		this.componentRegistry = componentRegistry;
		this.codec = codec;
		this.themePresetIndex = presetIndexFor(uiConfig.theme());
		this.onPersist = onPersist;
		this.statusSink = statusSink;
		this.session = new GraphEditorSession(registry, portTypes, codec, initialDocument, this);
		this.editor = new GraphEditorComponent(session, paletteRegistry, componentRegistry, uiConfig);
		this.statusMessage = translate("mcng.ui.debug.command_hint", "/mcng editor");
	}

	private static MinecraftClient minecraftClient() {
		return MinecraftClient.getInstance();
	}

	private static TextRenderer minecraftTextRenderer() {
		MinecraftClient client = MinecraftClient.getInstance();
		return client == null ? null : client.textRenderer;
	}

	@Override
	protected void init() {
		super.init();
		editor.init(textRenderer, new GraphEditorBounds(0, 0, width, height));
	}

	@Override
	public void tick() {
		super.tick();
		session.tickExecution(EXECUTION_STEP_BUDGET);
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		editor.setBounds(new GraphEditorBounds(0, 0, width, height));
		editor.render(context, textRenderer, mouseX, mouseY, delta);
		renderOverlay(context);
	}

	@Override
	public boolean mouseClicked(Click click, boolean doubleClick) {
		return mouseClicked(click.x(), click.y(), click.button()) || super.mouseClicked(click, doubleClick);
	}

	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (button == 0 && clickVariableRow(mouseX, mouseY)) {
			return true;
		}
		if (button == 0 && clickDebugButton(mouseX, mouseY)) {
			return true;
		}
		if (overlayBlocksEditorAt(mouseX, mouseY)) {
			return true;
		}
		return editor.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseDragged(Click click, double deltaX, double deltaY) {
		return mouseDragged(click.x(), click.y(), click.button(), deltaX, deltaY) || super.mouseDragged(click, deltaX, deltaY);
	}

	public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
		return editor.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
	}

	@Override
	public boolean mouseReleased(Click click) {
		return mouseReleased(click.x(), click.y(), click.button()) || super.mouseReleased(click);
	}

	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		return editor.mouseReleased(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		return editor.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount) || super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
	}

	@Override
	public boolean keyPressed(KeyInput input) {
		return keyPressed(input.key(), input.scancode(), input.modifiers()) || super.keyPressed(input);
	}

	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (editor.keyPressed(keyCode, scanCode, modifiers)) {
			return true;
		}
		if (keyCode == GLFW.GLFW_KEY_O) {
			debugPanelVisible = !debugPanelVisible;
			showMessage(debugPanelVisible
				? translate("mcng.ui.debug.message.panel_shown", "Debug panel shown")
				: translate("mcng.ui.debug.message.panel_hidden", "Debug panel hidden"));
			return true;
		}

		switch (keyCode) {
			case GLFW.GLFW_KEY_E -> session.executeGraph();
			case GLFW.GLFW_KEY_T -> session.triggerSelectedEvent();
			case GLFW.GLFW_KEY_P -> session.cancelExecution();
			case GLFW.GLFW_KEY_J -> session.exportJson();
			case GLFW.GLFW_KEY_I -> session.importFromClipboard();
			case GLFW.GLFW_KEY_DELETE, GLFW.GLFW_KEY_BACKSPACE -> session.removeSelectedNode();
			case GLFW.GLFW_KEY_R -> {
				session.replaceDocument(MCNGDebugDocuments.createDefaultDocument(registry, portTypes));
				showMessage(translate("mcng.ui.debug.message.reset_graph", "Reset debug graph"));
			}
			default -> {
				return false;
			}
		}
		return true;
	}

	@Override
	public boolean charTyped(CharInput input) {
		if (input.isValidChar()) {
			String text = input.asString();
			if (text.length() == 1 && charTyped(text.charAt(0), input.modifiers())) {
				return true;
			}
		}
		return super.charTyped(input);
	}

	public boolean charTyped(char chr, int modifiers) {
		if (editor.charTyped(chr, modifiers)) {
			return true;
		}
		return false;
	}

	@Override
	public void close() {
		onPersist.accept(session.document());
		editor.close();
		super.close();
	}

	@Override
	public void onDocumentChanged(GraphDocument document) {
		onPersist.accept(document);
	}

	@Override
	public void copyToClipboard(String value) {
		if (client != null) {
			client.keyboard.setClipboard(value);
		}
	}

	@Override
	public String readClipboard() {
		return client != null ? client.keyboard.getClipboard() : "";
	}

	@Override
	public void showMessage(String message) {
		statusMessage = message;
		statusSink.accept(message);
	}

	@Override
	public GraphEditorI18n i18n() {
		return MINECRAFT_I18N;
	}

	@Override
	public boolean supportsFileDialogs() {
		return true;
	}

	@Override
	public Optional<String> chooseFile(GraphFileDialogRequest request) {
		if (request == null) {
			return Optional.empty();
		}
		try (MemoryStack stack = MemoryStack.stackPush()) {
			ByteBuffer[] patterns = request.extensions().stream()
				.map(extension -> stack.UTF8("*." + extension))
				.toArray(ByteBuffer[]::new);
			PointerBuffer filterPatterns = stack.pointers(patterns);
			String initialPath = request.initialPath().isBlank() ? null : request.initialPath();
			String selected = TinyFileDialogs.tinyfd_openFileDialog(request.title(), initialPath, filterPatterns, translate("mcng.ui.debug.image_files_filter", "Image Files"), false);
			return selected == null || selected.isBlank() ? Optional.empty() : Optional.of(selected);
		} catch (RuntimeException | UnsatisfiedLinkError exception) {
			showMessage(translate("mcng.ui.debug.message.file_dialog_failed", "Failed to open file dialog: %s", exception.getMessage()));
			return Optional.empty();
		}
	}

	private void renderOverlay(DrawContext context) {
		GraphEditorUiConfig uiConfig = editor.uiConfig();
		GraphEditorTheme theme = uiConfig.theme();
		List<String> help = helpLines();

		int panelWidth = 420;
		int panelHeight = 110;
		int x = editor.isPaletteOpen() ? editor.paletteSidebarRight() + 10 : 10;
		int y = 36;
		EditorStyleRenderer.drawBox(context, x, y, panelWidth, panelHeight, theme.panelBackgroundColor(), theme.panelBorderColor(), uiConfig);
		context.drawText(textRenderer, title, x + 8, y + 8, theme.primaryTextColor(), false);
		for (int index = 0; index < help.size(); index++) {
			context.drawText(textRenderer, help.get(index), x + 8, y + 24 + (index * 12), theme.secondaryTextColor(), false);
		}

		int statusY = y + panelHeight + 6;
		EditorStyleRenderer.drawBox(context, x, statusY, panelWidth, 16, theme.panelBackgroundColor(), theme.panelBorderColor(), uiConfig);
		context.drawText(textRenderer, translate("mcng.ui.debug.status", "Status: %s", statusMessage), x + 8, statusY + 4, theme.accentColor(), false);

		if (!debugPanelVisible) {
			return;
		}

		DebugPanelLayout layout = debugPanelLayout();
		EditorStyleRenderer.drawBox(context, layout.x(), layout.y(), layout.width(), layout.height(), theme.panelBackgroundColor(), theme.panelBorderColor(), uiConfig);
		context.drawText(textRenderer, translate("mcng.ui.debug.panel_title", "Debug"), layout.x() + 8, layout.y() + 8, theme.primaryTextColor(), false);
		context.drawText(textRenderer, session.isExecutionRunning() ? translate("mcng.ui.debug.running", "Running") : translate("mcng.ui.debug.idle", "Idle"), layout.x() + layout.width() - 46, layout.y() + 8, session.isExecutionRunning() ? theme.executionColor() : theme.secondaryTextColor(), false);

		List<String> debug = session.debugMessages();
		for (int index = 0; index < Math.min(debug.size(), 2); index++) {
			context.drawText(textRenderer, debug.get(debug.size() - 1 - index), layout.x() + 8, layout.y() + 24 + (index * 12), theme.secondaryTextColor(), false);
		}

		List<GraphError> errors = session.lastErrors();
		for (int index = 0; index < Math.min(errors.size(), 2); index++) {
			context.drawText(textRenderer, GraphEditorTranslations.formatError(i18n(), errors.get(index)), layout.x() + 8, layout.y() + 50 + (index * 10), theme.errorColor(), false);
		}

		context.drawText(textRenderer, translate("mcng.ui.debug.section.editor", "Editor"), layout.x() + DEBUG_PANEL_PADDING, settingsTitleY(layout), theme.primaryTextColor(), false);

		for (DebugButton button : debugButtons(layout)) {
			int fill = button.active()
				? EditorStyleRenderer.blend(theme.nodeBodyColor(), theme.accentColor(), 0.22f)
				: theme.nodeBodyColor();
			int border = button.active() ? theme.accentColor() : theme.panelBorderColor();
			EditorStyleRenderer.drawBox(context, button.x(), button.y(), button.width(), button.height(), fill, border, uiConfig);
			context.drawText(textRenderer, button.label(), button.x() + 6, button.y() + 5, theme.primaryTextColor(), false);
		}

		context.drawText(textRenderer, translate("mcng.ui.debug.section.variables", "Variables"), layout.x() + DEBUG_PANEL_PADDING, variablesTitleY(layout), theme.primaryTextColor(), false);
		for (VariableRow row : variableRows(layout)) {
			int fill = row.selected()
				? EditorStyleRenderer.blend(theme.nodeBodyColor(), theme.accentColor(), 0.2f)
				: EditorStyleRenderer.darken(theme.nodeBodyColor(), 0.02f);
			EditorStyleRenderer.drawBox(context, row.x(), row.y(), row.width(), row.height(), fill, row.selected() ? theme.accentColor() : theme.panelBorderColor(), uiConfig);
			context.drawText(textRenderer, row.label(), row.x() + 6, row.y() + 5, theme.secondaryTextColor(), false);
		}
	}

	private boolean clickDebugButton(double mouseX, double mouseY) {
		if (!debugPanelVisible) {
			return false;
		}
		for (DebugButton button : debugButtons(debugPanelLayout())) {
			if (button.contains(mouseX, mouseY)) {
				switch (button.action()) {
					case CYCLE_THEME -> cycleTheme();
					case EDGE_STYLE -> cycleEdgeStyle();
					case NODE_CORNERS -> cycleNodeCorners();
					case PORT_SHAPE -> cyclePortShape();
					case NEW_SUBGRAPH -> createDefinitionAtVisibleCenter(DocumentNodeDefinitionKind.SUBGRAPH);
					case NEW_CUSTOM -> createDefinitionAtVisibleCenter(DocumentNodeDefinitionKind.CUSTOM_NODE);
					case SELECTION_SUBGRAPH -> session.createDefinitionFromSelection(DocumentNodeDefinitionKind.SUBGRAPH);
					case SELECTION_CUSTOM -> session.createDefinitionFromSelection(DocumentNodeDefinitionKind.CUSTOM_NODE);
					case NEW_VARIABLE -> {
						GraphVariableDefinition variable = session.createVariable();
						selectedVariableId = variable.id();
						showMessage(translate("mcng.ui.debug.message.variable_created", "Created variable %s", variable.id()));
					}
					case CYCLE_VARIABLE_TYPE -> {
						if (selectedVariableId != null && session.cycleVariableType(selectedVariableId)) {
							showMessage(translate("mcng.ui.debug.message.variable_type_updated", "Variable type updated: %s", selectedVariableId));
						}
					}
					case DELETE_VARIABLE -> {
						if (selectedVariableId != null && session.removeVariable(selectedVariableId)) {
							showMessage(translate("mcng.ui.debug.message.variable_removed", "Removed variable %s", selectedVariableId));
							selectedVariableId = session.variables().stream().findFirst().map(GraphVariableDefinition::id).orElse(null);
						}
					}
					case STOP_EXECUTION -> session.cancelExecution();
				}
				return true;
			}
		}
		return false;
	}

	private boolean clickVariableRow(double mouseX, double mouseY) {
		if (!debugPanelVisible) {
			return false;
		}
		for (VariableRow row : variableRows(debugPanelLayout())) {
			if (row.contains(mouseX, mouseY)) {
				selectedVariableId = row.id();
				showMessage(translate("mcng.ui.debug.message.variable_selected", "Selected variable %s", row.id()));
				return true;
			}
		}
		return false;
	}

	private void cycleTheme() {
		themePresetIndex = (themePresetIndex + 1) % THEME_OPTIONS.size();
		ThemeOption option = THEME_OPTIONS.get(themePresetIndex);
		editor.setUiConfig(editor.uiConfig().withTheme(option.theme()));
		showMessage(translate("mcng.ui.debug.message.theme", "Theme: %s", themeLabel(option)));
	}

	private void cycleEdgeStyle() {
		EdgeStyle[] values = EdgeStyle.values();
		GraphEditorUiConfig uiConfig = editor.uiConfig();
		EdgeStyle next = values[(uiConfig.edgeStyle().ordinal() + 1) % values.length];
		editor.setUiConfig(uiConfig.withEdgeStyle(next));
		showMessage(translate("mcng.ui.debug.message.edge_style", "Edge style: %s", label(next)));
	}

	private void cycleNodeCorners() {
		GraphEditorUiConfig uiConfig = editor.uiConfig();
		NodeCornerStyle next = uiConfig.nodeCornerStyle() == NodeCornerStyle.ROUNDED ? NodeCornerStyle.SQUARE : NodeCornerStyle.ROUNDED;
		editor.setUiConfig(uiConfig.withNodeCornerStyle(next));
		showMessage(translate("mcng.ui.debug.message.node_corners", "Node corners: %s", label(next)));
	}

	private void cyclePortShape() {
		GraphEditorUiConfig uiConfig = editor.uiConfig();
		PortShape next = uiConfig.portShape() == PortShape.CIRCLE ? PortShape.SQUARE : PortShape.CIRCLE;
		editor.setUiConfig(uiConfig.withPortShape(next));
		showMessage(translate("mcng.ui.debug.message.port_shape", "Port shape: %s", label(next)));
	}

	private void createDefinitionAtVisibleCenter(DocumentNodeDefinitionKind kind) {
		GraphEditorBounds bounds = editor.bounds();
		double visibleLeft = editor.isPaletteOpen() ? editor.paletteSidebarRight() + 10.0 : bounds.x();
		double centerX = visibleLeft + ((bounds.right() - visibleLeft) / 2.0);
		double centerY = bounds.y() + (bounds.height() / 2.0);
		double x = editor.viewport().toWorldX(centerX - bounds.x()) - 80;
		double y = editor.viewport().toWorldY(centerY - (bounds.y() + 28)) - 40;
		session.createBlankDefinition(kind, x, y);
		editor.viewport().reset();
	}

	private DebugPanelLayout debugPanelLayout() {
		return new DebugPanelLayout(Math.max(10, width - (DEBUG_PANEL_WIDTH + 10)), 10, DEBUG_PANEL_WIDTH, debugPanelHeight());
	}

	private List<DebugButton> debugButtons(DebugPanelLayout layout) {
		int startX = layout.x() + DEBUG_PANEL_PADDING;
		int rightX = startX + DEBUG_BUTTON_WIDTH + DEBUG_BUTTON_GAP;
		int rowOneY = settingsRowsStartY(layout);
		int rowTwoY = nextButtonRowY(rowOneY);
		int rowThreeY = nextButtonRowY(rowTwoY);
		int rowFourY = nextButtonRowY(rowThreeY);
		int rowFiveY = variableActionsStartY(layout);
		int rowSixY = nextButtonRowY(rowFiveY);
		int rowSevenY = nextButtonRowY(rowSixY);
		ThemeOption currentTheme = THEME_OPTIONS.get(themePresetIndex);
		GraphEditorUiConfig uiConfig = editor.uiConfig();
		return List.of(
			new DebugButton(startX, rowOneY, DEBUG_BUTTON_WIDTH, DEBUG_BUTTON_HEIGHT, translate("mcng.ui.debug.button.theme", "Theme: %s", themeLabel(currentTheme)), DebugAction.CYCLE_THEME, true),
			new DebugButton(rightX, rowOneY, DEBUG_BUTTON_WIDTH, DEBUG_BUTTON_HEIGHT, translate("mcng.ui.debug.button.edge", "Edge: %s", label(uiConfig.edgeStyle())), DebugAction.EDGE_STYLE, true),
			new DebugButton(startX, rowTwoY, DEBUG_BUTTON_WIDTH, DEBUG_BUTTON_HEIGHT, translate("mcng.ui.debug.button.node", "Node: %s", label(uiConfig.nodeCornerStyle())), DebugAction.NODE_CORNERS, uiConfig.nodeCornerStyle() == NodeCornerStyle.ROUNDED),
			new DebugButton(rightX, rowTwoY, DEBUG_BUTTON_WIDTH, DEBUG_BUTTON_HEIGHT, translate("mcng.ui.debug.button.port", "Port: %s", label(uiConfig.portShape())), DebugAction.PORT_SHAPE, uiConfig.portShape() == PortShape.CIRCLE),
			new DebugButton(startX, rowThreeY, DEBUG_BUTTON_WIDTH, DEBUG_BUTTON_HEIGHT, translate("mcng.ui.debug.button.new_subgraph", "New Subgraph"), DebugAction.NEW_SUBGRAPH, true),
			new DebugButton(rightX, rowThreeY, DEBUG_BUTTON_WIDTH, DEBUG_BUTTON_HEIGHT, translate("mcng.ui.debug.button.new_custom", "New Custom"), DebugAction.NEW_CUSTOM, true),
			new DebugButton(startX, rowFourY, DEBUG_BUTTON_WIDTH, DEBUG_BUTTON_HEIGHT, translate("mcng.ui.debug.button.selection_subgraph", "Sel -> Subgraph"), DebugAction.SELECTION_SUBGRAPH, !session.selectedNodeIds().isEmpty()),
			new DebugButton(rightX, rowFourY, DEBUG_BUTTON_WIDTH, DEBUG_BUTTON_HEIGHT, translate("mcng.ui.debug.button.selection_custom", "Sel -> Custom"), DebugAction.SELECTION_CUSTOM, !session.selectedNodeIds().isEmpty()),
			new DebugButton(startX, rowFiveY, DEBUG_BUTTON_WIDTH, DEBUG_BUTTON_HEIGHT, translate("mcng.ui.debug.button.new_variable", "New Var"), DebugAction.NEW_VARIABLE, true),
			new DebugButton(rightX, rowFiveY, DEBUG_BUTTON_WIDTH, DEBUG_BUTTON_HEIGHT, translate("mcng.ui.debug.button.variable_type", "Var Type"), DebugAction.CYCLE_VARIABLE_TYPE, selectedVariableId != null),
			new DebugButton(startX, rowSixY, (DEBUG_BUTTON_WIDTH * 2) + DEBUG_BUTTON_GAP, DEBUG_BUTTON_HEIGHT, translate("mcng.ui.debug.button.delete_variable", "Delete Var"), DebugAction.DELETE_VARIABLE, selectedVariableId != null),
			new DebugButton(startX, rowSevenY, (DEBUG_BUTTON_WIDTH * 2) + DEBUG_BUTTON_GAP, DEBUG_BUTTON_HEIGHT, translate("mcng.ui.debug.button.stop", "Stop [P]"), DebugAction.STOP_EXECUTION, session.isExecutionRunning())
		);
	}

	private List<VariableRow> variableRows(DebugPanelLayout layout) {
		List<GraphVariableDefinition> variables = session.variables();
		List<VariableRow> rows = new java.util.ArrayList<>();
		int x = layout.x() + DEBUG_PANEL_PADDING;
		int y = variableRowsStartY(layout);
		int width = layout.width() - (DEBUG_PANEL_PADDING * 2);
		for (int index = 0; index < Math.min(variables.size(), DEBUG_VARIABLE_ROWS_VISIBLE); index++) {
			GraphVariableDefinition variable = variables.get(index);
			String label = variable.id() + " : " + shortTypeLabel(variable.typeId());
			rows.add(new VariableRow(variable.id(), x, y + (index * DEBUG_VARIABLE_ROW_STEP), width, DEBUG_VARIABLE_ROW_HEIGHT, label, variable.id().equals(selectedVariableId)));
		}
		return rows;
	}

	private static int debugPanelHeight() {
		int lastButtonTop = variableActionsStartOffset() + ((DEBUG_BUTTON_HEIGHT + DEBUG_BUTTON_ROW_GAP) * 2);
		return lastButtonTop + DEBUG_BUTTON_HEIGHT + DEBUG_PANEL_PADDING;
	}

	private static int settingsTitleY(DebugPanelLayout layout) {
		return layout.y() + DEBUG_SETTINGS_TITLE_OFFSET;
	}

	private static int settingsRowsStartY(DebugPanelLayout layout) {
		return layout.y() + settingsRowsStartOffset();
	}

	private static int variablesTitleY(DebugPanelLayout layout) {
		return layout.y() + variablesTitleOffset();
	}

	private static int variableRowsStartY(DebugPanelLayout layout) {
		return layout.y() + variableRowsStartOffset();
	}

	private static int variableActionsStartY(DebugPanelLayout layout) {
		return layout.y() + variableActionsStartOffset();
	}

	private static int nextButtonRowY(int currentRowY) {
		return currentRowY + DEBUG_BUTTON_HEIGHT + DEBUG_BUTTON_ROW_GAP;
	}

	private static int settingsRowsStartOffset() {
		return DEBUG_SETTINGS_TITLE_OFFSET + DEBUG_SECTION_TITLE_GAP;
	}

	private static int settingsButtonsBottomOffset() {
		return settingsRowsStartOffset() + ((DEBUG_BUTTON_HEIGHT + DEBUG_BUTTON_ROW_GAP) * 3) + DEBUG_BUTTON_HEIGHT;
	}

	private static int variablesTitleOffset() {
		return settingsButtonsBottomOffset() + DEBUG_SECTION_GAP;
	}

	private static int variableRowsStartOffset() {
		return variablesTitleOffset() + DEBUG_SECTION_TITLE_GAP;
	}

	private static int variableActionsStartOffset() {
		return variableRowsStartOffset() + (DEBUG_VARIABLE_ROWS_VISIBLE * DEBUG_VARIABLE_ROW_STEP) + DEBUG_SECTION_GAP;
	}

	private String translate(String key, String fallback, Object... args) {
		return i18n().translate(key, fallback, args);
	}

	private List<String> helpLines() {
		return List.of(
			translate("mcng.ui.debug.help.palette_toggle", "Nodes button or Tab: toggle node palette"),
			translate("mcng.ui.debug.help.panel_toggle", "O: toggle debug panel"),
			translate("mcng.ui.debug.help.enter_definition", "Double click subgraph/custom node: enter definition"),
			translate("mcng.ui.debug.help.palette_drag", "Click a palette entry to add at center, or drag it onto the canvas"),
			translate("mcng.ui.debug.help.selection", "Left click: select/drag or connect ports   Shift+Click / drag box: multi-select"),
			translate("mcng.ui.debug.help.secondary", "Right click while wiring: cancel   Right click port: clear edges   Right click drag/release: pan or menu"),
			translate("mcng.ui.debug.help.shortcuts", "Ctrl+Z undo   Ctrl+Shift+Z redo   E execute   T trigger   P stop   Del delete")
		);
	}

	private int presetIndexFor(GraphEditorTheme theme) {
		for (int index = 0; index < THEME_OPTIONS.size(); index++) {
			if (THEME_OPTIONS.get(index).theme().equals(theme)) {
				return index;
			}
		}
		return 0;
	}

	private String label(EdgeStyle edgeStyle) {
		return switch (edgeStyle) {
			case STRAIGHT -> translate("mcng.ui.debug.edge_style.straight", "Straight");
			case CURVE -> translate("mcng.ui.debug.edge_style.curve", "Curve");
			case ORTHOGONAL -> translate("mcng.ui.debug.edge_style.orthogonal", "Ortho");
		};
	}

	private String label(NodeCornerStyle nodeCornerStyle) {
		return nodeCornerStyle == NodeCornerStyle.ROUNDED
			? translate("mcng.ui.debug.node_corner.rounded", "Rounded")
			: translate("mcng.ui.debug.node_corner.square", "Square");
	}

	private String label(PortShape portShape) {
		return portShape == PortShape.CIRCLE
			? translate("mcng.ui.debug.port_shape.circle", "Circle")
			: translate("mcng.ui.debug.port_shape.square", "Square");
	}

	private boolean overlayBlocksEditorAt(double mouseX, double mouseY) {
		int panelWidth = 420;
		int panelHeight = 110;
		int x = editor.isPaletteOpen() ? editor.paletteSidebarRight() + 10 : 10;
		int y = 36;
		if (contains(mouseX, mouseY, x, y, panelWidth, panelHeight)) {
			return true;
		}
		int statusY = y + panelHeight + 6;
		if (contains(mouseX, mouseY, x, statusY, panelWidth, 16)) {
			return true;
		}
		if (debugPanelVisible) {
			DebugPanelLayout layout = debugPanelLayout();
			return contains(mouseX, mouseY, layout.x(), layout.y(), layout.width(), layout.height());
		}
		return false;
	}

	private boolean contains(double mouseX, double mouseY, int x, int y, int width, int height) {
		return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
	}

	private enum DebugAction {
		CYCLE_THEME,
		EDGE_STYLE,
		NODE_CORNERS,
		PORT_SHAPE,
		NEW_SUBGRAPH,
		NEW_CUSTOM,
		SELECTION_SUBGRAPH,
		SELECTION_CUSTOM,
		NEW_VARIABLE,
		CYCLE_VARIABLE_TYPE,
		DELETE_VARIABLE,
		STOP_EXECUTION
	}

	private String shortTypeLabel(String typeId) {
		return GraphEditorTranslations.shortPortTypeLabel(i18n(), typeId);
	}

	private String themeLabel(ThemeOption option) {
		return translate("mcng.ui.debug.theme." + option.id(), option.fallbackName());
	}

	private record ThemeOption(String id, String fallbackName, GraphEditorTheme theme) {
	}

	private record DebugPanelLayout(int x, int y, int width, int height) {
	}

	private record DebugButton(int x, int y, int width, int height, String label, DebugAction action, boolean active) {
		boolean contains(double mouseX, double mouseY) {
			return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
		}
	}

	private record VariableRow(String id, int x, int y, int width, int height, String label, boolean selected) {
		boolean contains(double mouseX, double mouseY) {
			return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
		}
	}
}
