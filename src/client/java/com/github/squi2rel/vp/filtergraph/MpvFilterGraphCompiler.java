package com.github.squi2rel.vp.filtergraph;

import com.github.squi2rel.mcng.core.EdgeDefinition;
import com.github.squi2rel.mcng.core.GraphDefinition;
import com.github.squi2rel.mcng.core.GraphDocument;
import com.github.squi2rel.mcng.core.NodeId;
import com.github.squi2rel.mcng.core.NodeInstance;
import com.github.squi2rel.mcng.core.PortDefinition;
import com.github.squi2rel.mcng.core.PortDirection;
import com.github.squi2rel.mcng.core.PortId;
import com.github.squi2rel.mcng.core.PortType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class MpvFilterGraphCompiler {
    private MpvFilterGraphCompiler() {
    }

    public static CompileResult compile(GraphDocument document) {
        return compile(document, true, true);
    }

    public static CompileResult compile(GraphDocument document, boolean videoInputAvailable, boolean audioInputAvailable) {
        try {
            Compiler compiler = new Compiler(
                    document == null ? MpvFilterGraphStore.defaultDocument().graph() : document.graph(),
                    videoInputAvailable,
                    audioInputAvailable
            );
            return new CompileResult(true, compiler.compile(), "");
        } catch (CompileException e) {
            return new CompileResult(false, "", e.getMessage());
        } catch (RuntimeException e) {
            return new CompileResult(false, "", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    public record CompileResult(boolean success, String graph, String error) {
        public boolean empty() {
            return success && (graph == null || graph.isBlank());
        }
    }

    private static final class Compiler {
        private final Map<NodeId, NodeInstance> nodes = new LinkedHashMap<>();
        private final List<EdgeDefinition> edges;
        private final Map<PortKey, List<EdgeDefinition>> allOutgoing = new HashMap<>();
        private final Map<PortKey, List<EdgeDefinition>> allIncoming = new HashMap<>();
        private final Map<PortKey, List<EdgeDefinition>> outgoing = new HashMap<>();
        private final Map<PortKey, List<EdgeDefinition>> incoming = new HashMap<>();
        private final Map<PortKey, String> outputLabels = new HashMap<>();
        private final Map<PortKey, Map<EdgeDefinition, String>> splitLabels = new HashMap<>();
        private final Set<NodeId> compiledNodes = new HashSet<>();
        private final Set<NodeId> compilingNodes = new HashSet<>();
        private final List<String> segments = new ArrayList<>();
        private final boolean videoInputAvailable;
        private final boolean audioInputAvailable;
        private int nextLabel;

        private Compiler(GraphDefinition graph, boolean videoInputAvailable, boolean audioInputAvailable) {
            this.videoInputAvailable = videoInputAvailable;
            this.audioInputAvailable = audioInputAvailable;
            for (NodeInstance node : graph.nodes()) {
                nodes.put(node.id(), node);
            }
            edges = graph.edges().stream()
                    .sorted(Comparator.comparing((EdgeDefinition edge) -> edge.fromNodeId().value())
                            .thenComparing(edge -> edge.fromPortId().value())
                            .thenComparing(edge -> edge.toNodeId().value())
                            .thenComparing(edge -> edge.toPortId().value()))
                    .toList();
            for (EdgeDefinition edge : edges) {
                allOutgoing.computeIfAbsent(new PortKey(edge.fromNodeId(), edge.fromPortId()), ignored -> new ArrayList<>()).add(edge);
                allIncoming.computeIfAbsent(new PortKey(edge.toNodeId(), edge.toPortId()), ignored -> new ArrayList<>()).add(edge);
            }
            if (videoInputAvailable && audioInputAvailable) {
                copyEdges(edges);
            } else {
                copyEdges(activeEdges());
            }
        }

        private void copyEdges(Iterable<EdgeDefinition> sourceEdges) {
            for (EdgeDefinition edge : sourceEdges) {
                outgoing.computeIfAbsent(new PortKey(edge.fromNodeId(), edge.fromPortId()), ignored -> new ArrayList<>()).add(edge);
                incoming.computeIfAbsent(new PortKey(edge.toNodeId(), edge.toPortId()), ignored -> new ArrayList<>()).add(edge);
            }
        }

        private Set<EdgeDefinition> activeEdges() {
            LinkedHashMap<EdgeDefinition, Boolean> active = new LinkedHashMap<>();
            List<NodeInstance> outputs = nodes.values().stream().filter(node -> MpvFilterGraphNodes.OUTPUT_ID.equals(node.typeId())).toList();
            if (outputs.size() != 1) return Set.of();
            NodeInstance output = outputs.get(0);
            markActiveOutput(output, MpvFilterGraphNodes.VIDEO_PORT, active);
            markActiveOutput(output, MpvFilterGraphNodes.AUDIO_PORT, active);
            return active.keySet();
        }

        private void markActiveOutput(NodeInstance output, PortId portId, Map<EdgeDefinition, Boolean> active) {
            List<EdgeDefinition> inputs = allIncoming.getOrDefault(new PortKey(output.id(), portId), List.of());
            if (inputs.size() != 1) {
                for (EdgeDefinition edge : inputs) {
                    active.put(edge, Boolean.TRUE);
                }
                return;
            }
            EdgeDefinition edge = inputs.get(0);
            if (!dependsOnUnavailableInput(edge, new HashSet<>())) {
                markActiveUpstream(edge, active);
            }
        }

        private void markActiveUpstream(EdgeDefinition edge, Map<EdgeDefinition, Boolean> active) {
            if (active.put(edge, Boolean.TRUE) != null) return;
            NodeInstance node = nodes.get(edge.fromNodeId());
            if (node == null || MpvFilterGraphNodes.INPUT_ID.equals(node.typeId())) return;

            if (MpvFilterGraphNodes.isLavfiNode(node.typeId())) {
                for (PortDefinition input : MpvFilterGraphNodes.lavfiStreamInputs(node)) {
                    for (EdgeDefinition inputEdge : allIncoming.getOrDefault(new PortKey(node.id(), input.id()), List.of())) {
                        markActiveUpstream(inputEdge, active);
                    }
                }
                for (PortDefinition input : MpvFilterGraphNodes.lavfiOptionInputs(node)) {
                    for (EdgeDefinition inputEdge : allIncoming.getOrDefault(new PortKey(node.id(), input.id()), List.of())) {
                        active.put(inputEdge, Boolean.TRUE);
                    }
                }
            } else if (MpvFilterGraphNodes.isSplitNode(node.typeId())) {
                PortDefinition input = (MpvFilterGraphNodes.isAudioSplit(node.typeId()) ? MpvFilterGraphNodes.ASPLIT : MpvFilterGraphNodes.SPLIT).inputs(node).get(0);
                for (EdgeDefinition inputEdge : allIncoming.getOrDefault(new PortKey(node.id(), input.id()), List.of())) {
                    markActiveUpstream(inputEdge, active);
                }
            }
        }

        private boolean dependsOnUnavailableInput(EdgeDefinition edge, Set<PortKey> visiting) {
            return outputDependsOnUnavailableInput(new PortKey(edge.fromNodeId(), edge.fromPortId()), visiting);
        }

        private boolean outputDependsOnUnavailableInput(PortKey output, Set<PortKey> visiting) {
            if (!visiting.add(output)) return false;
            try {
                NodeInstance node = nodes.get(output.nodeId());
                if (node == null) return false;
                if (MpvFilterGraphNodes.INPUT_ID.equals(node.typeId())) {
                    if (MpvFilterGraphNodes.VIDEO_PORT.equals(output.portId())) return !videoInputAvailable;
                    if (MpvFilterGraphNodes.AUDIO_PORT.equals(output.portId())) return !audioInputAvailable;
                    return false;
                }
                if (MpvFilterGraphNodes.isLavfiNode(node.typeId())) {
                    for (PortDefinition input : MpvFilterGraphNodes.lavfiStreamInputs(node)) {
                        if (inputDependsOnUnavailableInput(node.id(), input.id(), visiting)) return true;
                    }
                } else if (MpvFilterGraphNodes.isSplitNode(node.typeId())) {
                    PortDefinition input = (MpvFilterGraphNodes.isAudioSplit(node.typeId()) ? MpvFilterGraphNodes.ASPLIT : MpvFilterGraphNodes.SPLIT).inputs(node).get(0);
                    if (inputDependsOnUnavailableInput(node.id(), input.id(), visiting)) return true;
                }
                return false;
            } finally {
                visiting.remove(output);
            }
        }

        private boolean inputDependsOnUnavailableInput(NodeId nodeId, PortId portId, Set<PortKey> visiting) {
            List<EdgeDefinition> inputs = allIncoming.getOrDefault(new PortKey(nodeId, portId), List.of());
            if (inputs.size() != 1) return false;
            return dependsOnUnavailableInput(inputs.get(0), visiting);
        }

        private String compile() {
            singleNode(MpvFilterGraphNodes.INPUT_ID, "MPV Input");
            NodeInstance output = singleNode(MpvFilterGraphNodes.OUTPUT_ID, "MPV Output");
            if (output == null) return "";

            compileOutput(output, MpvFilterGraphNodes.VIDEO_PORT, "vo", Media.VIDEO);
            compileOutput(output, MpvFilterGraphNodes.AUDIO_PORT, "ao", Media.AUDIO);
            return String.join(";", segments);
        }

        private NodeInstance singleNode(String typeId, String displayName) {
            List<NodeInstance> matches = nodes.values().stream().filter(node -> typeId.equals(node.typeId())).toList();
            if (matches.size() > 1) throw error("Graph must contain only one " + displayName + " node");
            return matches.isEmpty() ? null : matches.get(0);
        }

        private void compileOutput(NodeInstance output, PortId portId, String targetLabel, Media media) {
            List<EdgeDefinition> inputs = incoming.getOrDefault(new PortKey(output.id(), portId), List.of());
            if (inputs.isEmpty()) return;
            if (inputs.size() > 1) throw error("Output port " + portId.value() + " has multiple inputs");
            EdgeDefinition edge = inputs.get(0);
            String source = labelForEdge(edge);
            segments.add(label(source) + (media == Media.VIDEO ? "null" : "anull") + label(targetLabel));
        }

        private String labelForEdge(EdgeDefinition edge) {
            PortKey output = new PortKey(edge.fromNodeId(), edge.fromPortId());
            List<EdgeDefinition> uses = outgoing.getOrDefault(output, List.of());
            if (uses.size() <= 1) {
                return baseOutputLabel(output);
            }
            return splitLabels.computeIfAbsent(output, key -> createImplicitSplit(key, uses)).get(edge);
        }

        private Map<EdgeDefinition, String> createImplicitSplit(PortKey output, List<EdgeDefinition> uses) {
            String base = baseOutputLabel(output);
            Media media = outputMedia(output);
            if (media == Media.OTHER) throw error("Cannot split non audio/video stream " + output.portId().value());
            int count = uses.size();
            ArrayList<String> labels = new ArrayList<>(count);
            for (int i = 0; i < count; i++) labels.add(nextLabel());
            StringBuilder segment = new StringBuilder();
            segment.append(label(base))
                    .append(media == Media.VIDEO ? "split" : "asplit")
                    .append("=outputs=")
                    .append(count);
            for (String label : labels) segment.append(label(label));
            segments.add(segment.toString());

            LinkedHashMap<EdgeDefinition, String> result = new LinkedHashMap<>();
            for (int i = 0; i < uses.size(); i++) result.put(uses.get(i), labels.get(i));
            return result;
        }

        private String baseOutputLabel(PortKey output) {
            String existing = outputLabels.get(output);
            if (existing != null) return existing;
            NodeInstance node = requireNode(output.nodeId());
            if (MpvFilterGraphNodes.INPUT_ID.equals(node.typeId())) {
                if (MpvFilterGraphNodes.VIDEO_PORT.equals(output.portId())) {
                    outputLabels.put(output, "vid1");
                    return "vid1";
                }
                if (MpvFilterGraphNodes.AUDIO_PORT.equals(output.portId())) {
                    outputLabels.put(output, "aid1");
                    return "aid1";
                }
                throw error("Unknown MPV input port " + output.portId().value());
            }

            compileNode(node);
            existing = outputLabels.get(output);
            if (existing == null) {
                throw error("Node " + node.id().value() + " does not produce " + output.portId().value());
            }
            return existing;
        }

        private void compileNode(NodeInstance node) {
            if (compiledNodes.contains(node.id())) return;
            if (!compilingNodes.add(node.id())) throw error("Filter graph contains a cycle");
            try {
                if (MpvFilterGraphNodes.isLavfiNode(node.typeId())) {
                    compileLavfiNode(node);
                } else if (MpvFilterGraphNodes.isSplitNode(node.typeId())) {
                    compileSplitNode(node);
                } else if (MpvFilterGraphNodes.OUTPUT_ID.equals(node.typeId()) || MpvFilterGraphNodes.INPUT_ID.equals(node.typeId())) {
                    return;
                } else {
                    throw error("Unsupported node type " + node.typeId());
                }
                compiledNodes.add(node.id());
            } finally {
                compilingNodes.remove(node.id());
            }
        }

        private void compileLavfiNode(NodeInstance node) {
            String name = MpvFilterGraphNodes.filterName(node);
            if (name.isBlank()) throw error("Lavfi node " + node.id().value() + " has no filter name");
            MpvLavfiFilterCatalog.Catalog catalog = MpvLavfiFilterCatalog.get();
            if (!catalog.available()) {
                throw error("MPV lavfi filter metadata is not available: " + catalog.error());
            }
            MpvLavfiFilterCatalog.Filter filter = MpvFilterGraphNodes.filterForNode(node)
                    .orElseThrow(() -> error("Unknown lavfi filter: " + name));
            if (MpvFilterGraphNodes.isLegacyGeneratorNode(node.typeId()) && (!filter.inputs().isEmpty() || filter.dynamicInputs())) {
                throw error("Lavfi filter " + name + " requires input pads and cannot be used as a generator");
            }

            List<PortDefinition> inputs = MpvFilterGraphNodes.lavfiStreamInputs(node);
            List<PortDefinition> outputs = MpvFilterGraphNodes.lavfiOutputs(node);
            if (outputs.isEmpty()) throw error("Lavfi filter " + name + " has no supported output pads");

            StringBuilder segment = new StringBuilder();
            for (PortDefinition input : inputs) {
                EdgeDefinition edge = singleIncoming(node.id(), input.id(), "input " + input.name() + " of " + name);
                segment.append(label(labelForEdge(edge)));
            }
            segment.append(filterSpec(filter.name(), optionsFor(node, filter)));
            for (PortDefinition output : outputs) {
                String out = nextLabel();
                outputLabels.put(new PortKey(node.id(), output.id()), out);
                segment.append(label(out));
            }
            segments.add(segment.toString());
            sinkUnusedOutputs(node, outputs);
        }

        private String optionsFor(NodeInstance node, MpvLavfiFilterCatalog.Filter filter) {
            ArrayList<String> parts = new ArrayList<>();
            String legacyOptions = MpvFilterGraphNodes.options(node);
            if (!legacyOptions.isBlank()) parts.add(legacyOptions);
            for (PortDefinition port : MpvFilterGraphNodes.lavfiOptionInputs(node)) {
                PortKey key = new PortKey(node.id(), port.id());
                List<EdgeDefinition> edges = incoming.getOrDefault(key, List.of());
                if (edges.size() > 1) {
                    throw error("Multiple edges connected to option " + port.name() + " of " + filter.name());
                }
                if (!edges.isEmpty()) {
                    throw error("Option " + port.name() + " of " + filter.name() + " must use its inline value");
                }
                if (!MpvFilterGraphNodes.hasInlineInput(node, port.id())) continue;
                String value = MpvFilterGraphNodes.inlineInputText(node, port);
                if (value.isBlank()) continue;
                MpvLavfiFilterCatalog.Option option = MpvFilterGraphNodes.optionForPort(filter, port.id())
                        .orElseThrow(() -> error("Unknown option port " + port.id().value() + " on " + filter.name()));
                if (option.defaultPresent() && value.equals(option.defaultValue())) continue;
                parts.add(option.name() + "=" + escapeOptionValue(value));
            }
            return String.join(":", parts);
        }

        private void compileSplitNode(NodeInstance node) {
            boolean audio = MpvFilterGraphNodes.isAudioSplit(node.typeId());
            PortDefinition input = (audio ? MpvFilterGraphNodes.ASPLIT : MpvFilterGraphNodes.SPLIT).inputs(node).get(0);
            EdgeDefinition edge = singleIncoming(node.id(), input.id(), audio ? "Audio Split input" : "Split input");
            List<PortDefinition> outputs = (audio ? MpvFilterGraphNodes.ASPLIT : MpvFilterGraphNodes.SPLIT).outputs(node);

            StringBuilder segment = new StringBuilder();
            segment.append(label(labelForEdge(edge)))
                    .append(audio ? "asplit" : "split")
                    .append("=outputs=")
                    .append(outputs.size());
            for (PortDefinition output : outputs) {
                String out = nextLabel();
                outputLabels.put(new PortKey(node.id(), output.id()), out);
                segment.append(label(out));
            }
            segments.add(segment.toString());
            sinkUnusedOutputs(node, outputs);
        }

        private EdgeDefinition singleIncoming(NodeId nodeId, PortId portId, String label) {
            List<EdgeDefinition> inputs = incoming.getOrDefault(new PortKey(nodeId, portId), List.of());
            if (inputs.isEmpty()) throw error("Missing " + label);
            if (inputs.size() > 1) throw error("Multiple edges connected to " + label);
            return inputs.get(0);
        }

        private void sinkUnusedOutputs(NodeInstance node, List<PortDefinition> outputs) {
            for (PortDefinition output : outputs) {
                PortKey key = new PortKey(node.id(), output.id());
                if (!outgoing.getOrDefault(key, List.of()).isEmpty()) continue;
                String out = outputLabels.get(key);
                if (out == null) continue;
                Media media = mediaFrom(output.type());
                if (media == Media.VIDEO) {
                    segments.add(label(out) + "nullsink");
                } else if (media == Media.AUDIO) {
                    segments.add(label(out) + "anullsink");
                }
            }
        }

        private Media outputMedia(PortKey output) {
            NodeInstance node = requireNode(output.nodeId());
            if (MpvFilterGraphNodes.INPUT_ID.equals(node.typeId())) {
                if (MpvFilterGraphNodes.VIDEO_PORT.equals(output.portId())) return Media.VIDEO;
                if (MpvFilterGraphNodes.AUDIO_PORT.equals(output.portId())) return Media.AUDIO;
            }
            List<PortDefinition> outputs;
            if (MpvFilterGraphNodes.isLavfiNode(node.typeId())) {
                outputs = MpvFilterGraphNodes.lavfiOutputs(node);
            } else if (MpvFilterGraphNodes.SPLIT_ID.equals(node.typeId())) {
                outputs = MpvFilterGraphNodes.SPLIT.outputs(node);
            } else if (MpvFilterGraphNodes.ASPLIT_ID.equals(node.typeId())) {
                outputs = MpvFilterGraphNodes.ASPLIT.outputs(node);
            } else {
                outputs = List.of();
            }
            for (PortDefinition port : outputs) {
                if (port.id().equals(output.portId())) return mediaFrom(port.type());
            }
            return Media.OTHER;
        }

        private NodeInstance requireNode(NodeId nodeId) {
            NodeInstance node = nodes.get(nodeId);
            if (node == null) throw error("Missing node " + nodeId.value());
            return node;
        }

        private String nextLabel() {
            return "vp" + nextLabel++;
        }

        private static String label(String label) {
            return "[" + label + "]";
        }

        private static String filterSpec(String name, String options) {
            String safeOptions = options == null ? "" : options.trim();
            return safeOptions.isBlank() ? name : name + "=" + safeOptions;
        }

        private static String escapeOptionValue(String value) {
            return value
                    .replace("\\", "\\\\")
                    .replace(":", "\\:")
                    .replace(";", "\\;")
                    .replace("[", "\\[")
                    .replace("]", "\\]");
        }

        private static Media mediaFrom(PortType<?> type) {
            if (MpvFilterGraphTypes.VIDEO_STREAM.equals(type)) return Media.VIDEO;
            if (MpvFilterGraphTypes.AUDIO_STREAM.equals(type)) return Media.AUDIO;
            return Media.OTHER;
        }

        private static CompileException error(String message) {
            return new CompileException(message);
        }
    }

    private enum Media {
        VIDEO,
        AUDIO,
        OTHER
    }

    private record PortKey(NodeId nodeId, PortId portId) {
        private PortKey {
            Objects.requireNonNull(nodeId, "nodeId");
            Objects.requireNonNull(portId, "portId");
        }
    }

    private static final class CompileException extends RuntimeException {
        private CompileException(String message) {
            super(message);
        }
    }
}
