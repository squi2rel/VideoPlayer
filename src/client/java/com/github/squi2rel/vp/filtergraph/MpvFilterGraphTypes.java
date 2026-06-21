package com.github.squi2rel.vp.filtergraph;

import com.github.squi2rel.mcng.core.MCNGPortTypes;
import com.github.squi2rel.mcng.core.PortType;
import com.github.squi2rel.mcng.core.PortTypeRegistry;

public final class MpvFilterGraphTypes {
    public static final PortType<Object> VIDEO_STREAM = new PortType<>("videoplayer:video_stream", Object.class);
    public static final PortType<Object> AUDIO_STREAM = new PortType<>("videoplayer:audio_stream", Object.class);

    private MpvFilterGraphTypes() {
    }

    public static PortTypeRegistry createRegistry() {
        PortTypeRegistry registry = MCNGPortTypes.createRegistry();
        registry.registerType(VIDEO_STREAM, 0xFF6CB6FF);
        registry.registerType(AUDIO_STREAM, 0xFFFFC857);
        return registry;
    }
}
