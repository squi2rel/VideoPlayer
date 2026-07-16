package com.github.squi2rel.vp.video;

import com.github.squi2rel.vp.network.ByteBufUtils;
import io.netty.buffer.ByteBuf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

public class VideoArea {
    public static final int MAX_AREAS_PER_WORLD = 256;
    public static final int MAX_SCREENS = 64;
    public static final float MAX_AXIS_LENGTH = 512;
    public Vector3f min = new Vector3f();
    public Vector3f max = new Vector3f();
    public String name;
    public String dim;
    private transient HashSet<UUID> players;
    public ArrayList<VideoScreen> screens = new ArrayList<>();

    public VideoArea(Vector3f v1, Vector3f v2, String name, String dim) {
        min.set(Math.min(v1.x, v2.x), Math.min(v1.y, v2.y), Math.min(v1.z, v2.z));
        max.set(Math.max(v1.x, v2.x), Math.max(v1.y, v2.y), Math.max(v1.z, v2.z));
        this.name = name;
        this.dim = dim;
    }

    public void afterLoad() {
        for (VideoScreen screen : screens) {
            screen.area = this;
            screen.initServer();
        }
    }

    public void remove() {
        for (VideoScreen screen : screens) {
            screen.remove();
        }
    }

    public void initServer() {
        players = new HashSet<>();
    }

    public boolean inBounds(double x, double y, double z) {
        return min.x <= x && min.y <= y && min.z <= z && x < max.x && y < max.y && z < max.z;
    }

    public boolean addPlayer(UUID uuid) {
        return players.add(uuid);
    }

    public void playerEntered() {
        if (players.size() > 1) return;
        for (VideoScreen screen : screens) {
            screen.playNext();
        }
    }

    public boolean removePlayer(UUID uuid) {
        boolean removed = players.remove(uuid);
        if (removed) {
            for (VideoScreen screen : screens) {
                screen.removePlayer(uuid);
            }
        }
        return removed;
    }

    public boolean containsPlayer(UUID uuid) {
        return players.contains(uuid);
    }

    public void forEachPlayer(Consumer<UUID> consumer) {
        for (UUID player : players) {
            consumer.accept(player);
        }
    }

    public List<UUID> playerSnapshot() {
        return List.copyOf(players);
    }

    public boolean hasPlayer() {
        return !players.isEmpty();
    }

    public int players() {
        return players.size();
    }

    public void addScreen(VideoScreen screen) {
        screen.area = this;
        screens.add(screen);
    }

    public VideoScreen getScreen(String name) {
        for (VideoScreen screen : screens) {
            if (screen.name.equals(name)) {
                return screen;
            }
        }
        return null;
    }

    public boolean isScreenReferenced(String name) {
        if (name == null || name.isEmpty()) return false;
        for (VideoScreen screen : screens) {
            if (screen != null && name.equals(screen.source)) return true;
        }
        return false;
    }

    public VideoScreen removeScreen(String name) {
        VideoScreen screen = getScreen(name);
        if (screen != null) {
            screen.remove();
            screens.remove(screen);
            return screen;
        }
        return null;
    }

    public static VideoArea from(Vector3f v, Vector3f v2, String name, String dim) {
        return new VideoArea(v, v2, name, dim);
    }

    public static boolean validSize(Vector3f first, Vector3f second) {
        return first != null && second != null
                && Float.isFinite(first.x) && Float.isFinite(first.y) && Float.isFinite(first.z)
                && Float.isFinite(second.x) && Float.isFinite(second.y) && Float.isFinite(second.z)
                && Math.abs((double) first.x - second.x) <= MAX_AXIS_LENGTH
                && Math.abs((double) first.y - second.y) <= MAX_AXIS_LENGTH
                && Math.abs((double) first.z - second.z) <= MAX_AXIS_LENGTH;
    }

    public static void write(ByteBuf buf, VideoArea area) {
        ByteBufUtils.writeVec3(buf, area.min);
        ByteBufUtils.writeVec3(buf, area.max);
        ByteBufUtils.writeString(buf, area.name);
        ByteBufUtils.writeString(buf, area.dim);
    }
}
