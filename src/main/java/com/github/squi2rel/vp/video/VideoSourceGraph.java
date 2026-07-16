package com.github.squi2rel.vp.video;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class VideoSourceGraph {
    private VideoSourceGraph() {
    }

    public static boolean isAcyclic(List<VideoScreen> screens) {
        if (screens == null) return true;
        Map<String, String> sources = new HashMap<>();
        for (VideoScreen screen : screens) {
            if (screen == null || screen.name == null) continue;
            String source = screen.source == null ? "" : screen.source;
            if (!source.isEmpty()) sources.put(screen.name, source);
        }
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        for (String name : sources.keySet()) {
            if (hasCycle(name, sources, visiting, visited)) return false;
        }
        return true;
    }

    public static boolean wouldCreateCycle(VideoArea area, String screenName, String source) {
        if (area == null || screenName == null || source == null || source.isEmpty()) return false;
        Map<String, String> sources = new HashMap<>();
        for (VideoScreen screen : area.screens) {
            if (screen == null || screen.name == null || screen.name.equals(screenName)) continue;
            String existing = screen.source == null ? "" : screen.source;
            if (!existing.isEmpty()) sources.put(screen.name, existing);
        }
        sources.put(screenName, source);
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        return hasCycle(screenName, sources, visiting, visited);
    }

    private static boolean hasCycle(String name, Map<String, String> sources, Set<String> visiting, Set<String> visited) {
        if (!sources.containsKey(name)) return false;
        if (!visiting.add(name)) return true;
        if (visited.contains(name)) {
            visiting.remove(name);
            return false;
        }
        String source = sources.get(name);
        boolean cycle = source != null && hasCycle(source, sources, visiting, visited);
        visiting.remove(name);
        visited.add(name);
        return cycle;
    }
}
