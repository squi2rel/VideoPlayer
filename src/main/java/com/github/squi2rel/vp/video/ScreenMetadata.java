package com.github.squi2rel.vp.video;

import com.github.squi2rel.vp.i18n.TranslatableIllegalArgumentException;
import com.github.squi2rel.vp.i18n.VpTranslation;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

public class ScreenMetadata {
    public static final int MAX_ENTRIES = 255;
    public static final String KEY_MAPPING_UVS = "mapping.uvs";
    public static final String KEY_DEFAULT_VOLUME = "defaultVolume";
    public static final String KEY_DANMAKU_ENABLED = "danmaku";
    public static final String KEY_BILIBILI_QUALITY = "bilibiliQuality";
    public static final String KEY_YOUTUBE_QUALITY = "youtubeQuality";
    public static final String KEY_CAMERA_ASPECT = "aspect";
    public static final String KEY_CAMERA_FOV = "fov";

    private static final Pattern VALID_KEY = Pattern.compile("[A-Za-z0-9_.:-]{1,64}");
    public Map<String, MetaValue> values = new HashMap<>();

    public ScreenMetadata() {
    }

    public void ensureValid() {
        if (values == null) values = new HashMap<>();
        values.entrySet().removeIf(entry -> entry.getKey() == null
                || !VALID_KEY.matcher(entry.getKey()).matches()
                || !validValue(entry.getValue()));
    }

    public boolean isEmpty() {
        ensureValid();
        return values.isEmpty();
    }

    public int size() {
        ensureValid();
        return values.size();
    }

    public Map<String, MetaValue> entries() {
        ensureValid();
        return Collections.unmodifiableMap(values);
    }

    public MetaValue get(String key) {
        ensureValid();
        return values.get(key);
    }

    public void set(String key, MetaValue value) {
        validateKey(key);
        if (value == null) throw invalid("error.videoplayer.meta.value_required", "Meta value is required");
        value.validateValue();
        ensureValid();
        if (!values.containsKey(key) && values.size() >= MAX_ENTRIES) {
            throw invalid("error.videoplayer.meta.too_many", "Meta entry count must not exceed %s", MAX_ENTRIES);
        }
        values.put(key, value);
    }

    public void remove(String key) {
        validateKey(key);
        ensureValid();
        values.remove(key);
    }

    public boolean getBool(String key, boolean fallback) {
        MetaValue value = get(key);
        return value == null ? fallback : value.bool(fallback);
    }

    public int getInt(String key, int fallback) {
        MetaValue value = get(key);
        return value == null ? fallback : value.intValue(fallback);
    }

    public long getLong(String key, long fallback) {
        MetaValue value = get(key);
        return value == null ? fallback : value.longValue(fallback);
    }

    public float getFloat(String key, float fallback) {
        MetaValue value = get(key);
        return value == null ? fallback : value.floatValue(fallback);
    }

    public double getDouble(String key, double fallback) {
        MetaValue value = get(key);
        return value == null ? fallback : value.doubleValue(fallback);
    }

    public String getString(String key, String fallback) {
        MetaValue value = get(key);
        return value == null ? fallback : value.stringValue(fallback);
    }

    public float[] getFloatArray(String key) {
        MetaValue value = get(key);
        return value == null ? null : value.floatArray();
    }

    public static void validateKey(String key) {
        if (key == null || !VALID_KEY.matcher(key).matches()) {
            throw invalid("error.videoplayer.meta.key_invalid", "Meta key may only contain A-Z, a-z, 0-9, _, ., :, or -, and must be 1-64 characters long");
        }
    }

    private static TranslatableIllegalArgumentException invalid(String key, String fallback, Object... args) {
        return new TranslatableIllegalArgumentException(VpTranslation.of(key, fallback, args));
    }

    private static boolean validValue(MetaValue value) {
        if (value == null) return false;
        try {
            value.validateValue();
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
