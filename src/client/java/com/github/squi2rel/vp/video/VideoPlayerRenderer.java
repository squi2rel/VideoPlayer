package com.github.squi2rel.vp.video;

import com.github.squi2rel.vp.ScreenRenderer;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

import static com.github.squi2rel.vp.VideoPlayerClient.config;

final class VideoPlayerRenderer {
    private static final int BACKING_COLOR = 0xFF000000;
    private static final int[] TRIANGLE_QUAD_ORDER = {0, 1, 2, 2};
    private static final int[] REVERSED_TRIANGLE_QUAD_ORDER = {0, 2, 1, 1};

    private VideoPlayerRenderer() {
    }

    static void draw(IVideoPlayer player, MatrixStack matrices, VertexConsumerProvider consumers, ClientVideoScreen target) {
        ClientVideoScreen source = player.screen();
        if (source == null || source.player == null) return;
        if (player.getTextureId() < 0) return;

        ScreenGeometry geometry;
        try {
            geometry = target.geometry();
        } catch (IllegalArgumentException ignored) {
            return;
        }

        matrices.push();
        matrices.translate(-ScreenRenderer.cameraX, -ScreenRenderer.cameraY, -ScreenRenderer.cameraZ);
        Matrix4f mat = matrices.peek().getPositionMatrix();
        matrices.pop();

        boolean fx = player.flippedX();
        boolean fy = player.flippedY();
        float[] bounds = geometry.contentBounds(
                target.u1,
                target.v1,
                target.u2,
                target.v2,
                target.fill,
                target.scaleX,
                target.scaleY,
                player.getWidth(),
                player.getHeight()
        );
        int[] triangles = geometry.triangleIndices();
        List<Vector3f> vertices = geometry.vertices();
        List<Vector2f> mappedUvs = mappedUvs(target, vertices);
        Vector3f normal = geometry.normal();
        VertexConsumer backingConsumer = consumers.getBuffer(ScreenRenderer.getBackingLayer(player.getTextureId()));
        for (int i = 0; i < triangles.length; i += 3) {
            drawBackingTriangle(mat, backingConsumer, target, geometry, vertices, triangles, i, bounds, mappedUvs, normal);
        }

        RenderLayer layer = ScreenRenderer.getLayer(player.getTextureId());
        VertexConsumer consumer = consumers.getBuffer(layer);
        for (int i = 0; i < triangles.length; i += 3) {
            drawTriangle(player, mat, consumer, target, geometry, vertices, triangles, i, bounds, fx, fy, mappedUvs, normal);
        }
    }

    static void drawTexture(int textureId, int textureWidth, int textureHeight,
                            MatrixStack matrices, VertexConsumerProvider consumers, ClientVideoScreen target) {
        if (textureId < 0) return;

        ScreenGeometry geometry;
        try {
            geometry = target.geometry();
        } catch (IllegalArgumentException ignored) {
            return;
        }

        matrices.push();
        matrices.translate(-ScreenRenderer.cameraX, -ScreenRenderer.cameraY, -ScreenRenderer.cameraZ);
        Matrix4f mat = matrices.peek().getPositionMatrix();
        matrices.pop();

        float[] bounds = geometry.contentBounds(
                target.u1,
                target.v1,
                target.u2,
                target.v2,
                target.fill,
                target.scaleX,
                target.scaleY,
                textureWidth,
                textureHeight
        );
        int[] triangles = geometry.triangleIndices();
        List<Vector3f> vertices = geometry.vertices();
        List<Vector2f> mappedUvs = mappedUvs(target, vertices);
        Vector3f normal = geometry.normal();
        VertexConsumer backingConsumer = consumers.getBuffer(ScreenRenderer.getBackingLayer(textureId));
        for (int i = 0; i < triangles.length; i += 3) {
            drawBackingTriangle(mat, backingConsumer, target, geometry, vertices, triangles, i, bounds, mappedUvs, normal);
        }

        RenderLayer layer = ScreenRenderer.getLayer(textureId);
        VertexConsumer consumer = consumers.getBuffer(layer);
        for (int i = 0; i < triangles.length; i += 3) {
            drawTextureTriangle(mat, consumer, target, geometry, vertices, triangles, i, bounds, mappedUvs, normal);
        }
    }

    private static void drawBackingTriangle(Matrix4f mat, VertexConsumer consumer, ClientVideoScreen target,
                                            ScreenGeometry geometry, List<Vector3f> vertices, int[] triangles, int offset,
                                            float[] bounds, List<Vector2f> mappedUvs, Vector3f normal) {
        if (mappedUvs != null) {
            drawMappedBackingTriangle(mat, consumer, vertices, triangles, offset, mappedUvs, normal);
            return;
        }
        ArrayList<ProjectedVertex> polygon = trianglePolygon(geometry, vertices, triangles, offset);
        polygon = clipPolygon(polygon, bounds);
        if (polygon.size() < 3) return;

        ProjectedVertex first = polygon.getFirst();
        for (int i = 1; i < polygon.size() - 1; i++) {
            drawBackingVertex(mat, consumer, target, geometry, first, bounds, normal);
            drawBackingVertex(mat, consumer, target, geometry, polygon.get(i), bounds, normal);
            drawBackingVertex(mat, consumer, target, geometry, polygon.get(i + 1), bounds, normal);
            drawBackingVertex(mat, consumer, target, geometry, polygon.get(i + 1), bounds, normal);
        }
    }

    private static void drawBackingVertex(Matrix4f mat, VertexConsumer consumer, ClientVideoScreen target,
                                          ScreenGeometry geometry, ProjectedVertex projected, float[] bounds, Vector3f normal) {
        Vector2f uv = geometry.textureCoord(projected.texturePoint.x, projected.texturePoint.y, bounds, target.u1, target.v1, target.u2, target.v2);
        drawVertex(mat, consumer, projected.vertex, uv.x, uv.y, BACKING_COLOR, normal);
    }

    private static void drawTextureTriangle(Matrix4f mat, VertexConsumer consumer, ClientVideoScreen target,
                                            ScreenGeometry geometry, List<Vector3f> vertices, int[] triangles, int offset,
                                            float[] bounds, List<Vector2f> mappedUvs, Vector3f normal) {
        if (mappedUvs != null) {
            drawMappedTextureTriangle(mat, consumer, vertices, triangles, offset, mappedUvs, normal);
            return;
        }
        ArrayList<ProjectedVertex> polygon = trianglePolygon(geometry, vertices, triangles, offset);
        polygon = clipPolygon(polygon, bounds);
        if (polygon.size() < 3) return;

        ProjectedVertex first = polygon.getFirst();
        for (int i = 1; i < polygon.size() - 1; i++) {
            drawTextureVertex(mat, consumer, target, geometry, first, bounds, normal);
            drawTextureVertex(mat, consumer, target, geometry, polygon.get(i), bounds, normal);
            drawTextureVertex(mat, consumer, target, geometry, polygon.get(i + 1), bounds, normal);
            drawTextureVertex(mat, consumer, target, geometry, polygon.get(i + 1), bounds, normal);
            drawTextureVertex(mat, consumer, target, geometry, first, bounds, normal);
            drawTextureVertex(mat, consumer, target, geometry, polygon.get(i + 1), bounds, normal);
            drawTextureVertex(mat, consumer, target, geometry, polygon.get(i), bounds, normal);
            drawTextureVertex(mat, consumer, target, geometry, polygon.get(i), bounds, normal);
        }
    }

    private static void drawTextureVertex(Matrix4f mat, VertexConsumer consumer, ClientVideoScreen target,
                                          ScreenGeometry geometry, ProjectedVertex projected, float[] bounds, Vector3f normal) {
        Vector2f uv = geometry.textureCoord(projected.texturePoint.x, projected.texturePoint.y, bounds, target.u1, target.v1, target.u2, target.v2);
        drawVertex(mat, consumer, projected.vertex, uv.x, uv.y, normal);
    }

    private static void drawTriangle(IVideoPlayer player, Matrix4f mat, VertexConsumer consumer, ClientVideoScreen target,
                                     ScreenGeometry geometry, List<Vector3f> vertices, int[] triangles, int offset,
                                     float[] bounds, boolean fx, boolean fy, List<Vector2f> mappedUvs, Vector3f normal) {
        if (mappedUvs != null) {
            drawMappedTriangle(player, mat, consumer, target, vertices, triangles, offset, mappedUvs, fx, fy, normal);
            return;
        }
        ArrayList<ProjectedVertex> polygon = trianglePolygon(geometry, vertices, triangles, offset);
        polygon = clipPolygon(polygon, bounds);
        if (polygon.size() < 3) return;

        ProjectedVertex first = polygon.getFirst();
        for (int i = 1; i < polygon.size() - 1; i++) {
            drawPlaneVertex(player, mat, consumer, target, geometry, first, bounds, fx, fy, normal);
            drawPlaneVertex(player, mat, consumer, target, geometry, polygon.get(i), bounds, fx, fy, normal);
            drawPlaneVertex(player, mat, consumer, target, geometry, polygon.get(i + 1), bounds, fx, fy, normal);
            drawPlaneVertex(player, mat, consumer, target, geometry, polygon.get(i + 1), bounds, fx, fy, normal);
            drawPlaneVertex(player, mat, consumer, target, geometry, first, bounds, fx, fy, normal);
            drawPlaneVertex(player, mat, consumer, target, geometry, polygon.get(i + 1), bounds, fx, fy, normal);
            drawPlaneVertex(player, mat, consumer, target, geometry, polygon.get(i), bounds, fx, fy, normal);
            drawPlaneVertex(player, mat, consumer, target, geometry, polygon.get(i), bounds, fx, fy, normal);
        }
    }

    private static void drawPlaneVertex(IVideoPlayer player, Matrix4f mat, VertexConsumer consumer, ClientVideoScreen target,
                                        ScreenGeometry geometry, ProjectedVertex projected, float[] bounds, boolean fx, boolean fy, Vector3f normal) {
        Vector2f uv = geometry.textureCoord(projected.texturePoint.x, projected.texturePoint.y, bounds, target.u1, target.v1, target.u2, target.v2);
        if (fx) uv.x = target.u1 + target.u2 - uv.x;
        if (fy) uv.y = target.v1 + target.v2 - uv.y;
        player.drawVertex(mat, consumer, projected.vertex, uv, normal, target);
    }

    private static void drawMappedTextureTriangle(Matrix4f mat, VertexConsumer consumer, List<Vector3f> vertices,
                                                  int[] triangles, int offset, List<Vector2f> uvs, Vector3f normal) {
        drawMappedTextureTriangle(mat, consumer, vertices, triangles, offset, uvs, normal, TRIANGLE_QUAD_ORDER);
        drawMappedTextureTriangle(mat, consumer, vertices, triangles, offset, uvs, normal, REVERSED_TRIANGLE_QUAD_ORDER);
    }

    private static void drawMappedBackingTriangle(Matrix4f mat, VertexConsumer consumer, List<Vector3f> vertices,
                                                  int[] triangles, int offset, List<Vector2f> uvs, Vector3f normal) {
        drawMappedTextureTriangle(mat, consumer, vertices, triangles, offset, uvs, BACKING_COLOR, normal, TRIANGLE_QUAD_ORDER);
    }

    private static void drawMappedTriangle(IVideoPlayer player, Matrix4f mat, VertexConsumer consumer, ClientVideoScreen target,
                                           List<Vector3f> vertices, int[] triangles, int offset, List<Vector2f> uvs,
                                           boolean fx, boolean fy, Vector3f normal) {
        drawMappedTriangle(player, mat, consumer, target, vertices, triangles, offset, uvs, fx, fy, normal, TRIANGLE_QUAD_ORDER);
        drawMappedTriangle(player, mat, consumer, target, vertices, triangles, offset, uvs, fx, fy, normal, REVERSED_TRIANGLE_QUAD_ORDER);
    }

    private static void drawMappedTextureTriangle(Matrix4f mat, VertexConsumer consumer, List<Vector3f> vertices,
                                                  int[] triangles, int offset, List<Vector2f> uvs, Vector3f normal,
                                                  int[] order) {
        drawMappedTextureTriangle(mat, consumer, vertices, triangles, offset, uvs, color(), normal, order);
    }

    private static void drawMappedTextureTriangle(Matrix4f mat, VertexConsumer consumer, List<Vector3f> vertices,
                                                  int[] triangles, int offset, List<Vector2f> uvs, int color, Vector3f normal,
                                                  int[] order) {
        for (int triangleOffset : order) {
            int vertexIndex = triangles[offset + triangleOffset];
            Vector2f uv = uvs.get(vertexIndex);
            drawVertex(mat, consumer, vertices.get(vertexIndex), uv.x, uv.y, color, normal);
        }
    }

    private static void drawMappedTriangle(IVideoPlayer player, Matrix4f mat, VertexConsumer consumer, ClientVideoScreen target,
                                           List<Vector3f> vertices, int[] triangles, int offset, List<Vector2f> uvs,
                                           boolean fx, boolean fy, Vector3f normal, int[] order) {
        for (int triangleOffset : order) {
            int vertexIndex = triangles[offset + triangleOffset];
            Vector2f mapped = new Vector2f(uvs.get(vertexIndex));
            if (fx) mapped.x = target.u1 + target.u2 - mapped.x;
            if (fy) mapped.y = target.v1 + target.v2 - mapped.y;
            player.drawVertex(mat, consumer, vertices.get(vertexIndex), mapped, normal, target);
        }
    }

    private static List<Vector2f> mappedUvs(ClientVideoScreen target, List<Vector3f> vertices) {
        if (!target.fill) return null;
        float[] values = target.metadata.getFloatArray(ScreenMetadata.KEY_MAPPING_UVS);
        if (values == null || values.length != vertices.size() * 2) return null;
        ArrayList<Vector2f> result = new ArrayList<>(vertices.size());
        for (int i = 0; i < vertices.size(); i++) {
            result.add(new Vector2f(values[i * 2], values[i * 2 + 1]));
        }
        return result;
    }

    private static ArrayList<ProjectedVertex> trianglePolygon(ScreenGeometry geometry, List<Vector3f> vertices, int[] triangles, int offset) {
        ArrayList<ProjectedVertex> polygon = new ArrayList<>(3);
        for (int i = 0; i < 3; i++) {
            int vertexIndex = triangles[offset + i];
            polygon.add(new ProjectedVertex(geometry.projectedPoint(vertexIndex), geometry.editPoint(vertexIndex), new Vector3f(vertices.get(vertexIndex))));
        }
        return polygon;
    }

    private static ArrayList<ProjectedVertex> clipPolygon(ArrayList<ProjectedVertex> polygon, float[] bounds) {
        polygon = clip(polygon, bounds[0], true, true);
        polygon = clip(polygon, bounds[1], true, false);
        polygon = clip(polygon, bounds[2], false, true);
        polygon = clip(polygon, bounds[3], false, false);
        return polygon;
    }

    private static ArrayList<ProjectedVertex> clip(ArrayList<ProjectedVertex> input, float limit, boolean axisU, boolean keepGreater) {
        ArrayList<ProjectedVertex> output = new ArrayList<>();
        if (input.isEmpty()) return output;

        ProjectedVertex previous = input.getLast();
        boolean previousInside = inside(previous, limit, axisU, keepGreater);
        for (ProjectedVertex current : input) {
            boolean currentInside = inside(current, limit, axisU, keepGreater);
            if (currentInside != previousInside) {
                output.add(intersection(previous, current, limit, axisU));
            }
            if (currentInside) {
                output.add(current.copy());
            }
            previous = current;
            previousInside = currentInside;
        }
        return output;
    }

    private static boolean inside(ProjectedVertex projected, float limit, boolean axisU, boolean keepGreater) {
        float value = axisU ? projected.texturePoint.x : projected.texturePoint.y;
        return keepGreater ? value >= limit - ScreenGeometry.EPSILON : value <= limit + ScreenGeometry.EPSILON;
    }

    private static ProjectedVertex intersection(ProjectedVertex from, ProjectedVertex to, float limit, boolean axisU) {
        float start = axisU ? from.texturePoint.x : from.texturePoint.y;
        float end = axisU ? to.texturePoint.x : to.texturePoint.y;
        float delta = end - start;
        if (Math.abs(delta) < ScreenGeometry.EPSILON) return to.copy();
        float t = (limit - start) / delta;
        return new ProjectedVertex(
                new Vector2f(
                        from.point.x + (to.point.x - from.point.x) * t,
                        from.point.y + (to.point.y - from.point.y) * t
                ),
                new Vector2f(
                        from.texturePoint.x + (to.texturePoint.x - from.texturePoint.x) * t,
                        from.texturePoint.y + (to.texturePoint.y - from.texturePoint.y) * t
                ),
                new Vector3f(
                        from.vertex.x + (to.vertex.x - from.vertex.x) * t,
                        from.vertex.y + (to.vertex.y - from.vertex.y) * t,
                        from.vertex.z + (to.vertex.z - from.vertex.z) * t
                )
        );
    }

    private record ProjectedVertex(Vector2f point, Vector2f texturePoint, Vector3f vertex) {
        private ProjectedVertex copy() {
            return new ProjectedVertex(new Vector2f(point), new Vector2f(texturePoint), new Vector3f(vertex));
        }
    }

    static void drawQuad(Matrix4f mat, VertexConsumer consumer, Vector3f p1, Vector3f p2, Vector3f p3, Vector3f p4, float u1, float v1, float u2, float v2) {
        Vector3f normal = quadNormal(p1, p2, p3);
        drawVertex(mat, consumer, p1, u1, v1, normal);
        drawVertex(mat, consumer, p2, u1, v2, normal);
        drawVertex(mat, consumer, p3, u2, v2, normal);
        drawVertex(mat, consumer, p4, u2, v1, normal);
        drawVertex(mat, consumer, p1, u1, v1, normal);
        drawVertex(mat, consumer, p4, u2, v1, normal);
        drawVertex(mat, consumer, p3, u2, v2, normal);
        drawVertex(mat, consumer, p2, u1, v2, normal);
    }

    static void drawVertex(Matrix4f mat, VertexConsumer consumer, Vector3f vertex, float u, float v) {
        drawVertex(mat, consumer, vertex, u, v, null);
    }

    static void drawVertex(Matrix4f mat, VertexConsumer consumer, Vector3f vertex, float u, float v, Vector3f normal) {
        drawVertex(mat, consumer, vertex, u, v, color(), normal);
    }

    private static void drawVertex(Matrix4f mat, VertexConsumer consumer, Vector3f vertex, float u, float v, int color, Vector3f normal) {
        ScreenRenderer.drawWorldTexturedVertex(mat, consumer, vertex, u, v, color, normal);
    }

    private static int color() {
        int gray = (int) (config.brightness / 100.0 * 255);
        return 0xFF000000 | (gray << 16) | (gray << 8) | gray;
    }

    private static Vector3f quadNormal(Vector3f p1, Vector3f p2, Vector3f p3) {
        Vector3f normal = new Vector3f(p2).sub(p1).cross(new Vector3f(p3).sub(p1));
        if (normal.lengthSquared() < ScreenGeometry.EPSILON * ScreenGeometry.EPSILON) {
            return new Vector3f(0, 1, 0);
        }
        return normal.normalize();
    }
}
