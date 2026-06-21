package com.github.squi2rel.vp.i18n;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public record VpTranslation(String key, String fallback, List<String> args) {
    public static final VpTranslation EMPTY = new VpTranslation("", "", List.of());

    public VpTranslation {
        key = key == null ? "" : key;
        fallback = fallback == null ? "" : fallback;
        args = args == null ? List.of() : List.copyOf(args);
    }

    public static VpTranslation of(String key, String fallback, Object... args) {
        return new VpTranslation(key, fallback, Arrays.stream(args == null ? new Object[0] : args)
                .map(VpTranslation::stringify)
                .toList());
    }

    public static VpTranslation literal(String text) {
        return new VpTranslation("", text, List.of());
    }

    public Object[] argumentArray() {
        return args.toArray(String[]::new);
    }

    public boolean isEmpty() {
        return key.isBlank() && fallback.isBlank();
    }

    public boolean isLiteral() {
        return key.isBlank();
    }

    private static String stringify(Object value) {
        return Objects.toString(value, "");
    }
}
