package com.github.squi2rel.vp.video;

import com.github.squi2rel.vp.ScreenRenderer;
import com.github.squi2rel.vp.vivecraft.Vivecraft;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BuiltBuffer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.MathHelper;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.github.squi2rel.vp.VideoPlayerClient.config;

public final class Degree360Player {
    private static final Quaternionf tmp = new Quaternionf();
    private static final int MAX_CACHED_MESHES = 24;
    private static final LinkedHashMap<MeshKey, SphereMesh> MESHES = new LinkedHashMap<>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<MeshKey, SphereMesh> eldest) {
            if (size() <= MAX_CACHED_MESHES) return false;
            eldest.getValue().close();
            return true;
        }
    };

    private Degree360Player() {
    }

    public static void drawTexture(int textureId, MatrixStack matrices, VertexConsumerProvider consumers, ClientVideoScreen screen) {
        drawTexture(textureId, matrices, consumers, screen, screen.stereo3d);
    }

    public static void drawTexture(int textureId, MatrixStack matrices, VertexConsumerProvider consumers, ClientVideoScreen screen, boolean is3d) {
        if (textureId < 0) return;
        boolean rightEye = is3d && Vivecraft.loaded && Vivecraft.isVRActive() && Vivecraft.isRightEye();
        SphereMesh mesh = meshFor(screen, is3d, rightEye);
        if (mesh == null || mesh.vertexBuffer == null || mesh.vertexBuffer.isClosed()) return;

        matrices.push();
        if (screen.sphereSkybox) {
            ScreenRenderer.skybox = true;
        } else {
            Vector3f center = screen.sphereCenter == null ? new Vector3f() : screen.sphereCenter;
            matrices.translate(-ScreenRenderer.cameraX, -ScreenRenderer.cameraY, -ScreenRenderer.cameraZ);
            matrices.translate(center.x, center.y, center.z);
        }
        applySphereRotation(matrices, screen.sphereRotX, screen.sphereRotY, screen.sphereRotZ);
        Matrix4f matrix = new Matrix4f(matrices.peek().getPositionMatrix());
        matrices.pop();

        int gray = (int) (config.brightness / 100.0 * 255);
        int color = 0xFF000000 | (gray << 16) | (gray << 8) | gray;
        flush(consumers);
        ScreenRenderer.drawWorldTexturedMesh(textureId, matrix, mesh.vertexBuffer, mesh.vertexCount, color);
    }

    public static void clearMeshCache() {
        for (SphereMesh mesh : MESHES.values()) {
            mesh.close();
        }
        MESHES.clear();
    }

    private static SphereMesh meshFor(ClientVideoScreen screen, boolean stereo3d, boolean rightEye) {
        int latSegments = clampSegments(screen.sphereLat);
        int lonSegments = clampSegments(screen.sphereLon);
        MeshKey key = MeshKey.of(screen, stereo3d, rightEye, latSegments, lonSegments);
        SphereMesh cached = MESHES.get(key);
        if (cached != null && !cached.vertexBuffer.isClosed()) return cached;
        SphereMesh mesh = buildMesh(key, latSegments, lonSegments);
        MESHES.put(key, mesh);
        return mesh;
    }

    private static SphereMesh buildMesh(MeshKey key, int latSegments, int lonSegments) {
        float[] vertices = key.hemisphere
                ? genHemisphereVertices(key.radius(), latSegments, lonSegments, key.u1(), key.u2(), key.v1(), key.v2())
                : genVertices(key.radius(), latSegments, lonSegments, key.u1(), key.u2(), key.v1(), key.v2());
        int vertexCount = stripVertexCount(latSegments, lonSegments);
        int size = Math.max(256, vertexCount * VertexFormats.POSITION_TEXTURE_COLOR.getVertexSize());
        try (BufferAllocator allocator = new BufferAllocator(size)) {
            BufferBuilder buffer = new BufferBuilder(allocator, VertexFormat.DrawMode.TRIANGLE_STRIP, VertexFormats.POSITION_TEXTURE_COLOR);
            appendSphereStrip(buffer, vertices, latSegments, lonSegments, key.stereo3d, key.rightEye, key.u1(), key.u2());
            try (BuiltBuffer built = buffer.end()) {
                GpuBuffer vertexBuffer = RenderSystem.getDevice().createBuffer(
                        () -> "VideoPlayer 360 sphere mesh",
                        GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST,
                        built.getBuffer()
                );
                return new SphereMesh(vertexBuffer, built.getDrawParameters().vertexCount());
            }
        }
    }

    private static int stripVertexCount(int latSegments, int lonSegments) {
        int verticesPerStrip = (lonSegments + 1) * 2;
        return latSegments * verticesPerStrip + Math.max(0, latSegments - 1) * 2;
    }

    private static void appendSphereStrip(VertexConsumer consumer, float[] vertices, int latSegments, int lonSegments,
                                          boolean stereo3d, boolean rightEye, float u1, float u2) {
        int strip = lonSegments + 1;
        int previousLast = -1;
        for (int latIndex = 0; latIndex < latSegments; latIndex++) {
            int first = latIndex * strip * 2;
            if (previousLast >= 0) {
                appendSphereVertex(consumer, vertices, previousLast, stereo3d, rightEye, u1, u2);
                appendSphereVertex(consumer, vertices, first, stereo3d, rightEye, u1, u2);
            }
            for (int lonIndex = 0; lonIndex <= lonSegments; lonIndex++) {
                int top = first + lonIndex * 2;
                appendSphereVertex(consumer, vertices, top, stereo3d, rightEye, u1, u2);
                appendSphereVertex(consumer, vertices, top + 1, stereo3d, rightEye, u1, u2);
            }
            previousLast = first + lonSegments * 2 + 1;
        }
    }

    private static void appendSphereVertex(VertexConsumer consumer, float[] vertices, int vertex,
                                           boolean stereo3d, boolean rightEye, float u1, float u2) {
        int idx = vertex * 5;
        float u = vertices[idx + 3];
        if (stereo3d) {
            float split = (u1 + u2) * 0.5f;
            u = rightEye ? split + (u - u1) * 0.5f : u1 + (u - u1) * 0.5f;
        }
        consumer.vertex(vertices[idx], vertices[idx + 1], vertices[idx + 2])
                .texture(u, vertices[idx + 4])
                .color(0xFFFFFFFF);
    }

    private static void flush(VertexConsumerProvider consumers) {
        if (consumers instanceof VertexConsumerProvider.Immediate immediate) {
            immediate.draw();
        }
    }

    private static void applySphereRotation(MatrixStack matrices, float x, float y, float z) {
        if (y != 0) matrices.multiply(tmp.rotationY((float) Math.toRadians(y)));
        if (x != 0) matrices.multiply(tmp.rotationX((float) Math.toRadians(x)));
        if (z != 0) matrices.multiply(tmp.rotationZ((float) Math.toRadians(z)));
    }

    static float[] genVertices(float radius, int latSegments, int lonSegments, float us, float ue, float vs, float ve) {
        latSegments = clampSegments(latSegments);
        lonSegments = clampSegments(lonSegments);
        return genVertices(radius, latSegments, lonSegments, us, ue, vs, ve, 0.0, Math.PI * 2.0);
    }

    static float[] genHemisphereVertices(float radius, int latSegments, int lonSegments, float us, float ue, float vs, float ve) {
        latSegments = clampSegments(latSegments);
        lonSegments = clampSegments(lonSegments);
        return genVertices(radius, latSegments, lonSegments, us, ue, vs, ve, 0.0, Math.PI);
    }

    private static int clampSegments(int value) {
        return VideoScreen.clampSphereSegments(value);
    }

    private static float[] genVertices(float radius, int latSegments, int lonSegments, float us, float ue, float vs, float ve,
                                       double phiStart, double phiEnd) {
        int vertexCount = latSegments * (lonSegments + 1) * 2;
        float[] data = new float[vertexCount * 5];

        int idx = 0;
        double phiRange = phiEnd - phiStart;
        for (int lat = 0; lat < latSegments; lat++) {
            double theta1 = Math.PI * lat / latSegments;
            double theta2 = Math.PI * (lat + 1) / latSegments;
            for (int lon = 0; lon <= lonSegments; lon++) {
                double phi = phiStart + phiRange * lon / lonSegments;
                float y1 = (float) (radius * Math.cos(theta1));
                float y2 = (float) (radius * Math.cos(theta2));
                float r1 = (float) (radius * Math.sin(theta1));
                float r2 = (float) (radius * Math.sin(theta2));
                float x1 = (float) (r1 * Math.cos(phi));
                float x2 = (float) (r2 * Math.cos(phi));
                float z1 = (float) (r1 * Math.sin(phi));
                float z2 = (float) (r2 * Math.sin(phi));
                float u = MathHelper.lerp((float) lon / lonSegments, us, ue);
                float v1 = MathHelper.lerp((float) lat / latSegments, vs, ve);
                float v2 = MathHelper.lerp((float) (lat + 1) / latSegments, vs, ve);
                data[idx++] = x1;
                data[idx++] = y1;
                data[idx++] = z1;
                data[idx++] = u;
                data[idx++] = v1;
                data[idx++] = x2;
                data[idx++] = y2;
                data[idx++] = z2;
                data[idx++] = u;
                data[idx++] = v2;
            }
        }

        return data;
    }

    private record MeshKey(int radiusBits, int lat, int lon, int u1Bits, int u2Bits, int v1Bits, int v2Bits,
                           boolean hemisphere, boolean stereo3d, boolean rightEye) {
        private static MeshKey of(ClientVideoScreen screen, boolean stereo3d, boolean rightEye, int lat, int lon) {
            return new MeshKey(
                    Float.floatToIntBits(screen.sphereRadius),
                    lat,
                    lon,
                    Float.floatToIntBits(screen.u1),
                    Float.floatToIntBits(screen.u2),
                    Float.floatToIntBits(screen.v1),
                    Float.floatToIntBits(screen.v2),
                    stereo3d,
                    stereo3d,
                    rightEye
            );
        }

        private float radius() {
            return Float.intBitsToFloat(radiusBits);
        }

        private float u1() {
            return Float.intBitsToFloat(u1Bits);
        }

        private float u2() {
            return Float.intBitsToFloat(u2Bits);
        }

        private float v1() {
            return Float.intBitsToFloat(v1Bits);
        }

        private float v2() {
            return Float.intBitsToFloat(v2Bits);
        }
    }

    private record SphereMesh(GpuBuffer vertexBuffer, int vertexCount) implements AutoCloseable {
        @Override
        public void close() {
            if (vertexBuffer != null && !vertexBuffer.isClosed()) {
                vertexBuffer.close();
            }
        }
    }
}
