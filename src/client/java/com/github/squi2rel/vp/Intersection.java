package com.github.squi2rel.vp;

import com.github.squi2rel.vp.video.ClientVideoScreen;
import com.github.squi2rel.vp.video.ScreenSurface;
import org.joml.Vector3f;

public class Intersection {
    public static Result intersect(Vector3f lineStart, Vector3f lineEnd, ClientVideoScreen player) {
        Result result = new Result();
        if (player.surface == ScreenSurface.SPHERE_360 && player.spherePreset) {
            intersectGeometry(lineStart, lineEnd, player, result);
            if (player.sphereSkybox) {
                return result;
            }
            Result sphere = new Result();
            if (intersectSphere(lineStart, lineEnd, player, sphere.point)) {
                sphere.distance = new Vector3f(sphere.point).sub(lineStart).length();
                sphere.intersects = true;
                sphere.screen = player;
                if (!result.intersects || sphere.distance < result.distance) {
                    result = sphere;
                }
            }
            return result;
        }
        intersectGeometry(lineStart, lineEnd, player, result);
        return result;
    }

    private static void intersectGeometry(Vector3f lineStart, Vector3f lineEnd, ClientVideoScreen player, Result result) {
        try {
            if (player.geometry().intersectsRay(lineStart, lineEnd, result.point)) {
                result.distance = new Vector3f(result.point).sub(lineStart).length();
                result.intersects = true;
                result.screen = player;
            }
        } catch (IllegalArgumentException ignored) {
        }
    }

    private static boolean intersectSphere(Vector3f lineStart, Vector3f lineEnd, ClientVideoScreen screen, Vector3f intersection) {
        Vector3f center = screen.sphereCenter == null ? new Vector3f() : screen.sphereCenter;
        float radius = screen.sphereRadius;
        if (!Float.isFinite(radius) || radius <= 0) return false;

        Vector3f d = new Vector3f(lineEnd).sub(lineStart);
        float a = d.dot(d);
        if (a <= 0.000001f) return false;
        Vector3f m = new Vector3f(lineStart).sub(center);
        float b = 2.0f * m.dot(d);
        float c = m.dot(m) - radius * radius;
        float disc = b * b - 4.0f * a * c;
        if (disc < 0) return false;

        float sqrt = (float) Math.sqrt(disc);
        float t1 = (-b - sqrt) / (2.0f * a);
        float t2 = (-b + sqrt) / (2.0f * a);
        float t = Float.POSITIVE_INFINITY;
        if (t1 >= 0 && t1 <= 1) t = t1;
        if (t2 >= 0 && t2 <= 1) t = Math.min(t, t2);
        if (!Float.isFinite(t)) return false;
        intersection.set(d).mul(t).add(lineStart);
        return true;
    }

    public static class Result {
        public boolean intersects;
        public Vector3f point;
        public float distance;
        public ClientVideoScreen screen;

        public Result() {
            this.intersects = false;
            this.point = new Vector3f();
            this.distance = 0f;
        }
    }
}
