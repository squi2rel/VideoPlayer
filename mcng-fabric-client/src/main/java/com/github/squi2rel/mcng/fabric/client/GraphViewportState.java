package com.github.squi2rel.mcng.fabric.client;

public final class GraphViewportState {
	public static final double MIN_ZOOM = 0.5;
	public static final double MAX_ZOOM = 2.5;

	private double offsetX;
	private double offsetY;
	private double zoom = 1.0;

	public double offsetX() {
		return offsetX;
	}

	public double offsetY() {
		return offsetY;
	}

	public double zoom() {
		return zoom;
	}

	public void reset() {
		offsetX = 40;
		offsetY = 40;
		zoom = 1.0;
	}

	public void pan(double deltaX, double deltaY) {
		offsetX += deltaX;
		offsetY += deltaY;
	}

	public void zoomAt(double amount, double screenX, double screenY) {
		double oldZoom = zoom;
		zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom + amount));
		double scaleRatio = zoom / oldZoom;
		offsetX = screenX - ((screenX - offsetX) * scaleRatio);
		offsetY = screenY - ((screenY - offsetY) * scaleRatio);
	}

	public double toScreenX(double worldX) {
		return (worldX * zoom) + offsetX;
	}

	public double toScreenY(double worldY) {
		return (worldY * zoom) + offsetY;
	}

	public double toWorldX(double screenX) {
		return (screenX - offsetX) / zoom;
	}

	public double toWorldY(double screenY) {
		return (screenY - offsetY) / zoom;
	}
}
