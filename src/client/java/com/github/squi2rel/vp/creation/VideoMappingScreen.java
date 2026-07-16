package com.github.squi2rel.vp.creation;

import com.github.squi2rel.vp.ClientPacketHandler;
import com.github.squi2rel.vp.ClientPermissionCache;
import com.github.squi2rel.vp.ScreenRenderer;
import com.github.squi2rel.vp.VideoPlayerClient;
import com.github.squi2rel.vp.i18n.VpTexts;
import com.github.squi2rel.vp.permission.VideoPermissionAction;
import com.github.squi2rel.vp.video.ClientVideoScreen;
import com.github.squi2rel.vp.video.MetaValue;
import com.github.squi2rel.vp.video.ScreenGeometry;
import com.github.squi2rel.vp.video.ScreenMetadata;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import org.joml.Vector2f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.function.Consumer;

public class VideoMappingScreen extends Screen implements ServerStateScreen {
    private static final int HANDLE_SIZE = 3;
    private static final int HANDLE_HIT_SIZE = 9;
    private static final int EDGE_HIT_SIZE = 6;
    private static final int TOP_HEIGHT = 38;
    private static final int BOTTOM_HEIGHT = 34;
    private static final int CONTROL_HEIGHT = 18;
    private static final VpUiTheme THEME = VpUiTheme.classic();
    private static final int OUTLINE_COLOR = THEME.accentColor();
    private static final int PREVIEW_OUTLINE_COLOR = THEME.accentColor();
    private static final int TRIANGLE_GUIDE_COLOR = VpUiRenderer.withAlpha(THEME.accentColor(), 0x99);
    private static final float GUIDE_LINE_WIDTH = 1.4f;
    private final Screen parent;
    private final ClientVideoScreen screen;
    private final ArrayList<Vector2f> uvs = new ArrayList<>();
    private int previewX;
    private int previewY;
    private int previewW;
    private int previewH;
    private int imageX;
    private int imageY;
    private int imageW;
    private int imageH;
    private int dragging = -1;
    private int draggingEdge = -1;
    private final HashSet<Integer> selectedVertices = new HashSet<>();
    private final ArrayList<Vector2f> dragStartUvs = new ArrayList<>();
    private DragMode dragMode = DragMode.NONE;
    private double lastMouseX;
    private double lastMouseY;
    private double dragStartMouseX;
    private double dragStartMouseY;
    private double selectionStartX;
    private double selectionStartY;
    private double selectionEndX;
    private double selectionEndY;
    private float rotationStartAngle;
    private Vector2f dragStartCenter = new Vector2f();
    private float previewYaw = -0.45f;
    private float previewPitch = 0.25f;
    private float edgeStartAX;
    private float edgeStartAY;
    private float edgeStartBX;
    private float edgeStartBY;
    private boolean dirty;
    private boolean keepAspect;
    private boolean savePending;
    private long editRevision;
    private VpButtonWidget saveButton;
    private VpButtonWidget resetButton;

    public VideoMappingScreen(Screen parent, ClientVideoScreen screen) {
        super(VpTexts.tr("screen.videoplayer.mapping_editor", "Custom Mapping Editor"));
        this.parent = parent;
        this.screen = screen;
        loadUvs();
    }

    @Override
    protected void init() {
        int bottom = height - 30;
        int startX = width / 2 - 166;
        saveButton = button(VpTexts.tr("button.videoplayer.save", "Save"), startX, bottom, 72, button -> save(button));
        resetButton = button(VpTexts.tr("button.videoplayer.reset", "Reset"), startX + 78, bottom, 72, button -> {
            resetUvs();
            save(button);
        });
        refreshSaveControls();
        VpButtonWidget keepAspectButton = new VpButtonWidget(startX + 156, bottom, 98, CONTROL_HEIGHT, keepAspectText(), button -> {
            this.keepAspect = !this.keepAspect;
            button.setMessage(keepAspectText());
        }, THEME);
        addDrawableChild(keepAspectButton);
        button(VpTexts.tr("button.videoplayer.close", "Close"), startX + 260, bottom, 72, this::close);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void close() {
        saveIfDirty();
        client.setScreen(parent);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        computeLayout();
        renderBackground(context, mouseX, mouseY, delta);
        drawChrome(context);
        super.render(context, mouseX, mouseY, delta);
        drawCenteredLabel(context, title, width / 2, 18, THEME.primaryTextColor());
        drawLabel(context, VpTexts.tr("label.videoplayer.preview", "Preview"), previewX, previewY - 14, THEME.secondaryTextColor());
        drawLabel(context, Text.literal(screen.name), imageX, imageY - 14, THEME.secondaryTextColor());

        drawFrame(context, previewX, previewY, previewW, previewH);
        drawPreview(context);
        context.drawStrokedRectangle(previewX - 1, previewY - 1, previewW + 2, previewH + 2, THEME.panelBorderColor());
        drawFrame(context, imageX, imageY, imageW, imageH);
        drawTexture(context);
        context.drawStrokedRectangle(imageX - 1, imageY - 1, imageW + 2, imageH + 2, THEME.panelBorderColor());
        drawPolygon(context);
        drawSelectionBox(context);
        drawHandles(context, mouseX, mouseY);
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, VpUiRenderer.withAlpha(THEME.canvasBackgroundColor(), 0xE6));
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubleClick) {
        double mouseX = click.x();
        double mouseY = click.y();
        int button = click.button();
        if (button == 0) {
            if (insidePreview(mouseX, mouseY)) {
                lastMouseX = mouseX;
                lastMouseY = mouseY;
                dragMode = DragMode.PREVIEW_ROTATE;
                return true;
            }
            int handle = handleAt(mouseX, mouseY);
            if (handle >= 0 && click.buttonInfo().hasCtrlOrCmd()) {
                toggleSelectedVertex(handle);
                return true;
            }
            if (handle >= 0) {
                if (selectedVertices.contains(handle) && selectedVertices.size() > 1) {
                    beginSelectionDrag(mouseX, mouseY);
                } else {
                    beginHandleDrag(handle);
                }
                return true;
            }
            if (selectedVertices.size() > 1 && insideSelectionBounds(mouseX, mouseY)) {
                beginSelectionDrag(mouseX, mouseY);
                return true;
            }
            draggingEdge = edgeAt(mouseX, mouseY);
            if (draggingEdge >= 0) {
                beginEdgeDrag(mouseX, mouseY);
                return true;
            }
            if (insideMappedPolygon(mouseX, mouseY)) {
                lastMouseX = mouseX;
                lastMouseY = mouseY;
                dragMode = DragMode.PAN;
                return true;
            }
            if (insideImage(mouseX, mouseY)) {
                beginBoxSelect(mouseX, mouseY);
                return true;
            }
        }
        if (button == 1 && insideImage(mouseX, mouseY)) {
            beginRotate(mouseX, mouseY);
            return true;
        }
        if (button == 1 && insidePreview(mouseX, mouseY)) {
            lastMouseX = mouseX;
            lastMouseY = mouseY;
            dragMode = DragMode.PREVIEW_ROTATE;
            return true;
        }
        return super.mouseClicked(click, doubleClick);
    }

    @Override
    public boolean mouseDragged(Click click, double deltaX, double deltaY) {
        double mouseX = click.x();
        double mouseY = click.y();
        int button = click.button();
        if (button == 0 && dragMode == DragMode.BOX_SELECT) {
            selectionEndX = mouseX;
            selectionEndY = mouseY;
            return true;
        }
        if (button == 0 && dragMode == DragMode.SELECTION) {
            moveSelected(mouseX, mouseY);
            return true;
        }
        if (button == 0 && dragMode == DragMode.HANDLE && dragging >= 0) {
            uvs.get(dragging).set(
                    clamp01((float) ((mouseX - imageX) / imageW)),
                    clamp01((float) ((mouseY - imageY) / imageH))
            );
            markDirty();
            return true;
        }
        if (button == 0 && dragMode == DragMode.EDGE && draggingEdge >= 0) {
            moveEdge(mouseX, mouseY, click.buttonInfo().hasShift());
            return true;
        }
        if (button == 0 && dragMode == DragMode.PAN) {
            translate((float) (mouseX - lastMouseX) / imageW, (float) (mouseY - lastMouseY) / imageH);
            lastMouseX = mouseX;
            lastMouseY = mouseY;
            return true;
        }
        if ((button == 0 || button == 1) && dragMode == DragMode.ROTATE) {
            float after = angleAt(mouseX, mouseY, dragStartCenter);
            float delta = normalizedAngle(after - rotationStartAngle);
            if (click.buttonInfo().hasShift()) {
                float step = (float) Math.toRadians(15);
                delta = Math.round(delta / step) * step;
            }
            rotateFromStart(delta);
            lastMouseX = mouseX;
            lastMouseY = mouseY;
            return true;
        }
        if ((button == 0 || button == 1) && dragMode == DragMode.PREVIEW_ROTATE) {
            previewYaw += (float) (mouseX - lastMouseX) * 0.012f;
            previewPitch = Math.clamp(previewPitch + (float) (mouseY - lastMouseY) * 0.012f, -1.35f, 1.35f);
            lastMouseX = mouseX;
            lastMouseY = mouseY;
            return true;
        }
        return super.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(Click click) {
        double mouseX = click.x();
        double mouseY = click.y();
        int button = click.button();
        if (button == 0 && dragMode == DragMode.BOX_SELECT) {
            selectionEndX = mouseX;
            selectionEndY = mouseY;
            selectVerticesInBox();
            dragging = -1;
            draggingEdge = -1;
            dragMode = DragMode.NONE;
            return true;
        }
        boolean finishingRotation = (button == 0 || button == 1) && dragMode == DragMode.ROTATE;
        if ((button == 0 && (dragMode == DragMode.HANDLE || dragMode == DragMode.EDGE || dragMode == DragMode.PAN || dragMode == DragMode.SELECTION))
                || finishingRotation) {
            if (finishingRotation) fitUvsInsideUnitSquare();
            dragging = -1;
            draggingEdge = -1;
            dragMode = DragMode.NONE;
            saveIfDirty();
            return true;
        }
        if ((button == 0 || button == 1) && dragMode == DragMode.PREVIEW_ROTATE) {
            dragMode = DragMode.NONE;
            return true;
        }
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (insideImage(mouseX, mouseY)) {
            scale(verticalAmount > 0 ? 1.08f : 0.9259259f);
            saveIfDirty();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    private Text keepAspectText() {
        return VpTexts.tr("label.videoplayer.keep_aspect", "Keep Aspect: %s",
                (keepAspect ? VpTexts.tr("label.videoplayer.on", "On") : VpTexts.tr("label.videoplayer.off", "Off")).getString());
    }

    private void beginRotate(double mouseX, double mouseY) {
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        dragStartMouseX = mouseX;
        dragStartMouseY = mouseY;
        captureDragStartUvs();
        dragStartCenter = center(dragStartUvs);
        rotationStartAngle = angleAt(mouseX, mouseY, dragStartCenter);
        dragMode = DragMode.ROTATE;
    }

    private void beginHandleDrag(int handle) {
        dragging = handle;
        selectedVertices.clear();
        selectedVertices.add(handle);
        dragMode = DragMode.HANDLE;
    }

    private void beginSelectionDrag(double mouseX, double mouseY) {
        dragging = -1;
        dragStartMouseX = mouseX;
        dragStartMouseY = mouseY;
        captureDragStartUvs();
        dragMode = DragMode.SELECTION;
    }

    private void beginBoxSelect(double mouseX, double mouseY) {
        selectionStartX = mouseX;
        selectionStartY = mouseY;
        selectionEndX = mouseX;
        selectionEndY = mouseY;
        dragMode = DragMode.BOX_SELECT;
    }

    private void beginEdgeDrag(double mouseX, double mouseY) {
        Vector2f a = uvs.get(draggingEdge);
        Vector2f b = uvs.get((draggingEdge + 1) % uvs.size());
        edgeStartAX = a.x;
        edgeStartAY = a.y;
        edgeStartBX = b.x;
        edgeStartBY = b.y;
        dragStartMouseX = mouseX;
        dragStartMouseY = mouseY;
        dragMode = DragMode.EDGE;
    }

    private void loadUvs() {
        uvs.clear();
        int vertexCount = screen.vertices.size();
        float[] stored = screen.metadata.getFloatArray(ScreenMetadata.KEY_MAPPING_UVS);
        if (stored != null && stored.length == vertexCount * 2) {
            for (int i = 0; i < vertexCount; i++) {
                uvs.add(new Vector2f(stored[i * 2], stored[i * 2 + 1]));
            }
            dirty = false;
            return;
        }
        resetUvValues();
        dirty = false;
    }

    private void resetUvs() {
        resetUvValues();
        dirty = true;
        editRevision++;
    }

    private void resetUvValues() {
        uvs.clear();
        if (!resetFromGeometry()) {
            int count = Math.max(1, screen.vertices.size());
            for (int i = 0; i < count; i++) {
                float angle = (float) (-Math.PI / 2.0 + Math.PI * 2.0 * i / count);
                uvs.add(new Vector2f(
                        clamp01(0.5f + (float) Math.cos(angle) * 0.42f),
                        clamp01(0.5f + (float) Math.sin(angle) * 0.42f)
                ));
            }
        }
    }

    private void computeLayout() {
        int margin = 24;
        int gap = 18;
        int availableW = Math.max(240, width - margin * 2);
        int availableH = Math.max(90, height - TOP_HEIGHT - BOTTOM_HEIGHT - 18);

        int leftW = Math.max(140, Math.round(availableW * 0.2f));
        int rightW = Math.max(120, availableW - leftW - gap);
        previewX = margin;
        previewY = TOP_HEIGHT;
        previewW = leftW;
        previewH = availableH;

        int maxW = rightW;
        int maxH = availableH;
        float textureAspect = screen.displayTextureWidth() / (float) Math.max(1, screen.displayTextureHeight());
        imageW = maxW;
        imageH = Math.round(imageW / textureAspect);
        if (imageH > maxH) {
            imageH = maxH;
            imageW = Math.round(imageH * textureAspect);
        }
        int rightX = previewX + previewW + gap;
        imageX = rightX + Math.max(0, (rightW - imageW) / 2);
        imageY = TOP_HEIGHT + Math.max(0, (maxH - imageH) / 2);
    }

    private void drawTexture(DrawContext context) {
        context.drawTexturedQuad(
                ScreenRenderer.textureIdentifier(screen.displayTextureId()),
                imageX,
                imageY,
                imageX + imageW,
                imageY + imageH,
                0,
                1,
                0,
                1
        );
    }

    private void drawChrome(DrawContext context) {
        int margin = 14;
        VpUiRenderer.drawBox(context, margin, 12, Math.max(1, width - margin * 2), Math.max(1, height - 24),
                THEME.panelBackgroundColor(), THEME.panelBorderColor());
    }

    private void drawFrame(DrawContext context, int x, int y, int frameWidth, int frameHeight) {
        VpUiRenderer.drawBox(context, x - 3, y - 3, frameWidth + 6, frameHeight + 6,
                VpUiRenderer.darken(THEME.nodeBodyColor(), 0.08f), THEME.panelBorderColor());
    }

    private void drawLabel(DrawContext context, Text text, int x, int y, int color) {
        if (THEME.textShadow()) {
            context.drawTextWithShadow(textRenderer, text, x, y, color);
            return;
        }
        context.drawText(textRenderer, text, x, y, color, false);
    }

    private void drawCenteredLabel(DrawContext context, Text text, int centerX, int y, int color) {
        drawLabel(context, text, centerX - textRenderer.getWidth(text) / 2, y, color);
    }

    private VpButtonWidget button(String label, int x, int y, int width, Runnable action) {
        VpButtonWidget button = new VpButtonWidget(x, y, Math.max(34, width), CONTROL_HEIGHT, Text.literal(label), b -> action.run(), THEME);
        addDrawableChild(button);
        return button;
    }

    private VpButtonWidget button(Text label, int x, int y, int width, Runnable action) {
        VpButtonWidget button = new VpButtonWidget(x, y, Math.max(34, width), CONTROL_HEIGHT, label, b -> action.run(), THEME);
        addDrawableChild(button);
        return button;
    }

    private VpButtonWidget button(String label, int x, int y, int width, Consumer<VpButtonWidget> action) {
        VpButtonWidget button = new VpButtonWidget(x, y, Math.max(34, width), CONTROL_HEIGHT, Text.literal(label), action, THEME);
        addDrawableChild(button);
        return button;
    }

    private VpButtonWidget button(Text label, int x, int y, int width, Consumer<VpButtonWidget> action) {
        VpButtonWidget button = new VpButtonWidget(x, y, Math.max(34, width), CONTROL_HEIGHT, label, action, THEME);
        addDrawableChild(button);
        return button;
    }

    private void drawPreview(DrawContext context) {
        if (uvs.size() < 3) return;
        ScreenGeometry geometry;
        try {
            geometry = screen.geometry();
        } catch (IllegalArgumentException ignored) {
            return;
        }
        if (uvs.size() != geometry.vertices().size()) return;

        context.fill(previewX, previewY, previewX + previewW, previewY + previewH, VpUiRenderer.darken(THEME.nodeBodyColor(), 0.36f));
        ArrayList<PreviewVertex3d> projected = projectPreviewVertices(geometry);
        if (projected == null) return;
        drawPreview3dTexture(context, geometry, projected);
        drawPreview3dOutline(context, geometry, projected);
    }

    private ArrayList<PreviewVertex3d> projectPreviewVertices(ScreenGeometry geometry) {
        List<Vector3f> vertices = geometry.vertices();
        if (vertices.isEmpty()) return null;

        Vector3f center = previewCenter(vertices);
        ArrayList<PreviewVertex3d> rotated = new ArrayList<>(vertices.size());
        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        for (Vector3f vertex : vertices) {
            PreviewVertex3d point = rotatePreviewVertex(vertex, center);
            rotated.add(point);
            minX = Math.min(minX, point.x);
            minY = Math.min(minY, point.y);
            maxX = Math.max(maxX, point.x);
            maxY = Math.max(maxY, point.y);
        }

        float shapeW = Math.max(maxX - minX, ScreenGeometry.EPSILON);
        float shapeH = Math.max(maxY - minY, ScreenGeometry.EPSILON);
        float scale = Math.min((previewW - 20) / shapeW, (previewH - 20) / shapeH);
        if (!Float.isFinite(scale) || scale <= 0) return null;

        float centerX = (minX + maxX) * 0.5f;
        float centerY = (minY + maxY) * 0.5f;
        ArrayList<PreviewVertex3d> projected = new ArrayList<>(rotated.size());
        for (PreviewVertex3d point : rotated) {
            projected.add(new PreviewVertex3d(
                    previewX + previewW * 0.5f + (point.x - centerX) * scale,
                    previewY + previewH * 0.5f - (point.y - centerY) * scale,
                    point.z
            ));
        }
        return projected;
    }

    private Vector3f previewCenter(List<Vector3f> vertices) {
        Vector3f min = new Vector3f(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY);
        Vector3f max = new Vector3f(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY);
        for (Vector3f vertex : vertices) {
            min.min(vertex);
            max.max(vertex);
        }
        return min.add(max).mul(0.5f);
    }

    private PreviewVertex3d rotatePreviewVertex(Vector3f vertex, Vector3f center) {
        float x = vertex.x - center.x;
        float y = vertex.y - center.y;
        float z = vertex.z - center.z;

        float cy = (float) Math.cos(previewYaw);
        float sy = (float) Math.sin(previewYaw);
        float yawX = x * cy + z * sy;
        float yawZ = -x * sy + z * cy;

        float cp = (float) Math.cos(previewPitch);
        float sp = (float) Math.sin(previewPitch);
        float pitchY = y * cp - yawZ * sp;
        float pitchZ = y * sp + yawZ * cp;
        return new PreviewVertex3d(yawX, pitchY, pitchZ);
    }

    private void drawPreview3dTexture(DrawContext context, ScreenGeometry geometry, ArrayList<PreviewVertex3d> projected) {
        int[] triangles = geometry.triangles();
        ArrayList<ScreenRenderer.GuiVertex> vertices = new ArrayList<>(triangles.length);
        for (int i = 0; i < triangles.length; i += 3) {
            addPreview3dVertex(vertices, projected, triangles[i], uvs.get(triangles[i]));
            addPreview3dVertex(vertices, projected, triangles[i + 1], uvs.get(triangles[i + 1]));
            addPreview3dVertex(vertices, projected, triangles[i + 2], uvs.get(triangles[i + 2]));
        }
        ScreenRenderer.drawGuiTexturedTriangles(context, screen.displayTextureId(), vertices);
    }

    private void addPreview3dVertex(ArrayList<ScreenRenderer.GuiVertex> vertices, ArrayList<PreviewVertex3d> projected, int index, Vector2f uv) {
        PreviewVertex3d point = projected.get(index);
        vertices.add(new ScreenRenderer.GuiVertex(point.x, point.y, uv.x, uv.y, 0xFFFFFFFF));
    }

    private void drawPreview3dOutline(DrawContext context, ScreenGeometry geometry, ArrayList<PreviewVertex3d> projected) {
        int count = geometry.vertices().size();
        for (int i = 0; i < count; i++) {
            PreviewVertex3d a = projected.get(i);
            PreviewVertex3d b = projected.get((i + 1) % count);
            drawLine(context, a.x, a.y, b.x, b.y, PREVIEW_OUTLINE_COLOR, GUIDE_LINE_WIDTH);
        }
    }

    private void drawPolygon(DrawContext context) {
        if (uvs.size() < 2) return;
        for (int i = 0; i < uvs.size(); i++) {
            Vector2f a = uvs.get(i);
            Vector2f b = uvs.get((i + 1) % uvs.size());
            drawLine(context, toX(a), toY(a), toX(b), toY(b), OUTLINE_COLOR, GUIDE_LINE_WIDTH);
        }
        drawTriangleGuides(context);
    }

    private void drawTriangleGuides(DrawContext context) {
        if (uvs.size() < 4) return;
        ScreenGeometry geometry;
        try {
            geometry = screen.geometry();
        } catch (IllegalArgumentException ignored) {
            return;
        }
        if (uvs.size() != geometry.vertices().size()) return;
        int[] triangles = geometry.triangles();
        for (int i = 0; i < triangles.length; i += 3) {
            drawTriangleGuideEdge(context, triangles[i], triangles[i + 1]);
            drawTriangleGuideEdge(context, triangles[i + 1], triangles[i + 2]);
            drawTriangleGuideEdge(context, triangles[i + 2], triangles[i]);
        }
    }

    private void drawTriangleGuideEdge(DrawContext context, int from, int to) {
        int size = uvs.size();
        if (from == to) return;
        int diff = Math.abs(from - to);
        if (diff == 1 || diff == size - 1) return;
        Vector2f a = uvs.get(from);
        Vector2f b = uvs.get(to);
        drawLine(context, toX(a), toY(a), toX(b), toY(b), TRIANGLE_GUIDE_COLOR, GUIDE_LINE_WIDTH);
    }

    private void drawHandles(DrawContext context, int mouseX, int mouseY) {
        pruneSelection();
        for (int i = 0; i < uvs.size(); i++) {
            Vector2f uv = uvs.get(i);
            int x = Math.round(toX(uv));
            int y = Math.round(toY(uv));
            boolean selected = selectedVertices.contains(i);
            boolean hot = i == dragging || dragMode == DragMode.SELECTION && selected
                    || Math.abs(mouseX - x) <= HANDLE_HIT_SIZE && Math.abs(mouseY - y) <= HANDLE_HIT_SIZE;
            int color = hot ? THEME.errorColor() : selected ? THEME.executionColor() : THEME.accentColor();
            context.fill(x - HANDLE_SIZE, y - HANDLE_SIZE, x + HANDLE_SIZE + 1, y + HANDLE_SIZE + 1, color);
            String label = String.valueOf(i + 1);
            int labelX = x + 7;
            int labelY = y - 5;
            context.fill(labelX - 2, labelY - 1, labelX + textRenderer.getWidth(label) + 2, labelY + 10,
                    VpUiRenderer.withAlpha(THEME.panelBackgroundColor(), 0xCC));
            drawLabel(context, Text.literal(label), labelX, labelY, THEME.primaryTextColor());
        }
    }

    private void drawSelectionBox(DrawContext context) {
        if (dragMode != DragMode.BOX_SELECT) return;
        int x1 = Math.round(Math.clamp((float) Math.min(selectionStartX, selectionEndX), imageX, imageX + imageW));
        int y1 = Math.round(Math.clamp((float) Math.min(selectionStartY, selectionEndY), imageY, imageY + imageH));
        int x2 = Math.round(Math.clamp((float) Math.max(selectionStartX, selectionEndX), imageX, imageX + imageW));
        int y2 = Math.round(Math.clamp((float) Math.max(selectionStartY, selectionEndY), imageY, imageY + imageH));
        if (x2 <= x1 || y2 <= y1) return;
        context.fill(x1, y1, x2, y2, VpUiRenderer.withAlpha(THEME.executionColor(), 0x28));
        context.drawStrokedRectangle(x1, y1, x2 - x1, y2 - y1, THEME.executionColor());
    }

    private void drawLine(DrawContext context, float x1, float y1, float x2, float y2, int color, float width) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float length = (float) Math.sqrt(dx * dx + dy * dy);
        if (length < 0.001f) return;
        int thickness = Math.max(1, Math.round(width));
        int half = Math.max(1, thickness) / 2;
        context.getMatrices().pushMatrix();
        context.getMatrices().translate(x1, y1);
        context.getMatrices().rotate((float) Math.atan2(dy, dx));
        context.fill(0, -half, Math.max(1, Math.round(length)), Math.max(1, thickness - half), color);
        context.getMatrices().popMatrix();
    }

    private int handleAt(double mouseX, double mouseY) {
        for (int i = uvs.size() - 1; i >= 0; i--) {
            Vector2f uv = uvs.get(i);
            if (Math.abs(mouseX - toX(uv)) <= HANDLE_HIT_SIZE && Math.abs(mouseY - toY(uv)) <= HANDLE_HIT_SIZE) {
                return i;
            }
        }
        return -1;
    }

    private int edgeAt(double mouseX, double mouseY) {
        if (uvs.size() < 2) return -1;
        float maxDistanceSq = EDGE_HIT_SIZE * EDGE_HIT_SIZE;
        for (int i = uvs.size() - 1; i >= 0; i--) {
            Vector2f a = uvs.get(i);
            Vector2f b = uvs.get((i + 1) % uvs.size());
            if (distanceToSegmentSq((float) mouseX, (float) mouseY, toX(a), toY(a), toX(b), toY(b)) <= maxDistanceSq) {
                return i;
            }
        }
        return -1;
    }

    private void toggleSelectedVertex(int index) {
        if (index < 0 || index >= uvs.size()) return;
        if (!selectedVertices.remove(index)) {
            selectedVertices.add(index);
        }
    }

    private void pruneSelection() {
        selectedVertices.removeIf(index -> index < 0 || index >= uvs.size());
    }

    private void selectVerticesInBox() {
        selectedVertices.clear();
        int x1 = Math.round(Math.clamp((float) Math.min(selectionStartX, selectionEndX), imageX, imageX + imageW));
        int y1 = Math.round(Math.clamp((float) Math.min(selectionStartY, selectionEndY), imageY, imageY + imageH));
        int x2 = Math.round(Math.clamp((float) Math.max(selectionStartX, selectionEndX), imageX, imageX + imageW));
        int y2 = Math.round(Math.clamp((float) Math.max(selectionStartY, selectionEndY), imageY, imageY + imageH));
        for (int i = 0; i < uvs.size(); i++) {
            Vector2f uv = uvs.get(i);
            float x = toX(uv);
            float y = toY(uv);
            if (x >= x1 && x <= x2 && y >= y1 && y <= y2) {
                selectedVertices.add(i);
            }
        }
    }

    private boolean insideSelectionBounds(double mouseX, double mouseY) {
        if (selectedVertices.isEmpty()) return false;
        Bounds bounds = selectedBounds(uvs);
        if (bounds == null) return false;
        float padding = HANDLE_HIT_SIZE;
        return mouseX >= toX(bounds.minX) - padding && mouseX <= toX(bounds.maxX) + padding
                && mouseY >= toY(bounds.minY) - padding && mouseY <= toY(bounds.maxY) + padding;
    }

    private Bounds selectedBounds(List<Vector2f> points) {
        if (points == null || points.isEmpty()) return null;
        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        boolean found = false;
        for (int index : selectedVertices) {
            if (index < 0 || index >= points.size()) continue;
            Vector2f uv = points.get(index);
            minX = Math.min(minX, uv.x);
            minY = Math.min(minY, uv.y);
            maxX = Math.max(maxX, uv.x);
            maxY = Math.max(maxY, uv.y);
            found = true;
        }
        return found ? new Bounds(minX, minY, maxX, maxY) : null;
    }

    private float distanceToSegmentSq(float px, float py, float x1, float y1, float x2, float y2) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float lengthSq = dx * dx + dy * dy;
        if (lengthSq < 0.001f) {
            float x = px - x1;
            float y = py - y1;
            return x * x + y * y;
        }
        float t = ((px - x1) * dx + (py - y1) * dy) / lengthSq;
        t = Math.clamp(t, 0, 1);
        float x = x1 + dx * t;
        float y = y1 + dy * t;
        float ox = px - x;
        float oy = py - y;
        return ox * ox + oy * oy;
    }

    private void markDirty() {
        dirty = true;
        editRevision++;
    }

    private void saveIfDirty() {
        if (dirty) save();
    }

    private void save() {
        save(null);
    }

    private void save(VpButtonWidget button) {
        if (!canEditMapping() || savePending) return;
        float[] submitted = toFloatArray();
        long submittedRevision = editRevision;
        savePending = true;
        dirty = false;
        refreshSaveControls();
        ClientPacketHandler.setMetadata(screen, ScreenMetadata.KEY_MAPPING_UVS, MetaValue.ofFloatArray(submitted), result -> {
            savePending = false;
            if (ClientPacketHandler.denied(result) && button != null) button.showPermissionDenied();
            boolean success = result != null && result.status() == com.github.squi2rel.vp.network.RequestResultStatus.OK;
            boolean newerEdit = editRevision != submittedRevision;
            if (newerEdit) {
                dirty = true;
                if (success && currentScreen() && canEditMapping()) {
                    save();
                }
            } else {
                dirty = !success;
            }
            refreshSaveControls();
        });
    }

    private void refreshSaveControls() {
        boolean active = currentScreen() && canEditMapping() && !savePending;
        if (saveButton != null) saveButton.active = active;
        if (resetButton != null) resetButton.active = active;
    }

    private boolean currentScreen() {
        return screen.area != null
                && VideoPlayerClient.screens.contains(screen)
                && screen.area.getScreen(screen.name) == screen;
    }

    private boolean canEditMapping() {
        return ClientPermissionCache.allowedOrUnknown(VideoPermissionAction.SET_METADATA, screen);
    }

    private float[] toFloatArray() {
        float[] result = new float[uvs.size() * 2];
        for (int i = 0; i < uvs.size(); i++) {
            result[i * 2] = uvs.get(i).x;
            result[i * 2 + 1] = uvs.get(i).y;
        }
        return result;
    }

    private boolean resetFromGeometry() {
        ScreenGeometry geometry;
        try {
            geometry = screen.geometry();
        } catch (IllegalArgumentException ignored) {
            return false;
        }

        ArrayList<Vector2f> points = new ArrayList<>();
        float minU = Float.POSITIVE_INFINITY;
        float minV = Float.POSITIVE_INFINITY;
        float maxU = Float.NEGATIVE_INFINITY;
        float maxV = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < geometry.vertices().size(); i++) {
            Vector2f point = geometry.editPoint(i);
            points.add(point);
            minU = Math.min(minU, point.x);
            minV = Math.min(minV, point.y);
            maxU = Math.max(maxU, point.x);
            maxV = Math.max(maxV, point.y);
        }

        float width = maxU - minU;
        float height = maxV - minV;
        if (width <= ScreenGeometry.EPSILON || height <= ScreenGeometry.EPSILON) return false;
        float screenAspect = width / height;
        float textureAspect = screen.displayTextureWidth() / (float) Math.max(1, screen.displayTextureHeight());
        float usedW = 1;
        float usedH = 1;
        if (screenAspect > textureAspect) {
            usedH = textureAspect / screenAspect;
        } else {
            usedW = screenAspect / textureAspect;
        }
        float left = (1 - usedW) * 0.5f;
        float top = (1 - usedH) * 0.5f;

        for (Vector2f point : points) {
            uvs.add(new Vector2f(
                    clamp01(left + ((point.x - minU) / width) * usedW),
                    clamp01(top + ((point.y - minV) / height) * usedH)
            ));
        }
        return !uvs.isEmpty();
    }

    private void translate(float dx, float dy) {
        if (uvs.isEmpty()) return;
        for (Vector2f uv : uvs) {
            uv.x = clamp01(uv.x + dx);
            uv.y = clamp01(uv.y + dy);
        }
        markDirty();
    }

    private void moveSelected(double mouseX, double mouseY) {
        pruneSelection();
        if (selectedVertices.isEmpty() || dragStartUvs.size() != uvs.size() || imageW <= 0 || imageH <= 0) return;
        float dx = (float) (mouseX - dragStartMouseX) / imageW;
        float dy = (float) (mouseY - dragStartMouseY) / imageH;
        Bounds bounds = selectedBounds(dragStartUvs);
        if (bounds == null) return;
        dx = clampMovement(dx, -bounds.minX, 1 - bounds.maxX);
        dy = clampMovement(dy, -bounds.minY, 1 - bounds.maxY);

        boolean changed = false;
        for (int index : selectedVertices) {
            if (index < 0 || index >= uvs.size()) continue;
            Vector2f start = dragStartUvs.get(index);
            Vector2f uv = uvs.get(index);
            float x = start.x + dx;
            float y = start.y + dy;
            if (Math.abs(uv.x - x) > 0.000001f || Math.abs(uv.y - y) > 0.000001f) {
                changed = true;
            }
            uv.set(x, y);
        }
        if (changed) markDirty();
    }

    private float clampMovement(float value, float min, float max) {
        if (min <= max) return Math.clamp(value, min, max);
        float middle = (min + max) * 0.5f;
        return value < middle ? max : min;
    }

    private void moveEdge(double mouseX, double mouseY, boolean snapAxis) {
        if (draggingEdge < 0 || draggingEdge >= uvs.size() || imageW <= 0 || imageH <= 0) return;
        Vector2f a = uvs.get(draggingEdge);
        Vector2f b = uvs.get((draggingEdge + 1) % uvs.size());
        float mouseDx = (float) (mouseX - dragStartMouseX);
        float mouseDy = (float) (mouseY - dragStartMouseY);
        float moveX = mouseDx / imageW;
        float moveY = mouseDy / imageH;
        if (snapAxis) {
            float edgeX = (edgeStartBX - edgeStartAX) * imageW;
            float edgeY = (edgeStartBY - edgeStartAY) * imageH;
            float edgeLength = (float) Math.sqrt(edgeX * edgeX + edgeY * edgeY);
            if (edgeLength > 0.001f) {
                float dirX = edgeX / edgeLength;
                float dirY = edgeY / edgeLength;
                float perpendicularX = -dirY;
                float perpendicularY = dirX;
                float along = mouseDx * dirX + mouseDy * dirY;
                float perpendicular = mouseDx * perpendicularX + mouseDy * perpendicularY;
                if (Math.abs(along) >= Math.abs(perpendicular)) {
                    moveX = dirX * along / imageW;
                    moveY = dirY * along / imageH;
                } else {
                    moveX = perpendicularX * perpendicular / imageW;
                    moveY = perpendicularY * perpendicular / imageH;
                }
            }
        }

        float minX = Math.min(edgeStartAX, edgeStartBX);
        float maxX = Math.max(edgeStartAX, edgeStartBX);
        float minY = Math.min(edgeStartAY, edgeStartBY);
        float maxY = Math.max(edgeStartAY, edgeStartBY);
        moveX = clampMovement(moveX, -minX, 1 - maxX);
        moveY = clampMovement(moveY, -minY, 1 - maxY);
        if (Math.abs(moveX) < 0.000001f && Math.abs(moveY) < 0.000001f) return;

        a.x = edgeStartAX + moveX;
        a.y = edgeStartAY + moveY;
        b.x = edgeStartBX + moveX;
        b.y = edgeStartBY + moveY;
        markDirty();
    }

    private void scale(float factor) {
        if (uvs.isEmpty()) return;
        Vector2f center = center();
        factor = constrainedScaleFactor(center, factor);
        if (Math.abs(factor - 1.0f) < 0.000001f) return;
        boolean changed = false;
        for (Vector2f uv : uvs) {
            float x = snapUnit(center.x + (uv.x - center.x) * factor);
            float y = snapUnit(center.y + (uv.y - center.y) * factor);
            if (Math.abs(uv.x - x) > 0.000001f || Math.abs(uv.y - y) > 0.000001f) {
                changed = true;
            }
            uv.set(x, y);
        }
        if (changed) markDirty();
    }

    private float constrainedScaleFactor(Vector2f center, float requested) {
        if (requested <= 1.0f) return requested;
        float maxFactor = Float.POSITIVE_INFINITY;
        for (Vector2f uv : uvs) {
            maxFactor = Math.min(maxFactor, maxScaleForAxis(center.x, uv.x));
            maxFactor = Math.min(maxFactor, maxScaleForAxis(center.y, uv.y));
        }
        if (!Float.isFinite(maxFactor)) return requested;
        if (maxFactor < 1.0f && maxFactor > 0.999999f) {
            maxFactor = 1.0f;
        }
        return Math.min(requested, Math.max(0.0f, maxFactor));
    }

    private float maxScaleForAxis(float center, float value) {
        float delta = value - center;
        if (Math.abs(delta) < 0.000001f) return Float.POSITIVE_INFINITY;
        if (delta > 0) return (1.0f - center) / delta;
        return center / -delta;
    }

    private float snapUnit(float value) {
        if (value < 0.0f && value > -0.000001f) return 0.0f;
        if (value > 1.0f && value < 1.000001f) return 1.0f;
        return value;
    }

    private void rotateFromStart(float radians) {
        if (uvs.isEmpty() || dragStartUvs.size() != uvs.size()) return;
        Vector2f center = dragStartCenter;
        float cos = (float) Math.cos(radians);
        float sin = (float) Math.sin(radians);
        boolean changed = false;
        for (int i = 0; i < uvs.size(); i++) {
            Vector2f start = dragStartUvs.get(i);
            Vector2f uv = uvs.get(i);
            float nextX;
            float nextY;
            if (keepAspect) {
                float sx = Math.max(1, imageW);
                float sy = Math.max(1, imageH);
                float x = (start.x - center.x) * sx;
                float y = (start.y - center.y) * sy;
                nextX = center.x + (x * cos - y * sin) / sx;
                nextY = center.y + (x * sin + y * cos) / sy;
            } else {
                float x = start.x - center.x;
                float y = start.y - center.y;
                nextX = center.x + x * cos - y * sin;
                nextY = center.y + x * sin + y * cos;
            }
            if (Math.abs(uv.x - nextX) > 0.000001f || Math.abs(uv.y - nextY) > 0.000001f) {
                changed = true;
            }
            uv.set(nextX, nextY);
        }
        if (changed) markDirty();
    }

    private void fitUvsInsideUnitSquare() {
        if (uvs.isEmpty()) return;
        Bounds bounds = bounds();
        if (bounds == null) return;

        boolean changed = false;
        float boundsW = bounds.maxX - bounds.minX;
        float boundsH = bounds.maxY - bounds.minY;
        if (boundsW > 1 || boundsH > 1) {
            Vector2f center = center();
            float scale = Math.min(1 / boundsW, 1 / boundsH);
            for (Vector2f uv : uvs) {
                uv.x = center.x + (uv.x - center.x) * scale;
                uv.y = center.y + (uv.y - center.y) * scale;
            }
            changed = true;
            bounds = bounds();
            if (bounds == null) return;
        }

        float dx = 0;
        float dy = 0;
        if (bounds.minX < 0) dx = -bounds.minX;
        else if (bounds.maxX > 1) dx = 1 - bounds.maxX;
        if (bounds.minY < 0) dy = -bounds.minY;
        else if (bounds.maxY > 1) dy = 1 - bounds.maxY;
        if (Math.abs(dx) > 0.000001f || Math.abs(dy) > 0.000001f) {
            for (Vector2f uv : uvs) {
                uv.x += dx;
                uv.y += dy;
            }
            changed = true;
        }

        if (changed) markDirty();
    }

    private Bounds bounds() {
        if (uvs.isEmpty()) return null;
        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        for (Vector2f uv : uvs) {
            minX = Math.min(minX, uv.x);
            minY = Math.min(minY, uv.y);
            maxX = Math.max(maxX, uv.x);
            maxY = Math.max(maxY, uv.y);
        }
        return new Bounds(minX, minY, maxX, maxY);
    }

    private Vector2f center() {
        return center(uvs);
    }

    private Vector2f center(List<Vector2f> points) {
        float x = 0;
        float y = 0;
        for (Vector2f uv : points) {
            x += uv.x;
            y += uv.y;
        }
        float count = Math.max(1, points.size());
        return new Vector2f(x / count, y / count);
    }

    private void captureDragStartUvs() {
        dragStartUvs.clear();
        for (Vector2f uv : uvs) {
            dragStartUvs.add(new Vector2f(uv));
        }
    }

    private float angleAt(double mouseX, double mouseY, Vector2f center) {
        return (float) Math.atan2((mouseY - imageY) / imageH - center.y, (mouseX - imageX) / imageW - center.x);
    }

    private float normalizedAngle(float radians) {
        return (float) Math.atan2(Math.sin(radians), Math.cos(radians));
    }

    private boolean insideImage(double mouseX, double mouseY) {
        return mouseX >= imageX && mouseX <= imageX + imageW && mouseY >= imageY && mouseY <= imageY + imageH;
    }

    private boolean insidePreview(double mouseX, double mouseY) {
        return mouseX >= previewX && mouseX <= previewX + previewW && mouseY >= previewY && mouseY <= previewY + previewH;
    }

    private boolean insideMappedPolygon(double mouseX, double mouseY) {
        if (uvs.size() < 3 || !insideImage(mouseX, mouseY)) return false;
        Vector2f point = new Vector2f(
                (float) ((mouseX - imageX) / imageW),
                (float) ((mouseY - imageY) / imageH)
        );
        return ScreenGeometry.contains2d(point, uvs);
    }

    private float toX(Vector2f uv) {
        return toX(uv.x);
    }

    private float toY(Vector2f uv) {
        return toY(uv.y);
    }

    private float toX(float u) {
        return imageX + u * imageW;
    }

    private float toY(float v) {
        return imageY + v * imageH;
    }

    private float clamp01(float value) {
        return Math.clamp(value, 0, 1);
    }

    private enum DragMode {
        NONE,
        HANDLE,
        SELECTION,
        BOX_SELECT,
        EDGE,
        PAN,
        ROTATE,
        PREVIEW_ROTATE
    }

    private record Bounds(float minX, float minY, float maxX, float maxY) {
    }

    private record PreviewVertex3d(float x, float y, float z) {
    }
}
