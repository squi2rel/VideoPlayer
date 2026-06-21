package com.github.squi2rel.vp.video;

import com.github.squi2rel.vp.DataHolder;
import com.github.squi2rel.vp.network.ServerPacketHandler;
import com.github.squi2rel.vp.network.VideoPackets;
import org.bukkit.entity.Player;

import java.util.List;

public class ScreenBroadcaster {
    private final VideoScreen screen;

    public ScreenBroadcaster(VideoScreen screen) {
        this.screen = screen;
    }

    public void send(byte[] data) {
        for (Player player : DataHolder.players(screen.area.playerSnapshot())) {
            ServerPacketHandler.sendTo(player, data);
        }
    }

    public void syncPlaylist() {
        send(VideoPackets.updatePlaylist(List.of(screen)));
    }
}
