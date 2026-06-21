package com.github.squi2rel.vp.video;

import com.github.squi2rel.vp.i18n.TranslatableIllegalArgumentException;
import com.github.squi2rel.vp.i18n.VpTranslation;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;

public class MetaValue {
    public static final int MAX_STRING_BYTES = 1024;
    public static final int MAX_ARRAY_LENGTH = 256;

    public MetaType type;
    public Boolean boolValue;
    public Integer intValue;
    public Long longValue;
    public Float floatValue;
    public Double doubleValue;
    public String stringValue;
    public boolean[] boolArray;
    public int[] intArray;
    public float[] floatArray;
    public String[] stringArray;

    public MetaValue() {
    }

    private MetaValue(MetaType type) {
        this.type = type;
    }

    public static MetaValue ofBool(boolean value) {
        MetaValue meta = new MetaValue(MetaType.BOOL);
        meta.boolValue = value;
        return meta;
    }

    public static MetaValue ofInt(int value) {
        MetaValue meta = new MetaValue(MetaType.INT);
        meta.intValue = value;
        return meta;
    }

    public static MetaValue ofLong(long value) {
        MetaValue meta = new MetaValue(MetaType.LONG);
        meta.longValue = value;
        return meta;
    }

    public static MetaValue ofFloat(float value) {
        MetaValue meta = new MetaValue(MetaType.FLOAT);
        meta.floatValue = value;
        return meta;
    }

    public static MetaValue ofDouble(double value) {
        MetaValue meta = new MetaValue(MetaType.DOUBLE);
        meta.doubleValue = value;
        return meta;
    }

    public static MetaValue ofString(String value) {
        MetaValue meta = new MetaValue(MetaType.STRING);
        meta.stringValue = value == null ? "" : value;
        return meta;
    }

    public static MetaValue ofBoolArray(boolean[] value) {
        MetaValue meta = new MetaValue(MetaType.BOOL_ARRAY);
        meta.boolArray = value == null ? new boolean[0] : Arrays.copyOf(value, value.length);
        return meta;
    }

    public static MetaValue ofIntArray(int[] value) {
        MetaValue meta = new MetaValue(MetaType.INT_ARRAY);
        meta.intArray = value == null ? new int[0] : Arrays.copyOf(value, value.length);
        return meta;
    }

    public static MetaValue ofFloatArray(float[] value) {
        MetaValue meta = new MetaValue(MetaType.FLOAT_ARRAY);
        meta.floatArray = value == null ? new float[0] : Arrays.copyOf(value, value.length);
        return meta;
    }

    public static MetaValue ofStringArray(String[] value) {
        MetaValue meta = new MetaValue(MetaType.STRING_ARRAY);
        meta.stringArray = value == null ? new String[0] : Arrays.copyOf(value, value.length);
        return meta;
    }

    public boolean bool(boolean fallback) {
        return type == MetaType.BOOL && boolValue != null ? boolValue : fallback;
    }

    public int intValue(int fallback) {
        return type == MetaType.INT && intValue != null ? intValue : fallback;
    }

    public long longValue(long fallback) {
        return type == MetaType.LONG && longValue != null ? longValue : fallback;
    }

    public float floatValue(float fallback) {
        return type == MetaType.FLOAT && floatValue != null ? floatValue : fallback;
    }

    public double doubleValue(double fallback) {
        return type == MetaType.DOUBLE && doubleValue != null ? doubleValue : fallback;
    }

    public String stringValue(String fallback) {
        return type == MetaType.STRING && stringValue != null ? stringValue : fallback;
    }

    public boolean[] boolArray() {
        return type == MetaType.BOOL_ARRAY && boolArray != null ? Arrays.copyOf(boolArray, boolArray.length) : null;
    }

    public int[] intArray() {
        return type == MetaType.INT_ARRAY && intArray != null ? Arrays.copyOf(intArray, intArray.length) : null;
    }

    public float[] floatArray() {
        return type == MetaType.FLOAT_ARRAY && floatArray != null ? Arrays.copyOf(floatArray, floatArray.length) : null;
    }

    public String[] stringArray() {
        return type == MetaType.STRING_ARRAY && stringArray != null ? Arrays.copyOf(stringArray, stringArray.length) : null;
    }

    public String toDisplayString() {
        return switch (type) {
            case BOOL -> String.valueOf(bool(false));
            case INT -> String.valueOf(intValue(0));
            case LONG -> String.valueOf(longValue(0));
            case FLOAT -> formatFloat(floatValue(0));
            case DOUBLE -> formatDouble(doubleValue(0));
            case STRING -> stringValue("");
            case BOOL_ARRAY -> join(boolArray == null ? new boolean[0] : boolArray);
            case INT_ARRAY -> join(intArray == null ? new int[0] : intArray);
            case FLOAT_ARRAY -> join(floatArray == null ? new float[0] : floatArray);
            case STRING_ARRAY -> String.join(", ", stringArray == null ? new String[0] : stringArray);
        };
    }

    public void validateValue() {
        if (type == null) throw invalid("error.videoplayer.meta.type_required", "Meta type is required");
        switch (type) {
            case BOOL -> {
                if (boolValue == null) throw invalid("error.videoplayer.meta.bool_required", "bool value is required");
            }
            case INT -> {
                if (intValue == null) throw invalid("error.videoplayer.meta.int_required", "int value is required");
            }
            case LONG -> {
                if (longValue == null) throw invalid("error.videoplayer.meta.long_required", "long value is required");
            }
            case FLOAT -> {
                if (floatValue == null || !Float.isFinite(floatValue)) throw invalid("error.videoplayer.meta.float_invalid", "float value is invalid");
            }
            case DOUBLE -> {
                if (doubleValue == null || !Double.isFinite(doubleValue)) throw invalid("error.videoplayer.meta.double_invalid", "double value is invalid");
            }
            case STRING -> validateString(stringValue == null ? "" : stringValue);
            case BOOL_ARRAY -> validateArray(boolArray == null ? 0 : boolArray.length);
            case INT_ARRAY -> validateArray(intArray == null ? 0 : intArray.length);
            case FLOAT_ARRAY -> {
                validateArray(floatArray == null ? 0 : floatArray.length);
                if (floatArray != null) {
                    for (float value : floatArray) {
                        if (!Float.isFinite(value)) throw invalid("error.videoplayer.meta.float_array_invalid", "float[] contains an invalid value");
                    }
                }
            }
            case STRING_ARRAY -> {
                validateArray(stringArray == null ? 0 : stringArray.length);
                validateStringArray(stringArray);
            }
        }
    }

    public static MetaValue parse(MetaType type, String text) {
        String value = text == null ? "" : text.trim();
        return switch (type) {
            case BOOL -> ofBool(parseBool(value));
            case INT -> ofInt(Integer.parseInt(value));
            case LONG -> ofLong(Long.parseLong(value));
            case FLOAT -> ofFloat(Float.parseFloat(value));
            case DOUBLE -> ofDouble(Double.parseDouble(value));
            case STRING -> ofString(text == null ? "" : text);
            case BOOL_ARRAY -> ofBoolArray(parseBoolArray(value));
            case INT_ARRAY -> ofIntArray(parseIntArray(value));
            case FLOAT_ARRAY -> ofFloatArray(parseFloatArray(value));
            case STRING_ARRAY -> ofStringArray(parseStringArray(text == null ? "" : text));
        };
    }

    private static boolean parseBool(String text) {
        String normalized = text.trim().toLowerCase(Locale.ROOT);
        if (normalized.equals("true") || normalized.equals("1") || normalized.equals("yes") || normalized.equals("on")) return true;
        if (normalized.equals("false") || normalized.equals("0") || normalized.equals("no") || normalized.equals("off")) return false;
        throw invalid("error.videoplayer.meta.bool_expected", "bool value must be true or false");
    }

    private static boolean[] parseBoolArray(String text) {
        String[] parts = splitArray(text);
        boolean[] result = new boolean[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = parseBool(parts[i]);
        }
        return result;
    }

    private static int[] parseIntArray(String text) {
        String[] parts = splitArray(text);
        int[] result = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = Integer.parseInt(parts[i].trim());
        }
        return result;
    }

    private static float[] parseFloatArray(String text) {
        String[] parts = splitArray(text);
        float[] result = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = Float.parseFloat(parts[i].trim());
        }
        return result;
    }

    private static String[] parseStringArray(String text) {
        String[] parts = splitArray(text);
        ArrayList<String> result = new ArrayList<>(parts.length);
        for (String part : parts) {
            result.add(part.trim());
        }
        return result.toArray(String[]::new);
    }

    private static String[] splitArray(String text) {
        if (text == null || text.trim().isEmpty()) return new String[0];
        return text.split("\\s*,\\s*");
    }

    private static void validateString(String value) {
        int length = value.getBytes(StandardCharsets.UTF_8).length;
        if (length > MAX_STRING_BYTES) {
            throw invalid("error.videoplayer.meta.string_too_long", "string must not exceed %s bytes", MAX_STRING_BYTES);
        }
    }

    private static void validateStringArray(String[] values) {
        if (values == null) return;
        int total = 0;
        for (String value : values) {
            String safeValue = value == null ? "" : value;
            validateString(safeValue);
            total += safeValue.getBytes(StandardCharsets.UTF_8).length;
            if (total > MAX_STRING_BYTES) {
                throw invalid("error.videoplayer.meta.string_array_too_long", "string[] total length must not exceed %s bytes", MAX_STRING_BYTES);
            }
        }
    }

    private static void validateArray(int length) {
        if (length > MAX_ARRAY_LENGTH) {
            throw invalid("error.videoplayer.meta.array_too_long", "array length must not exceed %s", MAX_ARRAY_LENGTH);
        }
    }

    private static TranslatableIllegalArgumentException invalid(String key, String fallback, Object... args) {
        return new TranslatableIllegalArgumentException(VpTranslation.of(key, fallback, args));
    }

    private static String join(boolean[] values) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) builder.append(", ");
            builder.append(values[i]);
        }
        return builder.toString();
    }

    private static String join(int[] values) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) builder.append(", ");
            builder.append(values[i]);
        }
        return builder.toString();
    }

    private static String join(float[] values) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) builder.append(", ");
            builder.append(formatFloat(values[i]));
        }
        return builder.toString();
    }

    private static String formatFloat(float value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    private static String formatDouble(double value) {
        return String.format(Locale.ROOT, "%.6f", value);
    }
}
