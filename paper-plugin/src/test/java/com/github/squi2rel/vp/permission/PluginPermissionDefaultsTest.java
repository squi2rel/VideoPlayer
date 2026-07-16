package com.github.squi2rel.vp.permission;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PluginPermissionDefaultsTest {
    @Test
    void permissionDefaultsMatchPublicAndAdministrativeActions() throws IOException {
        Map<String, String> defaults = readDefaults(Path.of("src/main/resources/plugin.yml"));

        assertEquals("op", defaults.get("videoplayer.admin"));
        assertEquals("true", defaults.get("videoplayer.action.play"));
        assertEquals("true", defaults.get("videoplayer.action.seek"));
        assertEquals("true", defaults.get("videoplayer.action.sync"));
        assertEquals("true", defaults.get("videoplayer.action.vote_skip"));
        assertEquals("true", defaults.get("videoplayer.action.auto_sync"));
        assertEquals("true", defaults.get("videoplayer.action.open_menu"));
        assertEquals("true", defaults.get("videoplayer.action.force_skip"));
        assertEquals("true", defaults.get("videoplayer.action.set_skip_percent"));
        assertEquals("true", defaults.get("videoplayer.action.create_area"));
        assertEquals("true", defaults.get("videoplayer.action.remove_area"));
        assertEquals("true", defaults.get("videoplayer.action.create_screen"));
        assertEquals("true", defaults.get("videoplayer.action.remove_screen"));
        assertEquals("true", defaults.get("videoplayer.action.update_screen"));
        assertEquals("true", defaults.get("videoplayer.action.set_uv"));
        assertEquals("true", defaults.get("videoplayer.action.set_scale"));
        assertEquals("true", defaults.get("videoplayer.action.set_metadata"));
        assertEquals("true", defaults.get("videoplayer.action.set_idle_play"));
        assertEquals(VideoPermissionAction.values().length + 1, defaults.size());
    }

    private static Map<String, String> readDefaults(Path path) throws IOException {
        HashMap<String, String> defaults = new HashMap<>();
        String permission = null;
        for (String line : Files.readAllLines(path)) {
            if (line.startsWith("  videoplayer.") && line.endsWith(":")) {
                permission = line.trim().substring(0, line.trim().length() - 1);
            } else if (permission != null && line.startsWith("    default: ")) {
                defaults.put(permission, line.substring("    default: ".length()).trim());
            }
        }
        return defaults;
    }
}
