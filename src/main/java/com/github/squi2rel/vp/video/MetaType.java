package com.github.squi2rel.vp.video;

public enum MetaType {
    BOOL,
    INT,
    LONG,
    FLOAT,
    DOUBLE,
    STRING,
    BOOL_ARRAY,
    INT_ARRAY,
    FLOAT_ARRAY,
    STRING_ARRAY;

    public static MetaType fromId(int id) {
        MetaType[] values = values();
        if (id < 0 || id >= values.length) return null;
        return values[id];
    }

    public String label() {
        return switch (this) {
            case BOOL -> "bool";
            case INT -> "int";
            case LONG -> "long";
            case FLOAT -> "float";
            case DOUBLE -> "double";
            case STRING -> "string";
            case BOOL_ARRAY -> "bool[]";
            case INT_ARRAY -> "int[]";
            case FLOAT_ARRAY -> "float[]";
            case STRING_ARRAY -> "string[]";
        };
    }

    public static MetaType fromLabel(String label) {
        if (label == null) return null;
        String normalized = label.trim().toLowerCase();
        for (MetaType type : values()) {
            if (type.label().equals(normalized) || type.name().equalsIgnoreCase(normalized)) {
                return type;
            }
        }
        return null;
    }
}
