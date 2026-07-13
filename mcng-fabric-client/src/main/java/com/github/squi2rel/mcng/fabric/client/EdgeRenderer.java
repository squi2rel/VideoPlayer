package com.github.squi2rel.mcng.fabric.client;

import net.minecraft.client.gui.DrawContext;

import java.util.ArrayList;
import java.util.List;

public final class EdgeRenderer {
	private static final int MIN_CONTROL_OFFSET = 28;
	private static final int MAX_CONTROL_OFFSET = 96;
	private static final int MIN_SEGMENTS = 16;
	private static final int MAX_SEGMENTS = 42;
	private static final int LINE_RADIUS = 1;

	private EdgeRenderer() {
	}

	public static void drawEdge(
		DrawContext context,
		int startX,
		int startY,
		int endX,
		int endY,
		int startColor,
		int endColor,
		EdgeStyle edgeStyle,
		NodeWidget.PortSide startSide,
		NodeWidget.PortSide endSide
	) {
		switch (edgeStyle) {
			case STRAIGHT -> drawStraight(context, startX, startY, endX, endY, startColor, endColor);
			case ORTHOGONAL -> drawOrthogonal(context, startX, startY, endX, endY, startColor, endColor, startSide, endSide);
			case CURVE -> drawCurve(context, startX, startY, endX, endY, startColor, endColor, startSide, endSide);
		}
	}

	private static void drawStraight(DrawContext context, int startX, int startY, int endX, int endY, int startColor, int endColor) {
		drawGradientSegment(context, startX, startY, endX, endY, startColor, endColor, 0.0, 1.0);
	}

	private static void drawOrthogonal(
		DrawContext context,
		int startX,
		int startY,
		int endX,
		int endY,
		int startColor,
		int endColor,
		NodeWidget.PortSide startSide,
		NodeWidget.PortSide endSide
	) {
		double distance = Math.hypot(endX - startX, endY - startY);
		double offset = clamp(distance * 0.25, 14.0, 48.0);
		Point start = new Point(startX, startY);
		Point startExit = offsetPoint(startX, startY, startSide, offset);
		Point endEntry = offsetPoint(endX, endY, endSide, offset);
		List<Point> points = new ArrayList<>();
		appendPoint(points, start);
		appendPoint(points, startExit);
		if (startSide.isHorizontal() && endSide.isHorizontal()) {
			int midX = startExit.x() + ((endEntry.x() - startExit.x()) / 2);
			appendPoint(points, new Point(midX, startExit.y()));
			appendPoint(points, new Point(midX, endEntry.y()));
		} else if (startSide.isVertical() && endSide.isVertical()) {
			int midY = startExit.y() + ((endEntry.y() - startExit.y()) / 2);
			appendPoint(points, new Point(startExit.x(), midY));
			appendPoint(points, new Point(endEntry.x(), midY));
		} else {
			appendPoint(points, new Point(endEntry.x(), startExit.y()));
		}
		appendPoint(points, endEntry);
		appendPoint(points, new Point(endX, endY));
		drawGradientOrthogonalPolyline(context, points, startColor, endColor);
	}

	private static void drawCurve(
		DrawContext context,
		int startX,
		int startY,
		int endX,
		int endY,
		int startColor,
		int endColor,
		NodeWidget.PortSide startSide,
		NodeWidget.PortSide endSide
	) {
		double dx = endX - startX;
		double distance = Math.hypot(dx, endY - startY);
		CurveControls controls = curveControls(startX, startY, endX, endY, startSide, endSide);
		int segments = (int) clamp(Math.round(distance / 14.0), MIN_SEGMENTS, MAX_SEGMENTS);
		List<Point> points = new ArrayList<>();
		points.add(new Point(startX, startY));
		for (int index = 1; index <= segments; index++) {
			double t = index / (double) segments;
			int x = (int) Math.round(cubic(startX, controls.control1X(), controls.control2X(), endX, t));
			int y = (int) Math.round(cubic(startY, controls.control1Y(), controls.control2Y(), endY, t));
			points.add(new Point(x, y));
		}
		drawGradientPolyline(context, points, startColor, endColor);
	}

	static CurveControls curveControls(int startX, int startY, int endX, int endY, NodeWidget.PortSide startSide, NodeWidget.PortSide endSide) {
		double distance = Math.hypot(endX - startX, endY - startY);
		double controlOffset = clamp(distance * 0.35, MIN_CONTROL_OFFSET, MAX_CONTROL_OFFSET);
		if (startSide == endSide) {
			controlOffset = Math.min(MAX_CONTROL_OFFSET, Math.max(controlOffset, MIN_CONTROL_OFFSET * 2.0));
		}
		return new CurveControls(
			startX + (startSide.normalX() * controlOffset),
			startY + (startSide.normalY() * controlOffset),
			endX + (endSide.normalX() * controlOffset),
			endY + (endSide.normalY() * controlOffset)
		);
	}

	private static void drawGradientPolyline(DrawContext context, List<Point> points, int startColor, int endColor) {
		double totalLength = 0.0;
		for (int index = 1; index < points.size(); index++) {
			totalLength += distance(points.get(index - 1), points.get(index));
		}
		if (totalLength <= 0.0) {
			return;
		}

		double traversed = 0.0;
		for (int index = 1; index < points.size(); index++) {
			Point from = points.get(index - 1);
			Point to = points.get(index);
			double segmentLength = distance(from, to);
			double startT = traversed / totalLength;
			double endT = (traversed + segmentLength) / totalLength;
			drawGradientSegment(context, from.x(), from.y(), to.x(), to.y(), startColor, endColor, startT, endT);
			traversed += segmentLength;
		}
	}

	private static void drawGradientOrthogonalPolyline(DrawContext context, List<Point> points, int startColor, int endColor) {
		double totalLength = 0.0;
		for (int index = 1; index < points.size(); index++) {
			totalLength += distance(points.get(index - 1), points.get(index));
		}
		if (totalLength <= 0.0) {
			return;
		}

		double traversed = 0.0;
		for (int index = 1; index < points.size(); index++) {
			Point from = points.get(index - 1);
			Point to = points.get(index);
			double segmentLength = distance(from, to);
			if (segmentLength <= 0.0) {
				continue;
			}
			double startT = traversed / totalLength;
			double endT = (traversed + segmentLength) / totalLength;
			drawGradientOrthogonalSegment(context, from, to, startColor, endColor, startT, endT);
			traversed += segmentLength;
		}
	}

	private static void drawGradientSegment(
		DrawContext context,
		double startX,
		double startY,
		double endX,
		double endY,
		int startColor,
		int endColor,
		double startT,
		double endT
	) {
		int steps = Math.max(1, (int) Math.ceil(Math.max(Math.abs(endX - startX), Math.abs(endY - startY))));
		for (int step = 0; step <= steps; step++) {
			double segmentT = step / (double) steps;
			double globalT = startT + ((endT - startT) * segmentT);
			int x = (int) Math.round(startX + ((endX - startX) * segmentT));
			int y = (int) Math.round(startY + ((endY - startY) * segmentT));
			int color = mixColor(startColor, endColor, globalT);
			context.fill(x - LINE_RADIUS, y - LINE_RADIUS, x + LINE_RADIUS + 1, y + LINE_RADIUS + 1, color);
		}
	}

	private static void drawGradientOrthogonalSegment(
		DrawContext context,
		Point from,
		Point to,
		int startColor,
		int endColor,
		double startT,
		double endT
	) {
		int dx = to.x() - from.x();
		int dy = to.y() - from.y();
		if (dx != 0 && dy != 0) {
			drawGradientSegment(context, from.x(), from.y(), to.x(), to.y(), startColor, endColor, startT, endT);
			return;
		}

		int steps = Math.abs(dx) + Math.abs(dy);
		int stepX = Integer.compare(dx, 0);
		int stepY = Integer.compare(dy, 0);
		for (int step = 0; step <= steps; step++) {
			double segmentT = steps == 0 ? 0.0 : step / (double) steps;
			double globalT = startT + ((endT - startT) * segmentT);
			int color = mixColor(startColor, endColor, globalT);
			int x = from.x() + (stepX * step);
			int y = from.y() + (stepY * step);
			if (stepX != 0) {
				context.fill(x, y - LINE_RADIUS, x + 1, y + LINE_RADIUS + 1, color);
			} else {
				context.fill(x - LINE_RADIUS, y, x + LINE_RADIUS + 1, y + 1, color);
			}
		}
	}

	private static double cubic(double p0, double p1, double p2, double p3, double t) {
		double inverse = 1.0 - t;
		return (inverse * inverse * inverse * p0)
			+ (3.0 * inverse * inverse * t * p1)
			+ (3.0 * inverse * t * t * p2)
			+ (t * t * t * p3);
	}

	private static double distance(Point from, Point to) {
		return Math.hypot(to.x() - from.x(), to.y() - from.y());
	}

	private static int mixColor(int startColor, int endColor, double t) {
		int alpha = mixChannel((startColor >>> 24) & 0xFF, (endColor >>> 24) & 0xFF, t);
		int red = mixChannel((startColor >>> 16) & 0xFF, (endColor >>> 16) & 0xFF, t);
		int green = mixChannel((startColor >>> 8) & 0xFF, (endColor >>> 8) & 0xFF, t);
		int blue = mixChannel(startColor & 0xFF, endColor & 0xFF, t);
		return (alpha << 24) | (red << 16) | (green << 8) | blue;
	}

	private static int mixChannel(int start, int end, double t) {
		return Math.max(0, Math.min(255, (int) Math.round(start + ((end - start) * t))));
	}

	private static double clamp(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}

	private static Point offsetPoint(int x, int y, NodeWidget.PortSide side, double offset) {
		return new Point(
			(int) Math.round(x + (side.normalX() * offset)),
			(int) Math.round(y + (side.normalY() * offset))
		);
	}

	private static void appendPoint(List<Point> points, Point point) {
		if (!points.isEmpty() && points.getLast().equals(point)) {
			return;
		}
		points.add(point);
	}

	record CurveControls(double control1X, double control1Y, double control2X, double control2Y) {
	}

	private record Point(int x, int y) {
	}
}
