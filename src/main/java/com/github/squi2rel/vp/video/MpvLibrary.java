package com.github.squi2rel.vp.video;

import com.github.squi2rel.vp.NativePackageManager;
import com.github.squi2rel.vp.NativeDownloadConfig;
import com.github.squi2rel.vp.VideoPlayerMain;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.jna.Callback;
import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Platform;
import com.sun.jna.Structure;
import com.sun.jna.ptr.PointerByReference;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

final class MpvLibrary {
    static final int MPV_FORMAT_STRING = 1;
    static final int MPV_FORMAT_FLAG = 3;
    static final int MPV_FORMAT_DOUBLE = 5;

    static final int MPV_EVENT_NONE = 0;
    static final int MPV_EVENT_SHUTDOWN = 1;
    static final int MPV_EVENT_END_FILE = 7;
    static final int MPV_EVENT_FILE_LOADED = 8;
    static final int MPV_EVENT_VIDEO_RECONFIG = 17;

    static final int MPV_END_FILE_REASON_EOF = 0;

    static final int MPV_RENDER_PARAM_INVALID = 0;
    static final int MPV_RENDER_PARAM_API_TYPE = 1;
    static final int MPV_RENDER_PARAM_OPENGL_INIT_PARAMS = 2;
    static final int MPV_RENDER_PARAM_OPENGL_FBO = 3;
    static final int MPV_RENDER_PARAM_FLIP_Y = 4;
    static final int MPV_RENDER_PARAM_ADVANCED_CONTROL = 10;
    static final int MPV_RENDER_PARAM_BLOCK_FOR_TARGET_TIME = 12;
    static final long MPV_RENDER_UPDATE_FRAME = 1;

    static final int MPV_AUDIO_OUTPUT_CB_FORMAT_S16LE = 1;
    static final int MPV_AUDIO_OUTPUT_CB_MAX_CHANNELS = 64;
    static final int MPV_AUDIO_OUTPUT_CB_SPEAKER_FL = 0;
    static final int MPV_AUDIO_OUTPUT_CB_SPEAKER_FR = 1;
    static final int MPV_AUDIO_OUTPUT_CB_SPEAKER_FC = 2;
    static final int MPV_AUDIO_OUTPUT_CB_SPEAKER_LFE = 3;
    static final int MPV_AUDIO_OUTPUT_CB_SPEAKER_BL = 4;
    static final int MPV_AUDIO_OUTPUT_CB_SPEAKER_BR = 5;
    static final int MPV_AUDIO_OUTPUT_CB_SPEAKER_FLC = 6;
    static final int MPV_AUDIO_OUTPUT_CB_SPEAKER_FRC = 7;
    static final int MPV_AUDIO_OUTPUT_CB_SPEAKER_BC = 8;
    static final int MPV_AUDIO_OUTPUT_CB_SPEAKER_SL = 9;
    static final int MPV_AUDIO_OUTPUT_CB_SPEAKER_SR = 10;
    static final int MPV_AUDIO_OUTPUT_CB_SPEAKER_TC = 11;
    static final int MPV_AUDIO_OUTPUT_CB_SPEAKER_TFL = 12;
    static final int MPV_AUDIO_OUTPUT_CB_SPEAKER_TFC = 13;
    static final int MPV_AUDIO_OUTPUT_CB_SPEAKER_TFR = 14;
    static final int MPV_AUDIO_OUTPUT_CB_SPEAKER_TBL = 15;
    static final int MPV_AUDIO_OUTPUT_CB_SPEAKER_TBC = 16;
    static final int MPV_AUDIO_OUTPUT_CB_SPEAKER_TBR = 17;
    static final int MPV_AUDIO_OUTPUT_CB_SPEAKER_DL = 29;
    static final int MPV_AUDIO_OUTPUT_CB_SPEAKER_DR = 30;
    static final int MPV_AUDIO_OUTPUT_CB_SPEAKER_WL = 31;
    static final int MPV_AUDIO_OUTPUT_CB_SPEAKER_WR = 32;
    static final int MPV_AUDIO_OUTPUT_CB_SPEAKER_SDL = 33;
    static final int MPV_AUDIO_OUTPUT_CB_SPEAKER_SDR = 34;
    static final int MPV_AUDIO_OUTPUT_CB_SPEAKER_LFE2 = 35;
    static final int MPV_AUDIO_OUTPUT_CB_SPEAKER_TSL = 36;
    static final int MPV_AUDIO_OUTPUT_CB_SPEAKER_TSR = 37;
    static final int MPV_AUDIO_OUTPUT_CB_SPEAKER_BFC = 38;
    static final int MPV_AUDIO_OUTPUT_CB_SPEAKER_BFL = 39;
    static final int MPV_AUDIO_OUTPUT_CB_SPEAKER_BFR = 40;
    static final int MPV_AUDIO_OUTPUT_CB_SPEAKER_NA = 64;

    static final int MPV_VIDEO_OUTPUT_CB_FORMAT_RGBA8 = 1;
    static final int MPV_VIDEO_OUTPUT_CB_FLAG_REPEAT = 1;
    static final int MPV_VIDEO_OUTPUT_CB_FLAG_REDRAW = 1 << 1;
    static final int MPV_VIDEO_OUTPUT_CB_FLAG_STILL = 1 << 2;

    private static final List<String> LIBRARY_CANDIDATES = Platform.isWindows()
            ? List.of("libmpv-2", "libmpv-2.dll", "mpv-2", "mpv", "libmpv")
            : List.of("mpv", "mpv-2", "libmpv-2", "libmpv");

    private static volatile LibMpv lib;
    private static volatile Throwable loadError;
    private static volatile boolean localeWarningLogged;

    private MpvLibrary() {
    }

    static LibMpv get() {
        prepareLocaleForMpv();
        LibMpv loaded = lib;
        if (loaded != null) return loaded;
        Throwable error = loadError;
        if (error != null) throw unavailable(error);
        synchronized (MpvLibrary.class) {
            if (lib != null) return lib;
            if (loadError != null) throw unavailable(loadError);
            Throwable last = null;
            Optional<NativePackageManager.PreparedNativePackage> prepared = NativePackageManager.prepareForLoad(NativePackageManager.BACKEND_MPV);
            if (prepared.isPresent() && prepared.get().library() != null) {
                NativeLibraryLoader.prepareWindowsDllDirectory(prepared.get().root());
                try {
                    lib = loadByPath(prepared.get().library());
                    return lib;
                } catch (Throwable t) {
                    last = t;
                } finally {
                    NativeLibraryLoader.clearWindowsDllDirectory();
                }
            }
            NativeLibraryLoader.clearWindowsDllDirectory();
            for (String name : LIBRARY_CANDIDATES) {
                try {
                    lib = loadByName(name);
                    return lib;
                } catch (Throwable t) {
                    if (last != null) t.addSuppressed(last);
                    last = t;
                }
            }
            loadError = last == null ? new IllegalStateException("No libmpv library names tried") : last;
            throw unavailable(loadError);
        }
    }

    static void resetLoadState() {
        synchronized (MpvLibrary.class) {
            if (lib != null) return;
            loadError = null;
        }
    }

    private static void prepareLocaleForMpv() {
        if (Platform.isWindows()) return;
        try {
            CLibrary.INSTANCE.setlocale(lcNumericCategory(), "C");
        } catch (Throwable t) {
            if (!localeWarningLogged) {
                localeWarningLogged = true;
                VideoPlayerMain.LOGGER.warn("Failed to set LC_NUMERIC=C before loading libmpv", t);
            }
        }
    }

    private static int lcNumericCategory() {
        return "macos".equals(NativeDownloadConfig.osKey()) ? 4 : 1;
    }

    private static IllegalStateException unavailable(Throwable cause) {
        return new IllegalStateException("libmpv is not available; tried " + String.join(", ", LIBRARY_CANDIDATES), cause);
    }

    private static LibMpv loadByName(String name) {
        Throwable preloadError = null;
        try {
            preloadBySystemLoader(name);
        } catch (Throwable t) {
            preloadError = t;
        }
        try {
            return Native.load(name, LibMpv.class);
        } catch (Throwable nativeLoadError) {
            if (preloadError != null) nativeLoadError.addSuppressed(preloadError);
            throw nativeLoadError;
        }
    }

    private static LibMpv loadByPath(java.nio.file.Path path) {
        String absolute = path.toAbsolutePath().toString();
        NativeLibraryLoader.prepareWindowsDllDirectoryForLibrary(path);
        System.load(absolute);
        return Native.load(absolute, LibMpv.class);
    }

    private static void preloadBySystemLoader(String name) {
        if (Platform.isWindows() && name.endsWith(".dll")) {
            System.loadLibrary(name.substring(0, name.length() - ".dll".length()));
            return;
        }
        System.loadLibrary(name);
    }

    static boolean isAvailable() {
        try {
            get();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static Throwable loadError() {
        try {
            get();
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    static String filterInfoJson(String type, String name) {
        LibMpv loaded = get();
        Pointer json;
        try {
            json = loaded.mpv_filter_info_json(emptyToNull(type), emptyToNull(name));
        } catch (UnsatisfiedLinkError e) {
            throw new IllegalStateException("libmpv filter info API is not available", e);
        }
        if (json == null) {
            throw new IllegalArgumentException("Invalid mpv filter info request");
        }
        try {
            return json.getString(0, StandardCharsets.UTF_8.name());
        } finally {
            loaded.mpv_free(json);
        }
    }

    static JsonObject filterInfo(String type, String name) {
        return JsonParser.parseString(filterInfoJson(type, name)).getAsJsonObject();
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    static Memory utf8(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        Memory memory = new Memory(bytes.length + 1L);
        memory.write(0, bytes, 0, bytes.length);
        memory.setByte(bytes.length, (byte) 0);
        return memory;
    }

    interface LibMpv extends Library {
        Pointer mpv_create();

        int mpv_initialize(Pointer ctx);

        void mpv_terminate_destroy(Pointer ctx);

        int mpv_command(Pointer ctx, Pointer args);

        int mpv_set_option_string(Pointer ctx, String name, String data);

        int mpv_set_property(Pointer ctx, String name, int format, Pointer data);

        int mpv_get_property(Pointer ctx, String name, int format, Pointer data);

        Pointer mpv_wait_event(Pointer ctx, double timeout);

        void mpv_wakeup(Pointer ctx);

        String mpv_error_string(int error);

        void mpv_free(Pointer data);

        Pointer mpv_filter_info_json(String type, String name);

        int mpv_render_context_create(PointerByReference res, Pointer mpv, Pointer params);

        void mpv_render_context_set_update_callback(Pointer ctx, MpvRenderUpdateCallback callback, Pointer callbackCtx);

        long mpv_render_context_update(Pointer ctx);

        int mpv_render_context_render(Pointer ctx, Pointer params);

        void mpv_render_context_free(Pointer ctx);

        void mpv_set_audio_output_callback(Pointer ctx, MpvAudioOutputCallback callback, Pointer userdata);

        void mpv_set_video_output_callback(Pointer ctx, MpvVideoOutputCallback callback, Pointer userdata);
    }

    private interface CLibrary extends Library {
        CLibrary INSTANCE = Native.load(Platform.C_LIBRARY_NAME, CLibrary.class);

        String setlocale(int category, String locale);
    }

    interface MpvRenderUpdateCallback extends Callback {
        void invoke(Pointer callbackCtx);
    }

    interface MpvAudioOutputCallback extends Callback {
        void invoke(Pointer userdata, Pointer data, Pointer info);
    }

    interface MpvVideoOutputCallback extends Callback {
        void invoke(Pointer userdata, Pointer data, Pointer info);
    }

    interface MpvOpenGLProcAddressCallback extends Callback {
        Pointer invoke(Pointer context, String name);
    }

    public static class MpvRenderParam extends Structure {
        public int type;
        public Pointer data;

        @Override
        protected List<String> getFieldOrder() {
            return List.of("type", "data");
        }
    }

    public static class MpvOpenGLInitParams extends Structure {
        public MpvOpenGLProcAddressCallback get_proc_address;
        public Pointer get_proc_address_ctx;

        @Override
        protected List<String> getFieldOrder() {
            return List.of("get_proc_address", "get_proc_address_ctx");
        }
    }

    public static class MpvOpenGLFbo extends Structure {
        public int fbo;
        public int w;
        public int h;
        public int internal_format;

        @Override
        protected List<String> getFieldOrder() {
            return List.of("fbo", "w", "h", "internal_format");
        }
    }

    public static class MpvEvent extends Structure {
        public int event_id;
        public int error;
        public long reply_userdata;
        public Pointer data;

        public MpvEvent(Pointer pointer) {
            super(pointer);
            read();
        }

        @Override
        protected List<String> getFieldOrder() {
            return List.of("event_id", "error", "reply_userdata", "data");
        }
    }

    public static class MpvAudioOutputCbInfo extends Structure {
        public int format;
        public int sample_rate;
        public int channels;
        public int samples;
        public long bytes;
        public long sequence;
        public long dropped_samples;
        public long channel_mask;
        public int[] channel_layout = new int[MPV_AUDIO_OUTPUT_CB_MAX_CHANNELS];

        public MpvAudioOutputCbInfo(Pointer pointer) {
            super(pointer);
            read();
        }

        @Override
        protected List<String> getFieldOrder() {
            return List.of("format", "sample_rate", "channels", "samples", "bytes", "sequence",
                    "dropped_samples", "channel_mask", "channel_layout");
        }
    }

    public static class MpvVideoOutputCbInfo extends Structure {
        public int format;
        public int width;
        public int height;
        public int stride;
        public long bytes;
        public long sequence;
        public long frame_id;
        public long dropped_frames;
        public int flags;
        public double pts;
        public double duration;

        public MpvVideoOutputCbInfo(Pointer pointer) {
            super(pointer);
            read();
        }

        @Override
        protected List<String> getFieldOrder() {
            return List.of("format", "width", "height", "stride", "bytes", "sequence", "frame_id",
                    "dropped_frames", "flags", "pts", "duration");
        }
    }

    public static class MpvEventEndFile extends Structure {
        public int reason;
        public int error;
        public long playlist_entry_id;
        public long playlist_insert_id;
        public int playlist_insert_num_entries;

        public MpvEventEndFile(Pointer pointer) {
            super(pointer);
            read();
        }

        @Override
        protected List<String> getFieldOrder() {
            return List.of("reason", "error", "playlist_entry_id", "playlist_insert_id", "playlist_insert_num_entries");
        }
    }
}
