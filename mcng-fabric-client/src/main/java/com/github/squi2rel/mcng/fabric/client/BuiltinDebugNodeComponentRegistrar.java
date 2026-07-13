package com.github.squi2rel.mcng.fabric.client;

import com.github.squi2rel.mcng.core.builtin.BuiltinNodeTypes;

public final class BuiltinDebugNodeComponentRegistrar {
	private BuiltinDebugNodeComponentRegistrar() {
	}

	public static void registerDebugComponents(NodeComponentRegistry registry) {
		registry.register(new NodeComponentDefinition(BuiltinNodeTypes.IMAGE_PREVIEW.id(), ImagePreviewNodeBodyComponent::new, ResizePolicy.allSides()));
	}
}
