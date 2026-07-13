package com.github.squi2rel.mcng.fabric.client;

import com.github.squi2rel.mcng.core.PortDirection;

public enum RerouteOrientation {
	LEFT_TO_RIGHT("left_to_right", NodeWidget.PortSide.LEFT),
	RIGHT_TO_LEFT("right_to_left", NodeWidget.PortSide.RIGHT),
	TOP_TO_BOTTOM("top_to_bottom", NodeWidget.PortSide.TOP),
	BOTTOM_TO_TOP("bottom_to_top", NodeWidget.PortSide.BOTTOM);

	private final String id;
	private final NodeWidget.PortSide inputSide;

	RerouteOrientation(String id, NodeWidget.PortSide inputSide) {
		this.id = id;
		this.inputSide = inputSide;
	}

	public String id() {
		return id;
	}

	public NodeWidget.PortSide inputSide() {
		return inputSide;
	}

	public NodeWidget.PortSide outputSide() {
		return inputSide.opposite();
	}

	public boolean vertical() {
		return inputSide.isVertical();
	}

	public static RerouteOrientation fromInputSide(NodeWidget.PortSide inputSide) {
		return switch (inputSide) {
			case LEFT -> LEFT_TO_RIGHT;
			case RIGHT -> RIGHT_TO_LEFT;
			case TOP -> TOP_TO_BOTTOM;
			case BOTTOM -> BOTTOM_TO_TOP;
		};
	}

	public static RerouteOrientation fromPortSide(NodeWidget.PortSide side, PortDirection direction) {
		return fromInputSide(direction == PortDirection.INPUT ? side : side.opposite());
	}

	public static RerouteOrientation fromFixedPort(NodeWidget.PortSide side, PortDirection direction) {
		return fromPortSide(side, direction);
	}

	public static RerouteOrientation parse(String value) {
		for (RerouteOrientation orientation : values()) {
			if (orientation.id.equals(value)) {
				return orientation;
			}
		}
		return null;
	}
}
