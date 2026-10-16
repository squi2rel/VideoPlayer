package net.fabricmc.fabric.api.client.networking.v1;

import com.github.squi2rel.vp.network.VideoPayload;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

public final class ClientPlayNetworking {
    private static final Map<CustomPacketPayload.Type<?>, PlayPayloadHandler<?>> RECEIVERS = new ConcurrentHashMap<>();

    private ClientPlayNetworking() {
    }

    @FunctionalInterface
    public interface PlayPayloadHandler<T extends CustomPacketPayload> {
        void receive(T payload, Context context);
    }

    public record Context(Minecraft client) {
    }

    public static <T extends CustomPacketPayload> void registerGlobalReceiver(CustomPacketPayload.Type<T> type,
                                                                              PlayPayloadHandler<T> receiver) {
        RECEIVERS.put(type, receiver);
    }

    public static void send(CustomPacketPayload payload) {
        PacketDistributor.sendToServer(payload);
    }

    public static boolean canSend(CustomPacketPayload.Type<?> type) {
        var connection = Minecraft.getInstance().getConnection();
        return connection != null && NetworkRegistry.hasChannel(connection, type.id());
    }

    @SuppressWarnings("unchecked")
    public static void receive(VideoPayload payload) {
        PlayPayloadHandler<VideoPayload> receiver = (PlayPayloadHandler<VideoPayload>) RECEIVERS.get(payload.type());
        if (receiver != null) receiver.receive(payload, new Context(Minecraft.getInstance()));
    }
}
