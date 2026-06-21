package com.github.squi2rel.vp.video;

import com.github.squi2rel.vp.NativeDownloadConfig;
import com.github.squi2rel.vp.NativePackageManager;
import com.github.squi2rel.vp.VideoPlayerMain;
import com.sun.jna.Callback;
import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class VlcLibrary {
    static final int LIBVLC_MEDIA_PLAYER_PLAYING = 0x104;
    static final int LIBVLC_MEDIA_PLAYER_PAUSED = 0x105;
    static final int LIBVLC_MEDIA_PLAYER_STOPPED = 0x106;
    static final int LIBVLC_MEDIA_PLAYER_END_REACHED = 0x109;
    static final int LIBVLC_MEDIA_PLAYER_ENCOUNTERED_ERROR = 0x10A;
    static final int LIBVLC_MEDIA_PLAYER_TIME_CHANGED = 0x10B;
    static final int LIBVLC_MEDIA_PLAYER_LENGTH_CHANGED = 0x111;

    private static volatile LibVlc lib;
    private static volatile Throwable loadError;
    private static volatile Path pluginPath;

    private VlcLibrary() {
    }

    static LibVlc get() {
        LibVlc loaded = lib;
        if (loaded != null) return loaded;
        Throwable error = loadError;
        if (error != null) throw unavailable(error);
        synchronized (VlcLibrary.class) {
            if (lib != null) return lib;
            if (loadError != null) throw unavailable(loadError);
            Throwable last = null;
            Optional<NativePackageManager.PreparedNativePackage> prepared = NativePackageManager.prepareForLoad(NativePackageManager.BACKEND_VLC);
            if (prepared.isPresent()) {
                NativePackageManager.PreparedNativePackage nativePackage = prepared.get();
                pluginPath = nativePackage.pluginPath();
                NativeLibraryLoader.prepareWindowsDllDirectory(nativePackage.root());
                if (nativePackage.library() != null) {
                    try {
                        lib = loadByPath(nativePackage.library());
                        return lib;
                    } catch (Throwable t) {
                        last = t;
                    }
                }
            }
            for (String name : libraryCandidates()) {
                try {
                    lib = loadByName(name);
                    return lib;
                } catch (Throwable t) {
                    if (last != null) t.addSuppressed(last);
                    last = t;
                }
            }
            loadError = last == null ? new IllegalStateException("No libvlc library names tried") : last;
            throw unavailable(loadError);
        }
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

    static void resetLoadState() {
        synchronized (VlcLibrary.class) {
            lib = null;
            loadError = null;
            pluginPath = null;
        }
    }

    static Pointer createInstance(List<String> options) {
        LibVlc loaded = get();
        ArrayList<String> effectiveOptions = new ArrayList<>();
        if (options != null) effectiveOptions.addAll(options);
        if (pluginPath != null) {
            effectiveOptions.add("--plugin-path=" + pluginPath.toAbsolutePath());
        }
        try (NativeStringArray argv = new NativeStringArray(effectiveOptions)) {
            Pointer instance = loaded.libvlc_new(effectiveOptions.size(), argv.pointer());
            if (instance == null) {
                throw new IllegalStateException("libvlc_new returned null: " + errmsg(loaded));
            }
            return instance;
        }
    }

    static void releaseInstance(Pointer instance) {
        if (instance != null) {
            get().libvlc_release(instance);
        }
    }

    static Pointer createMedia(Pointer instance, String rawPath, String[] options) {
        String path = rawPath == null ? "" : rawPath.replace("rtspt://", "rtsp://");
        LibVlc loaded = get();
        Pointer media;
        Memory pathMemory = utf8(path);
        if (hasScheme(path)) {
            media = loaded.libvlc_media_new_location(instance, pathMemory);
        } else {
            media = loaded.libvlc_media_new_path(instance, pathMemory);
        }
        if (media == null) {
            throw new IllegalStateException("Failed to create VLC media: " + errmsg(loaded));
        }
        if (options != null) {
            for (String option : options) {
                if (option == null || option.isBlank()) continue;
                loaded.libvlc_media_add_option(media, utf8(option));
            }
        }
        return media;
    }

    static Memory utf8(String value) {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        Memory memory = new Memory(bytes.length + 1L);
        memory.write(0, bytes, 0, bytes.length);
        memory.setByte(bytes.length, (byte) 0);
        return memory;
    }

    static String errmsg(LibVlc loaded) {
        try {
            Pointer pointer = loaded.libvlc_errmsg();
            return pointer == null ? "unknown error" : pointer.getString(0, StandardCharsets.UTF_8.name());
        } catch (Throwable ignored) {
            return "unknown error";
        }
    }

    static long pointerValue(Pointer pointer) {
        return pointer == null ? 0 : Pointer.nativeValue(pointer);
    }

    private static IllegalStateException unavailable(Throwable cause) {
        return new IllegalStateException("libvlc is not available; tried " + String.join(", ", libraryCandidates()), cause);
    }

    private static List<String> libraryCandidates() {
        String os = NativeDownloadConfig.osKey();
        if ("windows".equals(os)) {
            return List.of("libvlc", "libvlc.dll");
        }
        if ("macos".equals(os)) {
            return List.of("vlc", "libvlc", "libvlc.dylib");
        }
        return List.of("vlc", "libvlc", "libvlc.so");
    }

    private static LibVlc loadByPath(Path path) {
        String absolute = path.toAbsolutePath().toString();
        NativeLibraryLoader.prepareWindowsDllDirectoryForLibrary(path);
        System.load(absolute);
        return Native.load(absolute, LibVlc.class);
    }

    private static LibVlc loadByName(String name) {
        Throwable preloadError = null;
        try {
            preloadBySystemLoader(name);
        } catch (Throwable t) {
            preloadError = t;
        }
        try {
            return Native.load(name, LibVlc.class);
        } catch (Throwable nativeLoadError) {
            if (preloadError != null) nativeLoadError.addSuppressed(preloadError);
            throw nativeLoadError;
        }
    }

    private static void preloadBySystemLoader(String name) {
        if ("windows".equals(NativeDownloadConfig.osKey()) && name.endsWith(".dll")) {
            System.loadLibrary(name.substring(0, name.length() - ".dll".length()));
            return;
        }
        System.loadLibrary(name);
    }

    private static boolean hasScheme(String path) {
        int colon = path.indexOf(':');
        if (colon <= 0) return false;
        if (colon == 1 && path.length() > 2 && Character.isLetter(path.charAt(0))) {
            char separator = path.charAt(2);
            if (separator == '\\' || separator == '/') return false;
        }
        for (int i = 0; i < colon; i++) {
            char c = path.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '+' || c == '-' || c == '.')) {
                return false;
            }
        }
        return Character.isLetter(path.charAt(0));
    }

    interface LibVlc extends Library {
        Pointer libvlc_new(int argc, Pointer argv);

        void libvlc_release(Pointer instance);

        Pointer libvlc_errmsg();

        Pointer libvlc_media_new_location(Pointer instance, Pointer mrl);

        Pointer libvlc_media_new_path(Pointer instance, Pointer path);

        void libvlc_media_add_option(Pointer media, Pointer option);

        void libvlc_media_release(Pointer media);

        Pointer libvlc_media_player_new(Pointer instance);

        Pointer libvlc_media_player_new_from_media(Pointer media);

        void libvlc_media_player_set_media(Pointer player, Pointer media);

        void libvlc_media_player_release(Pointer player);

        int libvlc_media_player_play(Pointer player);

        void libvlc_media_player_stop(Pointer player);

        void libvlc_media_player_set_pause(Pointer player, int pause);

        int libvlc_media_player_is_playing(Pointer player);

        int libvlc_media_player_can_pause(Pointer player);

        int libvlc_media_player_is_seekable(Pointer player);

        long libvlc_media_player_get_time(Pointer player);

        void libvlc_media_player_set_time(Pointer player, long time);

        long libvlc_media_player_get_length(Pointer player);

        int libvlc_media_player_set_rate(Pointer player, float rate);

        float libvlc_media_player_get_rate(Pointer player);

        int libvlc_audio_set_volume(Pointer player, int volume);

        Pointer libvlc_media_player_event_manager(Pointer player);

        int libvlc_event_attach(Pointer eventManager, int eventType, EventCallback callback, Pointer userData);

        void libvlc_event_detach(Pointer eventManager, int eventType, EventCallback callback, Pointer userData);

        void libvlc_video_set_callbacks(Pointer player, VideoLockCallback lock, VideoUnlockCallback unlock,
                                        VideoDisplayCallback display, Pointer opaque);

        void libvlc_video_set_format_callbacks(Pointer player, VideoFormatCallback setup, VideoCleanupCallback cleanup);

        void libvlc_audio_set_callbacks(Pointer player, AudioPlayCallback play, AudioPauseCallback pause,
                                        AudioResumeCallback resume, AudioFlushCallback flush, AudioDrainCallback drain,
                                        Pointer opaque);

        void libvlc_audio_set_volume_callback(Pointer player, AudioSetVolumeCallback setVolume);

        void libvlc_audio_set_format_callbacks(Pointer player, AudioSetupCallback setup, AudioCleanupCallback cleanup);
    }

    interface EventCallback extends Callback {
        void invoke(Pointer event, Pointer userData);
    }

    interface VideoLockCallback extends Callback {
        Pointer invoke(Pointer opaque, Pointer planes);
    }

    interface VideoUnlockCallback extends Callback {
        void invoke(Pointer opaque, Pointer picture, Pointer planes);
    }

    interface VideoDisplayCallback extends Callback {
        void invoke(Pointer opaque, Pointer picture);
    }

    interface VideoFormatCallback extends Callback {
        int invoke(PointerByReference opaque, Pointer chroma, Pointer width, Pointer height, Pointer pitches, Pointer lines);
    }

    interface VideoCleanupCallback extends Callback {
        void invoke(Pointer opaque);
    }

    interface AudioPlayCallback extends Callback {
        void invoke(Pointer data, Pointer samples, int count, long pts);
    }

    interface AudioPauseCallback extends Callback {
        void invoke(Pointer data, long pts);
    }

    interface AudioResumeCallback extends Callback {
        void invoke(Pointer data, long pts);
    }

    interface AudioFlushCallback extends Callback {
        void invoke(Pointer data, long pts);
    }

    interface AudioDrainCallback extends Callback {
        void invoke(Pointer data);
    }

    interface AudioSetVolumeCallback extends Callback {
        void invoke(Pointer data, float volume, int mute);
    }

    interface AudioSetupCallback extends Callback {
        int invoke(PointerByReference data, Pointer format, Pointer rate, Pointer channels);
    }

    interface AudioCleanupCallback extends Callback {
        void invoke(Pointer data);
    }

    interface VlcAudioSink {
        int setup(VlcAudioFormat format);

        void cleanup();

        void play(Pointer samples, int count, long pts);

        void pause(long pts);

        void resume(long pts);

        void flush(long pts);

        void drain();

        void setVolume(float volume, boolean mute);
    }

    record VlcAudioFormat(String format, int rate, int channels) {
    }

    private static final class NativeStringArray implements AutoCloseable {
        private final ArrayList<Memory> strings;
        private final Memory pointer;

        private NativeStringArray(List<String> values) {
            strings = new ArrayList<>(values.size());
            pointer = new Memory((long) (values.size() + 1) * Native.POINTER_SIZE);
            for (int i = 0; i < values.size(); i++) {
                Memory string = utf8(values.get(i));
                strings.add(string);
                pointer.setPointer((long) i * Native.POINTER_SIZE, string);
            }
            pointer.setPointer((long) values.size() * Native.POINTER_SIZE, null);
        }

        private Pointer pointer() {
            return pointer;
        }

        @Override
        public void close() {
            strings.clear();
        }
    }
}
