package com.github.squi2rel.vp.video;

import com.google.gson.JsonObject;

public final class MpvFilterInfo {
    private MpvFilterInfo() {
    }

    public static JsonObject all() {
        return get(null, null);
    }

    public static JsonObject video() {
        return get("vf", null);
    }

    public static JsonObject audio() {
        return get("af", null);
    }

    public static JsonObject get(String type, String name) {
        return MpvLibrary.filterInfo(type, name);
    }

    public static String json(String type, String name) {
        return MpvLibrary.filterInfoJson(type, name);
    }
}
