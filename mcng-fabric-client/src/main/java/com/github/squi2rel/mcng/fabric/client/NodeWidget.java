package com.github.squi2rel.mcng.fabric.client;

import com.github.squi2rel.mcng.core.DocumentNodeTypes;
import com.github.squi2rel.mcng.core.GraphLayout;
import com.github.squi2rel.mcng.core.NodeEditorControl;
import com.github.squi2rel.mcng.core.NodeId;
import com.github.squi2rel.mcng.core.NodeInstance;
import com.github.squi2rel.mcng.core.NodePosition;
import com.github.squi2rel.mcng.core.NodeSize;
import com.github.squi2rel.mcng.core.NodeType;
import com.github.squi2rel.mcng.core.NodeVisualStyle;
import com.github.squi2rel.mcng.core.PortChannel;
import com.github.squi2rel.mcng.core.PortDefinition;
import com.github.squi2rel.mcng.core.PortDirection;
import com.github.squi2rel.mcng.core.PortId;
import com.github.squi2rel.mcng.core.PortInlineWidget;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiPredicate;
import java.util.function.Function;

public final class NodeWidget {
	private static final int BASE_WIDTH = 196;
	private static final int BASE_HEADER_HEIGHT = 22;
	private static final int BASE_EDGE_PADDING = 8;
	private static final int BASE_PORT_RADIUS = 4;
	private static final int BASE_PORT_HIT_RADIUS = 6;
	private static final int BASE_ROW_HEIGHT = 20;
	private static final int BASE_ROW_GAP = 2;
	private static final int BASE_GROUP_GAP = 6;
	private static final int BASE_BODY_TOP_PADDING = 8;
	private static final int BASE_BODY_BOTTOM_PADDING = 8;
	private static final int BASE_EMPTY_BODY_HEIGHT = 20;
	private static final int BASE_FIELD_WIDTH = 86;
	private static final int BASE_ARROW_WIDTH = 15;
	private static final int BASE_REROUTE_WIDTH = 56;
	private static final int BASE_REROUTE_HEIGHT = 16;
	private static final int BASE_CUSTOM_BODY_GAP = 6;
	private static final int BASE_RESIZE_EDGE = 6;
	private static final int BASE_RESIZE_CORNER = 10;

	private final NodeInstance node;
	private final NodeType<?> nodeType;
	private final NodeBodyComponent bodyComponent;
	private final ResizePolicy resizePolicy;
	private final Optional<GraphEditorSession> session;
	private final GraphEditorI18n i18n;
	private final GraphEditorUiConfig uiConfig;
	private final boolean preview;
	private final double zoom;
	private final NodeRenderDetailLevel detailLevel;
	private final int x;
	private final int y;
	private final int width;
	private final int height;
	private final int minWidth;
	private final int minHeight;
	private final int headerHeight;
	private final boolean embeddedHeaderControl;
	private final int edgePadding;
	private final boolean compactReroute;
	private final RerouteOrientation rerouteOrientation;
	private final List<Integer> channelSeparators;
	private final List<PortWidget> ports;
	private final List<RowWidget> rows;
	private final Bounds bodyBounds;

	public NodeWidget(NodeInstance node, NodeType<?> nodeType, GraphEditorSession session, GraphLayout layout, GraphViewportState viewport) {
		this(node, nodeType, session, layout, viewport, GraphEditorUiConfig.defaultConfig(), null, ResizePolicy.none());
	}

	public NodeWidget(
		NodeInstance node,
		NodeType<?> nodeType,
		GraphEditorSession session,
		GraphLayout layout,
		GraphViewportState viewport,
		GraphEditorUiConfig uiConfig,
		NodeBodyComponent bodyComponent,
		ResizePolicy resizePolicy
	) {
		this(
			node,
			nodeType,
			layout,
			viewport,
			uiConfig,
			Optional.of(session),
			session.i18n(),
			bodyComponent,
			resizePolicy,
			false,
			session::hasIncomingConnection,
			session::rerouteOrientation
		);
	}

	private NodeWidget(
		NodeInstance node,
		NodeType<?> nodeType,
		GraphLayout layout,
		GraphViewportState viewport,
		GraphEditorUiConfig uiConfig,
		Optional<GraphEditorSession> session,
		GraphEditorI18n i18n,
		NodeBodyComponent bodyComponent,
		ResizePolicy resizePolicy,
		boolean preview,
		BiPredicate<NodeId, PortId> hasIncomingConnection,
		Function<NodeId, RerouteOrientation> rerouteOrientationProvider
	) {
		this.node = node;
		this.nodeType = nodeType;
		this.bodyComponent = bodyComponent;
		this.resizePolicy = resizePolicy == null ? ResizePolicy.none() : resizePolicy;
		this.session = session;
		this.i18n = i18n == null ? GraphEditorI18n.identity() : i18n;
		this.uiConfig = uiConfig;
		this.preview = preview;
		this.zoom = viewport.zoom();
		this.detailLevel = NodeRenderDetailLevel.fromZoom(viewport.zoom());

		NodePosition position = layout.nodePositions().getOrDefault(node.id(), new NodePosition(0, 0));
		NodeSize storedSize = layout.nodeSizes().get(node.id());
		this.x = (int) Math.round(position.x());
		this.y = (int) Math.round(position.y());
		this.compactReroute = nodeType.visualStyle() == NodeVisualStyle.COMPACT_REROUTE;
		this.rerouteOrientation = compactReroute ? rerouteOrientationProvider.apply(node.id()) : RerouteOrientation.LEFT_TO_RIGHT;
		this.embeddedHeaderControl = !compactReroute
			&& (DocumentNodeTypes.isSubgraphType(node.typeId()) || DocumentNodeTypes.isDefinitionType(node.typeId()));
		this.headerHeight = compactReroute ? 0 : BASE_HEADER_HEIGHT;
		this.edgePadding = BASE_EDGE_PADDING;

		if (compactReroute) {
			this.width = this.rerouteOrientation.vertical()
				? compactRerouteThickness()
				: Math.max(compactRerouteMinimumLength(), storedSize == null ? compactRerouteMinimumLength() : storedSize.width());
			this.height = this.rerouteOrientation.vertical()
				? Math.max(compactRerouteMinimumLength(), storedSize == null ? compactRerouteThickness() : storedSize.height())
				: compactRerouteThickness();
			this.minWidth = this.rerouteOrientation.vertical() ? compactRerouteThickness() : compactRerouteMinimumLength();
			this.minHeight = this.rerouteOrientation.vertical() ? compactRerouteMinimumLength() : compactRerouteThickness();
			this.ports = buildReroutePorts(this.rerouteOrientation);
			this.channelSeparators = List.of();
			this.rows = List.of();
			this.bodyBounds = null;
			return;
		}

		RequestedNodeSize requestedSize = requestedNodeSize(storedSize);
		NodeBodyMeasurement bodyMeasurement = measureBody(requestedSize.width() - (BASE_EDGE_PADDING * 2), viewport.zoom());
		int calculatedMinWidth = Math.max(BASE_WIDTH, bodyMeasurement.visible() ? bodyMeasurement.minWidth() + (BASE_EDGE_PADDING * 2) : BASE_WIDTH);
		int provisionalWidth = Math.max(requestedSize.width(), calculatedMinWidth);
		if (storedSize == null && bodyMeasurement.visible()) {
			provisionalWidth = Math.max(provisionalWidth, bodyMeasurement.preferredWidth() + (BASE_EDGE_PADDING * 2));
		}
		if (provisionalWidth != requestedSize.width()) {
			bodyMeasurement = measureBody(provisionalWidth - (BASE_EDGE_PADDING * 2), viewport.zoom());
			calculatedMinWidth = Math.max(BASE_WIDTH, bodyMeasurement.visible() ? bodyMeasurement.minWidth() + (BASE_EDGE_PADDING * 2) : BASE_WIDTH);
			provisionalWidth = Math.max(provisionalWidth, calculatedMinWidth);
		}
		this.width = provisionalWidth;

		FullLayout layoutMetrics = buildFullLayout(hasIncomingConnection, bodyMeasurement, requestedSize.height());
		this.ports = layoutMetrics.ports();
		this.channelSeparators = layoutMetrics.channelSeparators();
		this.rows = layoutMetrics.rows();
		this.bodyBounds = layoutMetrics.bodyBounds();
		this.height = layoutMetrics.height();
		this.minWidth = calculatedMinWidth;
		this.minHeight = layoutMetrics.minHeight();
	}

	public static NodeWidget preview(
		NodeType<?> nodeType,
		int centerX,
		int centerY
	) {
		return preview(nodeType, GraphEditorUiConfig.defaultConfig(), GraphEditorI18n.identity(), null, ResizePolicy.none(), centerX, centerY);
	}

	public static NodeWidget preview(
		NodeType<?> nodeType,
		GraphEditorUiConfig uiConfig,
		NodeBodyComponent bodyComponent,
		ResizePolicy resizePolicy,
		int centerX,
		int centerY
	) {
		return preview(nodeType, uiConfig, GraphEditorI18n.identity(), bodyComponent, resizePolicy, centerX, centerY);
	}

	public static NodeWidget preview(
		NodeType<?> nodeType,
		GraphEditorUiConfig uiConfig,
		GraphEditorI18n i18n,
		NodeBodyComponent bodyComponent,
		ResizePolicy resizePolicy,
		int centerX,
		int centerY
	) {
		NodeInstance previewNode = createPreviewNode(nodeType);
		GraphViewportState viewport = new GraphViewportState();
		NodeWidget provisional = new NodeWidget(
			previewNode,
			nodeType,
			new GraphLayout(Map.of(previewNode.id(), new NodePosition(0, 0))),
			viewport,
			uiConfig,
			Optional.empty(),
			i18n,
			bodyComponent,
			resizePolicy,
			true,
			(nodeId, portId) -> false,
			nodeId -> RerouteOrientation.LEFT_TO_RIGHT
		);
		return new NodeWidget(
			previewNode,
			nodeType,
			new GraphLayout(Map.of(previewNode.id(), new NodePosition(centerX - (provisional.width() / 2), centerY - (provisional.height() / 2)))),
			viewport,
			uiConfig,
			Optional.empty(),
			i18n,
			bodyComponent,
			resizePolicy,
			true,
			(nodeId, portId) -> false,
			nodeId -> RerouteOrientation.LEFT_TO_RIGHT
		);
	}

	public NodeInstance node() {
		return node;
	}

	public NodeType<?> nodeType() {
		return nodeType;
	}

	public NodeBodyComponent bodyComponent() {
		return bodyComponent;
	}

	public ResizePolicy resizePolicy() {
		return resizePolicy;
	}

	public Optional<GraphEditorSession> session() {
		return session;
	}

	public GraphEditorUiConfig uiConfig() {
		return uiConfig;
	}

	public GraphEditorI18n i18n() {
		return i18n;
	}

	public boolean preview() {
		return preview;
	}

	public double zoom() {
		return zoom;
	}

	public int x() {
		return x;
	}

	public int y() {
		return y;
	}

	public int width() {
		return width;
	}

	public int height() {
		return height;
	}

	public int minWidth() {
		return minWidth;
	}

	public int minHeight() {
		return minHeight;
	}

	public int headerHeight() {
		return headerHeight;
	}

	public boolean hasHeader() {
		return headerHeight > 0;
	}

	public boolean showHeaderTitle() {
		return hasHeader() && !embeddedHeaderControl;
	}

	public int edgePadding() {
		return edgePadding;
	}

	public boolean compactReroute() {
		return compactReroute;
	}

	public RerouteOrientation rerouteOrientation() {
		return rerouteOrientation;
	}

	public NodeRenderDetailLevel detailLevel() {
		return detailLevel;
	}

	public Bounds bodyBounds() {
		return bodyBounds;
	}

	public boolean hasVisibleBody() {
		return bodyBounds != null;
	}

	public boolean hasInteractiveBody() {
		return bodyComponent != null && bodyBounds != null && detailLevel == NodeRenderDetailLevel.FULL;
	}

	public boolean contains(double mouseX, double mouseY) {
		return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
	}

	public PortWidget findPortAt(double mouseX, double mouseY) {
		for (PortWidget port : ports) {
			if (port.contains(mouseX, mouseY)) {
				return port;
			}
		}
		return null;
	}

	public ResizeHandle findResizeHandleAt(double mouseX, double mouseY) {
		if (!contains(mouseX, mouseY)) {
			return null;
		}
		if (compactReroute) {
			return findCompactRerouteResizeHandleAt(mouseX, mouseY);
		}
		if (bodyComponent == null || !resizePolicy.resizable()) {
			return null;
		}
		boolean left = resizePolicy.allowLeft() && mouseX <= x + BASE_RESIZE_CORNER;
		boolean right = resizePolicy.allowRight() && mouseX >= (x + width) - BASE_RESIZE_CORNER;
		boolean top = resizePolicy.allowTop() && mouseY <= y + BASE_RESIZE_CORNER;
		boolean bottom = resizePolicy.allowBottom() && mouseY >= (y + height) - BASE_RESIZE_CORNER;
		if (left && top && resizePolicy.supports(ResizeDirection.TOP_LEFT)) {
			return new ResizeHandle(ResizeDirection.TOP_LEFT);
		}
		if (right && top && resizePolicy.supports(ResizeDirection.TOP_RIGHT)) {
			return new ResizeHandle(ResizeDirection.TOP_RIGHT);
		}
		if (left && bottom && resizePolicy.supports(ResizeDirection.BOTTOM_LEFT)) {
			return new ResizeHandle(ResizeDirection.BOTTOM_LEFT);
		}
		if (right && bottom && resizePolicy.supports(ResizeDirection.BOTTOM_RIGHT)) {
			return new ResizeHandle(ResizeDirection.BOTTOM_RIGHT);
		}
		if (resizePolicy.allowLeft() && mouseX <= x + BASE_RESIZE_EDGE) {
			return new ResizeHandle(ResizeDirection.LEFT);
		}
		if (resizePolicy.allowRight() && mouseX >= (x + width) - BASE_RESIZE_EDGE) {
			return new ResizeHandle(ResizeDirection.RIGHT);
		}
		if (resizePolicy.allowTop() && mouseY <= y + BASE_RESIZE_EDGE) {
			return new ResizeHandle(ResizeDirection.TOP);
		}
		if (resizePolicy.allowBottom() && mouseY >= (y + height) - BASE_RESIZE_EDGE) {
			return new ResizeHandle(ResizeDirection.BOTTOM);
		}
		return null;
	}

	private ResizeHandle findCompactRerouteResizeHandleAt(double mouseX, double mouseY) {
		int cornerSpan = 4;
		boolean leftCorner = mouseX <= x + cornerSpan;
		boolean rightCorner = mouseX >= (x + width) - cornerSpan;
		boolean topCorner = mouseY <= y + cornerSpan;
		boolean bottomCorner = mouseY >= (y + height) - cornerSpan;
		if (leftCorner && topCorner) {
			return new ResizeHandle(ResizeDirection.TOP_LEFT);
		}
		if (rightCorner && topCorner) {
			return new ResizeHandle(ResizeDirection.TOP_RIGHT);
		}
		if (leftCorner && bottomCorner) {
			return new ResizeHandle(ResizeDirection.BOTTOM_LEFT);
		}
		if (rightCorner && bottomCorner) {
			return new ResizeHandle(ResizeDirection.BOTTOM_RIGHT);
		}
		if (rerouteOrientation.vertical()) {
			if (mouseY <= y + BASE_RESIZE_EDGE) {
				return new ResizeHandle(ResizeDirection.TOP);
			}
			if (mouseY >= (y + height) - BASE_RESIZE_EDGE) {
				return new ResizeHandle(ResizeDirection.BOTTOM);
			}
			return null;
		}
		if (mouseX <= x + BASE_RESIZE_EDGE) {
			return new ResizeHandle(ResizeDirection.LEFT);
		}
		if (mouseX >= (x + width) - BASE_RESIZE_EDGE) {
			return new ResizeHandle(ResizeDirection.RIGHT);
		}
		return null;
	}

	public InlineHit findInlineHitAt(double mouseX, double mouseY) {
		if (detailLevel != NodeRenderDetailLevel.FULL) {
			return null;
		}
		for (RowWidget row : rows) {
			switch (row) {
				case InputPortRowWidget inputRow -> {
					if (inputRow.fieldBounds() == null || !inputRow.fieldBounds().contains(mouseX, mouseY)) {
						continue;
					}
					PortInlineWidget inlineWidget = inputRow.port().definition().inlineWidget();
					if (inlineWidget == null) {
						continue;
					}
					return switch (inlineWidget.kind()) {
						case NUMERIC_TEXT, STRING_TEXT -> new InlineHit.PortTextFieldHit(node.id(), inputRow.port().definition().id(), inputRow.fieldBounds(), inlineWidget.kind() == PortInlineWidget.Kind.NUMERIC_TEXT);
						case BOOLEAN_TOGGLE -> new InlineHit.PortBooleanHit(node.id(), inputRow.port().definition().id());
					};
				}
				case TextControlRowWidget textRow -> {
					if (textRow.fieldBounds().contains(mouseX, mouseY)) {
						return new InlineHit.ControlTextFieldHit(node.id(), textRow.control().key(), textRow.fieldBounds(), false);
					}
				}
				case NumericTextControlRowWidget numericTextRow -> {
					if (numericTextRow.fieldBounds().contains(mouseX, mouseY)) {
						return new InlineHit.ControlTextFieldHit(node.id(), numericTextRow.control().key(), numericTextRow.fieldBounds(), true);
					}
				}
				case BooleanControlRowWidget booleanRow -> {
					if (booleanRow.toggleBounds().contains(mouseX, mouseY)) {
						return new InlineHit.ControlBooleanHit(node.id(), booleanRow.control().key());
					}
				}
				case CycleControlRowWidget cycleRow -> {
					if (cycleRow.leftArrowBounds().contains(mouseX, mouseY)) {
						return new InlineHit.ControlCycleHit(node.id(), cycleRow.control().key(), -1);
					}
					if (cycleRow.rightArrowBounds().contains(mouseX, mouseY)) {
						return new InlineHit.ControlCycleHit(node.id(), cycleRow.control().key(), 1);
					}
				}
				case OutputPortRowWidget ignored -> {
				}
			}
		}
		return null;
	}

	public List<PortWidget> ports() {
		return ports;
	}

	public List<RowWidget> rows() {
		return rows;
	}

	public List<Integer> channelSeparators() {
		return channelSeparators;
	}

	private RequestedNodeSize requestedNodeSize(NodeSize storedSize) {
		if (storedSize != null) {
			return new RequestedNodeSize(Math.max(BASE_WIDTH, storedSize.width()), Math.max(1, storedSize.height()));
		}
		return new RequestedNodeSize(BASE_WIDTH, 0);
	}

	private NodeBodyMeasurement measureBody(int availableWidth, double zoom) {
		if (bodyComponent == null) {
			return NodeBodyMeasurement.hidden();
		}
		int clampedAvailableWidth = Math.max(24, availableWidth);
		NodeBodyMeasurement measurement = bodyComponent.measure(new NodeBodyMeasureContext(
			node,
			nodeType,
			session,
			i18n,
			uiConfig,
			uiConfig.theme(),
			zoom,
			preview,
			clampedAvailableWidth
		));
		return measurement == null ? NodeBodyMeasurement.hidden() : measurement;
	}

	private FullLayout buildFullLayout(BiPredicate<NodeId, PortId> hasIncomingConnection, NodeBodyMeasurement bodyMeasurement, int requestedHeight) {
		int portRadius = BASE_PORT_RADIUS;
		int hitRadius = BASE_PORT_HIT_RADIUS;
		int rowHeight = BASE_ROW_HEIGHT;
		int rowGap = BASE_ROW_GAP;
		int bodyTopPadding = BASE_BODY_TOP_PADDING;
		int bodyBottomPadding = BASE_BODY_BOTTOM_PADDING;
		int fieldWidth = BASE_FIELD_WIDTH;

		List<PortWidget> builtPorts = new ArrayList<>();
		List<RowWidget> builtRows = new ArrayList<>();
		int controlRight = x + width - edgePadding;
		int contentStart = y + headerHeight + bodyTopPadding;
		int rowY = contentStart;
		appendHeaderControls(headerControls(), rowHeight, builtRows);
		List<PortDefinition> inputPorts = nodeType.inputs(node);
		List<PortDefinition> outputPorts = nodeType.outputs(node);
		List<PortDefinition> controlInputs = inputPorts.stream().filter(port -> port.channel() == PortChannel.CONTROL).toList();
		List<PortDefinition> dataInputs = inputPorts.stream().filter(port -> port.channel() == PortChannel.DATA).toList();
		List<PortDefinition> controlOutputs = outputPorts.stream().filter(port -> port.channel() == PortChannel.CONTROL).toList();
		List<PortDefinition> dataOutputs = outputPorts.stream().filter(port -> port.channel() == PortChannel.DATA).toList();
		List<Integer> separators = new ArrayList<>();

		rowY = appendPortGroup(controlInputs, controlOutputs, hasIncomingConnection, controlRight, fieldWidth, rowHeight, rowGap, portRadius, hitRadius, builtPorts, builtRows, rowY);
		if ((!controlInputs.isEmpty() || !controlOutputs.isEmpty()) && (!dataInputs.isEmpty() || !dataOutputs.isEmpty())) {
			separators.add(rowY + ((BASE_GROUP_GAP - rowGap) / 2));
			rowY += BASE_GROUP_GAP;
		}
		rowY = appendPortGroup(dataInputs, dataOutputs, hasIncomingConnection, controlRight, fieldWidth, rowHeight, rowGap, portRadius, hitRadius, builtPorts, builtRows, rowY);

		boolean hasPortRows = rowY > contentStart;
		int portBottom = hasPortRows ? rowY - rowGap : contentStart;
		List<NodeEditorControl> bodyControls = bodyControls();
		int controlCount = bodyControls.size();
		int controlsHeight = controlCount == 0 ? 0 : (controlCount * rowHeight) + ((controlCount - 1) * rowGap);
		boolean visibleBody = bodyMeasurement.visible();
		int bodyTop = visibleBody ? portBottom + (hasPortRows ? BASE_CUSTOM_BODY_GAP : 0) : portBottom;
		int storedContentHeight = requestedHeight <= 0 ? -1 : Math.max(0, requestedHeight - headerHeight - bodyTopPadding - bodyBottomPadding);
		int outsideBodyHeight = Math.max(0, bodyTop - contentStart) + (visibleBody && controlsHeight > 0 ? BASE_GROUP_GAP : 0) + controlsHeight;
		int allocatedBodyHeight = 0;
		if (visibleBody) {
			allocatedBodyHeight = storedContentHeight > 0
				? Math.max(bodyMeasurement.minHeight(), storedContentHeight - outsideBodyHeight)
				: bodyMeasurement.preferredHeight();
		}
		Bounds body = visibleBody ? new Bounds(x + edgePadding, bodyTop, Math.max(24, width - (edgePadding * 2)), allocatedBodyHeight) : null;
		int controlsStartY = visibleBody
			? bodyTop + allocatedBodyHeight + (controlsHeight > 0 ? BASE_GROUP_GAP : 0)
			: (hasPortRows ? rowY : contentStart);
		int afterControlsY = appendControls(bodyControls, controlRight, fieldWidth, rowHeight, rowGap, builtRows, controlsStartY);
		int rowsBottom = controlCount > 0
			? afterControlsY - rowGap
			: (hasPortRows ? rowY - rowGap : contentStart);
		int contentBottom;
		if (hasPortRows || controlCount > 0) {
			contentBottom = Math.max(visibleBody ? bodyTop + allocatedBodyHeight : contentStart, rowsBottom);
		} else if (visibleBody) {
			contentBottom = bodyTop + allocatedBodyHeight;
		} else {
			contentBottom = contentStart + BASE_EMPTY_BODY_HEIGHT;
		}
		int minContentHeight = Math.max(
			BASE_EMPTY_BODY_HEIGHT,
			Math.max(0, portBottom - contentStart)
				+ (visibleBody ? ((hasPortRows ? BASE_CUSTOM_BODY_GAP : 0) + bodyMeasurement.minHeight()) : 0)
				+ (visibleBody && controlsHeight > 0 ? BASE_GROUP_GAP : 0)
				+ controlsHeight
		);
		int finalHeight = headerHeight + bodyTopPadding + Math.max(minContentHeight, contentBottom - contentStart) + bodyBottomPadding;
		int calculatedMinHeight = headerHeight + bodyTopPadding + minContentHeight + bodyBottomPadding;
		return new FullLayout(List.copyOf(builtPorts), List.copyOf(builtRows), List.copyOf(separators), body, finalHeight, calculatedMinHeight);
	}

	private int appendPortGroup(
		List<PortDefinition> inputs,
		List<PortDefinition> outputs,
		BiPredicate<NodeId, PortId> hasIncomingConnection,
		int controlRight,
		int fieldWidth,
		int rowHeight,
		int rowGap,
		int portRadius,
		int hitRadius,
		List<PortWidget> builtPorts,
		List<RowWidget> builtRows,
		int startY
	) {
		int rowY = startY;
		int inputIndex = 0;
		int outputIndex = 0;
		while (inputIndex < inputs.size() || outputIndex < outputs.size()) {
			boolean advanceRow = false;
			if (inputIndex < inputs.size()) {
				PortDefinition input = inputs.get(inputIndex++);
				boolean connected = hasIncomingConnection.test(node.id(), input.id());
				Bounds fieldBounds = inputFieldBounds(input, connected, controlRight, fieldWidth, rowY, rowHeight);
				PortWidget inputWidget = new PortWidget(node.id(), input, PortSide.LEFT, x + edgePadding, rowY + (rowHeight / 2), portRadius, hitRadius);
				builtPorts.add(inputWidget);
				builtRows.add(new InputPortRowWidget(rowY, rowHeight, inputWidget, fieldBounds, connected));
				advanceRow = true;

				if (fieldBounds == null && outputIndex < outputs.size()) {
					PortDefinition output = outputs.get(outputIndex++);
					PortWidget outputWidget = new PortWidget(node.id(), output, PortSide.RIGHT, x + width - edgePadding, rowY + (rowHeight / 2), portRadius, hitRadius);
					builtPorts.add(outputWidget);
					builtRows.add(new OutputPortRowWidget(rowY, rowHeight, outputWidget));
				}
			} else if (outputIndex < outputs.size()) {
				PortDefinition output = outputs.get(outputIndex++);
				PortWidget outputWidget = new PortWidget(node.id(), output, PortSide.RIGHT, x + width - edgePadding, rowY + (rowHeight / 2), portRadius, hitRadius);
				builtPorts.add(outputWidget);
				builtRows.add(new OutputPortRowWidget(rowY, rowHeight, outputWidget));
				advanceRow = true;
			}

			if (advanceRow) {
				rowY += rowHeight + rowGap;
			}
		}
		return rowY;
	}

	private void appendHeaderControls(List<NodeEditorControl> controls, int rowHeight, List<RowWidget> builtRows) {
		int fieldHeight = Math.max(10, rowHeight - 4);
		int fieldY = y + Math.max(2, (headerHeight - fieldHeight) / 2);
		for (NodeEditorControl control : controls) {
			if (control instanceof NodeEditorControl.TextControl textControl) {
				builtRows.add(new TextControlRowWidget(
					y,
					headerHeight,
					textControl,
					new Bounds(x + edgePadding, fieldY, width - (edgePadding * 2), fieldHeight),
					false
				));
			}
		}
	}

	private int appendControls(
		List<NodeEditorControl> controls,
		int controlRight,
		int fieldWidth,
		int rowHeight,
		int rowGap,
		List<RowWidget> builtRows,
		int startY
	) {
		int rowY = startY;
		int arrowWidth = BASE_ARROW_WIDTH;
		for (NodeEditorControl control : controls) {
			boolean fullWidth = fullWidthControl(control);
			int controlX = fullWidth ? x + edgePadding : controlRight - fieldWidth;
			int controlWidth = fullWidth ? width - (edgePadding * 2) : fieldWidth;
			switch (control) {
				case NodeEditorControl.TextControl textControl -> builtRows.add(new TextControlRowWidget(
					rowY,
					rowHeight,
					textControl,
					new Bounds(controlX, rowY + 2, controlWidth, Math.max(10, rowHeight - 4)),
					!fullWidth
				));
				case NodeEditorControl.NumericTextControl numericTextControl -> builtRows.add(new NumericTextControlRowWidget(
					rowY,
					rowHeight,
					numericTextControl,
					new Bounds(controlX, rowY + 2, controlWidth, Math.max(10, rowHeight - 4))
				));
				case NodeEditorControl.BooleanControl booleanControl -> builtRows.add(new BooleanControlRowWidget(
					rowY,
					rowHeight,
					booleanControl,
					new Bounds(controlX, rowY + 2, fieldWidth, Math.max(10, rowHeight - 4))
				));
				case NodeEditorControl.CycleControl cycleControl -> {
					int controlHeight = Math.max(10, rowHeight - 4);
					int valueWidth = Math.max(18, fieldWidth - (arrowWidth * 2));
					int cycleY = rowY + 2;
					builtRows.add(new CycleControlRowWidget(
						rowY,
						rowHeight,
						cycleControl,
						new Bounds(controlX, cycleY, arrowWidth, controlHeight),
						new Bounds(controlX + arrowWidth, cycleY, valueWidth, controlHeight),
						new Bounds(controlX + arrowWidth + valueWidth, cycleY, arrowWidth, controlHeight)
					));
				}
			}
			rowY += rowHeight + rowGap;
		}
		return rowY;
	}

	private List<NodeEditorControl> headerControls() {
		if (!embeddedHeaderControl) {
			return List.of();
		}
		return nodeType.editorControls().stream()
			.filter(this::fullWidthControl)
			.toList();
	}

	private List<NodeEditorControl> bodyControls() {
		if (!embeddedHeaderControl) {
			return nodeType.editorControls();
		}
		return nodeType.editorControls().stream()
			.filter(control -> !fullWidthControl(control))
			.toList();
	}

	private boolean fullWidthControl(NodeEditorControl control) {
		return embeddedHeaderControl
			&& control instanceof NodeEditorControl.TextControl textControl
			&& DocumentNodeTypes.DEFINITION_NAME_CONTROL_KEY.equals(textControl.key());
	}

	private List<PortWidget> buildReroutePorts(RerouteOrientation orientation) {
		List<PortWidget> widgets = new ArrayList<>();
		int portRadius = BASE_PORT_RADIUS;
		int hitRadius = BASE_PORT_HIT_RADIUS;

		for (PortDefinition port : nodeType.inputs(node)) {
			PortSide side = reroutePortSide(orientation, port.direction());
			widgets.add(new PortWidget(node.id(), port, side, reroutePortCenterX(side), reroutePortCenterY(side), portRadius, hitRadius));
		}
		for (PortDefinition port : nodeType.outputs(node)) {
			PortSide side = reroutePortSide(orientation, port.direction());
			widgets.add(new PortWidget(node.id(), port, side, reroutePortCenterX(side), reroutePortCenterY(side), portRadius, hitRadius));
		}
		return List.copyOf(widgets);
	}

	private PortSide reroutePortSide(RerouteOrientation orientation, PortDirection direction) {
		return direction == PortDirection.INPUT ? orientation.inputSide() : orientation.outputSide();
	}

	private int reroutePortCenterX(PortSide side) {
		return switch (side) {
			case LEFT -> x + edgePadding;
			case RIGHT -> x + width - edgePadding;
			case TOP, BOTTOM -> x + (width / 2);
		};
	}

	private int reroutePortCenterY(PortSide side) {
		return switch (side) {
			case LEFT, RIGHT -> y + (height / 2);
			case TOP -> y + edgePadding;
			case BOTTOM -> y + height - edgePadding;
		};
	}

	private Bounds inputFieldBounds(PortDefinition port, boolean connected, int controlRight, int fieldWidth, int rowY, int rowHeight) {
		if (connected || port.inlineWidget() == null) {
			return null;
		}
		return new Bounds(controlRight - fieldWidth, rowY + 2, fieldWidth, Math.max(10, rowHeight - 4));
	}

	@SuppressWarnings("unchecked")
	private static NodeInstance createPreviewNode(NodeType<?> nodeType) {
		NodeType<Object> typed = (NodeType<Object>) nodeType;
		return new NodeInstance(new NodeId("__palette_preview__"), typed.id(), typed.configCodec().toJson(typed.defaultConfig()));
	}

	static int compactRerouteMinimumLength() {
		return BASE_REROUTE_WIDTH;
	}

	static int compactRerouteThickness() {
		return BASE_REROUTE_HEIGHT;
	}

	public enum PortSide {
		LEFT,
		RIGHT,
		TOP,
		BOTTOM;

		public boolean isHorizontal() {
			return this == LEFT || this == RIGHT;
		}

		public boolean isVertical() {
			return this == TOP || this == BOTTOM;
		}

		public boolean positiveAxis() {
			return this == RIGHT || this == BOTTOM;
		}

		public int normalX() {
			return switch (this) {
				case LEFT -> -1;
				case RIGHT -> 1;
				case TOP, BOTTOM -> 0;
			};
		}

		public int normalY() {
			return switch (this) {
				case TOP -> -1;
				case BOTTOM -> 1;
				case LEFT, RIGHT -> 0;
			};
		}

		public PortSide opposite() {
			return switch (this) {
				case LEFT -> RIGHT;
				case RIGHT -> LEFT;
				case TOP -> BOTTOM;
				case BOTTOM -> TOP;
			};
		}
	}

	public record PortWidget(NodeId nodeId, PortDefinition definition, PortSide side, int centerX, int centerY, int radius, int hitRadius) {
		public boolean contains(double mouseX, double mouseY) {
			return mouseX >= centerX - hitRadius && mouseX <= centerX + hitRadius && mouseY >= centerY - hitRadius && mouseY <= centerY + hitRadius;
		}

		public boolean isInput() {
			return definition.direction() == PortDirection.INPUT;
		}
	}

	public sealed interface RowWidget permits InputPortRowWidget, OutputPortRowWidget, TextControlRowWidget, NumericTextControlRowWidget, BooleanControlRowWidget, CycleControlRowWidget {
		int y();

		int height();
	}

	public record InputPortRowWidget(int y, int height, PortWidget port, Bounds fieldBounds, boolean connected) implements RowWidget {
	}

	public record OutputPortRowWidget(int y, int height, PortWidget port) implements RowWidget {
	}

	public record TextControlRowWidget(int y, int height, NodeEditorControl.TextControl control, Bounds fieldBounds, boolean labelVisible) implements RowWidget {
	}

	public record NumericTextControlRowWidget(int y, int height, NodeEditorControl.NumericTextControl control, Bounds fieldBounds) implements RowWidget {
	}

	public record BooleanControlRowWidget(int y, int height, NodeEditorControl.BooleanControl control, Bounds toggleBounds) implements RowWidget {
	}

	public record CycleControlRowWidget(
		int y,
		int height,
		NodeEditorControl.CycleControl control,
		Bounds leftArrowBounds,
		Bounds valueBounds,
		Bounds rightArrowBounds
	) implements RowWidget {
	}

	public record Bounds(int x, int y, int width, int height) {
		public boolean contains(double mouseX, double mouseY) {
			return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
		}
	}

	public record ResizeHandle(ResizeDirection direction) {
	}

	public sealed interface InlineHit permits InlineHit.PortTextFieldHit, InlineHit.PortBooleanHit, InlineHit.ControlTextFieldHit, InlineHit.ControlBooleanHit, InlineHit.ControlCycleHit {
		record PortTextFieldHit(NodeId nodeId, PortId portId, Bounds bounds, boolean numeric) implements InlineHit {
		}

		record PortBooleanHit(NodeId nodeId, PortId portId) implements InlineHit {
		}

		record ControlTextFieldHit(NodeId nodeId, String key, Bounds bounds, boolean numeric) implements InlineHit {
		}

		record ControlBooleanHit(NodeId nodeId, String key) implements InlineHit {
		}

		record ControlCycleHit(NodeId nodeId, String key, int direction) implements InlineHit {
		}
	}

	private record RequestedNodeSize(int width, int height) {
	}

	private record FullLayout(List<PortWidget> ports, List<RowWidget> rows, List<Integer> channelSeparators, Bounds bodyBounds, int height, int minHeight) {
	}
}
