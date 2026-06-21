package com.github.squi2rel.vp.filtergraph;

import com.github.squi2rel.mcng.core.GraphDocument;
import com.github.squi2rel.vp.VideoPlayerMain;
import com.github.squi2rel.vp.video.MpvVideoBackend;

import java.util.HashMap;
import java.util.Map;

public final class MpvFilterGraphManager {
    private static MpvFilterGraphStore.State state;
    private static MpvFilterGraphCompiler.CompileResult lastCompile;
    private static final Map<InputAvailability, MpvFilterGraphCompiler.CompileResult> playbackCompiles = new HashMap<>();

    private MpvFilterGraphManager() {
    }

    public static synchronized GraphDocument document() {
        return state().document();
    }

    public static synchronized boolean autoApply() {
        return state().autoApply();
    }

    public static synchronized void setAutoApply(boolean autoApply) {
        state = new MpvFilterGraphStore.State(autoApply, state().document());
        save();
    }

    public static synchronized void setDocument(GraphDocument document) {
        state = new MpvFilterGraphStore.State(state().autoApply(), document);
        lastCompile = null;
        playbackCompiles.clear();
        save();
    }

    public static synchronized MpvFilterGraphCompiler.CompileResult compileCurrent() {
        if (lastCompile == null) {
            lastCompile = MpvFilterGraphCompiler.compile(state().document());
        }
        return lastCompile;
    }

    public static synchronized MpvFilterGraphCompiler.CompileResult compileCurrentForPlayback(boolean audioOnly) {
        return compileCurrentForPlayback(!audioOnly, true);
    }

    public static synchronized MpvFilterGraphCompiler.CompileResult compileCurrentForPlayback(boolean videoInputAvailable, boolean audioInputAvailable) {
        if (videoInputAvailable && audioInputAvailable) return compileCurrent();
        InputAvailability key = new InputAvailability(videoInputAvailable, audioInputAvailable);
        return playbackCompiles.computeIfAbsent(key, ignored ->
                MpvFilterGraphCompiler.compile(state().document(), videoInputAvailable, audioInputAvailable));
    }

    public static synchronized String currentLavfiComplexForPlayback() {
        return currentLavfiComplexForPlayback(false);
    }

    public static synchronized String currentLavfiComplexForPlayback(boolean audioOnly) {
        return currentLavfiComplexForPlayback(!audioOnly, true);
    }

    public static synchronized String currentLavfiComplexForPlayback(boolean videoInputAvailable, boolean audioInputAvailable) {
        MpvFilterGraphCompiler.CompileResult compiled = compileCurrentForPlayback(videoInputAvailable, audioInputAvailable);
        if (!compiled.success()) {
            VideoPlayerMain.LOGGER.warn("Ignoring MPV filter graph because it failed to compile: {}", compiled.error());
            return "";
        }
        return compiled.graph();
    }

    public static ApplyResult applyToActivePlayers() {
        MpvFilterGraphCompiler.CompileResult compiled = compileCurrent();
        if (!compiled.success()) {
            return new ApplyResult(false, compiled.error(), 0);
        }
        int count = MpvVideoBackend.applyLavfiComplexToAll(compiled.graph());
        return new ApplyResult(true, compiled.graph().isBlank() ? "Cleared MPV filter graph" : "Applied MPV filter graph", count);
    }

    private static MpvFilterGraphStore.State state() {
        if (state == null) {
            state = MpvFilterGraphStore.load();
        }
        return state;
    }

    private static void save() {
        MpvFilterGraphStore.save(state());
    }

    public record ApplyResult(boolean success, String message, int playerCount) {
    }

    private record InputAvailability(boolean videoInputAvailable, boolean audioInputAvailable) {
    }
}
