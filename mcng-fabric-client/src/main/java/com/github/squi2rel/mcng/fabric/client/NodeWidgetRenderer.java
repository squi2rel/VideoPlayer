package com.github.squi2rel.mcng.fabric.client;

import com.github.squi2rel.mcng.core.NodeConfigValues;
import com.github.squi2rel.mcng.core.NodeEditorControl;
import com.github.squi2rel.mcng.core.NodeId;
import com.github.squi2rel.mcng.core.NodeVisualStyle;
import com.github.squi2rel.mcng.core.NumericTypes;
import com.github.squi2rel.mcng.core.PortId;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;

final class NodeWidgetRenderer {
	private NodeWidgetRenderer() {
	}

	static void render(
		DrawContext context,
		TextRenderer textRenderer,
		NodeWidget widget,
		GraphEditorUiConfig uiConfig,
		GraphEditorTheme theme,
		boolean selected,
		boolean executing,
		boolean hasError,
		Function<NodeWidget.PortWidget, Integer> portColorResolver,
		Predicate<NodeWidget.PortWidget> pendingPort,
		BiPredicate<NodeId, PortId> activePortEditor,
		BiPredicate<NodeId, String> activeControlEditor
	) {
		NodeRenderDetailLevel detailLevel = widget.detailLevel();
		int bodyColor = executing
			? EditorStyleRenderer.blend(theme.nodeBodyColor(), theme.executionColor(), 0.12f)
			: selected ? EditorStyleRenderer.blend(theme.nodeBodyColor(), theme.accentColor(), 0.18f) : theme.nodeBodyColor();
		int headerColor = executing
			? EditorStyleRenderer.blend(theme.nodeHeaderColor(), theme.executionColor(), 0.18f)
			: selected ? EditorStyleRenderer.blend(theme.nodeHeaderColor(), theme.accentColor(), 0.22f) : theme.nodeHeaderColor();
		int borderColor = executing ? theme.executionColor() : hasError ? theme.errorColor() : selected ? theme.accentColor() : theme.nodeBorderColor();
		boolean compactReroute = widget.nodeType().visualStyle() == NodeVisualStyle.COMPACT_REROUTE;

		if (compactReroute) {
			EditorStyleRenderer.drawBox(context, widget.x(), widget.y(), widget.width(), widget.height(), bodyColor, borderColor, uiConfig);
		} else {
			EditorStyleRenderer.drawNodeBox(context, widget, bodyColor, headerColor, borderColor, uiConfig);
		}

		if (!compactReroute && widget.showHeaderTitle() && detailLevel != NodeRenderDetailLevel.MINIMAL) {
			int titleY = widget.y() + Math.max(2, (widget.headerHeight() - textRenderer.fontHeight) / 2);
			context.drawText(textRenderer, GraphEditorTranslations.nodeTitle(widget.i18n(), widget.nodeType()), widget.x() + widget.edgePadding(), titleY, theme.primaryTextColor(), false);
		}

		if (!compactReroute && detailLevel == NodeRenderDetailLevel.FULL) {
			for (NodeWidget.RowWidget row : widget.rows()) {
				renderRow(context, textRenderer, widget, row, uiConfig, theme, activePortEditor, activeControlEditor);
			}
			renderBody(context, textRenderer, widget, theme, uiConfig, selected, executing, hasError);
		}

		if (!compactReroute) {
			int separatorColor = EditorStyleRenderer.blend(theme.nodeBorderColor(), theme.primaryTextColor(), 0.12f);
			for (int separatorY : widget.channelSeparators()) {
				context.fill(widget.x() + 1, separatorY, widget.x() + widget.width() - 1, separatorY + 1, separatorColor);
			}
		}

		for (NodeWidget.PortWidget port : widget.ports()) {
			int color = portColorResolver.apply(port);
			if (pendingPort.test(port)) {
				color = EditorStyleRenderer.brighten(color, 0.22f);
			}
			EditorStyleRenderer.drawPort(context, port.centerX(), port.centerY(), port.radius(), color, uiConfig.portShape());
		}
	}

	private static void renderRow(
		DrawContext context,
		TextRenderer textRenderer,
		NodeWidget widget,
		NodeWidget.RowWidget row,
		GraphEditorUiConfig uiConfig,
		GraphEditorTheme theme,
		BiPredicate<NodeId, PortId> activePortEditor,
		BiPredicate<NodeId, String> activeControlEditor
	) {
		switch (row) {
			case NodeWidget.InputPortRowWidget inputRow -> renderInputRow(context, textRenderer, widget, inputRow, uiConfig, theme, activePortEditor);
			case NodeWidget.OutputPortRowWidget outputRow -> renderOutputRow(context, textRenderer, widget, outputRow, theme);
			case NodeWidget.TextControlRowWidget textControlRow -> renderTextControlRow(context, textRenderer, widget, textControlRow, uiConfig, theme, activeControlEditor);
			case NodeWidget.NumericTextControlRowWidget numericTextControlRow -> renderNumericTextControlRow(context, textRenderer, widget, numericTextControlRow, uiConfig, theme, activeControlEditor);
			case NodeWidget.BooleanControlRowWidget booleanControlRow -> renderBooleanControlRow(context, textRenderer, widget, booleanControlRow, uiConfig, theme);
			case NodeWidget.CycleControlRowWidget cycleControlRow -> renderCycleControlRow(context, textRenderer, widget, cycleControlRow, uiConfig, theme);
		}
	}

	private static void renderInputRow(
		DrawContext context,
		TextRenderer textRenderer,
		NodeWidget widget,
		NodeWidget.InputPortRowWidget row,
		GraphEditorUiConfig uiConfig,
		GraphEditorTheme theme,
		BiPredicate<NodeId, PortId> activePortEditor
	) {
		String label = trimText(textRenderer, GraphEditorTranslations.portLabel(widget.i18n(), widget.nodeType(), row.port().definition()), labelWidthForInputRow(widget, row));
		int labelX = row.port().centerX() + row.port().radius() + 6;
		int labelY = row.y() + Math.max(2, (row.height() - textRenderer.fontHeight) / 2);
		context.drawText(textRenderer, label, labelX, labelY, theme.primaryTextColor(), false);
		if (row.fieldBounds() == null || activePortEditor.test(row.port().nodeId(), row.port().definition().id())) {
			return;
		}

		int fill = row.connected() ? EditorStyleRenderer.darken(theme.nodeBodyColor(), 0.08f) : EditorStyleRenderer.darken(theme.nodeBodyColor(), 0.04f);
		int border = row.connected() ? theme.nodeBorderColor() : theme.panelBorderColor();
		EditorStyleRenderer.drawBox(context, row.fieldBounds().x(), row.fieldBounds().y(), row.fieldBounds().width(), row.fieldBounds().height(), fill, border, uiConfig);
		Object value;
		try {
			value = NodeConfigValues.readInlineInputValue(widget.node().config(), row.port().definition());
		} catch (IllegalArgumentException exception) {
			value = GraphEditorTranslations.ui(widget.i18n(), "common.invalid", "<invalid>");
		}
		String renderedValue = switch (row.port().definition().inlineWidget().kind()) {
			case NUMERIC_TEXT -> NumericTypes.displayText(value);
			case STRING_TEXT -> String.valueOf(value);
			case BOOLEAN_TOGGLE -> Boolean.TRUE.equals(value)
				? GraphEditorTranslations.ui(widget.i18n(), "common.on", "On")
				: GraphEditorTranslations.ui(widget.i18n(), "common.off", "Off");
		};
		context.drawText(textRenderer, trimText(textRenderer, renderedValue, row.fieldBounds().width() - 8), row.fieldBounds().x() + 4, row.fieldBounds().y() + 3, theme.secondaryTextColor(), false);
	}

	private static void renderOutputRow(DrawContext context, TextRenderer textRenderer, NodeWidget widget, NodeWidget.OutputPortRowWidget row, GraphEditorTheme theme) {
		String label = trimText(textRenderer, GraphEditorTranslations.portLabel(widget.i18n(), widget.nodeType(), row.port().definition()), outputLabelWidth(widget, row));
		int labelWidth = textRenderer.getWidth(label);
		int labelRight = row.port().centerX() - row.port().radius() - 6;
		int labelX = Math.max(widget.x() + widget.edgePadding(), labelRight - labelWidth);
		int labelY = row.y() + Math.max(2, (row.height() - textRenderer.fontHeight) / 2);
		context.drawText(textRenderer, label, labelX, labelY, theme.primaryTextColor(), false);
	}

	private static void renderTextControlRow(
		DrawContext context,
		TextRenderer textRenderer,
		NodeWidget widget,
		NodeWidget.TextControlRowWidget row,
		GraphEditorUiConfig uiConfig,
		GraphEditorTheme theme,
		BiPredicate<NodeId, String> activeControlEditor
	) {
		if (row.labelVisible()) {
			context.drawText(textRenderer, trimText(textRenderer, GraphEditorTranslations.controlLabel(widget.i18n(), widget.nodeType(), row.control()), labelWidthForControlRow(widget, row.fieldBounds())), widget.x() + widget.edgePadding(), row.y() + Math.max(2, (row.height() - textRenderer.fontHeight) / 2), theme.primaryTextColor(), false);
		}
		if (activeControlEditor.test(widget.node().id(), row.control().key())) {
			return;
		}
		int fill = row.labelVisible()
			? EditorStyleRenderer.darken(theme.nodeBodyColor(), 0.04f)
			: EditorStyleRenderer.darken(theme.nodeHeaderColor(), 0.04f);
		EditorStyleRenderer.drawBox(context, row.fieldBounds().x(), row.fieldBounds().y(), row.fieldBounds().width(), row.fieldBounds().height(), fill, theme.panelBorderColor(), uiConfig);
		String value = NodeConfigValues.readTextControlValue(widget.node().config(), row.control());
		context.drawText(textRenderer, trimText(textRenderer, value, row.fieldBounds().width() - 8), row.fieldBounds().x() + 4, row.fieldBounds().y() + 3, theme.secondaryTextColor(), false);
	}

	private static void renderNumericTextControlRow(
		DrawContext context,
		TextRenderer textRenderer,
		NodeWidget widget,
		NodeWidget.NumericTextControlRowWidget row,
		GraphEditorUiConfig uiConfig,
		GraphEditorTheme theme,
		BiPredicate<NodeId, String> activeControlEditor
	) {
		context.drawText(textRenderer, trimText(textRenderer, GraphEditorTranslations.controlLabel(widget.i18n(), widget.nodeType(), row.control()), labelWidthForControlRow(widget, row.fieldBounds())), widget.x() + widget.edgePadding(), row.y() + Math.max(2, (row.height() - textRenderer.fontHeight) / 2), theme.primaryTextColor(), false);
		if (activeControlEditor.test(widget.node().id(), row.control().key())) {
			return;
		}
		EditorStyleRenderer.drawBox(context, row.fieldBounds().x(), row.fieldBounds().y(), row.fieldBounds().width(), row.fieldBounds().height(), EditorStyleRenderer.darken(theme.nodeBodyColor(), 0.04f), theme.panelBorderColor(), uiConfig);
		String value = NodeConfigValues.readNumericTextControlValue(widget.node().config(), row.control());
		context.drawText(textRenderer, trimText(textRenderer, value, row.fieldBounds().width() - 8), row.fieldBounds().x() + 4, row.fieldBounds().y() + 3, theme.secondaryTextColor(), false);
	}

	private static void renderBooleanControlRow(
		DrawContext context,
		TextRenderer textRenderer,
		NodeWidget widget,
		NodeWidget.BooleanControlRowWidget row,
		GraphEditorUiConfig uiConfig,
		GraphEditorTheme theme
	) {
		context.drawText(textRenderer, trimText(textRenderer, GraphEditorTranslations.controlLabel(widget.i18n(), widget.nodeType(), row.control()), labelWidthForControlRow(widget, row.toggleBounds())), widget.x() + widget.edgePadding(), row.y() + Math.max(2, (row.height() - textRenderer.fontHeight) / 2), theme.primaryTextColor(), false);
		boolean value = NodeConfigValues.readBooleanControlValue(widget.node().config(), row.control());
		int fill = value ? EditorStyleRenderer.blend(theme.nodeBodyColor(), theme.accentColor(), 0.2f) : EditorStyleRenderer.darken(theme.nodeBodyColor(), 0.04f);
		int border = value ? theme.accentColor() : theme.panelBorderColor();
		EditorStyleRenderer.drawBox(context, row.toggleBounds().x(), row.toggleBounds().y(), row.toggleBounds().width(), row.toggleBounds().height(), fill, border, uiConfig);
		String label = value
			? GraphEditorTranslations.ui(widget.i18n(), "common.enabled", "Enabled")
			: GraphEditorTranslations.ui(widget.i18n(), "common.disabled", "Disabled");
		context.drawText(textRenderer, trimText(textRenderer, label, row.toggleBounds().width() - 8), row.toggleBounds().x() + 4, row.toggleBounds().y() + 3, theme.secondaryTextColor(), false);
	}

	private static void renderCycleControlRow(
		DrawContext context,
		TextRenderer textRenderer,
		NodeWidget widget,
		NodeWidget.CycleControlRowWidget row,
		GraphEditorUiConfig uiConfig,
		GraphEditorTheme theme
	) {
		context.drawText(textRenderer, trimText(textRenderer, GraphEditorTranslations.controlLabel(widget.i18n(), widget.nodeType(), row.control()), labelWidthForControlRow(widget, row.valueBounds())), widget.x() + widget.edgePadding(), row.y() + Math.max(2, (row.height() - textRenderer.fontHeight) / 2), theme.primaryTextColor(), false);
		EditorStyleRenderer.drawBox(context, row.leftArrowBounds().x(), row.leftArrowBounds().y(), row.leftArrowBounds().width(), row.leftArrowBounds().height(), EditorStyleRenderer.darken(theme.nodeBodyColor(), 0.04f), theme.panelBorderColor(), uiConfig);
		EditorStyleRenderer.drawBox(context, row.valueBounds().x(), row.valueBounds().y(), row.valueBounds().width(), row.valueBounds().height(), EditorStyleRenderer.darken(theme.nodeBodyColor(), 0.04f), theme.panelBorderColor(), uiConfig);
		EditorStyleRenderer.drawBox(context, row.rightArrowBounds().x(), row.rightArrowBounds().y(), row.rightArrowBounds().width(), row.rightArrowBounds().height(), EditorStyleRenderer.darken(theme.nodeBodyColor(), 0.04f), theme.panelBorderColor(), uiConfig);
		context.drawText(textRenderer, "<", row.leftArrowBounds().x() + 4, row.leftArrowBounds().y() + 3, theme.secondaryTextColor(), false);
		context.drawText(textRenderer, ">", row.rightArrowBounds().x() + 4, row.rightArrowBounds().y() + 3, theme.secondaryTextColor(), false);
		String currentId = NodeConfigValues.readCycleControlValue(widget.node().config(), row.control());
		String currentLabel = row.control().options().stream()
			.filter(option -> option.id().equals(currentId))
			.map(option -> GraphEditorTranslations.controlOptionLabel(widget.i18n(), widget.nodeType(), row.control(), option))
			.findFirst()
			.orElse(currentId);
		context.drawText(textRenderer, trimText(textRenderer, currentLabel, row.valueBounds().width() - 8), row.valueBounds().x() + 4, row.valueBounds().y() + 3, theme.secondaryTextColor(), false);
	}

	private static void renderBody(
		DrawContext context,
		TextRenderer textRenderer,
		NodeWidget widget,
		GraphEditorTheme theme,
		GraphEditorUiConfig uiConfig,
		boolean selected,
		boolean executing,
		boolean hasError
	) {
		if (!widget.hasVisibleBody() || widget.bodyComponent() == null) {
			return;
		}
		widget.bodyComponent().render(new NodeBodyRenderContext(
			context,
			textRenderer,
			widget.bodyBounds(),
			widget.node(),
			widget.nodeType(),
			widget.session(),
			widget.i18n(),
			uiConfig,
			theme,
			widget.zoom(),
			selected,
			executing,
			hasError,
			widget.preview()
		));
	}

	private static int labelWidthForInputRow(NodeWidget widget, NodeWidget.InputPortRowWidget row) {
		int labelX = row.port().centerX() + row.port().radius() + 6;
		int right = row.fieldBounds() == null ? widget.x() + widget.width() - widget.edgePadding() : row.fieldBounds().x() - 6;
		return Math.max(18, right - labelX);
	}

	private static int outputLabelWidth(NodeWidget widget, NodeWidget.OutputPortRowWidget row) {
		return Math.max(18, (row.port().centerX() - row.port().radius() - 6) - (widget.x() + widget.edgePadding()));
	}

	private static int labelWidthForControlRow(NodeWidget widget, NodeWidget.Bounds controlBounds) {
		return Math.max(18, controlBounds.x() - (widget.x() + widget.edgePadding()) - 6);
	}

	private static String trimText(TextRenderer textRenderer, String value, int maxWidth) {
		if (textRenderer.getWidth(value) <= maxWidth) {
			return value;
		}
		String ellipsis = "...";
		int ellipsisWidth = textRenderer.getWidth(ellipsis);
		if (ellipsisWidth >= maxWidth) {
			return "";
		}
		String candidate = value;
		while (!candidate.isEmpty() && textRenderer.getWidth(candidate) + ellipsisWidth > maxWidth) {
			candidate = candidate.substring(0, candidate.length() - 1);
		}
		return candidate + ellipsis;
	}

}
