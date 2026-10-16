package net.fabricmc.fabric.api.client.networking.v1;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;

public final class ClientPlayConnectionEvents {
    public static final JoinEvent JOIN = new JoinEvent();
    public static final DisconnectEvent DISCONNECT = new DisconnectEvent();

    private ClientPlayConnectionEvents() {
    }

    public interface PacketSender {
    }

    @FunctionalInterface
    public interface Join {
        void onJoin(ClientPacketListener handler, PacketSender sender, Minecraft client);
    }

    @FunctionalInterface
    public interface Disconnect {
        void onDisconnect(ClientPacketListener handler, Minecraft client);
    }

    public static final class JoinEvent {
        private final List<Join> callbacks = new ArrayList<>();

        public void register(Join callback) {
            callbacks.add(callback);
        }

        public void invoke(ClientPacketListener handler, Minecraft client) {
            for (Join callback : List.copyOf(callbacks)) callback.onJoin(handler, null, client);
        }
    }

    public static final class DisconnectEvent {
        private final List<Disconnect> callbacks = new ArrayList<>();

        public void register(Disconnect callback) {
            callbacks.add(callback);
        }

        public void invoke(ClientPacketListener handler, Minecraft client) {
            for (Disconnect callback : List.copyOf(callbacks)) callback.onDisconnect(handler, client);
        }
    }
}
