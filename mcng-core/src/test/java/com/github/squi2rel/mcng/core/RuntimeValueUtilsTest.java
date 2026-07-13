package com.github.squi2rel.mcng.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeValueUtilsTest {
	@Test
	void copyWithinLimitsRejectsCycles() {
		List<Object> cyclic = new ArrayList<>();
		cyclic.add(cyclic);

		IllegalArgumentException exception = assertThrows(
			IllegalArgumentException.class,
			() -> RuntimeValueUtils.copyWithinLimits(cyclic, RuntimeValueLimits.DEFAULT)
		);

		assertTrue(exception.getMessage().contains("cycle"));
	}

	@Test
	void deepCopyClonesNestedContainers() {
		List<Object> nestedList = new ArrayList<>(List.of("x"));
		Map<String, Object> nestedMap = new LinkedHashMap<>();
		nestedMap.put("nested", nestedList);
		List<Object> root = new ArrayList<>(List.of(nestedMap));

		@SuppressWarnings("unchecked")
		List<Object> copy = (List<Object>) RuntimeValueUtils.deepCopy(root);
		@SuppressWarnings("unchecked")
		Map<String, Object> copiedMap = (Map<String, Object>) copy.getFirst();
		@SuppressWarnings("unchecked")
		List<Object> copiedList = (List<Object>) copiedMap.get("nested");
		copiedList.add("y");

		assertEquals(List.of(Map.of("nested", List.of("x"))), root);
		assertEquals(List.of(Map.of("nested", List.of("x", "y"))), copy);
		assertNotSame(root, copy);
		assertNotSame(nestedMap, copiedMap);
		assertNotSame(nestedList, copiedList);
	}
}
