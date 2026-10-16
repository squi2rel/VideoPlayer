package com.github.squi2rel.vp.network;

import com.github.squi2rel.vp.VideoPlayerMain;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record VideoPayload(byte[] data) implements CustomPacketPayload {
    public static final ResourceLocation VIDEO_PAYLOAD_ID = ResourceLocation.fromNamespaceAndPath(VideoPlayerMain.MOD_ID, "video");
    public static final CustomPacketPayload.Type<VideoPayload> ID = new CustomPacketPayload.Type<>(VIDEO_PAYLOAD_ID);
    public static final StreamCodec<RegistryFriendlyByteBuf, VideoPayload> CODEC = StreamCodec.ofMember((payload, buf) ->
            buf.writeBytes(payload.data), buf -> {
        if (buf.readableBytes() > VideoPackets.MAX_PAYLOAD_BYTES) {
            throw new IllegalStateException("VideoPlayer payload exceeds " + VideoPackets.MAX_PAYLOAD_BYTES + " bytes");
        }
        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        return new VideoPayload(data);
    });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
