package com.github.squi2rel.vp.video;

import com.github.squi2rel.vp.provider.VideoInfo;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.UUID;

public class PlaybackQueue {
    private final VideoScreen screen;
    private final ArrayDeque<VideoInfo> infos = new ArrayDeque<>();
    private final HashSet<UUID> skipped = new HashSet<>();

    public PlaybackQueue(VideoScreen screen) {
        this.screen = screen;
    }

    public void add(VideoInfo info) {
        infos.offer(info);
    }

    public VideoInfo peek() {
        return infos.peek();
    }

    public VideoInfo poll() {
        return infos.poll();
    }

    public void clear() {
        infos.clear();
        skipped.clear();
    }

    public int size() {
        return infos.size();
    }

    public Iterable<VideoInfo> infos() {
        return infos;
    }

    public ArrayDeque<VideoInfo> rawInfos() {
        return infos;
    }

    public void clearVotes() {
        skipped.clear();
    }

    public void voteSkip(UUID uuid) {
        skipped.add(uuid);
    }

    public void removePlayer(UUID uuid) {
        skipped.remove(uuid);
    }

    public int skipped() {
        return skipped.size();
    }

    public boolean shouldSkip() {
        return skipped.size() > screen.area.players() * screen.skipPercent;
    }
}
