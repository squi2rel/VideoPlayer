package com.github.squi2rel.mcng.fabric.client;

import com.github.squi2rel.mcng.core.NodeId;
import com.github.squi2rel.mcng.core.NodePosition;
import com.github.squi2rel.mcng.core.NodeSize;
import com.github.squi2rel.mcng.core.PortDirection;
import com.github.squi2rel.mcng.fabric.client.NodeWidget.PortWidget;

import java.util.LinkedHashMap;
import java.util.Map;

public final class GraphInteractionController {
	private final Map<NodeId, NodePosition> draggingNodes = new LinkedHashMap<>();
	private ResizeOperation resizeOperation;
	private NodeId collapseSelectionNodeId;
	private boolean draggedNodeSelection;

	private double dragAnchorWorldX;
	private double dragAnchorWorldY;
	private boolean panning;
	private boolean selecting;
	private boolean additiveSelection;
	private double selectionStartWorldX;
	private double selectionStartWorldY;
	private double selectionCurrentWorldX;
	private double selectionCurrentWorldY;

	public boolean mouseClicked(GraphCanvasComponent canvas, double mouseX, double mouseY, int button) {
		PortWidget port = canvas.portAtLocal(mouseX, mouseY);
		if (port != null) {
			if (button == 1) {
				canvas.session().clearPortConnections(port.nodeId(), port.definition().id(), port.definition().direction());
				return true;
			}
			canvas.session().toggleConnectionCandidate(port.nodeId(), port.definition().id(), port.definition().direction(), port.side());
			return true;
		}

		GraphCanvasComponent.ResizeTarget resizeTarget = canvas.resizeTargetAtLocal(mouseX, mouseY);
		if (resizeTarget != null && button == 0) {
			NodeWidget widget = resizeTarget.widget();
			if (!canvas.session().isSelected(widget.node().id())) {
				canvas.session().selectNode(widget.node().id());
			}
			canvas.session().beginCompositeEdit();
			NodeWidget.PortSide fixedRerouteSide = widget.compactReroute() ? fixedReroutePortSide(widget.rerouteOrientation(), resizeTarget.direction()) : null;
			PortWidget fixedReroutePort = fixedRerouteSide == null ? null : reroutePortOnSide(widget, fixedRerouteSide);
			resizeOperation = new ResizeOperation(
				widget.node().id(),
				resizeTarget.direction(),
				canvas.session().positions().getOrDefault(widget.node().id(), new NodePosition(widget.x(), widget.y())),
				new NodeSize(widget.width(), widget.height()),
				widget.minWidth(),
				widget.minHeight(),
				mouseX,
				mouseY,
				canvas.viewport().toWorldX(mouseX),
				canvas.viewport().toWorldY(mouseY),
				widget.compactReroute(),
				widget.rerouteOrientation(),
				widget.edgePadding(),
				fixedReroutePort == null ? null : fixedReroutePort.definition().direction(),
				fixedReroutePort == null ? 0.0 : fixedReroutePort.centerX(),
				fixedReroutePort == null ? 0.0 : fixedReroutePort.centerY()
			);
			draggingNodes.clear();
			collapseSelectionNodeId = null;
			draggedNodeSelection = false;
			return true;
		}

		NodeWidget node = canvas.nodeAtLocal(mouseX, mouseY);
		if (node != null) {
			if (button == 0) {
				if (shiftDown()) {
					canvas.session().toggleNodeSelection(node.node().id());
					collapseSelectionNodeId = null;
				} else if (!canvas.session().isSelected(node.node().id())) {
					canvas.session().selectNode(node.node().id());
					collapseSelectionNodeId = null;
				} else {
					collapseSelectionNodeId = canvas.session().selectedNodeIds().size() > 1 ? node.node().id() : null;
				}
				draggingNodes.clear();
				for (NodeId nodeId : canvas.session().selectedNodeIds()) {
					draggingNodes.put(nodeId, canvas.session().positions().getOrDefault(nodeId, new NodePosition(0, 0)));
				}
				canvas.session().beginCompositeEdit();
				dragAnchorWorldX = canvas.viewport().toWorldX(mouseX);
				dragAnchorWorldY = canvas.viewport().toWorldY(mouseY);
				draggedNodeSelection = false;
			} else {
				collapseSelectionNodeId = null;
				canvas.session().selectNode(node.node().id());
			}
			return true;
		}

		if (button == 2) {
			collapseSelectionNodeId = null;
			panning = true;
			return true;
		}
		if (button == 0) {
			collapseSelectionNodeId = null;
			additiveSelection = shiftDown();
			if (!additiveSelection) {
				canvas.session().clearSelection();
			}
			selecting = true;
			selectionStartWorldX = canvas.viewport().toWorldX(mouseX);
			selectionStartWorldY = canvas.viewport().toWorldY(mouseY);
			selectionCurrentWorldX = selectionStartWorldX;
			selectionCurrentWorldY = selectionStartWorldY;
			return true;
		}

		return false;
	}

	public boolean mouseDragged(GraphCanvasComponent canvas, double mouseX, double mouseY, int button, double deltaX, double deltaY) {
		if (resizeOperation != null) {
			if (resizeOperation.compactReroute()) {
				return dragCompactReroute(canvas, mouseX, mouseY);
			}
			return dragRegularResize(canvas, mouseX, mouseY);
		}
		if (!draggingNodes.isEmpty()) {
			double currentWorldX = canvas.viewport().toWorldX(mouseX);
			double currentWorldY = canvas.viewport().toWorldY(mouseY);
			double offsetX = currentWorldX - dragAnchorWorldX;
			double offsetY = currentWorldY - dragAnchorWorldY;
			if (offsetX != 0.0 || offsetY != 0.0) {
				draggedNodeSelection = true;
			}

			Map<NodeId, NodePosition> updated = new LinkedHashMap<>();
			draggingNodes.forEach((nodeId, position) -> updated.put(nodeId, new NodePosition(position.x() + offsetX, position.y() + offsetY)));
			canvas.session().moveNodes(updated);
			return true;
		}
		if (panning) {
			canvas.viewport().pan(deltaX, deltaY);
			return true;
		}
		if (selecting) {
			selectionCurrentWorldX = canvas.viewport().toWorldX(mouseX);
			selectionCurrentWorldY = canvas.viewport().toWorldY(mouseY);
			return true;
		}
		return false;
	}

	private boolean dragRegularResize(GraphCanvasComponent canvas, double mouseX, double mouseY) {
		double currentWorldX = canvas.viewport().toWorldX(mouseX);
		double currentWorldY = canvas.viewport().toWorldY(mouseY);
		double offsetX = currentWorldX - resizeOperation.anchorWorldX();
		double offsetY = currentWorldY - resizeOperation.anchorWorldY();
		double left = resizeOperation.startPosition().x();
		double top = resizeOperation.startPosition().y();
		double right = left + resizeOperation.startSize().width();
		double bottom = top + resizeOperation.startSize().height();

		if (resizeOperation.direction().includesLeft()) {
			left = Math.min(right - resizeOperation.minWidth(), left + offsetX);
		}
		if (resizeOperation.direction().includesRight()) {
			right = Math.max(left + resizeOperation.minWidth(), right + offsetX);
		}
		if (resizeOperation.direction().includesTop()) {
			top = Math.min(bottom - resizeOperation.minHeight(), top + offsetY);
		}
		if (resizeOperation.direction().includesBottom()) {
			bottom = Math.max(top + resizeOperation.minHeight(), bottom + offsetY);
		}

		canvas.session().resizeNode(
			resizeOperation.nodeId(),
			new NodePosition(left, top),
			new NodeSize(Math.max(resizeOperation.minWidth(), (int) Math.round(right - left)), Math.max(resizeOperation.minHeight(), (int) Math.round(bottom - top)))
		);
		return true;
	}

	private boolean dragCompactReroute(GraphCanvasComponent canvas, double mouseX, double mouseY) {
		double mouseWorldX = canvas.viewport().toWorldX(mouseX);
		double mouseWorldY = canvas.viewport().toWorldY(mouseY);
		boolean vertical = activeRerouteOrientation(mouseWorldX, mouseWorldY);
		NodeWidget.PortSide draggedSide = rerouteDraggedSide(mouseWorldX, mouseWorldY, vertical);
		NodeWidget.PortSide fixedSide = draggedSide.opposite();
		double fixedPortX = resizeOperation.fixedPortWorldX();
		double fixedPortY = resizeOperation.fixedPortWorldY();
		int thickness = NodeWidget.compactRerouteThickness();
		int minimumLength = NodeWidget.compactRerouteMinimumLength();
		int minimumSpan = Math.max(0, minimumLength - (resizeOperation.portInset() * 2));
		double left;
		double top;
		NodeSize size;

		if (vertical) {
			double draggedPortY = draggedSide == NodeWidget.PortSide.BOTTOM
				? Math.max(mouseWorldY, fixedPortY + minimumSpan)
				: Math.min(mouseWorldY, fixedPortY - minimumSpan);
			left = fixedPortX - (thickness / 2.0);
			top = fixedSide == NodeWidget.PortSide.TOP
				? fixedPortY - resizeOperation.portInset()
				: draggedPortY - resizeOperation.portInset();
			double bottom = fixedSide == NodeWidget.PortSide.BOTTOM
				? fixedPortY + resizeOperation.portInset()
				: draggedPortY + resizeOperation.portInset();
			size = new NodeSize(thickness, Math.max(minimumLength, (int) Math.round(bottom - top)));
		} else {
			double draggedPortX = draggedSide == NodeWidget.PortSide.RIGHT
				? Math.max(mouseWorldX, fixedPortX + minimumSpan)
				: Math.min(mouseWorldX, fixedPortX - minimumSpan);
			left = fixedSide == NodeWidget.PortSide.LEFT
				? fixedPortX - resizeOperation.portInset()
				: draggedPortX - resizeOperation.portInset();
			top = fixedPortY - (thickness / 2.0);
			double right = fixedSide == NodeWidget.PortSide.RIGHT
				? fixedPortX + resizeOperation.portInset()
				: draggedPortX + resizeOperation.portInset();
			size = new NodeSize(Math.max(minimumLength, (int) Math.round(right - left)), thickness);
		}

		RerouteOrientation orientation = RerouteOrientation.fromFixedPort(fixedSide, resizeOperation.fixedPortDirection());
		canvas.session().resizeReroute(resizeOperation.nodeId(), new NodePosition(left, top), size, orientation);
		return true;
	}

	private boolean activeRerouteOrientation(double mouseWorldX, double mouseWorldY) {
		if (!resizeOperation.direction().isCorner()) {
			return resizeOperation.startOrientation().vertical();
		}
		double deltaX = mouseWorldX - resizeOperation.fixedPortWorldX();
		double deltaY = mouseWorldY - resizeOperation.fixedPortWorldY();
		return Math.abs(deltaY) > Math.abs(deltaX);
	}

	private NodeWidget.PortSide rerouteDraggedSide(double mouseWorldX, double mouseWorldY, boolean vertical) {
		if (vertical) {
			return mouseWorldY >= resizeOperation.fixedPortWorldY() ? NodeWidget.PortSide.BOTTOM : NodeWidget.PortSide.TOP;
		}
		return mouseWorldX >= resizeOperation.fixedPortWorldX() ? NodeWidget.PortSide.RIGHT : NodeWidget.PortSide.LEFT;
	}

	private static NodeWidget.PortSide fixedReroutePortSide(RerouteOrientation orientation, ResizeDirection direction) {
		if (orientation.vertical()) {
			return direction.includesTop() ? NodeWidget.PortSide.BOTTOM : NodeWidget.PortSide.TOP;
		}
		return direction.includesLeft() ? NodeWidget.PortSide.RIGHT : NodeWidget.PortSide.LEFT;
	}

	private static PortWidget reroutePortOnSide(NodeWidget widget, NodeWidget.PortSide side) {
		return widget.ports().stream()
			.filter(port -> port.side() == side)
			.findFirst()
			.orElse(null);
	}

	public boolean mouseReleased(GraphCanvasComponent canvas, double mouseX, double mouseY, int button) {
		if (button == 0) {
			if (resizeOperation != null) {
				resizeOperation = null;
				collapseSelectionNodeId = null;
				draggedNodeSelection = false;
				canvas.session().endCompositeEdit();
				return true;
			}
			if (!draggingNodes.isEmpty()) {
				if (!draggedNodeSelection && collapseSelectionNodeId != null) {
					canvas.session().selectNode(collapseSelectionNodeId);
				}
				draggingNodes.clear();
				collapseSelectionNodeId = null;
				draggedNodeSelection = false;
				canvas.session().endCompositeEdit();
				return true;
			}
			if (selecting) {
				selecting = false;
				collapseSelectionNodeId = null;
				draggedNodeSelection = false;
				canvas.session().selectNodes(
					canvas.nodesInRect(
						Math.min(selectionStartWorldX, selectionCurrentWorldX),
						Math.min(selectionStartWorldY, selectionCurrentWorldY),
						Math.max(selectionStartWorldX, selectionCurrentWorldX),
						Math.max(selectionStartWorldY, selectionCurrentWorldY)
					),
					additiveSelection
				);
				return true;
			}
		}
		if (button == 2) {
			panning = false;
			return true;
		}
		return false;
	}

	public boolean mouseScrolled(GraphCanvasComponent canvas, double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		canvas.viewport().zoomAt(verticalAmount * 0.1, mouseX, mouseY);
		return true;
	}

	public SelectionBox selectionBox() {
		if (!selecting) {
			return null;
		}
		return new SelectionBox(
			Math.min(selectionStartWorldX, selectionCurrentWorldX),
			Math.min(selectionStartWorldY, selectionCurrentWorldY),
			Math.max(selectionStartWorldX, selectionCurrentWorldX),
			Math.max(selectionStartWorldY, selectionCurrentWorldY)
		);
	}

	public ResizeDirection activeResizeDirection() {
		return resizeOperation == null ? null : resizeOperation.direction();
	}

	public record SelectionBox(double minX, double minY, double maxX, double maxY) {
	}

	private static final class ResizeOperation {
		private final NodeId nodeId;
		private final ResizeDirection direction;
		private final NodePosition startPosition;
		private final NodeSize startSize;
		private final int minWidth;
		private final int minHeight;
		private final double anchorLocalX;
		private final double anchorLocalY;
		private final double anchorWorldX;
		private final double anchorWorldY;
		private final boolean compactReroute;
		private final RerouteOrientation startOrientation;
		private final int portInset;
		private final PortDirection fixedPortDirection;
		private final double fixedPortWorldX;
		private final double fixedPortWorldY;

		private ResizeOperation(
			NodeId nodeId,
			ResizeDirection direction,
			NodePosition startPosition,
			NodeSize startSize,
			int minWidth,
			int minHeight,
			double anchorLocalX,
			double anchorLocalY,
			double anchorWorldX,
			double anchorWorldY,
			boolean compactReroute,
			RerouteOrientation startOrientation,
			int portInset,
			PortDirection fixedPortDirection,
			double fixedPortWorldX,
			double fixedPortWorldY
		) {
			this.nodeId = nodeId;
			this.direction = direction;
			this.startPosition = startPosition;
			this.startSize = startSize;
			this.minWidth = minWidth;
			this.minHeight = minHeight;
			this.anchorLocalX = anchorLocalX;
			this.anchorLocalY = anchorLocalY;
			this.anchorWorldX = anchorWorldX;
			this.anchorWorldY = anchorWorldY;
			this.compactReroute = compactReroute;
			this.startOrientation = startOrientation;
			this.portInset = portInset;
			this.fixedPortDirection = fixedPortDirection;
			this.fixedPortWorldX = fixedPortWorldX;
			this.fixedPortWorldY = fixedPortWorldY;
		}

		private NodeId nodeId() {
			return nodeId;
		}

		private ResizeDirection direction() {
			return direction;
		}

		private NodePosition startPosition() {
			return startPosition;
		}

		private NodeSize startSize() {
			return startSize;
		}

		private int minWidth() {
			return minWidth;
		}

		private int minHeight() {
			return minHeight;
		}

		private double anchorLocalX() {
			return anchorLocalX;
		}

		private double anchorLocalY() {
			return anchorLocalY;
		}

		private double anchorWorldX() {
			return anchorWorldX;
		}

		private double anchorWorldY() {
			return anchorWorldY;
		}

		private boolean compactReroute() {
			return compactReroute;
		}

		private RerouteOrientation startOrientation() {
			return startOrientation;
		}

		private int portInset() {
			return portInset;
		}

		private PortDirection fixedPortDirection() {
			return fixedPortDirection;
		}

		private double fixedPortWorldX() {
			return fixedPortWorldX;
		}

		private double fixedPortWorldY() {
			return fixedPortWorldY;
		}
	}

	private static boolean shiftDown() {
		return GraphInputModifiers.shiftDown();
	}
}
