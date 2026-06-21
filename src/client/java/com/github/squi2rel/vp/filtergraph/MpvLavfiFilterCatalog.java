package com.github.squi2rel.vp.filtergraph;

import com.github.squi2rel.vp.VideoPlayerMain;
import com.github.squi2rel.vp.video.MpvFilterInfo;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class MpvLavfiFilterCatalog {
    private static final long FAILED_RETRY_DELAY_MS = 5_000L;
    private static volatile Catalog cached;
    private static volatile long cachedAt;

    private MpvLavfiFilterCatalog() {
    }

    public static Catalog get() {
        Catalog local = cached;
        long now = System.currentTimeMillis();
        if (local != null && (local.usable() || now - cachedAt < FAILED_RETRY_DELAY_MS)) {
            return local;
        }
        synchronized (MpvLavfiFilterCatalog.class) {
            if (cached != null && (cached.usable() || now - cachedAt < FAILED_RETRY_DELAY_MS)) {
                return cached;
            }
            cached = load();
            cachedAt = now;
            return cached;
        }
    }

    public static void reset() {
        cached = null;
        cachedAt = 0L;
    }

    private static Catalog load() {
        ArrayList<String> errors = new ArrayList<>();
        try {
            LinkedHashMap<String, Filter> filters = parseFilters(queryByType("graph", errors), true);
            if (filters.isEmpty()) {
                filters = parseFilters(queryByType("lavfi", errors), true);
            }
            if (filters.isEmpty()) {
                filters = parseFilters(queryAll(errors), false);
            }
            if (filters.isEmpty()) {
                filters = parseTypeFilters(queryByType("vf", errors), queryByType("af", errors));
            }
            addSupplementalGraphFilters(filters);
            if (filters.isEmpty()) {
                String suffix = errors.isEmpty() ? "" : ": " + String.join("; ", errors);
                return new Catalog(false, "Native MPV filter info API returned no lavfi filters" + suffix, filters);
            }
            return new Catalog(true, "", filters);
        } catch (Throwable t) {
            VideoPlayerMain.LOGGER.warn("Failed to query MPV lavfi filter metadata through native API", t);
            return new Catalog(false, t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage(), new LinkedHashMap<>());
        }
    }

    private static JsonObject queryAll(List<String> errors) {
        try {
            return MpvFilterInfo.all();
        } catch (Throwable t) {
            errors.add("all=" + errorMessage(t));
            return null;
        }
    }

    private static JsonObject queryByType(String type, List<String> errors) {
        try {
            return MpvFilterInfo.get(type, null);
        } catch (Throwable t) {
            errors.add(type + "=" + errorMessage(t));
            return null;
        }
    }

    private static String errorMessage(Throwable t) {
        return t == null ? "" : t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    private static LinkedHashMap<String, Filter> parseFilters(JsonObject root, boolean graphRequest) {
        LinkedHashMap<String, Filter> filters = new LinkedHashMap<>();
        if (root == null) return filters;
        addFilters(filters, array(root, "lavfi"), true);
        if (filters.isEmpty()) {
            addLavfiBridgeFilters(filters, array(root, "vf"), !graphRequest);
            addLavfiBridgeFilters(filters, array(root, "af"), !graphRequest);
        }
        return filters;
    }

    private static LinkedHashMap<String, Filter> parseTypeFilters(JsonObject videoRoot, JsonObject audioRoot) {
        LinkedHashMap<String, Filter> filters = new LinkedHashMap<>();
        mergeFilters(filters, parseFilters(videoRoot, false));
        mergeFilters(filters, parseFilters(audioRoot, false));
        return filters;
    }

    private static void mergeFilters(LinkedHashMap<String, Filter> filters, LinkedHashMap<String, Filter> additions) {
        for (Map.Entry<String, Filter> entry : additions.entrySet()) {
            filters.putIfAbsent(entry.getKey(), entry.getValue());
        }
    }

    private static void addSupplementalGraphFilters(LinkedHashMap<String, Filter> filters) {
        List<Pad> audioIn = List.of(new Pad("in", MediaType.AUDIO));
        List<Pad> audioOut = List.of(new Pad("out", MediaType.AUDIO));
        List<Pad> videoOut = List.of(new Pad("out", MediaType.VIDEO));
        List<Pad> noInputs = List.of();

        addSupplemental(filters, "avectorscope", "Convert input audio to a vectorscope video.", audioIn, videoOut);
        addSupplemental(filters, "showcqt", "Convert input audio to a CQT spectrum video.", audioIn, videoOut);
        addSupplemental(filters, "showfreqs", "Convert input audio to a frequency graph video.", audioIn, videoOut);
        addSupplemental(filters, "showspectrum", "Convert input audio to a spectrum video.", audioIn, videoOut);
        addSupplemental(filters, "showspectrumpic", "Convert input audio to a spectrum image.", audioIn, videoOut);
        addSupplemental(filters, "showvolume", "Convert input audio volume to video.", audioIn, videoOut);
        addSupplemental(filters, "showwaves", "Convert input audio to waveform video.", audioIn, videoOut);
        addSupplemental(filters, "showwavespic", "Convert input audio to a waveform image.", audioIn, videoOut);

        addSupplemental(filters, "allrgb", "Generate all RGB colors.", noInputs, videoOut);
        addSupplemental(filters, "allyuv", "Generate all YUV colors.", noInputs, videoOut);
        addSupplemental(filters, "color", "Generate a solid color video.", noInputs, videoOut);
        addSupplemental(filters, "haldclutsrc", "Generate a Hald CLUT video.", noInputs, videoOut);
        addSupplemental(filters, "life", "Generate a Conway life video.", noInputs, videoOut);
        addSupplemental(filters, "mandelbrot", "Generate a Mandelbrot video.", noInputs, videoOut);
        addSupplemental(filters, "nullsrc", "Generate null video frames.", noInputs, videoOut);
        addSupplemental(filters, "rgbtestsrc", "Generate an RGB test pattern.", noInputs, videoOut);
        addSupplemental(filters, "smptebars", "Generate SMPTE bars.", noInputs, videoOut);
        addSupplemental(filters, "smptehdbars", "Generate HD SMPTE bars.", noInputs, videoOut);
        addSupplemental(filters, "testsrc", "Generate a test pattern.", noInputs, videoOut);
        addSupplemental(filters, "testsrc2", "Generate a test pattern.", noInputs, videoOut);
        addSupplemental(filters, "yuvtestsrc", "Generate a YUV test pattern.", noInputs, videoOut);

        addSupplemental(filters, "anoisesrc", "Generate audio noise.", noInputs, audioOut);
        addSupplemental(filters, "anullsrc", "Generate null audio.", noInputs, audioOut);
        addSupplemental(filters, "sine", "Generate sine wave audio.", noInputs, audioOut);
    }

    private static void addSupplemental(LinkedHashMap<String, Filter> filters, String name, String description,
                                        List<Pad> inputs, List<Pad> outputs) {
        filters.putIfAbsent(name, new Filter(
                name,
                "lavfi",
                description,
                false,
                false,
                List.copyOf(inputs),
                List.copyOf(outputs),
                List.of()
        ));
    }

    private static JsonArray array(JsonObject root, String key) {
        return root != null && root.has(key) && root.get(key).isJsonArray()
                ? root.getAsJsonArray(key)
                : new JsonArray();
    }

    private static void addFilters(LinkedHashMap<String, Filter> filters, JsonArray array, boolean graphFilters) {
        for (JsonElement element : array) {
            if (!element.isJsonObject()) continue;
            Filter filter = parseFilter(element.getAsJsonObject(), graphFilters, false);
            if (!filter.name().isBlank()) filters.putIfAbsent(filter.name(), filter);
        }
    }

    private static void addLavfiBridgeFilters(LinkedHashMap<String, Filter> filters, JsonArray array, boolean inferPads) {
        for (JsonElement element : array) {
            if (!element.isJsonObject()) continue;
            JsonObject json = element.getAsJsonObject();
            if (!"lavfi".equals(string(json, "source"))) continue;
            Filter filter = parseFilter(json, false, inferPads);
            if (!filter.name().isBlank()) filters.putIfAbsent(filter.name(), filter);
        }
    }

    private static Filter parseFilter(JsonObject json, boolean graphFilter, boolean inferPads) {
        String name = graphFilter ? string(json, "name") : bridgeFilterName(json);
        String type = string(json, "type");
        String description = string(json, "description");
        List<Option> options = parseOptions(json.getAsJsonArray("options"));
        List<Pad> inputs = parsePads(json.getAsJsonArray("inputs"));
        List<Pad> outputs = parsePads(json.getAsJsonArray("outputs"));
        boolean dynamicInputs = bool(json, "dynamic-inputs");
        boolean dynamicOutputs = bool(json, "dynamic-outputs");
        if (inferPads && inputs.isEmpty() && outputs.isEmpty()) {
            MediaType mediaType = mediaTypeFromFilterType(type);
            if (mediaType != MediaType.OTHER) {
                inputs = List.of(new Pad("in", mediaType));
                outputs = List.of(new Pad("out", mediaType));
                dynamicInputs = hasOption(options, "inputs");
                dynamicOutputs = hasOption(options, "outputs");
            }
        }
        return new Filter(
                name,
                type,
                description,
                dynamicInputs,
                dynamicOutputs,
                inputs,
                outputs,
                options
        );
    }

    private static String bridgeFilterName(JsonObject json) {
        String name = string(json, "filter-name");
        if (name.isBlank()) name = string(json, "name");
        if (name.startsWith("lavfi-")) name = name.substring("lavfi-".length());
        return name;
    }

    private static List<Pad> parsePads(JsonArray array) {
        if (array == null) return List.of();
        ArrayList<Pad> pads = new ArrayList<>();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) continue;
            JsonObject json = element.getAsJsonObject();
            pads.add(new Pad(string(json, "name"), MediaType.from(string(json, "type"))));
        }
        return List.copyOf(pads);
    }

    private static List<Option> parseOptions(JsonArray array) {
        if (array == null) return List.of();
        ArrayList<Option> options = new ArrayList<>();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) continue;
            JsonObject json = element.getAsJsonObject();
            DefaultValue defaultValue = defaultValue(json, string(json, "type"));
            options.add(new Option(
                    string(json, "name"),
                    string(json, "type"),
                    string(json, "help"),
                    defaultValue.value(),
                    defaultValue.present()
            ));
        }
        return List.copyOf(options);
    }

    private static DefaultValue defaultValue(JsonObject json, String type) {
        if (json == null || !json.has("default-value") || json.get("default-value").isJsonNull()) {
            return new DefaultValue(defaultForType(type), false);
        }
        JsonElement value = json.get("default-value");
        try {
            if (value.isJsonObject() && "rational".equals(type)) {
                JsonObject rational = value.getAsJsonObject();
                return new DefaultValue(string(rational, "num") + "/" + string(rational, "den"), true);
            }
            return new DefaultValue(value.getAsString(), true);
        } catch (RuntimeException e) {
            return new DefaultValue(defaultForType(type), false);
        }
    }

    private static String defaultForType(String type) {
        return switch (type == null ? "" : type.trim().toLowerCase(Locale.ROOT)) {
            case "bool" -> "false";
            case "int", "int64", "uint64", "duration", "flags" -> "0";
            case "double", "float" -> "0.0";
            default -> "";
        };
    }

    private static boolean hasOption(List<Option> options, String name) {
        for (Option option : options) {
            if (option.name().equals(name)) return true;
        }
        return false;
    }

    private static MediaType mediaTypeFromFilterType(String type) {
        return switch (type == null ? "" : type.trim().toLowerCase(Locale.ROOT)) {
            case "vf", "video" -> MediaType.VIDEO;
            case "af", "audio" -> MediaType.AUDIO;
            default -> MediaType.OTHER;
        };
    }

    private static String string(JsonObject json, String key) {
        try {
            return json != null && json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : "";
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static boolean bool(JsonObject json, String key) {
        try {
            return json != null && json.has(key) && json.get(key).getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }

    public record Catalog(boolean available, String error, Map<String, Filter> filters) {
        public boolean usable() {
            return available && !filters.isEmpty();
        }

        public Optional<Filter> find(String name) {
            if (name == null || name.isBlank()) return Optional.empty();
            Filter exact = filters.get(name.trim());
            if (exact != null) return Optional.of(exact);
            String lower = name.trim().toLowerCase(Locale.ROOT);
            return filters.values().stream().filter(filter -> filter.name().toLowerCase(Locale.ROOT).equals(lower)).findFirst();
        }

        public List<Filter> sorted() {
            return filters.values().stream().sorted(Comparator.comparing(Filter::name)).toList();
        }
    }

    public record Filter(String name, String type, String description, boolean dynamicInputs, boolean dynamicOutputs,
                         List<Pad> inputs, List<Pad> outputs, List<Option> options) {
    }

    public record Pad(String name, MediaType type) {
    }

    public record Option(String name, String type, String help, String defaultValue, boolean defaultPresent) {
    }

    private record DefaultValue(String value, boolean present) {
    }

    public enum MediaType {
        VIDEO,
        AUDIO,
        OTHER;

        static MediaType from(String value) {
            return switch (value == null ? "" : value.trim().toLowerCase(Locale.ROOT)) {
                case "video" -> VIDEO;
                case "audio" -> AUDIO;
                default -> OTHER;
            };
        }
    }
}
