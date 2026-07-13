package com.github.squi2rel.mcng.fabric.client;

public record ResizePolicy(
	boolean allowLeft,
	boolean allowRight,
	boolean allowTop,
	boolean allowBottom
) {
	public static ResizePolicy none() {
		return new ResizePolicy(false, false, false, false);
	}

	public static ResizePolicy horizontal() {
		return new ResizePolicy(true, true, false, false);
	}

	public static ResizePolicy vertical() {
		return new ResizePolicy(false, false, true, true);
	}

	public static ResizePolicy allSides() {
		return new ResizePolicy(true, true, true, true);
	}

	public boolean resizable() {
		return allowLeft || allowRight || allowTop || allowBottom;
	}

	public boolean supports(ResizeDirection direction) {
		return (!direction.includesLeft() || allowLeft)
			&& (!direction.includesRight() || allowRight)
			&& (!direction.includesTop() || allowTop)
			&& (!direction.includesBottom() || allowBottom);
	}
}
