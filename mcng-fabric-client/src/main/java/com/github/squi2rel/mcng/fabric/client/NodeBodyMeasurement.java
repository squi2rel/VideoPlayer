package com.github.squi2rel.mcng.fabric.client;

public record NodeBodyMeasurement(
	boolean visible,
	int minWidth,
	int minHeight,
	int preferredWidth,
	int preferredHeight
) {
	public NodeBodyMeasurement {
		if (visible) {
			if (minWidth <= 0) {
				throw new IllegalArgumentException("minWidth must be positive for visible node bodies");
			}
			if (minHeight <= 0) {
				throw new IllegalArgumentException("minHeight must be positive for visible node bodies");
			}
			if (preferredWidth < minWidth) {
				throw new IllegalArgumentException("preferredWidth must be at least minWidth");
			}
			if (preferredHeight < minHeight) {
				throw new IllegalArgumentException("preferredHeight must be at least minHeight");
			}
		}
	}

	public static NodeBodyMeasurement hidden() {
		return new NodeBodyMeasurement(false, 0, 0, 0, 0);
	}

	public static NodeBodyMeasurement fixed(int width, int height) {
		return new NodeBodyMeasurement(true, width, height, width, height);
	}
}
