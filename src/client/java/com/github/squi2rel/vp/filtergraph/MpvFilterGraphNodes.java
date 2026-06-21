package com.github.squi2rel.vp.filtergraph;

import com.github.squi2rel.mcng.core.MCNGPortTypes;
import com.github.squi2rel.mcng.core.NodeConfigCodec;
import com.github.squi2rel.mcng.core.NodeConfigValues;
import com.github.squi2rel.mcng.core.NodeControlMode;
import com.github.squi2rel.mcng.core.NodeEditorControl;
import com.github.squi2rel.mcng.core.NodeExecutionResult;
import com.github.squi2rel.mcng.core.NodeInstance;
import com.github.squi2rel.mcng.core.NodeKind;
import com.github.squi2rel.mcng.core.NodeType;
import com.github.squi2rel.mcng.core.NodeTypeRegistry;
import com.github.squi2rel.mcng.core.PortDefinition;
import com.github.squi2rel.mcng.core.PortDirection;
import com.github.squi2rel.mcng.core.PortId;
import com.github.squi2rel.mcng.core.PortInlineWidget;
import com.github.squi2rel.mcng.core.PortType;
import com.github.squi2rel.mcng.fabric.client.NodePaletteDefinition;
import com.github.squi2rel.mcng.fabric.client.NodePaletteRegistry;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class MpvFilterGraphNodes {
    public static final String INPUT_ID = "videoplayer:mpv_input";
    public static final String OUTPUT_ID = "videoplayer:mpv_output";
    public static final String FILTER_ID = "videoplayer:lavfi_filter";
    public static final String GENERATOR_ID = "videoplayer:lavfi_generator";
    public static final String CONCRETE_FILTER_PREFIX = "videoplayer:lavfi_filter/";
    public static final String SPLIT_ID = "videoplayer:split";
    public static final String ASPLIT_ID = "videoplayer:asplit";
    public static final String OPTION_PORT_PREFIX = "opt_";

    public static final PortId VIDEO_PORT = new PortId("vf");
    public static final PortId AUDIO_PORT = new PortId("af");
    public static final String FILTER_KEY = "filter";
    public static final String OPTIONS_KEY = "options";
    public static final String INPUTS_KEY = "inputs";
    public static final String OUTPUTS_KEY = "outputs";
    public static final int MIN_DYNAMIC_PORTS = 2;
    public static final int MAX_DYNAMIC_PORTS = 16;

    private static final JsonCodec JSON_CODEC = new JsonCodec();
    private static final NodeEditorControl.TextControl FILTER_CONTROL = new NodeEditorControl.TextControl(FILTER_KEY, "Filter", "scale");
    private static final NodeEditorControl.TextControl GENERATOR_CONTROL = new NodeEditorControl.TextControl(FILTER_KEY, "Filter", "color");
    private static final NodeEditorControl.TextControl OPTIONS_CONTROL = new NodeEditorControl.TextControl(OPTIONS_KEY, "Options", "");
    private static final NodeEditorControl.NumericTextControl INPUTS_CONTROL = new NodeEditorControl.NumericTextControl(INPUTS_KEY, "Inputs", "2");
    private static final NodeEditorControl.NumericTextControl OUTPUTS_CONTROL = new NodeEditorControl.NumericTextControl(OUTPUTS_KEY, "Outputs", "2");

    public static final NodeType<JsonObject> INPUT = new StaticNodeType(
            INPUT_ID,
            "MPV Input",
            List.of(),
            List.of(
                    PortDefinition.output("vf", "Video", MpvFilterGraphTypes.VIDEO_STREAM),
                    PortDefinition.output("af", "Audio", MpvFilterGraphTypes.AUDIO_STREAM)
            ),
            json()
    );

    public static final NodeType<JsonObject> OUTPUT = new StaticNodeType(
            OUTPUT_ID,
            "MPV Output",
            List.of(
                    PortDefinition.input("vf", "Video", MpvFilterGraphTypes.VIDEO_STREAM, false),
                    PortDefinition.input("af", "Audio", MpvFilterGraphTypes.AUDIO_STREAM, false)
            ),
            List.of(),
            json()
    );

    public static final NodeType<JsonObject> FILTER = new LavfiNodeType(FILTER_ID, "Lavfi Filter", false);
    public static final NodeType<JsonObject> GENERATOR = new LavfiNodeType(GENERATOR_ID, "Lavfi Generator", true);
    public static final NodeType<JsonObject> SPLIT = new SplitNodeType(SPLIT_ID, "Split", false);
    public static final NodeType<JsonObject> ASPLIT = new SplitNodeType(ASPLIT_ID, "Audio Split", true);

    private MpvFilterGraphNodes() {
    }

    public static NodeTypeRegistry createRegistry() {
        NodeTypeRegistry registry = new NodeTypeRegistry();
        registry.register(INPUT);
        registry.register(OUTPUT);
        registry.register(FILTER);
        registry.register(GENERATOR);
        registry.register(SPLIT);
        registry.register(ASPLIT);
        MpvLavfiFilterCatalog.Catalog catalog = MpvLavfiFilterCatalog.get();
        if (catalog.available()) {
            for (MpvLavfiFilterCatalog.Filter filter : catalog.sorted()) {
                registry.register(new ConcreteLavfiNodeType(filter));
            }
        }
        return registry;
    }

    public static NodePaletteRegistry createPalette() {
        NodePaletteRegistry registry = new NodePaletteRegistry();
        registry.register(new NodePaletteDefinition(INPUT_ID, "MPV", 10, 10, List.of("input", "source", "vid1", "aid1")));
        registry.register(new NodePaletteDefinition(OUTPUT_ID, "MPV", 10, 20, List.of("output", "sink", "vo", "ao")));
        registry.register(new NodePaletteDefinition(SPLIT_ID, "Lavfi Utility", 20, 10, List.of("split", "video")));
        registry.register(new NodePaletteDefinition(ASPLIT_ID, "Lavfi Utility", 20, 20, List.of("split", "audio", "asplit")));

        MpvLavfiFilterCatalog.Catalog catalog = MpvLavfiFilterCatalog.get();
        if (catalog.available()) {
            Map<String, Integer> sectionCounts = new HashMap<>();
            for (MpvLavfiFilterCatalog.Filter filter : catalog.sorted()) {
                PaletteSection section = paletteSection(filter);
                int sectionIndex = sectionCounts.getOrDefault(section.title(), 0);
                sectionCounts.put(section.title(), sectionIndex + 1);
                int itemOrder = 10 + sectionIndex * 10;
                registry.register(new NodePaletteDefinition(
                        filterNodeId(filter.name()),
                        section.title(),
                        section.order(),
                        itemOrder,
                        searchKeywords(filter, section)
                ));
            }
        }
        return registry;
    }

    public static String filterName(NodeInstance node) {
        if (node == null) return "";
        Optional<String> name = filterNameFromTypeId(node.typeId());
        return name.orElseGet(() -> string(node.config(), FILTER_KEY));
    }

    public static Optional<MpvLavfiFilterCatalog.Filter> filterForNode(NodeInstance node) {
        String name = filterName(node);
        return name.isBlank() ? Optional.empty() : MpvLavfiFilterCatalog.get().find(name);
    }

    public static String options(NodeInstance node) {
        return string(node == null ? null : node.config(), OPTIONS_KEY);
    }

    public static int inputCount(NodeInstance node) {
        return intValue(node == null ? null : node.config(), INPUTS_KEY, MIN_DYNAMIC_PORTS);
    }

    public static int outputCount(NodeInstance node) {
        return intValue(node == null ? null : node.config(), OUTPUTS_KEY, MIN_DYNAMIC_PORTS);
    }

    public static boolean isLavfiNode(String typeId) {
        return FILTER_ID.equals(typeId) || GENERATOR_ID.equals(typeId) || isConcreteLavfiNode(typeId);
    }

    public static boolean isLegacyGeneratorNode(String typeId) {
        return GENERATOR_ID.equals(typeId);
    }

    public static boolean isConcreteLavfiNode(String typeId) {
        return typeId != null && typeId.startsWith(CONCRETE_FILTER_PREFIX);
    }

    public static String filterNodeId(String filterName) {
        String encoded = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString((filterName == null ? "" : filterName).getBytes(StandardCharsets.UTF_8));
        return CONCRETE_FILTER_PREFIX + encoded;
    }

    public static Optional<String> filterNameFromTypeId(String typeId) {
        if (!isConcreteLavfiNode(typeId)) return Optional.empty();
        String encoded = typeId.substring(CONCRETE_FILTER_PREFIX.length());
        if (encoded.isBlank()) return Optional.empty();
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(encoded);
            String name = new String(decoded, StandardCharsets.UTF_8).trim();
            return name.isBlank() ? Optional.empty() : Optional.of(name);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public static boolean isSplitNode(String typeId) {
        return SPLIT_ID.equals(typeId) || ASPLIT_ID.equals(typeId);
    }

    public static boolean isAudioSplit(String typeId) {
        return ASPLIT_ID.equals(typeId);
    }

    public static int portIndex(PortId portId, String prefix) {
        String value = portId == null ? "" : portId.value();
        if (!value.startsWith(prefix)) return -1;
        try {
            return Integer.parseInt(value.substring(prefix.length()));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public static String inputPortId(int index) {
        return "in" + index;
    }

    public static String outputPortId(int index) {
        return "out" + index;
    }

    public static JsonObject json() {
        return new JsonObject();
    }

    public static JsonObject filterDefault(String name) {
        JsonObject json = new JsonObject();
        json.add(FILTER_KEY, new JsonPrimitive(name));
        json.add(OPTIONS_KEY, new JsonPrimitive(""));
        json.add(INPUTS_KEY, new JsonPrimitive(MIN_DYNAMIC_PORTS));
        json.add(OUTPUTS_KEY, new JsonPrimitive(MIN_DYNAMIC_PORTS));
        return json;
    }

    public static List<PortDefinition> lavfiInputs(NodeInstance node) {
        ArrayList<PortDefinition> ports = new ArrayList<>(lavfiStreamInputs(node));
        ports.addAll(lavfiOptionInputs(node));
        return List.copyOf(ports);
    }

    public static List<PortDefinition> lavfiStreamInputs(NodeInstance node) {
        Optional<MpvLavfiFilterCatalog.Filter> filter = filterForNode(node);
        if (filter.isEmpty()) return List.of();
        return lavfiPorts(filter.get(), node, PortDirection.INPUT, isLegacyGeneratorNode(node.typeId()));
    }

    public static List<PortDefinition> lavfiOptionInputs(NodeInstance node) {
        Optional<MpvLavfiFilterCatalog.Filter> filter = filterForNode(node);
        if (filter.isEmpty()) return List.of();
        return optionPorts(filter.get());
    }

    public static List<PortDefinition> lavfiOutputs(NodeInstance node) {
        Optional<MpvLavfiFilterCatalog.Filter> filter = filterForNode(node);
        if (filter.isEmpty()) return List.of();
        return lavfiPorts(filter.get(), node, PortDirection.OUTPUT, false);
    }

    public static boolean isOptionPort(PortId portId) {
        return portId != null && portId.value().startsWith(OPTION_PORT_PREFIX);
    }

    public static Optional<String> optionNameFromPortId(PortId portId) {
        if (!isOptionPort(portId)) return Optional.empty();
        String encoded = portId.value().substring(OPTION_PORT_PREFIX.length());
        if (encoded.isBlank()) return Optional.empty();
        try {
            String name = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8).trim();
            return name.isBlank() ? Optional.empty() : Optional.of(name);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public static Optional<MpvLavfiFilterCatalog.Option> optionForPort(MpvLavfiFilterCatalog.Filter filter, PortId portId) {
        Optional<String> name = optionNameFromPortId(portId);
        if (name.isEmpty()) return Optional.empty();
        return filter.options().stream().filter(option -> option.name().equals(name.get())).findFirst();
    }

    public static boolean hasInlineInput(NodeInstance node, PortId portId) {
        try {
            JsonObject config = node == null ? null : node.config();
            JsonObject inlineInputs = config != null && config.has(NodeConfigValues.INLINE_INPUTS_KEY) && config.get(NodeConfigValues.INLINE_INPUTS_KEY).isJsonObject()
                    ? config.getAsJsonObject(NodeConfigValues.INLINE_INPUTS_KEY)
                    : null;
            return inlineInputs != null && inlineInputs.has(portId.value());
        } catch (RuntimeException e) {
            return false;
        }
    }

    public static String inlineInputText(NodeInstance node, PortDefinition port) {
        Object value = NodeConfigValues.readInlineInputValue(node.config(), port);
        return value == null ? "" : String.valueOf(value).trim();
    }

    static PortType<?> portType(MpvLavfiFilterCatalog.MediaType type) {
        return switch (type) {
            case VIDEO -> MpvFilterGraphTypes.VIDEO_STREAM;
            case AUDIO -> MpvFilterGraphTypes.AUDIO_STREAM;
            case OTHER -> MCNGPortTypes.ANY;
        };
    }

    private static List<PortDefinition> lavfiPorts(MpvLavfiFilterCatalog.Filter filter, NodeInstance node, PortDirection direction, boolean suppressInputs) {
        if (direction == PortDirection.INPUT && suppressInputs) return List.of();
        List<MpvLavfiFilterCatalog.Pad> pads = direction == PortDirection.INPUT ? filter.inputs() : filter.outputs();
        boolean dynamic = direction == PortDirection.INPUT ? filter.dynamicInputs() : filter.dynamicOutputs();
        int count = dynamic
                ? dynamicCount(filter, node, direction)
                : pads.size();
        count = Math.clamp(count, 0, MAX_DYNAMIC_PORTS);
        ArrayList<PortDefinition> ports = new ArrayList<>();
        MpvLavfiFilterCatalog.MediaType fallbackType = fallbackType(pads, direction == PortDirection.INPUT ? filter.outputs() : filter.inputs());
        for (int i = 0; i < count; i++) {
            MpvLavfiFilterCatalog.Pad pad = i < pads.size() ? pads.get(i) : new MpvLavfiFilterCatalog.Pad("", fallbackType);
            String name = pad.name().isBlank() ? (direction == PortDirection.INPUT ? "In " : "Out ") + (i + 1) : title(pad.name());
            if (direction == PortDirection.INPUT) {
                ports.add(PortDefinition.input(inputPortId(i), name, portType(pad.type()), true));
            } else {
                ports.add(PortDefinition.output(outputPortId(i), name, portType(pad.type())));
            }
        }
        return List.copyOf(ports);
    }

    private static int dynamicCount(MpvLavfiFilterCatalog.Filter filter, NodeInstance node, PortDirection direction) {
        String optionName = direction == PortDirection.INPUT ? "inputs" : "outputs";
        Optional<MpvLavfiFilterCatalog.Option> option = filter.options().stream()
                .filter(candidate -> candidate.name().equals(optionName))
                .findFirst();
        if (option.isPresent()) {
            PortDefinition port = optionPort(option.get());
            try {
                return Math.clamp(Integer.parseInt(inlineInputText(node, port)), 0, MAX_DYNAMIC_PORTS);
            } catch (RuntimeException ignored) {
            }
        }
        return direction == PortDirection.INPUT ? inputCount(node) : outputCount(node);
    }

    private static List<PortDefinition> optionPorts(MpvLavfiFilterCatalog.Filter filter) {
        ArrayList<PortDefinition> ports = new ArrayList<>();
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (MpvLavfiFilterCatalog.Option option : filter.options()) {
            if (option.name().isBlank() || !names.add(option.name())) continue;
            ports.add(optionPort(option));
        }
        return List.copyOf(ports);
    }

    private static PortDefinition optionPort(MpvLavfiFilterCatalog.Option option) {
        String id = optionPortId(option.name());
        String name = title(option.name());
        return switch (optionType(option)) {
            case BOOLEAN -> PortDefinition.input(id, name, MCNGPortTypes.BOOLEAN, false, PortInlineWidget.booleanToggle(Boolean.parseBoolean(option.defaultValue())));
            case NUMERIC -> PortDefinition.inputNumeric(id, name, false, PortInlineWidget.numericText(numericDefault(option)));
            case STRING -> PortDefinition.input(id, name, MCNGPortTypes.STRING, false, PortInlineWidget.stringText(option.defaultValue() == null ? "" : option.defaultValue()));
        };
    }

    private static String optionPortId(String optionName) {
        return OPTION_PORT_PREFIX + Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString((optionName == null ? "" : optionName).getBytes(StandardCharsets.UTF_8));
    }

    private static OptionPortKind optionType(MpvLavfiFilterCatalog.Option option) {
        return switch (option.type() == null ? "" : option.type().trim().toLowerCase(Locale.ROOT)) {
            case "bool" -> OptionPortKind.BOOLEAN;
            case "int", "int64", "uint64", "duration", "flags", "double", "float" -> OptionPortKind.NUMERIC;
            default -> OptionPortKind.STRING;
        };
    }

    private static String numericDefault(MpvLavfiFilterCatalog.Option option) {
        String value = option.defaultValue() == null ? "" : option.defaultValue().trim();
        if (!value.isBlank()) return value;
        return switch (option.type() == null ? "" : option.type().trim().toLowerCase(Locale.ROOT)) {
            case "double", "float" -> "0.0";
            default -> "0";
        };
    }

    private static JsonObject concreteFilterDefault(MpvLavfiFilterCatalog.Filter filter) {
        JsonObject json = new JsonObject();
        if (filter.dynamicInputs()) {
            json.add(INPUTS_KEY, new JsonPrimitive(dynamicDefaultCount(filter.inputs().size())));
        }
        if (filter.dynamicOutputs()) {
            json.add(OUTPUTS_KEY, new JsonPrimitive(dynamicDefaultCount(filter.outputs().size())));
        }
        return json;
    }

    private static int dynamicDefaultCount(int metadataPads) {
        return Math.clamp(Math.max(MIN_DYNAMIC_PORTS, metadataPads), MIN_DYNAMIC_PORTS, MAX_DYNAMIC_PORTS);
    }

    private static PaletteSection paletteSection(MpvLavfiFilterCatalog.Filter filter) {
        boolean hasInputs = !filter.inputs().isEmpty() || filter.dynamicInputs();
        boolean hasOutputs = !filter.outputs().isEmpty() || filter.dynamicOutputs();
        if (!hasInputs && hasOutputs) return new PaletteSection("Lavfi Generators", 30);
        if (generatesVideoWithoutVideoInput(filter)) return new PaletteSection("Lavfi Generators", 30);
        boolean audio = hasMedia(filter, MpvLavfiFilterCatalog.MediaType.AUDIO);
        boolean video = hasMedia(filter, MpvLavfiFilterCatalog.MediaType.VIDEO);
        if (video && !audio) return new PaletteSection("Lavfi Video", 40);
        if (audio && !video) return new PaletteSection("Lavfi Audio", 50);
        if (audio) return new PaletteSection("Lavfi Audio/Video", 60);
        return new PaletteSection("Lavfi Other", 70);
    }

    private static boolean generatesVideoWithoutVideoInput(MpvLavfiFilterCatalog.Filter filter) {
        boolean videoInput = hasMedia(filter.inputs(), MpvLavfiFilterCatalog.MediaType.VIDEO);
        boolean videoOutput = hasMedia(filter.outputs(), MpvLavfiFilterCatalog.MediaType.VIDEO) || filter.dynamicOutputs();
        return videoOutput && !videoInput;
    }

    private static boolean hasMedia(MpvLavfiFilterCatalog.Filter filter, MpvLavfiFilterCatalog.MediaType mediaType) {
        for (MpvLavfiFilterCatalog.Pad pad : filter.inputs()) {
            if (pad.type() == mediaType) return true;
        }
        for (MpvLavfiFilterCatalog.Pad pad : filter.outputs()) {
            if (pad.type() == mediaType) return true;
        }
        return false;
    }

    private static boolean hasMedia(List<MpvLavfiFilterCatalog.Pad> pads, MpvLavfiFilterCatalog.MediaType mediaType) {
        for (MpvLavfiFilterCatalog.Pad pad : pads) {
            if (pad.type() == mediaType) return true;
        }
        return false;
    }

    private static List<String> searchKeywords(MpvLavfiFilterCatalog.Filter filter, PaletteSection section) {
        LinkedHashSet<String> keywords = new LinkedHashSet<>();
        addKeyword(keywords, "lavfi");
        addKeyword(keywords, "ffmpeg");
        addKeyword(keywords, filter.name());
        for (String part : section.title().split("[ /]+")) {
            addKeyword(keywords, part);
        }
        for (MpvLavfiFilterCatalog.Option option : filter.options()) {
            addKeyword(keywords, option.name());
            if (keywords.size() >= 40) break;
        }
        if (generatesVideoWithoutVideoInput(filter)) {
            addKeyword(keywords, "generator");
            addKeyword(keywords, "visualizer");
            addKeyword(keywords, "scope");
        }
        if (!filter.description().isBlank() && keywords.size() < 40) {
            for (String part : filter.description().split("[^A-Za-z0-9_+-]+")) {
                addKeyword(keywords, part);
                if (keywords.size() >= 40) break;
            }
        }
        return List.copyOf(keywords);
    }

    private static void addKeyword(Set<String> keywords, String keyword) {
        if (keyword == null) return;
        String value = keyword.trim().toLowerCase(Locale.ROOT);
        if (value.length() >= 2) keywords.add(value);
    }

    private static MpvLavfiFilterCatalog.MediaType fallbackType(List<MpvLavfiFilterCatalog.Pad> preferred, List<MpvLavfiFilterCatalog.Pad> other) {
        for (MpvLavfiFilterCatalog.Pad pad : preferred) {
            if (pad.type() != MpvLavfiFilterCatalog.MediaType.OTHER) return pad.type();
        }
        for (MpvLavfiFilterCatalog.Pad pad : other) {
            if (pad.type() != MpvLavfiFilterCatalog.MediaType.OTHER) return pad.type();
        }
        return MpvLavfiFilterCatalog.MediaType.OTHER;
    }

    private static String title(String value) {
        if (value == null || value.isBlank()) return "";
        String normalized = value.replace('_', ' ').replace('-', ' ');
        return normalized.substring(0, 1).toUpperCase(Locale.ROOT) + normalized.substring(1);
    }

    private static String string(JsonObject json, String key) {
        try {
            return json != null && json.has(key) ? json.get(key).getAsString().trim() : "";
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static int intValue(JsonObject json, String key, int fallback) {
        try {
            return Math.clamp(json != null && json.has(key) ? json.get(key).getAsInt() : fallback, MIN_DYNAMIC_PORTS, MAX_DYNAMIC_PORTS);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static class StaticNodeType implements NodeType<JsonObject> {
        private final String id;
        private final String displayName;
        private final List<PortDefinition> inputs;
        private final List<PortDefinition> outputs;
        private final JsonObject defaultConfig;

        private StaticNodeType(String id, String displayName, List<PortDefinition> inputs, List<PortDefinition> outputs, JsonObject defaultConfig) {
            this.id = id;
            this.displayName = displayName;
            this.inputs = List.copyOf(inputs);
            this.outputs = List.copyOf(outputs);
            this.defaultConfig = defaultConfig;
        }

        @Override public String id() { return id; }
        @Override public String displayName() { return displayName; }
        @Override public NodeKind kind() { return NodeKind.COMPUTE; }
        @Override public List<PortDefinition> inputs() { return inputs; }
        @Override public List<PortDefinition> outputs() { return outputs; }
        @Override public JsonObject defaultConfig() { return defaultConfig; }
        @Override public NodeConfigCodec<JsonObject> configCodec() { return JSON_CODEC; }
        @Override public NodeExecutionResult execute(com.github.squi2rel.mcng.core.NodeExecutionRequest<JsonObject> request) { return NodeExecutionResult.of(Map.of()); }
    }

    private static final class LavfiNodeType extends StaticNodeType {
        private final boolean generator;

        private LavfiNodeType(String id, String displayName, boolean generator) {
            super(id, displayName, List.of(), List.of(), filterDefault(generator ? "color" : "scale"));
            this.generator = generator;
        }

        @Override
        public List<PortDefinition> inputs(NodeInstance node) {
            Optional<MpvLavfiFilterCatalog.Filter> filter = filterForNode(node);
            if (filter.isEmpty()) return List.of();
            ArrayList<PortDefinition> ports = new ArrayList<>(lavfiPorts(filter.get(), node, PortDirection.INPUT, generator));
            ports.addAll(optionPorts(filter.get()));
            return List.copyOf(ports);
        }

        @Override
        public List<PortDefinition> outputs(NodeInstance node) {
            Optional<MpvLavfiFilterCatalog.Filter> filter = filterForNode(node);
            return filter.map(value -> lavfiPorts(value, node, PortDirection.OUTPUT, false)).orElseGet(List::of);
        }

        @Override
        public List<NodeEditorControl> editorControls() {
            return generator
                    ? List.of(GENERATOR_CONTROL, OPTIONS_CONTROL, OUTPUTS_CONTROL)
                    : List.of(FILTER_CONTROL, OPTIONS_CONTROL, INPUTS_CONTROL, OUTPUTS_CONTROL);
        }
    }

    private static final class ConcreteLavfiNodeType extends StaticNodeType {
        private final MpvLavfiFilterCatalog.Filter filter;

        private ConcreteLavfiNodeType(MpvLavfiFilterCatalog.Filter filter) {
            super(filterNodeId(filter.name()), filter.name(), List.of(), List.of(), concreteFilterDefault(filter));
            this.filter = filter;
        }

        @Override
        public List<PortDefinition> inputs(NodeInstance node) {
            ArrayList<PortDefinition> ports = new ArrayList<>(lavfiPorts(filter, node, PortDirection.INPUT, false));
            ports.addAll(optionPorts(filter));
            return List.copyOf(ports);
        }

        @Override
        public List<PortDefinition> outputs(NodeInstance node) {
            return lavfiPorts(filter, node, PortDirection.OUTPUT, false);
        }

        @Override
        public List<NodeEditorControl> editorControls() {
            return List.of();
        }
    }

    private record PaletteSection(String title, int order) {
    }

    private enum OptionPortKind {
        BOOLEAN,
        NUMERIC,
        STRING
    }

    private static final class SplitNodeType extends StaticNodeType {
        private final boolean audio;

        private SplitNodeType(String id, String displayName, boolean audio) {
            super(id, displayName, List.of(), List.of(), splitDefault());
            this.audio = audio;
        }

        @Override
        public List<PortDefinition> inputs(NodeInstance node) {
            return List.of(PortDefinition.input("in0", "In", audio ? MpvFilterGraphTypes.AUDIO_STREAM : MpvFilterGraphTypes.VIDEO_STREAM, true));
        }

        @Override
        public List<PortDefinition> outputs(NodeInstance node) {
            int count = outputCount(node);
            ArrayList<PortDefinition> ports = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                ports.add(PortDefinition.output(outputPortId(i), "Out " + (i + 1), audio ? MpvFilterGraphTypes.AUDIO_STREAM : MpvFilterGraphTypes.VIDEO_STREAM));
            }
            return List.copyOf(ports);
        }

        @Override
        public List<NodeEditorControl> editorControls() {
            return List.of(OUTPUTS_CONTROL);
        }

        private static JsonObject splitDefault() {
            JsonObject json = new JsonObject();
            json.add(OUTPUTS_KEY, new JsonPrimitive(MIN_DYNAMIC_PORTS));
            return json;
        }
    }

    private static final class JsonCodec implements NodeConfigCodec<JsonObject> {
        @Override
        public JsonObject toJson(JsonObject config) {
            return config == null ? new JsonObject() : config.deepCopy();
        }

        @Override
        public JsonObject fromJson(JsonObject json) {
            return json == null ? new JsonObject() : json.deepCopy();
        }
    }
}
