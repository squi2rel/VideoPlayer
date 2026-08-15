package com.github.squi2rel.vp.video;

import com.github.squi2rel.vp.NativeDownloadConfig;
import com.github.squi2rel.vp.NativePackageManager;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;

import static com.github.squi2rel.vp.video.MpvLibrary.MPV_FORMAT_STRING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "VPLIGHT_SAMPLE", matches = ".+")
class MpvTelemetryIntegrationTest {
    @Test
    void readsAudioAndVideoTelemetryFromRealMedia() {
        System.setProperty("videoplayer.configDir", System.getenv("VPLIGHT_NATIVE_DIR"));
        NativePackageManager.selectPlatform(NativePackageManager.BACKEND_MPV, "windows_x64");
        NativeDownloadConfig downloads = NativeDownloadConfig.load();
        NativePackageManager.DownloadResult installed = NativePackageManager.downloadAndInstall(
                NativePackageManager.BACKEND_MPV,
                "windows_x64",
                downloads.sources(NativePackageManager.BACKEND_MPV, "windows_x64"),
                null
        );
        assertTrue(installed.success(), () -> String.valueOf(installed.error()));
        MpvLibrary.LibMpv lib = MpvLibrary.get();
        Pointer context = lib.mpv_create();
        if (context == null) throw new IllegalStateException("mpv_create returned null");
        try {
            option(lib, context, "config", "no");
            option(lib, context, "terminal", "no");
            option(lib, context, "vo", "null");
            option(lib, context, "ao", "null");
            option(lib, context, "mute", "yes");
            option(lib, context, "af", "@videoplayer_audio_meter:lavfi=[astats=metadata=1:reset=1]");
            option(lib, context, "vf", "@videoplayer_color_meter:lavfi=[fps=10,scale=32:18:flags=area,format=pix_fmts=yuv444p,signalstats]");
            check(lib, lib.mpv_initialize(context));
            command(lib, context, "loadfile", Path.of(System.getenv("VPLIGHT_SAMPLE")).toAbsolutePath().toString(), "replace");
            AudioLevelSnapshot audio = AudioLevelSnapshot.waiting();
            VideoColorSnapshot color = VideoColorSnapshot.waiting();
            long deadline = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < deadline
                    && (audio.status() != AudioLevelSnapshot.Status.AVAILABLE
                    || color.status() != VideoColorSnapshot.Status.AVAILABLE)) {
                lib.mpv_wait_event(context, 0.1);
                long now = System.currentTimeMillis();
                String audioMetadata = string(lib, context, "af-metadata/videoplayer_audio_meter");
                String colorMetadata = string(lib, context, "vf-metadata/videoplayer_color_meter");
                if (audioMetadata != null) audio = MpvAudioLevelParser.parse(audioMetadata, now);
                if (colorMetadata != null) {
                    color = MpvFrameColorParser.parse(colorMetadata,
                            string(lib, context, "video-params/colormatrix"),
                            string(lib, context, "video-params/colorlevels"), now);
                }
            }
            assertEquals(AudioLevelSnapshot.Status.AVAILABLE, audio.status());
            assertEquals(VideoColorSnapshot.Status.AVAILABLE, color.status());
        } finally {
            lib.mpv_terminate_destroy(context);
        }
    }

    private static void option(MpvLibrary.LibMpv lib, Pointer context, String name, String value) {
        check(lib, lib.mpv_set_option_string(context, name, value));
    }

    private static void command(MpvLibrary.LibMpv lib, Pointer context, String... values) {
        ArrayList<Memory> strings = new ArrayList<>(values.length);
        Memory arguments = new Memory((long) (values.length + 1) * Native.POINTER_SIZE);
        for (int index = 0; index < values.length; index++) {
            Memory value = MpvLibrary.utf8(values[index]);
            strings.add(value);
            arguments.setPointer((long) index * Native.POINTER_SIZE, value);
        }
        arguments.setPointer((long) values.length * Native.POINTER_SIZE, null);
        check(lib, lib.mpv_command(context, arguments));
    }

    private static String string(MpvLibrary.LibMpv lib, Pointer context, String name) {
        PointerByReference reference = new PointerByReference();
        if (lib.mpv_get_property(context, name, MPV_FORMAT_STRING, reference.getPointer()) < 0) return null;
        Pointer value = reference.getValue();
        if (value == null) return null;
        try {
            return value.getString(0, StandardCharsets.UTF_8.name());
        } finally {
            lib.mpv_free(value);
        }
    }

    private static void check(MpvLibrary.LibMpv lib, int result) {
        if (result < 0) throw new IllegalStateException(lib.mpv_error_string(result));
    }
}
