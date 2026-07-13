package com.github.squi2rel.mcng.fabric.client;

import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

final class ImagePreviewNodeBodyComponent implements NodeBodyComponent {
	private static final AtomicInteger NEXT_TEXTURE_ID = new AtomicInteger();
	private static final List<String> SUPPORTED_EXTENSIONS = List.of("png", "jpg", "jpeg", "bmp", "tga");
	private static final String FILE_PATH_KEY = "filePath";
	private static final int PADDING = 4;
	private static final int FIELD_HEIGHT = 18;
	private static final int BUTTON_HEIGHT = 18;
	private static final int BUTTON_GAP = 6;
	private static final int MIN_BODY_WIDTH = 180;
	private static final int MIN_BODY_HEIGHT = 82;
	private static final int PREFERRED_BODY_WIDTH = 224;
	private static final int PREFERRED_BODY_HEIGHT = 142;

	private String loadedPath = null;
	private LoadError loadError;
	private Identifier textureId;
	private NativeImageBackedTexture texture;
	private int imageWidth;
	private int imageHeight;

	@Override
	public NodeBodyMeasurement measure(NodeBodyMeasureContext context) {
		return new NodeBodyMeasurement(true, MIN_BODY_WIDTH, MIN_BODY_HEIGHT, PREFERRED_BODY_WIDTH, PREFERRED_BODY_HEIGHT);
	}

	@Override
	public void render(NodeBodyRenderContext context) {
		Layout layout = Layout.forBounds(context.bounds());
		String filePath = filePath(context.configCopy());
		if (!context.preview()) {
			syncTexture(filePath);
		}

		renderPreviewArea(context, layout.previewBounds());
		renderPathField(context, layout.pathBounds(), filePath);
		renderButtons(context, layout, filePath);
	}

	@Override
	public NodeInteractionResult mouseClicked(NodeBodyInputContext context, double localMouseX, double localMouseY, int button) {
		if (button != 0) {
			return NodeInteractionResult.ignored();
		}

		Layout layout = Layout.forSize(context.bounds().width(), context.bounds().height());
		String filePath = filePath(context.configCopy());
		if (layout.browseButton().contains(localMouseX, localMouseY)) {
			if (!context.supportsFileDialogs()) {
				context.showMessage(context.translate("mcng.ui.image_preview.file_dialogs_unsupported", "Host does not support file dialogs"));
				return NodeInteractionResult.handledResult();
			}
			context.chooseFile(new GraphFileDialogRequest(context.translate("mcng.ui.image_preview.choose_dialog_title", "Choose Image"), SUPPORTED_EXTENSIONS, filePath))
				.ifPresent(selectedPath -> updateFilePath(context, selectedPath));
			return NodeInteractionResult.handledResult();
		}
		if (layout.clearButton().contains(localMouseX, localMouseY)) {
			if (!filePath.isBlank()) {
				updateFilePath(context, "");
			}
			return NodeInteractionResult.handledResult();
		}
		return NodeInteractionResult.ignored();
	}

	@Override
	public void close() {
		releaseTexture();
	}

	private void renderPreviewArea(NodeBodyRenderContext context, NodeWidget.Bounds bounds) {
		DrawContext drawContext = context.drawContext();
		GraphEditorTheme theme = context.theme();
		int fill = EditorStyleRenderer.darken(theme.nodeBodyColor(), 0.06f);
		int border = loadError == null ? theme.panelBorderColor() : theme.errorColor();
		GraphEditorUiConfig uiConfig = context.uiConfig();
		EditorStyleRenderer.drawBox(drawContext, bounds.x(), bounds.y(), bounds.width(), bounds.height(), fill, border, uiConfig);

		if (context.preview()) {
			drawCenteredLabel(drawContext, context.textRenderer(), bounds, context.translate("mcng.ui.image_preview.preview", "Image preview"), theme.secondaryTextColor());
			return;
		}
		if (textureId != null && imageWidth > 0 && imageHeight > 0) {
			drawTexture(drawContext, bounds);
			String info = imageWidth + " x " + imageHeight;
			int infoWidth = context.textRenderer().getWidth(info);
			int infoX = bounds.x() + Math.max(PADDING, bounds.width() - infoWidth - PADDING);
			int infoY = bounds.y() + Math.max(PADDING, bounds.height() - context.textRenderer().fontHeight - PADDING);
			drawContext.fill(infoX - 3, infoY - 1, infoX + infoWidth + 3, infoY + context.textRenderer().fontHeight + 1, 0x99000000);
			drawContext.drawText(context.textRenderer(), info, infoX, infoY, theme.primaryTextColor(), false);
			return;
		}

		String label = loadError != null
			? context.translate(loadError.translationKey(), loadError.fallback())
			: context.translate("mcng.ui.image_preview.no_image", "No image selected");
		int color = loadError != null ? theme.errorColor() : theme.secondaryTextColor();
		drawCenteredLabel(drawContext, context.textRenderer(), bounds, label, color);
	}

	private void renderPathField(NodeBodyRenderContext context, NodeWidget.Bounds bounds, String filePath) {
		GraphTextInputRenderer.renderFrame(context.drawContext(), bounds, context.theme(), context.uiConfig(), false);
		String display = filePath.isBlank()
			? context.translate("mcng.ui.image_preview.no_file", "No file selected")
			: trimLeading(context.textRenderer(), filePath, Math.max(1, bounds.width() - (GraphTextInputRenderer.CONTENT_PADDING_X * 2)));
		int color = filePath.isBlank() ? context.theme().secondaryTextColor() : context.theme().primaryTextColor();
		int baselineY = bounds.y() + Math.max(2, (bounds.height() - context.textRenderer().fontHeight) / 2);
		context.drawContext().drawText(context.textRenderer(), display, bounds.x() + GraphTextInputRenderer.CONTENT_PADDING_X, baselineY, color, false);
	}

	private void renderButtons(NodeBodyRenderContext context, Layout layout, String filePath) {
		boolean fileDialogsSupported = context.session().map(GraphEditorSession::supportsFileDialogs).orElse(false);
		renderButton(context, layout.browseButtonBounds(), context.translate("mcng.ui.image_preview.browse", "Browse"), context.theme().accentColor(), fileDialogsSupported);
		renderButton(context, layout.clearButtonBounds(), context.translate("mcng.ui.image_preview.clear", "Clear"), context.theme().panelBorderColor(), !filePath.isBlank());
	}

	private void renderButton(NodeBodyRenderContext context, NodeWidget.Bounds bounds, String label, int accentColor, boolean enabled) {
		GraphEditorTheme theme = context.theme();
		int fill = enabled
			? EditorStyleRenderer.blend(theme.nodeBodyColor(), accentColor, 0.16f)
			: EditorStyleRenderer.darken(theme.nodeBodyColor(), 0.08f);
		int border = enabled ? accentColor : theme.panelBorderColor();
		int textColor = enabled ? theme.primaryTextColor() : theme.secondaryTextColor();
		EditorStyleRenderer.drawBox(context.drawContext(), bounds.x(), bounds.y(), bounds.width(), bounds.height(), fill, border, context.uiConfig());
		int textX = bounds.x() + Math.max(4, (bounds.width() - context.textRenderer().getWidth(label)) / 2);
		int textY = bounds.y() + Math.max(2, (bounds.height() - context.textRenderer().fontHeight) / 2);
		context.drawContext().drawText(context.textRenderer(), label, textX, textY, textColor, false);
	}

	private void drawTexture(DrawContext context, NodeWidget.Bounds bounds) {
		int availableWidth = Math.max(1, bounds.width() - (PADDING * 2));
		int availableHeight = Math.max(1, bounds.height() - (PADDING * 2));
		double scale = Math.min(availableWidth / (double) imageWidth, availableHeight / (double) imageHeight);
		int drawWidth = Math.max(1, (int) Math.round(imageWidth * scale));
		int drawHeight = Math.max(1, (int) Math.round(imageHeight * scale));
		int drawX = bounds.x() + ((bounds.width() - drawWidth) / 2);
		int drawY = bounds.y() + ((bounds.height() - drawHeight) / 2);
		context.drawTexture(RenderPipelines.GUI_TEXTURED, textureId, drawX, drawY, 0.0f, 0.0f, drawWidth, drawHeight, imageWidth, imageHeight, imageWidth, imageHeight);
	}

	private void drawCenteredLabel(DrawContext context, TextRenderer textRenderer, NodeWidget.Bounds bounds, String label, int color) {
		String text = trimCenter(textRenderer, label, Math.max(1, bounds.width() - (PADDING * 2)));
		int x = bounds.x() + Math.max(PADDING, (bounds.width() - textRenderer.getWidth(text)) / 2);
		int y = bounds.y() + Math.max(PADDING, (bounds.height() - textRenderer.fontHeight) / 2);
		context.drawText(textRenderer, text, x, y, color, false);
	}

	private void syncTexture(String filePath) {
		String normalizedPath = filePath == null ? "" : filePath;
		if (Objects.equals(loadedPath, normalizedPath)) {
			return;
		}

		releaseTexture();
		loadedPath = normalizedPath;
		loadError = null;
		if (normalizedPath.isBlank()) {
			return;
		}

		try {
			Path path = Path.of(normalizedPath);
			if (!Files.isRegularFile(path)) {
				loadError = LoadError.FILE_NOT_FOUND;
				return;
			}

			try (InputStream stream = Files.newInputStream(path)) {
				NativeImage image = NativeImage.read(stream);
				Identifier id = Identifier.of("mcng", "image_preview/" + NEXT_TEXTURE_ID.incrementAndGet());
				NativeImageBackedTexture loadedTexture = new NativeImageBackedTexture(id::toString, image);
				MinecraftClient client = MinecraftClient.getInstance();
				if (client == null) {
					loadedTexture.close();
					loadError = LoadError.CLIENT_UNAVAILABLE;
					return;
				}
				client.getTextureManager().registerTexture(id, loadedTexture);
				textureId = id;
				texture = loadedTexture;
				imageWidth = image.getWidth();
				imageHeight = image.getHeight();
			}
		} catch (InvalidPathException | IOException exception) {
			loadError = LoadError.FAILED_TO_LOAD;
		}
	}

	private void releaseTexture() {
		if (textureId != null) {
			MinecraftClient client = MinecraftClient.getInstance();
			if (client != null) {
				client.getTextureManager().destroyTexture(textureId);
			} else if (texture != null) {
				texture.close();
			}
		} else if (texture != null) {
			texture.close();
		}
		textureId = null;
		texture = null;
		imageWidth = 0;
		imageHeight = 0;
		loadError = null;
	}

	private void updateFilePath(NodeBodyInputContext context, String filePath) {
		JsonObject config = context.configCopy();
		config.addProperty(FILE_PATH_KEY, filePath);
		context.updateConfig(config);
	}

	private static String filePath(JsonObject config) {
		if (config == null || !config.has(FILE_PATH_KEY)) {
			return "";
		}
		return config.get(FILE_PATH_KEY).getAsString();
	}

	private static String trimLeading(TextRenderer textRenderer, String text, int maxWidth) {
		if (textRenderer.getWidth(text) <= maxWidth) {
			return text;
		}
		String ellipsis = "...";
		int ellipsisWidth = textRenderer.getWidth(ellipsis);
		if (ellipsisWidth >= maxWidth) {
			return ellipsis;
		}
		String value = text;
		while (!value.isEmpty() && textRenderer.getWidth(value) + ellipsisWidth > maxWidth) {
			value = value.substring(1);
		}
		return ellipsis + value;
	}

	private static String trimCenter(TextRenderer textRenderer, String text, int maxWidth) {
		if (textRenderer.getWidth(text) <= maxWidth) {
			return text;
		}
		String ellipsis = "...";
		int ellipsisWidth = textRenderer.getWidth(ellipsis);
		if (ellipsisWidth >= maxWidth) {
			return ellipsis;
		}
		String value = text;
		while (!value.isEmpty() && textRenderer.getWidth(value) + ellipsisWidth > maxWidth) {
			value = value.substring(0, value.length() - 1);
		}
		return value + ellipsis;
	}

	private record Layout(
		NodeWidget.Bounds preview,
		NodeWidget.Bounds path,
		NodeWidget.Bounds browseButton,
		NodeWidget.Bounds clearButton
	) {
		private static final int BROWSE_WIDTH = 58;
		private static final int CLEAR_WIDTH = 44;

		static Layout forBounds(NodeWidget.Bounds bounds) {
			Layout local = forSize(bounds.width(), bounds.height());
			return new Layout(
				translate(bounds, local.preview()),
				translate(bounds, local.path()),
				translate(bounds, local.browseButton()),
				translate(bounds, local.clearButton())
			);
		}

		static Layout forSize(int width, int height) {
			int innerWidth = Math.max(24, width - (PADDING * 2));
			int previewHeight = Math.max(30, height - (PADDING * 4) - FIELD_HEIGHT - BUTTON_HEIGHT);
			NodeWidget.Bounds preview = new NodeWidget.Bounds(PADDING, PADDING, innerWidth, previewHeight);
			NodeWidget.Bounds path = new NodeWidget.Bounds(PADDING, preview.y() + preview.height() + PADDING, innerWidth, FIELD_HEIGHT);
			int buttonY = path.y() + path.height() + PADDING;
			int buttonRight = PADDING + innerWidth;
			NodeWidget.Bounds clear = new NodeWidget.Bounds(buttonRight - CLEAR_WIDTH, buttonY, CLEAR_WIDTH, BUTTON_HEIGHT);
			NodeWidget.Bounds browse = new NodeWidget.Bounds(clear.x() - BUTTON_GAP - BROWSE_WIDTH, buttonY, BROWSE_WIDTH, BUTTON_HEIGHT);
			return new Layout(preview, path, browse, clear);
		}

		NodeWidget.Bounds previewBounds() {
			return preview;
		}

		NodeWidget.Bounds pathBounds() {
			return path;
		}

		NodeWidget.Bounds browseButtonBounds() {
			return browseButton;
		}

		NodeWidget.Bounds clearButtonBounds() {
			return clearButton;
		}

		private static NodeWidget.Bounds translate(NodeWidget.Bounds base, NodeWidget.Bounds child) {
			return new NodeWidget.Bounds(base.x() + child.x(), base.y() + child.y(), child.width(), child.height());
		}
	}

	private enum LoadError {
		FILE_NOT_FOUND("mcng.ui.image_preview.error.file_not_found", "File not found"),
		CLIENT_UNAVAILABLE("mcng.ui.image_preview.error.client_unavailable", "Client unavailable"),
		FAILED_TO_LOAD("mcng.ui.image_preview.error.failed_to_load", "Failed to load image");

		private final String translationKey;
		private final String fallback;

		LoadError(String translationKey, String fallback) {
			this.translationKey = translationKey;
			this.fallback = fallback;
		}

		private String translationKey() {
			return translationKey;
		}

		private String fallback() {
			return fallback;
		}
	}
}
