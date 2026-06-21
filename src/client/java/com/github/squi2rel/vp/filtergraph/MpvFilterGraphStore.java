package com.github.squi2rel.vp.filtergraph;

import com.github.squi2rel.mcng.core.GraphDefinition;
import com.github.squi2rel.mcng.core.GraphDocument;
import com.github.squi2rel.mcng.core.GraphJsonCodec;
import com.github.squi2rel.mcng.core.GraphLayout;
import com.github.squi2rel.mcng.core.NodeId;
import com.github.squi2rel.mcng.core.NodeInstance;
import com.github.squi2rel.mcng.core.NodePosition;
import com.github.squi2rel.vp.VideoPlayerMain;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class MpvFilterGraphStore {
    private static final int STORE_VERSION = 1;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final GraphJsonCodec CODEC = new GraphJsonCodec();
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("videoplayer").resolve("mpv-filter-graph.json");

    private MpvFilterGraphStore() {
    }

    public static State load() {
        if (!Files.exists(PATH)) {
            return new State(true, defaultDocument());
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(PATH)).getAsJsonObject();
            boolean autoApply = !root.has("autoApply") || root.get("autoApply").getAsBoolean();
            GraphDocument document = root.has("document") && root.get("document").isJsonObject()
                    ? CODEC.fromJson(GSON.toJson(root.getAsJsonObject("document")))
                    : defaultDocument();
            return new State(autoApply, document);
        } catch (RuntimeException | IOException e) {
            VideoPlayerMain.LOGGER.warn("Failed to load MPV filter graph config", e);
            return new State(true, defaultDocument());
        }
    }

    public static void save(State state) {
        try {
            Files.createDirectories(PATH.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("version", STORE_VERSION);
            root.addProperty("autoApply", state == null || state.autoApply());
            GraphDocument document = state == null ? defaultDocument() : state.document();
            root.add("document", JsonParser.parseString(CODEC.toJson(document)).getAsJsonObject());
            Files.writeString(PATH, GSON.toJson(root));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static GraphDocument defaultDocument() {
        NodeId input = new NodeId("mpv_input");
        NodeId output = new NodeId("mpv_output");
        GraphDefinition graph = new GraphDefinition(
                List.of(
                        new NodeInstance(input, MpvFilterGraphNodes.INPUT_ID, MpvFilterGraphNodes.json()),
                        new NodeInstance(output, MpvFilterGraphNodes.OUTPUT_ID, MpvFilterGraphNodes.json())
                ),
                List.of()
        );
        GraphLayout layout = new GraphLayout(Map.of(
                input, new NodePosition(80, 120),
                output, new NodePosition(520, 120)
        ));
        return GraphDocument.of(graph, layout);
    }

    public record State(boolean autoApply, GraphDocument document) {
    }
}
