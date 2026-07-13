package com.github.squi2rel.mcng.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortTypeRegistryTest {
	@Test
	void rejectsDuplicateTypeRegistration() {
		PortTypeRegistry registry = new PortTypeRegistry();

		registry.registerType(MCNGPortTypes.DOUBLE, MCNGPortTypes.DOUBLE_COLOR);

		IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
			() -> registry.registerType(new PortType<>("mcng:double", Double.class), 0xFFFFFFFF));
		assertTrue(exception.getMessage().contains("Duplicate port type id"));
	}

	@Test
	void rejectsDuplicateConverterRegistration() {
		PortTypeRegistry registry = MCNGPortTypes.createRegistry();
		PortType<Integer> integer = new PortType<>("test:integer", Integer.class);
		registry.registerType(integer, 0xFF66AAFF);
		registry.registerConverter(integer, MCNGPortTypes.DOUBLE, Integer::doubleValue);

		IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
			() -> registry.registerConverter(integer, MCNGPortTypes.DOUBLE, Integer::doubleValue));
		assertTrue(exception.getMessage().contains("Duplicate converter"));
	}

	@Test
	void returnsRegisteredColors() {
		PortTypeRegistry registry = MCNGPortTypes.createRegistry();

		assertEquals(MCNGPortTypes.STRING_COLOR, registry.colorOf(MCNGPortTypes.STRING));
	}

	@Test
	void convertsBetweenNumberAndBoolean() {
		PortTypeRegistry registry = MCNGPortTypes.createRegistry();

		assertEquals(true, registry.convert(3.0, MCNGPortTypes.DOUBLE, MCNGPortTypes.BOOLEAN));
		assertEquals(false, registry.convert(0.0, MCNGPortTypes.DOUBLE, MCNGPortTypes.BOOLEAN));
		assertEquals(1.0, registry.convert(true, MCNGPortTypes.BOOLEAN, MCNGPortTypes.DOUBLE));
		assertEquals(0.0, registry.convert(false, MCNGPortTypes.BOOLEAN, MCNGPortTypes.DOUBLE));
	}
}
