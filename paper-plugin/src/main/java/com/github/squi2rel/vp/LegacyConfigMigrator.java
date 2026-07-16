package com.github.squi2rel.vp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

final class LegacyConfigMigrator {
    private static final int DATA_VERSION = ServerConfig.CURRENT_DATA_VERSION;
    private static final int MAX_META_ENTRIES = 255;
    private static final Pattern VALID_META_KEY = Pattern.compile("[A-Za-z0-9_.:-]{1,64}");
    private static final Set<String> BOOLEAN_META_KEYS = Set.of(
            "mute", "interactable", "autoSync", "debug", "danmaku"
    );

    private LegacyConfigMigrator() {
    }

    static Result migrate(JsonElement input) {
        if (input == null || input.isJsonNull()) throw new JsonParseException("VideoPlayer config is empty");
        boolean legacyRoot = input.isJsonArray();
        if (!legacyRoot && !input.isJsonObject()) {
            throw new JsonParseException("VideoPlayer config root must be an object or an area array");
        }

        JsonObject root = legacyRoot ? new JsonObject() : input.getAsJsonObject().deepCopy();
        if (legacyRoot) root.add("areas", input.getAsJsonArray().deepCopy());
        int version = root.has("dataVersion") && root.get("dataVersion").isJsonPrimitive()
                ? root.get("dataVersion").getAsInt()
                : 0;
        JsonElement areasElement = root.get("areas");
        if (areasElement == null || areasElement.isJsonNull()) {
            root.add("areas", new JsonArray());
            root.addProperty("dataVersion", DATA_VERSION);
            return new Result(root, legacyRoot || version < DATA_VERSION, 0, 0);
        }
        if (!areasElement.isJsonArray()) throw new JsonParseException("VideoPlayer areas must be an array");

        boolean changed = legacyRoot || version < DATA_VERSION;
        int areaCount = 0;
        int screenCount = 0;
        for (JsonElement areaElement : areasElement.getAsJsonArray()) {
            if (!areaElement.isJsonObject()) throw new JsonParseException("VideoPlayer area must be an object");
            areaCount++;
            JsonObject area = areaElement.getAsJsonObject();
            JsonElement screensElement = area.get("screens");
            if (screensElement == null || screensElement.isJsonNull()) {
                area.add("screens", new JsonArray());
                changed = true;
                continue;
            }
            if (!screensElement.isJsonArray()) throw new JsonParseException("VideoPlayer screens must be an array");
            for (JsonElement screenElement : screensElement.getAsJsonArray()) {
                if (!screenElement.isJsonObject()) throw new JsonParseException("VideoPlayer screen must be an object");
                screenCount++;
                changed |= migrateScreen(screenElement.getAsJsonObject());
            }
        }
        if (changed) root.addProperty("dataVersion", DATA_VERSION);
        return new Result(root, changed, areaCount, screenCount);
    }

    private static boolean migrateScreen(JsonObject screen) {
        boolean changed = false;
        boolean hasLegacyVertex = screen.has("p1") || screen.has("p2") || screen.has("p3") || screen.has("p4");
        if (hasLegacyVertex) {
            if (!validVertices(screen.get("vertices"))) {
                JsonArray vertices = new JsonArray();
                for (String key : new String[]{"p1", "p4", "p3", "p2"}) {
                    JsonElement vertex = screen.get(key);
                    if (vertex == null || !vertex.isJsonObject()) {
                        throw new JsonParseException("Legacy screen must contain p1, p2, p3, and p4");
                    }
                    vertices.add(vertex.deepCopy());
                }
                screen.add("vertices", vertices);
            }
            for (String key : new String[]{"p1", "p2", "p3", "p4"}) screen.remove(key);
            changed = true;
        }

        if (screen.has("idleInfo")) {
            if (!screen.has("idlePlayUrls")) {
                JsonArray urls = new JsonArray();
                JsonElement idleElement = screen.get("idleInfo");
                if (idleElement != null && idleElement.isJsonObject()) {
                    JsonObject idle = idleElement.getAsJsonObject();
                    String url = nonBlankString(idle.get("rawPath"));
                    if (url == null) url = nonBlankString(idle.get("path"));
                    if (url != null) urls.add(url);
                }
                screen.add("idlePlayUrls", urls);
            }
            screen.remove("idleInfo");
            changed = true;
        }

        if (screen.has("meta")) {
            JsonObject metadata = objectOrNew(screen.get("metadata"));
            JsonObject values = objectOrNew(metadata.get("values"));
            if (values.size() > MAX_META_ENTRIES) {
                throw new JsonParseException("Screen metadata exceeds the 2.0 limit of " + MAX_META_ENTRIES);
            }
            JsonElement legacyMeta = screen.get("meta");
            if (legacyMeta != null && legacyMeta.isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry : legacyMeta.getAsJsonObject().entrySet()) {
                    String key = entry.getKey();
                    JsonElement value = entry.getValue();
                    if (values.has(key) || !VALID_META_KEY.matcher(key).matches()
                            || value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
                        continue;
                    }
                    if (values.size() >= MAX_META_ENTRIES) {
                        throw new JsonParseException("Legacy screen metadata exceeds the 2.0 limit of " + MAX_META_ENTRIES);
                    }
                    values.add(key, convertMeta(key, value.getAsInt()));
                }
            }
            metadata.add("values", values);
            screen.add("metadata", metadata);
            screen.remove("meta");
            changed = true;
        }

        if (changed) addLegacyDefaults(screen);
        return changed;
    }

    private static JsonObject convertMeta(String key, int value) {
        JsonObject converted = new JsonObject();
        if (BOOLEAN_META_KEYS.contains(key)) {
            converted.addProperty("type", "BOOL");
            converted.addProperty("boolValue", value != 0);
        } else if (key.equals("aspect")) {
            float aspect = Float.intBitsToFloat(value);
            if (!Float.isFinite(aspect)) throw new JsonParseException("Legacy aspect metadata is invalid");
            converted.addProperty("type", "FLOAT");
            converted.addProperty("floatValue", aspect);
        } else {
            converted.addProperty("type", "INT");
            converted.addProperty("intValue", value);
        }
        return converted;
    }

    private static void addLegacyDefaults(JsonObject screen) {
        addString(screen, "source", "");
        addString(screen, "surface", "FLAT");
        addBoolean(screen, "stereo3d", false);
        addBoolean(screen, "spherePreset", false);
        if (!screen.has("sphereCenter") || screen.get("sphereCenter").isJsonNull()) {
            JsonObject center = new JsonObject();
            center.addProperty("x", 0);
            center.addProperty("y", 0);
            center.addProperty("z", 0);
            screen.add("sphereCenter", center);
        }
        addNumber(screen, "sphereRadius", 10);
        addNumber(screen, "sphereLat", 32);
        addNumber(screen, "sphereLon", 32);
        addNumber(screen, "sphereRotX", 0);
        addNumber(screen, "sphereRotY", 0);
        addNumber(screen, "sphereRotZ", 0);
        addBoolean(screen, "sphereSkybox", false);
        if (!screen.has("idlePlayUrls") || screen.get("idlePlayUrls").isJsonNull()) screen.add("idlePlayUrls", new JsonArray());
        addBoolean(screen, "idlePlayRandom", false);
        if (!screen.has("metadata") || screen.get("metadata").isJsonNull()) {
            JsonObject metadata = new JsonObject();
            metadata.add("values", new JsonObject());
            screen.add("metadata", metadata);
        }
    }

    private static boolean validVertices(JsonElement value) {
        if (value == null || !value.isJsonArray() || value.getAsJsonArray().size() == 0) return false;
        for (JsonElement vertex : value.getAsJsonArray()) {
            if (!vertex.isJsonObject()) return false;
        }
        return true;
    }

    private static JsonObject objectOrNew(JsonElement value) {
        return value != null && value.isJsonObject() ? value.getAsJsonObject().deepCopy() : new JsonObject();
    }

    private static String nonBlankString(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return null;
        String string = value.getAsString().trim();
        return string.isEmpty() ? null : string;
    }

    private static void addString(JsonObject object, String key, String value) {
        if (!object.has(key) || object.get(key).isJsonNull()) object.addProperty(key, value);
    }

    private static void addBoolean(JsonObject object, String key, boolean value) {
        if (!object.has(key) || object.get(key).isJsonNull()) object.addProperty(key, value);
    }

    private static void addNumber(JsonObject object, String key, Number value) {
        if (!object.has(key) || object.get(key).isJsonNull()) object.addProperty(key, value);
    }

    record Result(JsonObject root, boolean migrated, int areas, int screens) {
    }
}
