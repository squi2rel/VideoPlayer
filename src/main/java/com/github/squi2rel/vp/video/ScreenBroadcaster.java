package com.github.squi2rel.vp.video;

import com.github.squi2rel.vp.network.ServerPacketHandler;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.List;

import static com.github.squi2rel.vp.DataHolder.server;

public class ScreenBroadcaster {
    private final VideoScreen screen;

    public ScreenBroadcaster(VideoScreen screen) {
        this.screen = screen;
    }

    public void send(byte[] data) {
        PlayerManager pm = server.getPlayerManager();
        for (var uuid : screen.area.playerSnapshot()) {
            ServerPlayerEntity player = pm.getPlayer(uuid);
            if (player != null) {
                ServerPacketHandler.sendTo(player, data);
            }
        }
    }

    public void syncPlaylist() {
        send(com.github.squi2rel.vp.network.VideoPackets.updatePlaylist(List.of(screen)));
    }
}
