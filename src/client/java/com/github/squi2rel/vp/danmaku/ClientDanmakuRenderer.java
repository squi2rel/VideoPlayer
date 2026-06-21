package com.github.squi2rel.vp.danmaku;

import com.github.squi2rel.vp.ScreenRenderer;
import com.github.squi2rel.vp.VideoPlayerClient;
import com.github.squi2rel.vp.mixin.client.DrawContextAccessor;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.github.squi2rel.vp.video.ClientVideoScreen;
import com.github.squi2rel.vp.video.ScreenGeometry;
import com.github.squi2rel.vp.video.ScreenMetadata;
import com.github.squi2rel.vp.video.ScreenSurface;
import net.minecraft.client.font.TextDrawable;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.ScreenRect;
import net.minecraft.client.gui.render.state.SimpleGuiElementRenderState;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.texture.TextureSetup;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import org.joml.Matrix3x2f;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ClientDanmakuRenderer {
    private static final float BASE_ROLLING_SURFACE_GAP = 0.003f;
    private static final float ROLLING_LAYER_DEPTH = 0.002f;
    private static final float BASE_FIXED_SURFACE_GAP = 0.006f;
    private static final float FIXED_LAYER_DEPTH = 0.001f;
    private static final float BASE_SUBTITLE_SURFACE_GAP = 0.010f;
    private static final float SUBTITLE_LAYER_DEPTH = 0.001f;
    private static final int LIGHT = 0xF000F0;
    private static final int SUBTITLE_VERTEX_COLOR = 0xFFFFFFFF;
    private static final int SUBTITLE_BACKGROUND_COLOR = 0x99000000;
    private static final float SUBTITLE_BACKGROUND_PADDING_X = 4.0f;
    private static final float SUBTITLE_BACKGROUND_PADDING_Y = 2.0f;
    private static final float SUBTITLE_BACKGROUND_SURFACE_GAP = 0.00035f;
    private static final Identifier SUBTITLE_BACKGROUND_TEXTURE = Identifier.of("minecraft", "textures/block/white_concrete.png");

    private ClientDanmakuRenderer() {
    }

    public static void beginFrame(Collection<ClientVideoScreen> screens) {
        if (screens == null || screens.isEmpty()) return;
        for (ClientVideoScreen target : screens) {
            if (target == null || target.surface == ScreenSurface.SPHERE_360) continue;
            ClientVideoScreen playback = target.getScreen();
            if (playback == null) continue;
            if (ClientDanmakuController.isEnabledOn(target)) {
                DanmakuTextLayoutCache.prepare(playback.danmaku().renderables());
            }
            DanmakuTextLayoutCache.prepare(playback.subtitles().renderables());
        }
    }

    public static void clearCache() {
        DanmakuTextLayoutCache.clear();
    }

    public static void draw(MatrixStack matrices, VertexConsumerProvider consumers, ClientVideoScreen target) {
        if (!ClientDanmakuController.isEnabledOn(target) || target.surface == ScreenSurface.SPHERE_360) return;
        ClientVideoScreen playback = target.getScreen();
        if (playback == null) return;
        List<ClientDanmakuController.RenderableDanmaku> items = playback.danmaku().renderables();
        if (items.isEmpty()) return;

        RenderContext context = renderContext(target, playback, playback.danmaku().canvasWidth(), playback.danmaku().canvasHeight());
        if (context == null) return;
        DanmakuTextLayoutCache.prepare(items);
        drawDanmakuItems(consumers, context, target, items);
    }

    public static void drawSubtitles(MatrixStack matrices, VertexConsumerProvider consumers, ClientVideoScreen target) {
        if (target == null || target.surface == ScreenSurface.SPHERE_360) return;
        ClientVideoScreen playback = target.getScreen();
        if (playback == null) return;
        List<ClientDanmakuController.RenderableDanmaku> items = playback.subtitles().renderables();
        if (items.isEmpty()) return;

        RenderContext context = renderContext(target, playback, playback.subtitles().canvasWidth(), playback.subtitles().canvasHeight());
        if (context == null) return;
        DanmakuTextLayoutCache.prepare(items);
        drawSubtitleItems(consumers, context, target, items);
    }

    public static void drawPreview(DrawContext context, ClientVideoScreen target, int x, int y, int width, int height) {
        if (target == null || width <= 0 || height <= 0) return;
        if (!ClientDanmakuController.isEnabledOn(target) || target.surface == ScreenSurface.SPHERE_360) return;
        ClientVideoScreen playback = target.getScreen();
        if (playback == null) return;
        float canvasWidth = previewCanvasWidth(width, height);
        float canvasHeight = ClientDanmakuController.VIRTUAL_HEIGHT;
        List<ClientDanmakuController.RenderableDanmaku> items = playback.danmaku().renderables(canvasWidth, canvasHeight);
        if (items.isEmpty()) return;
        float scaleX = width / canvasWidth;
        float scaleY = height / canvasHeight;
        int alpha = alpha(opacityVertexColor());

        DanmakuTextLayoutCache.prepare(items);
        context.enableScissor(x, y, x + width, y + height);
        try {
            GuiTextBatch batch = new GuiTextBatch(context.scissorStack.peekLast());
            for (ClientDanmakuController.RenderableDanmaku item : items) {
                collectPreviewItem(context, batch, item, x, y, width, height, scaleX, scaleY, alpha);
            }
            batch.submit(context);
        } finally {
            context.disableScissor();
        }
    }

    public static void drawSubtitlePreview(DrawContext context, ClientVideoScreen target, int x, int y, int width, int height) {
        if (target == null || width <= 0 || height <= 0 || target.surface == ScreenSurface.SPHERE_360) return;
        ClientVideoScreen playback = target.getScreen();
        if (playback == null) return;
        float canvasWidth = previewCanvasWidth(width, height);
        float canvasHeight = ClientDanmakuController.VIRTUAL_HEIGHT;
        List<ClientDanmakuController.RenderableDanmaku> items = playback.subtitles().renderables(canvasWidth, canvasHeight);
        if (items.isEmpty()) return;
        float scaleX = width / canvasWidth;
        float scaleY = height / canvasHeight;

        DanmakuTextLayoutCache.prepare(items);
        context.enableScissor(x, y, x + width, y + height);
        try {
            GuiTextBatch batch = new GuiTextBatch(context.scissorStack.peekLast());
            for (ClientDanmakuController.RenderableDanmaku item : items) {
                drawPreviewSubtitleBackground(context, item, x, y, width, height, scaleX, scaleY);
                collectPreviewItem(context, batch, item, x, y, width, height, scaleX, scaleY, alpha(SUBTITLE_VERTEX_COLOR));
            }
            batch.submit(context);
        } finally {
            context.disableScissor();
        }
    }

    private static float previewCanvasWidth(int width, int height) {
        float aspect = Math.max(1, width) / (float) Math.max(1, height);
        return Math.max(1.0f, ClientDanmakuController.VIRTUAL_HEIGHT * aspect);
    }

    private static RenderContext renderContext(ClientVideoScreen target, ClientVideoScreen playback, float canvasWidth, float canvasHeight) {
        ScreenGeometry targetGeometry;
        ScreenGeometry sourceGeometry;
        try {
            targetGeometry = target.geometry();
            sourceGeometry = playback.geometry();
        } catch (IllegalArgumentException ignored) {
            return null;
        }

        int videoWidth = playback.player == null ? Math.max(1, Math.round(canvasWidth)) : Math.max(1, playback.player.getWidth());
        int videoHeight = playback.player == null ? Math.max(1, Math.round(canvasHeight)) : Math.max(1, playback.player.getHeight());
        boolean rootTarget = playback == target;
        float[] sourceFullBounds = sourceGeometry.contentBounds(0, 0, 1, 1, true, 1, 1,
                Math.max(1, Math.round(canvasWidth)), Math.max(1, Math.round(canvasHeight)));
        SourceMapping source = new SourceMapping(
                sourceFullBounds,
                sourceFullBounds,
                playback.u1,
                playback.v1,
                playback.u2,
                playback.v2,
                playback.player != null && playback.player.flippedX(),
                playback.player != null && playback.player.flippedY(),
                canvasWidth,
                canvasHeight
        );
        float[] targetBounds = targetGeometry.contentBounds(
                target.u1,
                target.v1,
                target.u2,
                target.v2,
                target.fill,
                target.scaleX,
                target.scaleY,
                videoWidth,
                videoHeight
        );
        TargetProjection projection = rootTarget
                ? new TargetProjection(surfaceTriangles(targetGeometry, SurfaceCoordinates.EDIT, null, false, false, target), false)
                : targetProjection(targetGeometry, target);
        DirectPlane directPlane = !projection.mappedUv() ? directPlane(targetGeometry) : null;
        return new RenderContext(targetGeometry, projection, source, targetBounds, rootTarget, directPlane);
    }

    private static void collectPreviewItem(DrawContext context, GuiTextBatch batch,
                                           ClientDanmakuController.RenderableDanmaku item,
                                           int x, int y, int width, int height,
                                           float scaleX, float scaleY, int alpha) {
        float drawX = x + item.x() * scaleX;
        float drawY = y + item.y() * scaleY;
        float drawW = item.width() * scaleX;
        float drawH = item.height() * scaleY;
        float padX = Math.max(1.0f, item.scale() * scaleX);
        float padY = Math.max(1.0f, item.scale() * scaleY);
        if (drawX >= x + width + padX || drawY >= y + height + padY || drawX + drawW + padX <= x || drawY + drawH + padY <= y) {
            return;
        }

        Matrix3x2f pose = new Matrix3x2f(context.getMatrices())
                .translate(drawX, drawY)
                .scale(item.scale() * scaleX, item.scale() * scaleY);
        Matrix4f matrix = new Matrix4f().mul(pose);
        DanmakuTextLayoutCache.CachedLayout layout = DanmakuTextLayoutCache.get(item.text());
        int bodyColor = colorWithAlpha(item.color(), alpha);
        layout.body().draw(new GuiGlyphCollector(batch, matrix, bodyColor));
    }

    private static void drawPreviewSubtitleBackground(DrawContext context, ClientDanmakuController.RenderableDanmaku item,
                                                      int x, int y, int width, int height, float scaleX, float scaleY) {
        float padX = SUBTITLE_BACKGROUND_PADDING_X * item.scale() * scaleX;
        float padY = SUBTITLE_BACKGROUND_PADDING_Y * item.scale() * scaleY;
        int x1 = Math.max(x, Math.round(x + item.x() * scaleX - padX));
        int y1 = Math.max(y, Math.round(y + item.y() * scaleY - padY));
        int x2 = Math.min(x + width, Math.round(x + (item.x() + item.width()) * scaleX + padX));
        int y2 = Math.min(y + height, Math.round(y + (item.y() + item.height()) * scaleY + padY));
        if (x2 > x1 && y2 > y1) {
            context.fill(x1, y1, x2, y2, SUBTITLE_BACKGROUND_COLOR);
        }
    }

    private static void drawDanmakuItems(VertexConsumerProvider consumers, RenderContext context, ClientVideoScreen target,
                                         List<ClientDanmakuController.RenderableDanmaku> items) {
        WorldTextBatch batch = new WorldTextBatch();
        int rollingCount = countItems(items, false);
        int rollingIndex = 0;
        for (ClientDanmakuController.RenderableDanmaku item : items) {
            if (item.fixed()) continue;
            drawDanmakuItem(batch, context, target, item,
                    layerDistance(BASE_ROLLING_SURFACE_GAP, ROLLING_LAYER_DEPTH, rollingIndex++, rollingCount));
        }

        int fixedCount = countItems(items, true);
        int fixedIndex = 0;
        for (ClientDanmakuController.RenderableDanmaku item : items) {
            if (!item.fixed()) continue;
            drawDanmakuItem(batch, context, target, item,
                    layerDistance(BASE_FIXED_SURFACE_GAP, FIXED_LAYER_DEPTH, fixedIndex++, fixedCount));
        }
        batch.submit(consumers);
    }

    private static void drawSubtitleItems(VertexConsumerProvider consumers, RenderContext context, ClientVideoScreen target,
                                          List<ClientDanmakuController.RenderableDanmaku> items) {
        drawSubtitleBackgrounds(consumers, context, target, items);
        WorldTextBatch batch = new WorldTextBatch();
        int count = items.size();
        for (int i = 0; i < count; i++) {
            drawDanmakuItem(batch, context, target, items.get(i),
                    layerDistance(BASE_SUBTITLE_SURFACE_GAP, SUBTITLE_LAYER_DEPTH, i, count), SUBTITLE_VERTEX_COLOR);
        }
        batch.submit(consumers);
    }

    private static void drawSubtitleBackgrounds(VertexConsumerProvider consumers, RenderContext context, ClientVideoScreen target,
                                                List<ClientDanmakuController.RenderableDanmaku> items) {
        VertexConsumer consumer = consumers.getBuffer(ScreenRenderer.getTranslucentLayer(SUBTITLE_BACKGROUND_TEXTURE));
        int count = items.size();
        for (int i = 0; i < count; i++) {
            float textDistance = layerDistance(BASE_SUBTITLE_SURFACE_GAP, SUBTITLE_LAYER_DEPTH, i, count);
            float backgroundDistance = Math.max(0.0f, textDistance - SUBTITLE_BACKGROUND_SURFACE_GAP);
            Vector3f normalOffset = cameraFacingOffset(context.geometry(), backgroundDistance);
            drawSubtitleBackground(consumer, context, target, items.get(i), normalOffset);
        }
    }

    private static void drawSubtitleBackground(VertexConsumer consumer, RenderContext context, ClientVideoScreen target,
                                               ClientDanmakuController.RenderableDanmaku item, Vector3f normalOffset) {
        float padX = SUBTITLE_BACKGROUND_PADDING_X * item.scale();
        float padY = SUBTITLE_BACKGROUND_PADDING_Y * item.scale();
        float x1 = item.x() - padX;
        float y1 = item.y() - padY;
        float x2 = item.x() + item.width() + padX;
        float y2 = item.y() + item.height() + padY;
        ClipVertex[] mapped = {
                mapGlyphVertex(context, target, new GlyphVertex(x1, y1, 0.0f, 0.0f, LIGHT)),
                mapGlyphVertex(context, target, new GlyphVertex(x2, y1, 1.0f, 0.0f, LIGHT)),
                mapGlyphVertex(context, target, new GlyphVertex(x2, y2, 1.0f, 1.0f, LIGHT)),
                mapGlyphVertex(context, target, new GlyphVertex(x1, y2, 0.0f, 1.0f, LIGHT))
        };
        drawMappedQuad(consumer, context, normalOffset, mapped, SUBTITLE_BACKGROUND_COLOR);
    }

    private static int countItems(List<ClientDanmakuController.RenderableDanmaku> items, boolean fixed) {
        int count = 0;
        for (ClientDanmakuController.RenderableDanmaku item : items) {
            if (item.fixed() == fixed) count++;
        }
        return count;
    }

    private static float layerDistance(float base, float depth, int index, int count) {
        if (count <= 0) return base;
        return base + depth * (index + 1.0f) / (count + 1.0f);
    }

    private static void drawDanmakuItem(WorldTextBatch batch, RenderContext context, ClientVideoScreen target,
                                        ClientDanmakuController.RenderableDanmaku item, float normalDistance) {
        drawDanmakuItem(batch, context, target, item, normalDistance, opacityVertexColor());
    }

    private static void drawDanmakuItem(WorldTextBatch batch, RenderContext context, ClientVideoScreen target,
                                        ClientDanmakuController.RenderableDanmaku item, float normalDistance, int vertexColor) {
        DanmakuTextLayoutCache.CachedLayout layout = DanmakuTextLayoutCache.get(item.text());
        Vector3f normalOffset = cameraFacingOffset(context.geometry(), normalDistance);
        Matrix4f matrix = new Matrix4f().translation(item.x(), item.y(), 0.0f).scale(item.scale(), item.scale(), 1.0f);
        int alpha = alpha(vertexColor);
        int bodyColor = colorWithAlpha(item.color(), alpha);
        layout.body().draw(new MappedGlyphDrawer(batch, context, target, normalOffset, matrix,
                TextRenderer.TextLayerType.POLYGON_OFFSET, bodyColor));
    }

    private static int opacityVertexColor() {
        int opacity = VideoPlayerClient.config == null ? 80 : Math.clamp(VideoPlayerClient.config.danmakuOpacity, 20, 100);
        int alpha = Math.clamp(Math.round(opacity * 255.0f / 100.0f), 0, 255);
        return (alpha << 24) | (alpha << 16) | (alpha << 8) | alpha;
    }

    private static int alpha(int color) {
        return Math.clamp(color >>> 24, 0, 255);
    }

    private static int colorWithAlpha(int color, int alpha) {
        return (Math.clamp(alpha, 0, 255) << 24) | (color & 0x00FFFFFF);
    }

    private static Vector3f cameraFacingOffset(ScreenGeometry geometry, float distance) {
        Vector3f normal = geometry.normal();
        Vector3f toCamera = new Vector3f(ScreenRenderer.cameraX, ScreenRenderer.cameraY, ScreenRenderer.cameraZ)
                .sub(geometry.firstVertex());
        if (normal.dot(toCamera) < 0.0f) {
            normal.negate();
        }
        return normal.mul(Math.max(0.0f, distance));
    }

    private static TargetProjection targetProjection(ScreenGeometry geometry, ClientVideoScreen target) {
        List<Vector2f> mappedUvs = mappedUvs(target, geometry.vertices().size());
        if (mappedUvs != null) {
            return new TargetProjection(surfaceTriangles(geometry, SurfaceCoordinates.MAPPED_UV, mappedUvs,
                    target.player != null && target.player.flippedX(),
                    target.player != null && target.player.flippedY(),
                    target), true);
        }
        return new TargetProjection(surfaceTriangles(geometry, SurfaceCoordinates.EDIT, null, false, false, target), false);
    }

    private static List<SurfaceTriangle> surfaceTriangles(ScreenGeometry geometry, SurfaceCoordinates coordinates,
                                                          List<Vector2f> mappedUvs, boolean flippedX, boolean flippedY,
                                                          ClientVideoScreen target) {
        List<Vector3f> vertices = geometry.vertices();
        int[] indices = geometry.triangles();
        ArrayList<SurfaceTriangle> result = new ArrayList<>(indices.length / 3);
        for (int i = 0; i < indices.length; i += 3) {
            int i1 = indices[i];
            int i2 = indices[i + 1];
            int i3 = indices[i + 2];
            Vector2f p1 = coordinate(geometry, coordinates, mappedUvs, i1, flippedX, flippedY, target);
            Vector2f p2 = coordinate(geometry, coordinates, mappedUvs, i2, flippedX, flippedY, target);
            Vector2f p3 = coordinate(geometry, coordinates, mappedUvs, i3, flippedX, flippedY, target);
            SurfaceTriangle triangle = new SurfaceTriangle(p1, p2, p3, vertices.get(i1), vertices.get(i2), vertices.get(i3));
            if (triangle.valid()) result.add(triangle);
        }
        return result;
    }

    private static Vector2f coordinate(ScreenGeometry geometry, SurfaceCoordinates coordinates, List<Vector2f> mappedUvs,
                                       int index, boolean flippedX, boolean flippedY, ClientVideoScreen target) {
        if (coordinates == SurfaceCoordinates.EDIT) return geometry.editPoint(index);
        Vector2f mapped = new Vector2f(mappedUvs.get(index));
        if (flippedX) mapped.x = target.u1 + target.u2 - mapped.x;
        if (flippedY) mapped.y = target.v1 + target.v2 - mapped.y;
        return mapped;
    }

    private static List<Vector2f> mappedUvs(ClientVideoScreen target, int vertexCount) {
        if (!target.fill || target.metadata == null) return null;
        float[] values = target.metadata.getFloatArray(ScreenMetadata.KEY_MAPPING_UVS);
        if (values == null || values.length != vertexCount * 2) return null;
        ArrayList<Vector2f> result = new ArrayList<>(vertexCount);
        for (int i = 0; i < vertexCount; i++) {
            result.add(new Vector2f(values[i * 2], values[i * 2 + 1]));
        }
        return result;
    }

    private static DirectPlane directPlane(ScreenGeometry geometry) {
        List<Vector3f> vertices = geometry.vertices();
        if (vertices.size() != 4) return null;

        float minX = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < vertices.size(); i++) {
            Vector2f point = geometry.editPoint(i);
            minX = Math.min(minX, point.x);
            maxX = Math.max(maxX, point.x);
            minY = Math.min(minY, point.y);
            maxY = Math.max(maxY, point.y);
        }
        float width = maxX - minX;
        float height = maxY - minY;
        if (width <= ScreenGeometry.EPSILON || height <= ScreenGeometry.EPSILON) return null;

        int minMin = editCorner(geometry, minX, minY);
        int maxMin = editCorner(geometry, maxX, minY);
        int maxMax = editCorner(geometry, maxX, maxY);
        int minMax = editCorner(geometry, minX, maxY);
        if (minMin < 0 || maxMin < 0 || maxMax < 0 || minMax < 0) return null;

        Vector3f origin = new Vector3f(vertices.get(minMin));
        Vector3f xAxis = new Vector3f(vertices.get(maxMin)).sub(origin).div(width);
        Vector3f yAxis = new Vector3f(vertices.get(minMax)).sub(origin).div(height);
        if (new Vector3f(xAxis).cross(yAxis).lengthSquared() <= 0.000001f) return null;

        Vector3f expectedMax = new Vector3f(origin)
                .add(new Vector3f(xAxis).mul(width))
                .add(new Vector3f(yAxis).mul(height));
        if (expectedMax.distanceSquared(vertices.get(maxMax)) > 0.0001f) return null;

        for (int i = 0; i < vertices.size(); i++) {
            Vector2f projected = geometry.projectedPoint(i);
            if (geometry.unproject(projected.x, projected.y).distanceSquared(vertices.get(i)) > 0.0001f) {
                return null;
            }
        }
        return new DirectPlane(origin, xAxis, yAxis, minX, minY);
    }

    private static int editCorner(ScreenGeometry geometry, float x, float y) {
        List<Vector3f> vertices = geometry.vertices();
        for (int i = 0; i < vertices.size(); i++) {
            Vector2f point = geometry.editPoint(i);
            if (Math.abs(point.x - x) <= 0.0001f && Math.abs(point.y - y) <= 0.0001f) {
                return i;
            }
        }
        return -1;
    }

    private static ClipVertex mapGlyphVertex(RenderContext context, ClientVideoScreen target, GlyphVertex vertex) {
        if (context.rootTarget()) {
            return context.source().canvasVertex(vertex);
        }
        if (context.projection().mappedUv()) {
            return context.source().videoUvVertex(vertex);
        }
        ClipVertex video = context.source().videoUvVertex(vertex);
        boolean flippedX = target.player != null && target.player.flippedX();
        boolean flippedY = target.player != null && target.player.flippedY();
        float targetU = flippedX ? target.u1 + target.u2 - video.x : video.x;
        float targetV = flippedY ? target.v1 + target.v2 - video.y : video.y;
        return new ClipVertex(
                lerp(context.targetBounds()[0], context.targetBounds()[1], inverseLerp(target.u1, target.u2, targetU)),
                lerp(context.targetBounds()[2], context.targetBounds()[3], inverseLerp(target.v1, target.v2, targetV)),
                video.u,
                video.v
        );
    }

    private static List<ClipVertex> clipToTriangle(List<ClipVertex> subject, SurfaceTriangle triangle) {
        return clipToPolygon(subject, List.of(triangle.c1, triangle.c2, triangle.c3));
    }

    private static List<ClipVertex> clipToRect(List<ClipVertex> subject, float[] bounds) {
        return clipToPolygon(subject, List.of(
                new Vector2f(bounds[0], bounds[2]),
                new Vector2f(bounds[1], bounds[2]),
                new Vector2f(bounds[1], bounds[3]),
                new Vector2f(bounds[0], bounds[3])
        ));
    }

    private static List<ClipVertex> clipToPolygon(List<ClipVertex> subject, List<Vector2f> clipPolygon) {
        ArrayList<ClipVertex> output = new ArrayList<>(subject);
        boolean ccw = signedArea(clipPolygon) >= 0;
        for (int i = 0; i < clipPolygon.size(); i++) {
            Vector2f a = clipPolygon.get(i);
            Vector2f b = clipPolygon.get((i + 1) % clipPolygon.size());
            output = clipEdge(output, a, b, ccw);
            if (output.isEmpty()) return output;
        }
        return output;
    }

    private static ArrayList<ClipVertex> clipEdge(List<ClipVertex> input, Vector2f a, Vector2f b, boolean ccw) {
        ArrayList<ClipVertex> output = new ArrayList<>();
        if (input.isEmpty()) return output;
        ClipVertex previous = input.getLast();
        boolean previousInside = inside(previous, a, b, ccw);
        for (ClipVertex current : input) {
            boolean currentInside = inside(current, a, b, ccw);
            if (currentInside != previousInside) {
                output.add(intersection(previous, current, a, b));
            }
            if (currentInside) {
                output.add(current);
            }
            previous = current;
            previousInside = currentInside;
        }
        return output;
    }

    private static boolean inside(ClipVertex point, Vector2f a, Vector2f b, boolean ccw) {
        float cross = cross(a.x, a.y, b.x, b.y, point.x, point.y);
        return ccw ? cross >= -0.0001f : cross <= 0.0001f;
    }

    private static ClipVertex intersection(ClipVertex from, ClipVertex to, Vector2f a, Vector2f b) {
        float fromSide = cross(a.x, a.y, b.x, b.y, from.x, from.y);
        float toSide = cross(a.x, a.y, b.x, b.y, to.x, to.y);
        float denominator = fromSide - toSide;
        float t = Math.abs(denominator) < 0.00001f ? 0.0f : fromSide / denominator;
        t = Math.clamp(t, 0.0f, 1.0f);
        return new ClipVertex(
                lerp(from.x, to.x, t),
                lerp(from.y, to.y, t),
                lerp(from.u, to.u, t),
                lerp(from.v, to.v, t)
        );
    }

    private static void drawMappedQuad(VertexConsumer consumer, RenderContext context, Vector3f normalOffset,
                                       ClipVertex[] mapped, int vertexColor) {
        if (context.directPlane() != null) {
            float[] bounds = context.rootTarget() ? context.source().fullBounds() : context.targetBounds();
            RectRelation relation = relateToBounds(mapped, bounds);
            if (relation == RectRelation.OUTSIDE) return;
            if (relation == RectRelation.INSIDE) {
                drawBackgroundDirectQuad(context.directPlane(), normalOffset, consumer, mapped, vertexColor);
                return;
            }
        }

        ArrayList<ClipVertex> subject = new ArrayList<>(mapped.length);
        for (ClipVertex vertex : mapped) {
            subject.add(vertex);
        }
        if (!context.rootTarget() && !context.projection().mappedUv()) {
            subject = new ArrayList<>(clipToRect(subject, context.targetBounds()));
        }
        if (subject.size() < 3) return;
        for (SurfaceTriangle triangle : context.projection().triangles()) {
            List<ClipVertex> clipped = clipToTriangle(subject, triangle);
            if (clipped.size() < 3) continue;
            ClipVertex first = clipped.getFirst();
            for (int i = 1; i < clipped.size() - 1; i++) {
                drawBackgroundTriangle(triangle, normalOffset, consumer, first, clipped.get(i), clipped.get(i + 1), vertexColor);
            }
        }
    }

    private static void drawBackgroundDirectQuad(DirectPlane plane, Vector3f normalOffset, VertexConsumer consumer,
                                                 ClipVertex[] mapped, int vertexColor) {
        drawBackgroundPlaneVertex(plane, normalOffset, consumer, mapped[0], vertexColor);
        drawBackgroundPlaneVertex(plane, normalOffset, consumer, mapped[1], vertexColor);
        drawBackgroundPlaneVertex(plane, normalOffset, consumer, mapped[2], vertexColor);
        drawBackgroundPlaneVertex(plane, normalOffset, consumer, mapped[3], vertexColor);
    }

    private static void drawBackgroundTriangle(SurfaceTriangle triangle, Vector3f normalOffset, VertexConsumer consumer,
                                               ClipVertex p1, ClipVertex p2, ClipVertex p3, int vertexColor) {
        drawBackgroundVertex(triangle, normalOffset, consumer, p1, vertexColor);
        drawBackgroundVertex(triangle, normalOffset, consumer, p2, vertexColor);
        drawBackgroundVertex(triangle, normalOffset, consumer, p3, vertexColor);
        drawBackgroundVertex(triangle, normalOffset, consumer, p3, vertexColor);
        drawBackgroundVertex(triangle, normalOffset, consumer, p1, vertexColor);
        drawBackgroundVertex(triangle, normalOffset, consumer, p3, vertexColor);
        drawBackgroundVertex(triangle, normalOffset, consumer, p2, vertexColor);
        drawBackgroundVertex(triangle, normalOffset, consumer, p2, vertexColor);
    }

    private static void drawBackgroundVertex(SurfaceTriangle triangle, Vector3f normalOffset, VertexConsumer consumer,
                                             ClipVertex point, int vertexColor) {
        Vector3f vertex = triangle.interpolate(point.x, point.y)
                .add(normalOffset)
                .sub(ScreenRenderer.cameraX, ScreenRenderer.cameraY, ScreenRenderer.cameraZ);
        consumer.vertex(vertex.x, vertex.y, vertex.z)
                .color(vertexColor)
                .texture(point.u, point.v);
    }

    private static void drawBackgroundPlaneVertex(DirectPlane plane, Vector3f normalOffset, VertexConsumer consumer,
                                                  ClipVertex point, int vertexColor) {
        float x = plane.origin.x + plane.xAxis.x * (point.x - plane.minX) + plane.yAxis.x * (point.y - plane.minY)
                + normalOffset.x - ScreenRenderer.cameraX;
        float y = plane.origin.y + plane.xAxis.y * (point.x - plane.minX) + plane.yAxis.y * (point.y - plane.minY)
                + normalOffset.y - ScreenRenderer.cameraY;
        float z = plane.origin.z + plane.xAxis.z * (point.x - plane.minX) + plane.yAxis.z * (point.y - plane.minY)
                + normalOffset.z - ScreenRenderer.cameraZ;
        consumer.vertex(x, y, z)
                .color(vertexColor)
                .texture(point.u, point.v);
    }

    private static void drawTriangle(SurfaceTriangle triangle, Vector3f normalOffset, VertexConsumer consumer,
                                     ClipVertex p1, ClipVertex p2, ClipVertex p3, int vertexColor, int light) {
        drawVertex(triangle, normalOffset, consumer, p1, vertexColor, light);
        drawVertex(triangle, normalOffset, consumer, p2, vertexColor, light);
        drawVertex(triangle, normalOffset, consumer, p3, vertexColor, light);
        drawVertex(triangle, normalOffset, consumer, p3, vertexColor, light);
        drawVertex(triangle, normalOffset, consumer, p1, vertexColor, light);
        drawVertex(triangle, normalOffset, consumer, p3, vertexColor, light);
        drawVertex(triangle, normalOffset, consumer, p2, vertexColor, light);
        drawVertex(triangle, normalOffset, consumer, p2, vertexColor, light);
    }

    private static void drawVertex(SurfaceTriangle triangle, Vector3f normalOffset, VertexConsumer consumer,
                                   ClipVertex point, int vertexColor, int light) {
        Vector3f vertex = triangle.interpolate(point.x, point.y)
                .add(normalOffset)
                .sub(ScreenRenderer.cameraX, ScreenRenderer.cameraY, ScreenRenderer.cameraZ);
        consumer.vertex(vertex.x, vertex.y, vertex.z)
                .color(vertexColor)
                .texture(point.u, point.v)
                .light(light);
    }

    private static void drawPlaneVertex(DirectPlane plane, Vector3f normalOffset, VertexConsumer consumer,
                                        ClipVertex point, int vertexColor, int light) {
        float x = plane.origin.x + plane.xAxis.x * (point.x - plane.minX) + plane.yAxis.x * (point.y - plane.minY)
                + normalOffset.x - ScreenRenderer.cameraX;
        float y = plane.origin.y + plane.xAxis.y * (point.x - plane.minX) + plane.yAxis.y * (point.y - plane.minY)
                + normalOffset.y - ScreenRenderer.cameraY;
        float z = plane.origin.z + plane.xAxis.z * (point.x - plane.minX) + plane.yAxis.z * (point.y - plane.minY)
                + normalOffset.z - ScreenRenderer.cameraZ;
        consumer.vertex(x, y, z)
                .color(vertexColor)
                .texture(point.u, point.v)
                .light(light);
    }

    private static float signedArea(List<Vector2f> polygon) {
        float area = 0;
        for (int i = 0; i < polygon.size(); i++) {
            Vector2f a = polygon.get(i);
            Vector2f b = polygon.get((i + 1) % polygon.size());
            area += a.x * b.y - b.x * a.y;
        }
        return area * 0.5f;
    }

    private static float cross(float ax, float ay, float bx, float by, float px, float py) {
        return (bx - ax) * (py - ay) - (by - ay) * (px - ax);
    }

    private static float lerp(float start, float end, float delta) {
        return start + (end - start) * delta;
    }

    private static float inverseLerp(float start, float end, float value) {
        float delta = end - start;
        if (Math.abs(delta) < 0.00001f) return 0.0f;
        return (value - start) / delta;
    }

    private record RenderContext(ScreenGeometry geometry, TargetProjection projection, SourceMapping source,
                                 float[] targetBounds, boolean rootTarget, DirectPlane directPlane) {
    }

    private record DirectPlane(Vector3f origin, Vector3f xAxis, Vector3f yAxis, float minX, float minY) {
        private DirectPlane {
            origin = new Vector3f(origin);
            xAxis = new Vector3f(xAxis);
            yAxis = new Vector3f(yAxis);
        }
    }

    private record SourceMapping(float[] fullBounds, float[] contentBounds, float u1, float v1, float u2, float v2,
                                 boolean flippedX, boolean flippedY,
                                 float canvasWidth, float canvasHeight) {
        private ClipVertex canvasVertex(GlyphVertex vertex) {
            return new ClipVertex(
                    lerp(fullBounds[0], fullBounds[1], vertex.x / canvasWidth),
                    lerp(fullBounds[2], fullBounds[3], vertex.y / canvasHeight),
                    vertex.u,
                    vertex.v
            );
        }

        private ClipVertex videoUvVertex(GlyphVertex vertex) {
            float sourceU = lerp(fullBounds[0], fullBounds[1], vertex.x / canvasWidth);
            float sourceV = lerp(fullBounds[2], fullBounds[3], vertex.y / canvasHeight);
            float videoU = lerp(u1, u2, inverseLerp(contentBounds[0], contentBounds[1], sourceU));
            float videoV = lerp(v1, v2, inverseLerp(contentBounds[2], contentBounds[3], sourceV));
            if (flippedX) videoU = u1 + u2 - videoU;
            if (flippedY) videoV = v1 + v2 - videoV;
            return new ClipVertex(videoU, videoV, vertex.u, vertex.v);
        }
    }

    private record TargetProjection(List<SurfaceTriangle> triangles, boolean mappedUv) {
    }

    private record SurfaceTriangle(Vector2f c1, Vector2f c2, Vector2f c3, Vector3f v1, Vector3f v2, Vector3f v3) {
        private boolean valid() {
            return Math.abs(cross(c1.x, c1.y, c2.x, c2.y, c3.x, c3.y)) > 0.00001f;
        }

        private Vector3f interpolate(float x, float y) {
            float denominator = (c2.y - c3.y) * (c1.x - c3.x) + (c3.x - c2.x) * (c1.y - c3.y);
            if (Math.abs(denominator) < 0.00001f) return new Vector3f(v1);
            float w1 = ((c2.y - c3.y) * (x - c3.x) + (c3.x - c2.x) * (y - c3.y)) / denominator;
            float w2 = ((c3.y - c1.y) * (x - c3.x) + (c1.x - c3.x) * (y - c3.y)) / denominator;
            float w3 = 1.0f - w1 - w2;
            return new Vector3f(v1).mul(w1)
                    .add(new Vector3f(v2).mul(w2))
                    .add(new Vector3f(v3).mul(w3));
        }
    }

    private enum SurfaceCoordinates {
        EDIT,
        MAPPED_UV
    }

    private static final class GuiTextBatch {
        private final ScreenRect scissorArea;
        private final Map<GuiTextBatchKey, ArrayList<GuiGlyphVertex>> verticesByBatch = new HashMap<>();

        private GuiTextBatch(ScreenRect scissorArea) {
            this.scissorArea = scissorArea;
        }

        private void add(TextDrawable drawable, Matrix4f matrix, int color) {
            GuiTextBatchKey key = new GuiTextBatchKey(drawable.getPipeline(), drawable.textureView());
            ArrayList<GuiGlyphVertex> vertices = verticesByBatch.computeIfAbsent(key, ignored -> new ArrayList<>());
            drawable.render(matrix, new GuiGlyphVertexCollector(vertices, color), LIGHT, true);
        }

        private void submit(DrawContext context) {
            if (verticesByBatch.isEmpty() || scissorArea == null) return;
            for (Map.Entry<GuiTextBatchKey, ArrayList<GuiGlyphVertex>> entry : verticesByBatch.entrySet()) {
                if (entry.getValue().isEmpty()) continue;
                ((DrawContextAccessor) context).videoplayer$getState().addSimpleElement(new GuiTextBatchRenderState(
                        entry.getKey().pipeline(),
                        entry.getKey().textureView(),
                        List.copyOf(entry.getValue()),
                        scissorArea
                ));
            }
        }
    }

    private static final class GuiGlyphCollector implements TextRenderer.GlyphDrawer {
        private final GuiTextBatch batch;
        private final Matrix4f matrix;
        private final int color;

        private GuiGlyphCollector(GuiTextBatch batch, Matrix4f matrix, int color) {
            this.batch = batch;
            this.matrix = matrix;
            this.color = color;
        }

        @Override
        public void drawGlyph(TextDrawable.DrawnGlyphRect glyph) {
            batch.add(glyph, matrix, color);
        }

        @Override
        public void drawRectangle(TextDrawable rectangle) {
            batch.add(rectangle, matrix, color);
        }
    }

    private static final class GuiGlyphVertexCollector implements VertexConsumer {
        private final List<GuiGlyphVertex> vertices;
        private final int color;
        private float x;
        private float y;
        private float z;
        private float u;
        private float v;

        private GuiGlyphVertexCollector(List<GuiGlyphVertex> vertices, int color) {
            this.vertices = vertices;
            this.color = color;
        }

        @Override
        public VertexConsumer vertex(float x, float y, float z) {
            this.x = x;
            this.y = y;
            this.z = z;
            return this;
        }

        @Override
        public VertexConsumer color(int red, int green, int blue, int alpha) {
            return this;
        }

        @Override
        public VertexConsumer color(int color) {
            return this;
        }

        @Override
        public VertexConsumer texture(float u, float v) {
            this.u = u;
            this.v = v;
            return this;
        }

        @Override
        public VertexConsumer overlay(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer light(int u, int v) {
            vertices.add(new GuiGlyphVertex(x, y, z, this.u, this.v, color, (v << 16) | (u & 0xFFFF)));
            return this;
        }

        @Override
        public VertexConsumer normal(float x, float y, float z) {
            return this;
        }

        @Override
        public VertexConsumer lineWidth(float width) {
            return this;
        }
    }

    private record GuiTextBatchRenderState(RenderPipeline pipeline, GpuTextureView textureView,
                                           List<GuiGlyphVertex> vertices,
                                           ScreenRect bounds) implements SimpleGuiElementRenderState {
        @Override
        public void setupVertices(VertexConsumer consumer) {
            for (GuiGlyphVertex vertex : vertices) {
                consumer.vertex(vertex.x(), vertex.y(), vertex.z())
                        .color(vertex.color())
                        .texture(vertex.u(), vertex.v())
                        .light(vertex.light());
            }
        }

        @Override
        public TextureSetup textureSetup() {
            return TextureSetup.withLightmap(textureView, RenderSystem.getSamplerCache().get(FilterMode.NEAREST));
        }

        @Override
        public ScreenRect scissorArea() {
            return bounds;
        }
    }

    private record GuiTextBatchKey(RenderPipeline pipeline, GpuTextureView textureView) {
    }

    private record GuiGlyphVertex(float x, float y, float z, float u, float v, int color, int light) {
    }

    private static final class WorldTextBatch {
        private final Map<RenderLayer, WorldGlyphVertexCollector> consumers = new LinkedHashMap<>();

        private VertexConsumer consumer(RenderLayer layer) {
            return consumers.computeIfAbsent(layer, ignored -> new WorldGlyphVertexCollector());
        }

        private void submit(VertexConsumerProvider output) {
            for (Map.Entry<RenderLayer, WorldGlyphVertexCollector> entry : consumers.entrySet()) {
                List<WorldGlyphVertex> vertices = entry.getValue().vertices();
                if (vertices.isEmpty()) continue;
                VertexConsumer consumer = output.getBuffer(entry.getKey());
                for (WorldGlyphVertex vertex : vertices) {
                    consumer.vertex(vertex.x(), vertex.y(), vertex.z())
                            .color(vertex.color())
                            .texture(vertex.u(), vertex.v())
                            .light(vertex.light());
                }
            }
        }
    }

    private static final class WorldGlyphVertexCollector implements VertexConsumer {
        private final ArrayList<WorldGlyphVertex> vertices = new ArrayList<>();
        private float x;
        private float y;
        private float z;
        private float u;
        private float v;
        private int color = 0xFFFFFFFF;

        private List<WorldGlyphVertex> vertices() {
            return vertices;
        }

        @Override
        public VertexConsumer vertex(float x, float y, float z) {
            this.x = x;
            this.y = y;
            this.z = z;
            return this;
        }

        @Override
        public VertexConsumer color(int red, int green, int blue, int alpha) {
            this.color = (Math.clamp(alpha, 0, 255) << 24)
                    | (Math.clamp(red, 0, 255) << 16)
                    | (Math.clamp(green, 0, 255) << 8)
                    | Math.clamp(blue, 0, 255);
            return this;
        }

        @Override
        public VertexConsumer color(int color) {
            this.color = color;
            return this;
        }

        @Override
        public VertexConsumer texture(float u, float v) {
            this.u = u;
            this.v = v;
            return this;
        }

        @Override
        public VertexConsumer overlay(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer light(int u, int v) {
            vertices.add(new WorldGlyphVertex(x, y, z, this.u, this.v, color, (v << 16) | (u & 0xFFFF)));
            return this;
        }

        @Override
        public VertexConsumer normal(float x, float y, float z) {
            return this;
        }

        @Override
        public VertexConsumer lineWidth(float width) {
            return this;
        }
    }

    private record WorldGlyphVertex(float x, float y, float z, float u, float v, int color, int light) {
    }

    private record ClipVertex(float x, float y, float u, float v) {
    }

    private record GlyphVertex(float x, float y, float u, float v, int light) {
    }

    private static final class MappedGlyphDrawer implements TextRenderer.GlyphDrawer {
        private final WorldTextBatch batch;
        private final RenderContext context;
        private final ClientVideoScreen target;
        private final Vector3f normalOffset;
        private final Matrix4f matrix;
        private final TextRenderer.TextLayerType layerType;
        private final int color;
        private final Map<RenderLayer, MappingVertexConsumer> layerConsumers = new HashMap<>();

        private MappedGlyphDrawer(WorldTextBatch batch, RenderContext context, ClientVideoScreen target,
                                  Vector3f normalOffset, Matrix4f matrix,
                                  TextRenderer.TextLayerType layerType, int color) {
            this.batch = batch;
            this.context = context;
            this.target = target;
            this.normalOffset = normalOffset;
            this.matrix = matrix;
            this.layerType = layerType;
            this.color = color;
        }

        @Override
        public void drawGlyph(TextDrawable.DrawnGlyphRect glyph) {
            draw(glyph);
        }

        @Override
        public void drawRectangle(TextDrawable rectangle) {
            draw(rectangle);
        }

        private void draw(TextDrawable drawable) {
            RenderLayer layer = drawable.getRenderLayer(layerType);
            MappingVertexConsumer consumer = layerConsumers.computeIfAbsent(layer, key ->
                    new MappingVertexConsumer(batch.consumer(key), context, target, normalOffset, color));
            drawable.render(matrix, consumer, LIGHT, false);
        }
    }

    private static final class MappingVertexConsumer implements VertexConsumer {
        private final VertexConsumer delegate;
        private final RenderContext context;
        private final ClientVideoScreen target;
        private final Vector3f normalOffset;
        private final int color;
        private final GlyphVertex[] vertices = new GlyphVertex[4];
        private int vertexCount;
        private float x;
        private float y;
        private float u;
        private float v;
        private int light = LIGHT;

        private MappingVertexConsumer(VertexConsumer delegate, RenderContext context, ClientVideoScreen target,
                                      Vector3f normalOffset, int color) {
            this.delegate = delegate;
            this.context = context;
            this.target = target;
            this.normalOffset = new Vector3f(normalOffset);
            this.color = color;
        }

        @Override
        public VertexConsumer vertex(float x, float y, float z) {
            this.x = x;
            this.y = y;
            return this;
        }

        @Override
        public VertexConsumer color(int red, int green, int blue, int alpha) {
            return this;
        }

        @Override
        public VertexConsumer color(int color) {
            return this;
        }

        @Override
        public VertexConsumer texture(float u, float v) {
            this.u = u;
            this.v = v;
            return this;
        }

        @Override
        public VertexConsumer overlay(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer light(int u, int v) {
            this.light = (v << 16) | (u & 0xFFFF);
            vertices[vertexCount++] = new GlyphVertex(x, y, this.u, this.v, light);
            if (vertexCount == vertices.length) {
                flushQuad();
                vertexCount = 0;
            }
            return this;
        }

        @Override
        public VertexConsumer normal(float x, float y, float z) {
            return this;
        }

        @Override
        public VertexConsumer lineWidth(float width) {
            return this;
        }

        private void flushQuad() {
            ClipVertex[] mapped = new ClipVertex[vertices.length];
            for (int i = 0; i < vertices.length; i++) {
                mapped[i] = mapGlyphVertex(context, target, vertices[i]);
            }
            if (context.directPlane() != null) {
                float[] bounds = context.rootTarget() ? context.source().fullBounds() : context.targetBounds();
                RectRelation relation = relateToBounds(mapped, bounds);
                if (relation == RectRelation.OUTSIDE) return;
                if (relation == RectRelation.INSIDE) {
                    drawDirectQuad(mapped, vertices[0].light());
                    return;
                }
            }

            ArrayList<ClipVertex> subject = new ArrayList<>(mapped.length);
            for (ClipVertex vertex : mapped) {
                subject.add(vertex);
            }
            if (!context.rootTarget() && !context.projection().mappedUv()) {
                subject = new ArrayList<>(clipToRect(subject, context.targetBounds()));
            }
            if (subject.size() < 3) return;
            int glyphLight = vertices[0].light();
            for (SurfaceTriangle triangle : context.projection().triangles()) {
                List<ClipVertex> clipped = clipToTriangle(subject, triangle);
                if (clipped.size() < 3) continue;
                ClipVertex first = clipped.getFirst();
                for (int i = 1; i < clipped.size() - 1; i++) {
                    drawTriangle(triangle, normalOffset, delegate, first, clipped.get(i), clipped.get(i + 1), color, glyphLight);
                }
            }
        }

        private void drawDirectQuad(ClipVertex[] mapped, int glyphLight) {
            drawPlaneVertex(context.directPlane(), normalOffset, delegate, mapped[0], color, glyphLight);
            drawPlaneVertex(context.directPlane(), normalOffset, delegate, mapped[1], color, glyphLight);
            drawPlaneVertex(context.directPlane(), normalOffset, delegate, mapped[2], color, glyphLight);
            drawPlaneVertex(context.directPlane(), normalOffset, delegate, mapped[3], color, glyphLight);
        }
    }

    private static RectRelation relateToBounds(ClipVertex[] vertices, float[] bounds) {
        boolean anyInside = false;
        boolean allInside = true;
        for (ClipVertex vertex : vertices) {
            boolean inside = vertex.x >= bounds[0] - 0.0001f
                    && vertex.x <= bounds[1] + 0.0001f
                    && vertex.y >= bounds[2] - 0.0001f
                    && vertex.y <= bounds[3] + 0.0001f;
            anyInside |= inside;
            allInside &= inside;
        }
        if (allInside) return RectRelation.INSIDE;
        if (anyInside) return RectRelation.INTERSECTING;

        float minX = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        for (ClipVertex vertex : vertices) {
            minX = Math.min(minX, vertex.x);
            maxX = Math.max(maxX, vertex.x);
            minY = Math.min(minY, vertex.y);
            maxY = Math.max(maxY, vertex.y);
        }
        if (maxX < bounds[0] - 0.0001f || minX > bounds[1] + 0.0001f
                || maxY < bounds[2] - 0.0001f || minY > bounds[3] + 0.0001f) {
            return RectRelation.OUTSIDE;
        }
        return RectRelation.INTERSECTING;
    }

    private enum RectRelation {
        INSIDE,
        INTERSECTING,
        OUTSIDE
    }
}
