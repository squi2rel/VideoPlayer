package com.github.squi2rel.vp.network;

import com.github.squi2rel.vp.VideoPlayerMain;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record VideoPayload(byte[] data) implements CustomPayload {
    public static final Identifier VIDEO_PAYLOAD_ID = Identifier.of(VideoPlayerMain.MOD_ID, "video");
    public static final CustomPayload.Id<VideoPayload> ID = new CustomPayload.Id<>(VIDEO_PAYLOAD_ID);
    public static final PacketCodec<PacketByteBuf, VideoPayload> CODEC = PacketCodec.of((p, buf) -> buf.writeBytes(p.data), buf -> {
        if (buf.readableBytes() > VideoPackets.MAX_PAYLOAD_BYTES) {
            throw new IllegalStateException("VideoPlayer payload exceeds " + VideoPackets.MAX_PAYLOAD_BYTES + " bytes");
        }
        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        return new VideoPayload(data);
    });

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(ID, CODEC);
        PayloadTypeRegistry.playC2S().register(ID, CODEC);
    }
}
