package com.github.squi2rel.mcng.fabric.client;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import net.minecraft.client.MinecraftClient;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeX11;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

final class GraphCursorManager {
	private static final Map<CursorKind, Long> STANDARD_CURSORS = new EnumMap<>(CursorKind.class);
	private static CursorKind currentKind = CursorKind.DEFAULT;

	private GraphCursorManager() {
	}

	static void apply(CursorKind kind) {
		CursorKind nextKind = kind == null ? CursorKind.DEFAULT : kind;
		if (nextKind == currentKind) {
			return;
		}

		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || client.getWindow() == null || client.mouse == null || client.mouse.isCursorLocked()) {
			return;
		}

		long windowHandle = client.getWindow().getHandle();
		if (X11ThemeCursorSupport.apply(windowHandle, nextKind)) {
			currentKind = nextKind;
			return;
		}

		if (nextKind == CursorKind.DEFAULT) {
			X11ThemeCursorSupport.reset(windowHandle);
		}
		GLFW.glfwSetCursor(windowHandle, cursorHandle(nextKind));
		currentKind = nextKind;
	}

	static void reset() {
		apply(CursorKind.DEFAULT);
	}

	static CursorKind forResizeDirection(ResizeDirection direction) {
		if (direction == null) {
			return CursorKind.DEFAULT;
		}
		return switch (direction) {
			case LEFT, RIGHT -> CursorKind.RESIZE_EW;
			case TOP, BOTTOM -> CursorKind.RESIZE_NS;
			case TOP_LEFT, BOTTOM_RIGHT -> CursorKind.RESIZE_NWSE;
			case TOP_RIGHT, BOTTOM_LEFT -> CursorKind.RESIZE_NESW;
		};
	}

	private static long cursorHandle(CursorKind kind) {
		if (kind == CursorKind.DEFAULT) {
			return 0L;
		}
		return STANDARD_CURSORS.computeIfAbsent(kind, ignored -> createStandardCursor(kind));
	}

	private static long createStandardCursor(CursorKind kind) {
		for (int shape : kind.glfwShapes()) {
			long handle = GLFW.glfwCreateStandardCursor(shape);
			if (handle != 0L) {
				return handle;
			}
		}
		return 0L;
	}

	enum CursorKind {
		DEFAULT(),
		TEXT(GLFW.GLFW_IBEAM_CURSOR),
		GRAB(GLFW.GLFW_HAND_CURSOR),
		RESIZE_EW(GLFW.GLFW_RESIZE_EW_CURSOR, GLFW.GLFW_HRESIZE_CURSOR),
		RESIZE_NS(GLFW.GLFW_RESIZE_NS_CURSOR, GLFW.GLFW_VRESIZE_CURSOR),
		RESIZE_NWSE(GLFW.GLFW_RESIZE_NWSE_CURSOR, GLFW.GLFW_RESIZE_ALL_CURSOR, GLFW.GLFW_HRESIZE_CURSOR, GLFW.GLFW_VRESIZE_CURSOR),
		RESIZE_NESW(GLFW.GLFW_RESIZE_NESW_CURSOR, GLFW.GLFW_RESIZE_ALL_CURSOR, GLFW.GLFW_HRESIZE_CURSOR, GLFW.GLFW_VRESIZE_CURSOR);

		private final int[] glfwShapes;

		CursorKind(int... glfwShapes) {
			this.glfwShapes = glfwShapes;
		}

		int[] glfwShapes() {
			return glfwShapes;
		}
	}

	private static final class X11ThemeCursorSupport {
		private static final Map<CursorKind, List<String>> CURSOR_NAMES = Map.of(
			CursorKind.RESIZE_NWSE, List.of("nwse-resize", "size_bdiag", "bd_double_arrow", "top_left_corner", "bottom_right_corner"),
			CursorKind.RESIZE_NESW, List.of("nesw-resize", "size_fdiag", "fd_double_arrow", "top_right_corner", "bottom_left_corner")
		);
		private static final Map<CursorKind, Long> THEME_CURSORS = new EnumMap<>(CursorKind.class);

		private X11ThemeCursorSupport() {
		}

		static boolean apply(long glfwWindowHandle, CursorKind kind) {
			if (!CURSOR_NAMES.containsKey(kind)) {
				return false;
			}
			try {
				Pointer display = displayPointer();
				if (display == null) {
					return false;
				}
				long x11Window = GLFWNativeX11.glfwGetX11Window(glfwWindowHandle);
				if (x11Window == 0L) {
					return false;
				}
				long cursor = THEME_CURSORS.computeIfAbsent(kind, ignored -> loadThemeCursor(display, kind));
				if (cursor == 0L) {
					return false;
				}
				X11Library.INSTANCE.XDefineCursor(display, x11Window, cursor);
				X11Library.INSTANCE.XFlush(display);
				return true;
			} catch (Throwable ignored) {
				return false;
			}
		}

		static void reset(long glfwWindowHandle) {
			try {
				Pointer display = displayPointer();
				if (display == null) {
					return;
				}
				long x11Window = GLFWNativeX11.glfwGetX11Window(glfwWindowHandle);
				if (x11Window == 0L) {
					return;
				}
				X11Library.INSTANCE.XUndefineCursor(display, x11Window);
				X11Library.INSTANCE.XFlush(display);
			} catch (Throwable ignored) {
			}
		}

		private static long loadThemeCursor(Pointer display, CursorKind kind) {
			for (String cursorName : CURSOR_NAMES.getOrDefault(kind, List.of())) {
				long handle = XcursorLibrary.INSTANCE.XcursorLibraryLoadCursor(display, cursorName);
				if (handle != 0L) {
					return handle;
				}
			}
			return 0L;
		}

		private static Pointer displayPointer() {
			long displayHandle = GLFWNativeX11.glfwGetX11Display();
			return displayHandle == 0L ? null : new Pointer(displayHandle);
		}
	}

	private interface XcursorLibrary extends Library {
		XcursorLibrary INSTANCE = Native.load("Xcursor", XcursorLibrary.class);

		long XcursorLibraryLoadCursor(Pointer display, String name);
	}

	private interface X11Library extends Library {
		X11Library INSTANCE = Native.load("X11", X11Library.class);

		int XDefineCursor(Pointer display, long window, long cursor);

		int XUndefineCursor(Pointer display, long window);

		int XFlush(Pointer display);
	}
}
