package com.github.squi2rel.vp.video;

import com.github.squi2rel.vp.network.ByteBufUtils;
import io.netty.buffer.ByteBuf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Objects;

import static com.github.squi2rel.vp.video.VideoScreen.MAX_NAME_BYTES;

public class ClientVideoArea extends VideoArea {
    public ArrayList<Runnable> clones = new ArrayList<>();

    public boolean loaded = false, removed = false;

    public ClientVideoArea(Vector3f v1, Vector3f v2, String name, String dim) {
        super(v1, v2, name, dim);
    }

    @Override
    public ClientVideoScreen getScreen(String name) {
        return (ClientVideoScreen) super.getScreen(name);
    }

    @Override
    public synchronized void addScreen(VideoScreen screen) {
        super.addScreen(screen);
        if (loaded) {
            ((ClientVideoScreen) screen).load();
        }
        run();
    }

    public synchronized void load() {
        if (removed) throw new IllegalStateException();
        if (loaded) return;
        for (VideoScreen screen : screens) {
            ((ClientVideoScreen) screen).load();
        }
        run();
        loaded = true;
    }

    private synchronized void run() {
        clones.forEach(Runnable::run);
        clones.clear();
    }

    public synchronized void unload() {
        if (!loaded) return;
        for (VideoScreen screen : screens) {
            ((ClientVideoScreen) screen).unload();
        }
        loaded = false;
    }

    @Override
    public synchronized void remove() {
        unload();
        removed = true;
    }

    public synchronized void remove(String name) {
        screens.removeIf(s -> {
            if (Objects.equals(s.name, name)) {
                if (loaded) ((ClientVideoScreen) s).unload();
                return true;
            }
            return false;
        });
    }

    public void afterLoad(Runnable r) {
        clones.add(r);
    }

    public static ClientVideoArea read(ByteBuf buf) {
        return new ClientVideoArea(ByteBufUtils.readVec3(buf), ByteBufUtils.readVec3(buf), ByteBufUtils.readString(buf, MAX_NAME_BYTES), ByteBufUtils.readString(buf, 256));
    }
}
