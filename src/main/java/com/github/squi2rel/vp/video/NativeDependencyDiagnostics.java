package com.github.squi2rel.vp.video;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class NativeDependencyDiagnostics {
    private static final Pattern MISSING_LIBRARY_AFTER_LOAD = Pattern.compile("error while loading shared libraries:\\s*([^:;\\s]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern MISSING_LIBRARY_BEFORE_OPEN = Pattern.compile("([^:;\\s]+):\\s*cannot open shared object file", Pattern.CASE_INSENSITIVE);
    private static final Pattern DLL_LIBRARY = Pattern.compile("(?:Can't find dependent libraries|cannot find)[:\\s]+([^\\s]+)", Pattern.CASE_INSENSITIVE);

    private NativeDependencyDiagnostics() {
    }

    public static String describe(Throwable error) {
        if (error == null) return "";
        Set<String> missing = new LinkedHashSet<>();
        ArrayList<String> messages = new ArrayList<>();
        Deque<Throwable> pending = new ArrayDeque<>();
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        pending.add(error);
        while (!pending.isEmpty() && visited.size() < 24) {
            Throwable current = pending.removeFirst();
            if (!visited.add(current)) continue;
            String message = current.getMessage();
            if (message != null && !message.isBlank()) {
                messages.add(message.trim());
                collect(missing, MISSING_LIBRARY_AFTER_LOAD, message);
                collect(missing, MISSING_LIBRARY_BEFORE_OPEN, message);
                collect(missing, DLL_LIBRARY, message);
            }
            Throwable cause = current.getCause();
            if (cause != null && cause != current) pending.addLast(cause);
            for (Throwable suppressed : current.getSuppressed()) {
                if (suppressed != null && suppressed != current) pending.addLast(suppressed);
            }
        }
        if (!missing.isEmpty()) {
            return "missing native dependencies: " + String.join(", ", missing);
        }
        String first = messages.isEmpty() ? error.getClass().getSimpleName() : messages.getFirst();
        return trim(first, 240);
    }

    public static String recommendation(Throwable error, String os) {
        String detail = describe(error);
        if (detail.isBlank()) return "";
        String normalized = os == null ? "" : os.trim().toLowerCase(Locale.ROOT);
        if (normalized.equals("linux")) {
            return detail + "; install the host libmpv runtime dependencies; on Ubuntu/Debian Docker use apt-get install --no-install-recommends libmpv2";
        }
        if (normalized.equals("macos")) {
            return detail + "; install the required system frameworks or select VLC";
        }
        if (normalized.equals("windows")) {
            return detail + "; install the Visual C++ runtime or select VLC";
        }
        return detail;
    }

    private static void collect(Set<String> values, Pattern pattern, String message) {
        Matcher matcher = pattern.matcher(message);
        while (matcher.find() && values.size() < 12) {
            String value = matcher.group(1);
            if (value != null && !value.isBlank()) values.add(value.trim());
        }
    }

    private static String trim(String value, int max) {
        if (value.length() <= max) return value;
        return value.substring(0, Math.max(0, max - 3)) + "...";
    }
}
