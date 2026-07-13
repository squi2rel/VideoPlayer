package com.github.squi2rel.mcng.fabric.client;

public enum NodeRenderDetailLevel {
	FULL,
	MINIMAL;

	private static final double MINIMAL_EPSILON = 1.0E-6;

	public static NodeRenderDetailLevel fromZoom(double zoom) {
		return zoom <= GraphViewportState.MIN_ZOOM + MINIMAL_EPSILON ? MINIMAL : FULL;
	}
}
