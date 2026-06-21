package com.github.squi2rel.vp.video;

import org.joml.Vector2f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ScreenGeometry {
    public static final int MIN_VERTICES = 3;
    public static final int MAX_VERTICES = 64;
    public static final float EPSILON = 0.02f;

    private final List<Vector3f> vertices;
    private final Vector3f origin;
    private final Vector3f uAxis;
    private final Vector3f vAxis;
    private final Vector3f normal;
    private final List<Vector2f> points2d;
    private final List<Vector2f> editPoints2d;
    private final int[] triangles;
    private final float minU;
    private final float maxU;
    private final float minV;
    private final float maxV;
    private final float textureMinU;
    private final float textureMaxU;
    private final float textureMinV;
    private final float textureMaxV;

    private ScreenGeometry(List<Vector3f> vertices, Vector3f origin, Vector3f uAxis, Vector3f vAxis, Vector3f normal,
                           List<Vector2f> points2d, List<Vector2f> editPoints2d, int[] triangles,
                           float minU, float maxU, float minV, float maxV,
                           float textureMinU, float textureMaxU, float textureMinV, float textureMaxV) {
        this.vertices = vertices;
        this.origin = origin;
        this.uAxis = uAxis;
        this.vAxis = vAxis;
        this.normal = normal;
        this.points2d = points2d;
        this.editPoints2d = editPoints2d;
        this.triangles = triangles;
        this.minU = minU;
        this.maxU = maxU;
        this.minV = minV;
        this.maxV = maxV;
        this.textureMinU = textureMinU;
        this.textureMaxU = textureMaxU;
        this.textureMinV = textureMinV;
        this.textureMaxV = textureMaxV;
    }

    public static ScreenGeometry create(List<Vector3f> rawVertices) {
        if (rawVertices == null || rawVertices.size() < MIN_VERTICES || rawVertices.size() > MAX_VERTICES) {
            throw new IllegalArgumentException("Screen vertex count must be between " + MIN_VERTICES + " and " + MAX_VERTICES);
        }

        ArrayList<Vector3f> vertices = new ArrayList<>(rawVertices.size());
        for (Vector3f vertex : rawVertices) {
            if (vertex == null || !Float.isFinite(vertex.x) || !Float.isFinite(vertex.y) || !Float.isFinite(vertex.z)) {
                throw new IllegalArgumentException("Invalid screen vertex");
            }
            vertices.add(new Vector3f(vertex));
        }

        Vector3f origin = new Vector3f(vertices.getFirst());
        Vector3f normal = findProjectionNormal(vertices);
        Vector3f uAxis = findAxis(vertices, origin, normal);
        Vector3f vAxis = new Vector3f(normal).cross(uAxis).normalize();

        ArrayList<Vector2f> points2d = new ArrayList<>(vertices.size());
        float minU = Float.POSITIVE_INFINITY;
        float maxU = Float.NEGATIVE_INFINITY;
        float minV = Float.POSITIVE_INFINITY;
        float maxV = Float.NEGATIVE_INFINITY;

        for (Vector3f vertex : vertices) {
            Vector3f relative = new Vector3f(vertex).sub(origin);
            float u = relative.dot(uAxis);
            float v = relative.dot(vAxis);
            points2d.add(new Vector2f(u, v));
            minU = Math.min(minU, u);
            maxU = Math.max(maxU, u);
            minV = Math.min(minV, v);
            maxV = Math.max(maxV, v);
        }

        if (Math.abs(maxU - minU) < EPSILON || Math.abs(maxV - minV) < EPSILON) {
            throw new IllegalArgumentException("Screen polygon is degenerate");
        }
        if (selfIntersects(points2d)) {
            throw new IllegalArgumentException("Screen polygon must not self-intersect");
        }

        Triangulation triangulation = triangulate(points2d);
        int[] triangles = triangulation.triangles();
        if (triangles.length < 3) {
            throw new IllegalArgumentException("Screen polygon cannot be triangulated");
        }
        List<Vector2f> editPoints = triangulation.stripRotation() >= 0 ? unfoldStrip(vertices, triangulation.stripRotation()) : null;
        if (editPoints == null) editPoints = points2d;
        float textureMinU = Float.POSITIVE_INFINITY;
        float textureMaxU = Float.NEGATIVE_INFINITY;
        float textureMinV = Float.POSITIVE_INFINITY;
        float textureMaxV = Float.NEGATIVE_INFINITY;
        for (Vector2f point : editPoints) {
            textureMinU = Math.min(textureMinU, point.x);
            textureMaxU = Math.max(textureMaxU, point.x);
            textureMinV = Math.min(textureMinV, point.y);
            textureMaxV = Math.max(textureMaxV, point.y);
        }

        return new ScreenGeometry(
                Collections.unmodifiableList(vertices),
                origin,
                uAxis,
                vAxis,
                normal,
                Collections.unmodifiableList(points2d),
                Collections.unmodifiableList(editPoints),
                triangles,
                minU,
                maxU,
                minV,
                maxV,
                textureMinU,
                textureMaxU,
                textureMinV,
                textureMaxV
        );
    }

    public List<Vector3f> vertices() {
        return vertices;
    }

    public Vector3f firstVertex() {
        return vertices.getFirst();
    }

    public Vector3f normal() {
        return new Vector3f(normal);
    }

    public int[] triangles() {
        return triangles.clone();
    }

    int[] triangleIndices() {
        return triangles;
    }

    public float width() {
        return maxU - minU;
    }

    public float height() {
        return maxV - minV;
    }

    private float textureWidth() {
        return textureMaxU - textureMinU;
    }

    private float textureHeight() {
        return textureMaxV - textureMinV;
    }

    public Vector2f project(Vector3f point) {
        Vector3f relative = new Vector3f(point).sub(origin);
        return new Vector2f(relative.dot(uAxis), relative.dot(vAxis));
    }

    public Vector2f projectedPoint(int index) {
        return new Vector2f(points2d.get(index));
    }

    public Vector2f editPoint(int index) {
        return new Vector2f(editPoints2d.get(index));
    }

    public Vector3f unproject(float u, float v) {
        return new Vector3f(origin)
                .add(new Vector3f(uAxis).mul(u))
                .add(new Vector3f(vAxis).mul(v));
    }

    public Vector2f textureCoord(Vector3f point, float u1, float v1, float u2, float v2,
                                 boolean fill, float scaleX, float scaleY,
                                 int videoWidth, int videoHeight) {
        Vector2f uv = project(point);
        return textureCoord(uv.x, uv.y, contentBounds(u1, v1, u2, v2, fill, scaleX, scaleY, videoWidth, videoHeight), u1, v1, u2, v2);
    }

    public Vector2f textureCoord(float planeU, float planeV, float[] bounds, float u1, float v1, float u2, float v2) {
        float tu = inverseLerp(bounds[0], bounds[1], planeU);
        float tv = inverseLerp(bounds[2], bounds[3], planeV);
        return new Vector2f(
                lerp(u1, u2, tu),
                lerp(v1, v2, tv)
        );
    }

    public float[] contentBounds(float u1, float v1, float u2, float v2,
                                 boolean fill, float scaleX, float scaleY,
                                 int videoWidth, int videoHeight) {
        float contentMinU = textureMinU;
        float contentMaxU = textureMaxU;
        float contentMinV = textureMinV;
        float contentMaxV = textureMaxV;

        float du = Math.abs(u2 - u1);
        float dv = Math.abs(v2 - v1);
        if (!fill && videoWidth > 0 && videoHeight > 0 && du > EPSILON && dv > EPSILON) {
            float safeScaleX = Math.max(scaleX, EPSILON);
            float safeScaleY = Math.max(scaleY, EPSILON);
            float screenAspect = textureWidth() / Math.max(textureHeight(), EPSILON) * safeScaleX / safeScaleY;
            float videoAspect = videoWidth * du / (videoHeight * dv);
            if (screenAspect > videoAspect) {
                float contentW = textureHeight() * videoAspect * safeScaleY / safeScaleX;
                float center = (textureMinU + textureMaxU) * 0.5f;
                contentMinU = center - contentW * 0.5f;
                contentMaxU = center + contentW * 0.5f;
            } else {
                float contentH = textureWidth() / videoAspect * safeScaleX / safeScaleY;
                float center = (textureMinV + textureMaxV) * 0.5f;
                contentMinV = center - contentH * 0.5f;
                contentMaxV = center + contentH * 0.5f;
            }
        }

        return new float[]{contentMinU, contentMaxU, contentMinV, contentMaxV};
    }

    public boolean contains(Vector3f point) {
        return contains2d(project(point), points2d);
    }

    public boolean intersectsRay(Vector3f lineStart, Vector3f lineEnd, Vector3f intersection) {
        Vector3f lineDir = new Vector3f(lineEnd).sub(lineStart);
        float length = lineDir.length();
        if (length <= EPSILON) return false;
        lineDir.div(length);

        boolean hit = false;
        float nearest = Float.POSITIVE_INFINITY;
        for (int i = 0; i < triangles.length; i += 3) {
            Float distance = intersectTriangle(
                    lineStart,
                    lineDir,
                    vertices.get(triangles[i]),
                    vertices.get(triangles[i + 1]),
                    vertices.get(triangles[i + 2])
            );
            if (distance == null || distance < 0 || distance > length || distance >= nearest) continue;
            nearest = distance;
            hit = true;
        }
        if (!hit) return false;
        intersection.set(lineDir).mul(nearest).add(lineStart);
        return true;
    }

    public static boolean contains2d(Vector2f point, List<Vector2f> polygon) {
        boolean inside = false;
        for (int i = 0, j = polygon.size() - 1; i < polygon.size(); j = i++) {
            Vector2f a = polygon.get(i);
            Vector2f b = polygon.get(j);
            if (((a.y > point.y) != (b.y > point.y)) &&
                    point.x < (b.x - a.x) * (point.y - a.y) / (b.y - a.y) + a.x) {
                inside = !inside;
            }
        }
        return inside;
    }

    private static Vector3f findProjectionNormal(List<Vector3f> vertices) {
        Vector3f normal = new Vector3f();
        for (int i = 0; i < vertices.size(); i++) {
            Vector3f current = vertices.get(i);
            Vector3f next = vertices.get((i + 1) % vertices.size());
            normal.x += (current.y - next.y) * (current.z + next.z);
            normal.y += (current.z - next.z) * (current.x + next.x);
            normal.z += (current.x - next.x) * (current.y + next.y);
        }
        if (normal.lengthSquared() >= EPSILON * EPSILON) {
            return normal.normalize();
        }

        Vector3f first = vertices.getFirst();
        for (int i = 1; i < vertices.size() - 1; i++) {
            Vector3f a = new Vector3f(vertices.get(i)).sub(first);
            Vector3f b = new Vector3f(vertices.get(i + 1)).sub(first);
            normal = a.cross(b);
            if (normal.lengthSquared() >= EPSILON * EPSILON) {
                return normal.normalize();
            }
        }
        throw new IllegalArgumentException("Screen polygon is degenerate");
    }

    private static Vector3f findAxis(List<Vector3f> vertices, Vector3f origin, Vector3f normal) {
        for (int i = 1; i < vertices.size(); i++) {
            Vector3f axis = new Vector3f(vertices.get(i)).sub(origin);
            axis.sub(new Vector3f(normal).mul(axis.dot(normal)));
            if (axis.lengthSquared() >= EPSILON * EPSILON) {
                return axis.normalize();
            }
        }
        throw new IllegalArgumentException("Screen polygon is degenerate");
    }

    private static Float intersectTriangle(Vector3f origin, Vector3f direction, Vector3f a, Vector3f b, Vector3f c) {
        Vector3f edge1 = new Vector3f(b).sub(a);
        Vector3f edge2 = new Vector3f(c).sub(a);
        Vector3f h = new Vector3f(direction).cross(edge2);
        float det = edge1.dot(h);
        if (Math.abs(det) < 0.000001f) return null;

        float invDet = 1.0f / det;
        Vector3f s = new Vector3f(origin).sub(a);
        float u = invDet * s.dot(h);
        if (u < -EPSILON || u > 1.0f + EPSILON) return null;

        Vector3f q = s.cross(edge1);
        float v = invDet * direction.dot(q);
        if (v < -EPSILON || u + v > 1.0f + EPSILON) return null;

        return invDet * edge2.dot(q);
    }

    private static Triangulation triangulate(List<Vector2f> polygon) {
        StripTriangulation strip = triangulateStrip(polygon);
        if (strip != null) return new Triangulation(strip.triangles(), strip.rotation());

        int size = polygon.size();
        ArrayList<Integer> indices = new ArrayList<>(size);
        for (int i = 0; i < size; i++) indices.add(i);
        if (signedArea(polygon) < 0) Collections.reverse(indices);

        ArrayList<Integer> triangles = new ArrayList<>((size - 2) * 3);
        int guard = 0;
        while (indices.size() > 3 && guard++ < size * size) {
            boolean clipped = false;
            for (int i = 0; i < indices.size(); i++) {
                int prev = indices.get((i - 1 + indices.size()) % indices.size());
                int curr = indices.get(i);
                int next = indices.get((i + 1) % indices.size());

                if (!isEar(prev, curr, next, indices, polygon)) continue;
                triangles.add(prev);
                triangles.add(curr);
                triangles.add(next);
                indices.remove(i);
                clipped = true;
                break;
            }
            if (!clipped) break;
        }

        if (indices.size() == 3) {
            triangles.add(indices.get(0));
            triangles.add(indices.get(1));
            triangles.add(indices.get(2));
        }

        int[] result = new int[triangles.size()];
        for (int i = 0; i < triangles.size(); i++) result[i] = triangles.get(i);
        return new Triangulation(result, -1);
    }

    private static List<Vector2f> unfoldStrip(List<Vector3f> vertices, int rotation) {
        int size = vertices.size();
        if (size < 4 || size % 2 != 0) return null;

        int half = size / 2;
        Vector2f[] result = new Vector2f[size];
        float x = 0;
        for (int i = 0; i < half; i++) {
            int topIndex = rotatedIndex(rotation, i, size);
            int bottomIndex = rotatedIndex(rotation, size - 1 - i, size);
            if (i > 0) {
                int previousTop = rotatedIndex(rotation, i - 1, size);
                int previousBottom = rotatedIndex(rotation, size - i, size);
                float topLength = new Vector3f(vertices.get(topIndex)).sub(vertices.get(previousTop)).length();
                float bottomLength = new Vector3f(vertices.get(bottomIndex)).sub(vertices.get(previousBottom)).length();
                if (!Float.isFinite(topLength) || !Float.isFinite(bottomLength)) return null;
                x += (topLength + bottomLength) * 0.5f;
            }
            float height = new Vector3f(vertices.get(bottomIndex)).sub(vertices.get(topIndex)).length();
            if (!Float.isFinite(height) || height <= EPSILON) return null;
            result[topIndex] = new Vector2f(x, 0);
            result[bottomIndex] = new Vector2f(x, height);
        }
        if (x <= EPSILON) return null;

        ArrayList<Vector2f> points = new ArrayList<>(size);
        Collections.addAll(points, result);
        return points;
    }

    private static StripTriangulation triangulateStrip(List<Vector2f> polygon) {
        int size = polygon.size();
        if (size < 4 || size % 2 != 0) return null;
        if (size == 4) return triangulateStrip(polygon, 0);

        StripTriangulation best = null;
        for (int rotation = 0; rotation < size; rotation++) {
            StripTriangulation candidate = triangulateStrip(polygon, rotation);
            if (candidate == null) continue;
            if (best == null || candidate.score() < best.score() - EPSILON) {
                best = candidate;
            }
        }
        return best;
    }

    private static StripTriangulation triangulateStrip(List<Vector2f> polygon, int rotation) {
        int size = polygon.size();
        int half = size / 2;
        ArrayList<Integer> triangles = new ArrayList<>((size - 2) * 3);
        for (int i = 0; i < half - 1; i++) {
            int topLeft = rotatedIndex(rotation, i, size);
            int topRight = rotatedIndex(rotation, i + 1, size);
            int bottomRight = rotatedIndex(rotation, size - 2 - i, size);
            int bottomLeft = rotatedIndex(rotation, size - 1 - i, size);
            addOrientedTriangle(triangles, polygon, topLeft, topRight, bottomRight);
            addOrientedTriangle(triangles, polygon, topLeft, bottomRight, bottomLeft);
        }
        if (triangles.size() != (size - 2) * 3 || !trianglesCoverPolygon(triangles, polygon)) {
            return null;
        }

        int[] result = new int[triangles.size()];
        for (int i = 0; i < triangles.size(); i++) result[i] = triangles.get(i);
        return new StripTriangulation(result, rotation, triangulationScore(triangles, polygon));
    }

    private static void addOrientedTriangle(ArrayList<Integer> triangles, List<Vector2f> polygon, int a, int b, int c) {
        float area = cross(polygon.get(a), polygon.get(b), polygon.get(c));
        if (Math.abs(area) <= EPSILON) return;
        if (area > 0) {
            triangles.add(a);
            triangles.add(b);
            triangles.add(c);
        } else {
            triangles.add(a);
            triangles.add(c);
            triangles.add(b);
        }
    }

    private static boolean trianglesCoverPolygon(List<Integer> triangles, List<Vector2f> polygon) {
        float polygonArea = Math.abs(signedArea(polygon));
        if (polygonArea <= EPSILON) return false;

        float triangleArea = 0;
        for (int i = 0; i < triangles.size(); i += 3) {
            Vector2f a = polygon.get(triangles.get(i));
            Vector2f b = polygon.get(triangles.get(i + 1));
            Vector2f c = polygon.get(triangles.get(i + 2));
            Vector2f centroid = new Vector2f((a.x + b.x + c.x) / 3.0f, (a.y + b.y + c.y) / 3.0f);
            if (!contains2d(centroid, polygon) && !onPolygonEdge(centroid, polygon)) return false;
            triangleArea += Math.abs(cross(a, b, c)) * 0.5f;
        }
        return Math.abs(triangleArea - polygonArea) <= Math.max(EPSILON, polygonArea * 0.02f);
    }

    private static boolean isEar(int prev, int curr, int next, List<Integer> indices, List<Vector2f> polygon) {
        Vector2f a = polygon.get(prev);
        Vector2f b = polygon.get(curr);
        Vector2f c = polygon.get(next);
        if (cross(a, b, c) <= EPSILON) return false;
        for (int index : indices) {
            if (index == prev || index == curr || index == next) continue;
            if (pointInTriangle(polygon.get(index), a, b, c)) return false;
        }
        return true;
    }

    private static boolean selfIntersects(List<Vector2f> polygon) {
        int size = polygon.size();
        for (int i = 0; i < size; i++) {
            Vector2f a1 = polygon.get(i);
            Vector2f a2 = polygon.get((i + 1) % size);
            for (int j = i + 1; j < size; j++) {
                if (Math.abs(i - j) <= 1 || (i == 0 && j == size - 1)) continue;
                Vector2f b1 = polygon.get(j);
                Vector2f b2 = polygon.get((j + 1) % size);
                if (segmentsIntersect(a1, a2, b1, b2)) return true;
            }
        }
        return false;
    }

    private static boolean segmentsIntersect(Vector2f a1, Vector2f a2, Vector2f b1, Vector2f b2) {
        float d1 = cross(a1, a2, b1);
        float d2 = cross(a1, a2, b2);
        float d3 = cross(b1, b2, a1);
        float d4 = cross(b1, b2, a2);
        return d1 * d2 < -EPSILON && d3 * d4 < -EPSILON;
    }

    private static boolean pointInTriangle(Vector2f p, Vector2f a, Vector2f b, Vector2f c) {
        float ab = cross(a, b, p);
        float bc = cross(b, c, p);
        float ca = cross(c, a, p);
        return ab >= -EPSILON && bc >= -EPSILON && ca >= -EPSILON;
    }

    private static boolean onPolygonEdge(Vector2f point, List<Vector2f> polygon) {
        for (int i = 0; i < polygon.size(); i++) {
            if (pointOnSegment(point, polygon.get(i), polygon.get((i + 1) % polygon.size()))) return true;
        }
        return false;
    }

    private static boolean pointOnSegment(Vector2f point, Vector2f a, Vector2f b) {
        if (Math.abs(cross(a, b, point)) > EPSILON) return false;
        return point.x >= Math.min(a.x, b.x) - EPSILON
                && point.x <= Math.max(a.x, b.x) + EPSILON
                && point.y >= Math.min(a.y, b.y) - EPSILON
                && point.y <= Math.max(a.y, b.y) + EPSILON;
    }

    private static float triangulationScore(List<Integer> triangles, List<Vector2f> polygon) {
        float score = 0;
        ArrayList<Long> diagonals = new ArrayList<>();
        for (int i = 0; i < triangles.size(); i += 3) {
            score += addDiagonalScore(diagonals, polygon, triangles.get(i), triangles.get(i + 1));
            score += addDiagonalScore(diagonals, polygon, triangles.get(i + 1), triangles.get(i + 2));
            score += addDiagonalScore(diagonals, polygon, triangles.get(i + 2), triangles.get(i));
        }
        return score;
    }

    private static float addDiagonalScore(List<Long> diagonals, List<Vector2f> polygon, int a, int b) {
        if (isBoundaryEdge(a, b, polygon.size())) return 0;
        long key = edgeKey(a, b);
        if (diagonals.contains(key)) return 0;
        diagonals.add(key);
        Vector2f p1 = polygon.get(a);
        Vector2f p2 = polygon.get(b);
        float dx = p1.x - p2.x;
        float dy = p1.y - p2.y;
        return dx * dx + dy * dy;
    }

    private static boolean isBoundaryEdge(int a, int b, int size) {
        int diff = Math.abs(a - b);
        return diff == 1 || diff == size - 1;
    }

    private static long edgeKey(int a, int b) {
        int min = Math.min(a, b);
        int max = Math.max(a, b);
        return ((long) min << 32) | (max & 0xFFFFFFFFL);
    }

    private static int rotatedIndex(int rotation, int offset, int size) {
        return (rotation + offset) % size;
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

    private static float cross(Vector2f a, Vector2f b, Vector2f c) {
        return (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x);
    }

    private static float inverseLerp(float start, float end, float value) {
        if (Math.abs(end - start) < EPSILON) return 0;
        return (value - start) / (end - start);
    }

    private static float lerp(float start, float end, float delta) {
        return start + (end - start) * delta;
    }

    private record Triangulation(int[] triangles, int stripRotation) {
    }

    private record StripTriangulation(int[] triangles, int rotation, float score) {
    }
}
