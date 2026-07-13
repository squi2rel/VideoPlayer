package com.github.squi2rel.mcng.core.builtin;

import com.github.squi2rel.mcng.core.MCNGPortTypes;
import com.github.squi2rel.mcng.core.NodeTypeRegistry;
import com.github.squi2rel.mcng.core.PortTypeRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuiltinRegistrarTest {
	@Test
	void coreNodeRegistrationDoesNotImplicitlyIncludeDebugNodes() {
		NodeTypeRegistry registry = new NodeTypeRegistry();

		BuiltinNodeRegistrar.registerCoreNodes(registry);

		assertTrue(registry.find(BuiltinNodeTypes.ADD.id()).isPresent());
		assertTrue(registry.find(BuiltinNodeTypes.LIST_CREATE.id()).isPresent());
		assertTrue(registry.find(BuiltinNodeTypes.TYPE_OF.id()).isPresent());
		assertFalse(registry.find(BuiltinNodeTypes.DEBUG_OUTPUT.id()).isPresent());
		assertFalse(registry.find(BuiltinNodeTypes.MANUAL_TRIGGER.id()).isPresent());
		assertFalse(registry.find(BuiltinNodeTypes.IMAGE_PREVIEW.id()).isPresent());
	}

	@Test
	void debugNodeRegistrationIncludesImagePreview() {
		NodeTypeRegistry registry = new NodeTypeRegistry();

		BuiltinNodeRegistrar.registerDebugNodes(registry);

		assertTrue(registry.find(BuiltinNodeTypes.DEBUG_OUTPUT.id()).isPresent());
		assertTrue(registry.find(BuiltinNodeTypes.MANUAL_TRIGGER.id()).isPresent());
		assertTrue(registry.find(BuiltinNodeTypes.IMAGE_PREVIEW.id()).isPresent());
	}

	@Test
	void portTypesAndConvertersCanBeRegisteredIncrementally() {
		PortTypeRegistry registry = new PortTypeRegistry();

		BuiltinPortTypeRegistrar.registerBaseTypes(registry);
		BuiltinPortTypeRegistrar.registerNumericTypes(registry);
		BuiltinPortTypeRegistrar.registerTextTypes(registry);
		BuiltinPortTypeRegistrar.registerBooleanTypes(registry);
		BuiltinPortTypeRegistrar.registerCollectionTypes(registry);
		BuiltinPortTypeRegistrar.registerMetaTypes(registry);
		BuiltinConverterRegistrar.registerBooleanNumericConverters(registry);

		assertTrue(registry.findType(MCNGPortTypes.ANY.id()).isPresent());
		assertTrue(registry.findType(MCNGPortTypes.INT.id()).isPresent());
		assertTrue(registry.findType(MCNGPortTypes.STRING.id()).isPresent());
		assertTrue(registry.findType(MCNGPortTypes.LIST.id()).isPresent());
		assertTrue(registry.findType(MCNGPortTypes.MAP.id()).isPresent());
		assertTrue(registry.findType(MCNGPortTypes.CLASS.id()).isPresent());
		assertTrue(registry.canConnect(MCNGPortTypes.INT, MCNGPortTypes.BOOLEAN));
		assertTrue(registry.canConnect(MCNGPortTypes.BOOLEAN, MCNGPortTypes.DOUBLE));
	}
}
