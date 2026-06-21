package com.github.squi2rel.vp.danmaku;

record DanmakuEntry(long id, String idStr, long progressMs, int mode, int fontSize, int color, String content, int pool) {
    static DanmakuEntry live(int mode, int fontSize, int color, String content) {
        return new DanmakuEntry(0, "", -1, mode, fontSize, color, content, 0);
    }

    boolean renderable() {
        return content != null && !content.isBlank() && mode >= 1 && mode <= 6;
    }

    boolean rolling() {
        return mode == 1 || mode == 2 || mode == 3 || mode == 6;
    }

    boolean leftToRight() {
        return mode == 6;
    }

    boolean fixedTop() {
        return mode == 5;
    }

    boolean fixedBottom() {
        return mode == 4;
    }

    int argb() {
        return 0xFF000000 | (color & 0x00FFFFFF);
    }

    float scale() {
        float base = (fontSize <= 0 ? 25 : fontSize) / 25.0f;
        return Math.clamp(base * 1.5f, 0.75f * 1.5f, 1.8f * 1.5f);
    }

    String key() {
        if (idStr != null && !idStr.isBlank()) return idStr;
        if (id > 0) return Long.toString(id);
        return progressMs + ":" + mode + ":" + color + ":" + content;
    }
}
