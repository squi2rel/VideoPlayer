package com.github.squi2rel.vp.video;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

public final class ScreenVolumeStore {
    private static final int MAX_ENTRIES = 4096;
    private static final Gson GSON = new Gson();
    private final Path path;
    private final LinkedHashMap<Key, Integer> volumes = new LinkedHashMap<>();
    private boolean dirty;

    public ScreenVolumeStore(Path path) {
        this.path = path.toAbsolutePath().normalize();
    }

    public synchronized void load() throws IOException {
        if (Files.notExists(path)) return;
        LinkedHashMap<Key, Integer> loaded = new LinkedHashMap<>();
        try {
            JsonElement root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
            if (!root.isJsonObject() || !root.getAsJsonObject().has("entries")
                    || !root.getAsJsonObject().get("entries").isJsonArray()) {
                throw new IllegalArgumentException("Expected an object containing an entries array");
            }
            for (JsonElement element : root.getAsJsonObject().getAsJsonArray("entries")) {
                try {
                    JsonObject entry = element.getAsJsonObject();
                    Key key = new Key(string(entry, "server"), string(entry, "dimension"),
                            string(entry, "areaName"), string(entry, "screenName"));
                    JsonElement value = entry.get("volume");
                    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) continue;
                    int volume = value.getAsBigDecimal().intValueExact();
                    if (volume < 0 || volume > 100) continue;
                    loaded.remove(key);
                    loaded.put(key, volume);
                    trim(loaded);
                } catch (IllegalArgumentException | IllegalStateException | ArithmeticException ignored) {
                }
            }
        } catch (RuntimeException e) {
            throw new IOException("Invalid screen volume configuration", e);
        }
        volumes.clear();
        volumes.putAll(loaded);
        dirty = false;
    }

    public synchronized Integer get(Key key) {
        return volumes.get(key);
    }

    public synchronized void put(Key key, int volume) {
        int clamped = Math.clamp(volume, 0, 100);
        Integer previous = volumes.get(key);
        if (previous != null && previous == clamped) return;
        volumes.remove(key);
        volumes.put(key, clamped);
        trim(volumes);
        dirty = true;
    }

    public synchronized void remove(Key key) {
        if (volumes.remove(key) != null) dirty = true;
    }

    public synchronized void removeScreen(String server, String dimension, String areaName, String screenName) {
        if (volumes.keySet().removeIf(key -> key.server().equals(server)
                && key.dimension().equals(dimension)
                && key.areaName().equals(areaName) && key.screenName().equals(screenName))) dirty = true;
    }

    public synchronized void removeArea(String server, String dimension, String areaName) {
        if (volumes.keySet().removeIf(key -> key.server().equals(server)
                && key.dimension().equals(dimension)
                && key.areaName().equals(areaName))) dirty = true;
    }

    public synchronized void save() throws IOException {
        if (!dirty) return;
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), path.getFileName() + ".", ".tmp");
        try {
            List<Entry> entries = volumes.entrySet().stream()
                    .map(entry -> new Entry(entry.getKey().server(), entry.getKey().dimension(),
                            entry.getKey().areaName(), entry.getKey().screenName(), entry.getValue()))
                    .toList();
            Files.writeString(temporary, GSON.toJson(new Data(entries)), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
            dirty = false;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String string(JsonObject entry, String name) {
        JsonElement value = entry.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("Expected a string for " + name);
        }
        return value.getAsString();
    }

    private static void trim(LinkedHashMap<Key, Integer> entries) {
        while (entries.size() > MAX_ENTRIES) entries.pollFirstEntry();
    }

    public record Key(String server, String dimension, String areaName, String screenName) {
        public Key {
            Objects.requireNonNull(server);
            Objects.requireNonNull(dimension);
            Objects.requireNonNull(areaName);
            Objects.requireNonNull(screenName);
            if (server.isBlank()) throw new IllegalArgumentException("Server identity must not be blank");
        }
    }

    private record Entry(String server, String dimension, String areaName, String screenName, int volume) {
    }

    private record Data(List<Entry> entries) {
    }
}
