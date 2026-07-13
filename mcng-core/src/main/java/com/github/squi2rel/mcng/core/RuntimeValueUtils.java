package com.github.squi2rel.mcng.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class RuntimeValueUtils {
	private RuntimeValueUtils() {
	}

	public static Object deepCopy(Object value) {
		return deepCopy(value, identitySet());
	}

	public static Object copyWithinLimits(Object value, RuntimeValueLimits limits) {
		Objects.requireNonNull(limits, "limits");
		return copyWithinLimits(value, limits, 0, new Counter(), identitySet());
	}

	public static List<Object> copyList(Object value, String label) {
		return requireList(copyIfContainer(value), label);
	}

	public static Map<String, Object> copyMap(Object value, String label) {
		return requireMap(copyIfContainer(value), label);
	}

	private static Object deepCopy(Object value, Set<Object> visiting) {
		if (value instanceof List<?> list) {
			requireAcyclic(list, visiting);
			List<Object> copy = new ArrayList<>(list.size());
			try {
				for (Object element : list) {
					copy.add(deepCopy(element, visiting));
				}
			} finally {
				visiting.remove(list);
			}
			return copy;
		}
		if (value instanceof Map<?, ?> map) {
			requireAcyclic(map, visiting);
			LinkedHashMap<String, Object> copy = new LinkedHashMap<>(map.size());
			try {
				for (Map.Entry<?, ?> entry : map.entrySet()) {
					if (!(entry.getKey() instanceof String key)) {
						throw new IllegalArgumentException("Map keys must be strings");
					}
					copy.put(key, deepCopy(entry.getValue(), visiting));
				}
			} finally {
				visiting.remove(map);
			}
			return copy;
		}
		return value;
	}

	public static Object copyIfContainer(Object value) {
		return value instanceof List<?> || value instanceof Map<?, ?> ? deepCopy(value) : value;
	}

	public static void validateWithinLimits(Object value, RuntimeValueLimits limits) {
		Objects.requireNonNull(limits, "limits");
		validate(value, limits, 0, new Counter(), identitySet());
	}

	public static List<Object> requireList(Object value, String label) {
		if (!(value instanceof List<?> list)) {
			throw new IllegalArgumentException(label + " expects a list");
		}
		List<Object> result = new ArrayList<>(list.size());
		for (Object element : list) {
			result.add(element);
		}
		return result;
	}

	public static Map<String, Object> requireMap(Object value, String label) {
		if (!(value instanceof Map<?, ?> map)) {
			throw new IllegalArgumentException(label + " expects a map");
		}
		LinkedHashMap<String, Object> result = new LinkedHashMap<>(map.size());
		for (Map.Entry<?, ?> entry : map.entrySet()) {
			if (!(entry.getKey() instanceof String key)) {
				throw new IllegalArgumentException(label + " expects string keys");
			}
			result.put(key, entry.getValue());
		}
		return result;
	}

	public static Object fromJsonValue(JsonElement value, PortType<?> declaredType, PortTypeRegistry portTypes) {
		Objects.requireNonNull(declaredType, "declaredType");
		Objects.requireNonNull(portTypes, "portTypes");
		if (value == null || value.isJsonNull()) {
			return null;
		}
		if (MCNGPortTypes.ANY.equals(declaredType)) {
			return readUntypedValue(value);
		}
		if (MCNGPortTypes.STRING.equals(declaredType)) {
			return value.getAsString();
		}
		if (MCNGPortTypes.BOOLEAN.equals(declaredType)) {
			return value.getAsBoolean();
		}
		if (MCNGPortTypes.INT.equals(declaredType) || MCNGPortTypes.LONG.equals(declaredType) || MCNGPortTypes.DOUBLE.equals(declaredType)) {
			Object parsed;
			if (value instanceof JsonPrimitive primitive && primitive.isString()) {
				parsed = NumericTypes.parseLiteral(primitive.getAsString()).value();
			} else {
				parsed = value.getAsNumber();
			}
			PortType<?> sourceType = parsed instanceof Integer
				? MCNGPortTypes.INT
				: parsed instanceof Long ? MCNGPortTypes.LONG : MCNGPortTypes.DOUBLE;
			return portTypes.convert(parsed, sourceType, declaredType);
		}
		if (MCNGPortTypes.LIST.equals(declaredType)) {
			if (!(value instanceof JsonArray array)) {
				throw new IllegalArgumentException("Expected list JSON value");
			}
			List<Object> list = new ArrayList<>(array.size());
			for (JsonElement element : array) {
				list.add(readUntypedValue(element));
			}
			return list;
		}
		if (MCNGPortTypes.MAP.equals(declaredType)) {
			if (!(value instanceof JsonObject object)) {
				throw new IllegalArgumentException("Expected map JSON value");
			}
			LinkedHashMap<String, Object> map = new LinkedHashMap<>();
			for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
				map.put(entry.getKey(), readUntypedValue(entry.getValue()));
			}
			return map;
		}
		if (MCNGPortTypes.CLASS.equals(declaredType)) {
			String typeId = value.getAsString();
			return portTypes.findType(typeId)
				.orElseThrow(() -> new IllegalArgumentException("Unknown port type: " + typeId));
		}
		throw new IllegalArgumentException("Unsupported variable type: " + declaredType);
	}

	public static Object readUntypedValue(JsonElement value) {
		if (value == null || value.isJsonNull()) {
			return null;
		}
		if (value instanceof JsonArray array) {
			List<Object> list = new ArrayList<>(array.size());
			for (JsonElement element : array) {
				list.add(readUntypedValue(element));
			}
			return list;
		}
		if (value instanceof JsonObject object) {
			LinkedHashMap<String, Object> map = new LinkedHashMap<>();
			for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
				map.put(entry.getKey(), readUntypedValue(entry.getValue()));
			}
			return map;
		}
		if (value instanceof JsonPrimitive primitive) {
			if (primitive.isBoolean()) {
				return primitive.getAsBoolean();
			}
			if (primitive.isNumber()) {
				return primitive.getAsDouble();
			}
			if (primitive.isString()) {
				return primitive.getAsString();
			}
		}
		return value.toString();
	}

	private static void validate(Object value, RuntimeValueLimits limits, int depth, Counter counter) {
		validate(value, limits, depth, counter, identitySet());
	}

	private static void validate(Object value, RuntimeValueLimits limits, int depth, Counter counter, Set<Object> visiting) {
		counter.increment(limits);
		if (value instanceof List<?> list) {
			requireAcyclic(list, visiting);
			requireDepth(depth + 1, limits);
			try {
				for (Object element : list) {
					validate(element, limits, depth + 1, counter, visiting);
				}
			} finally {
				visiting.remove(list);
			}
			return;
		}
		if (value instanceof Map<?, ?> map) {
			requireAcyclic(map, visiting);
			requireDepth(depth + 1, limits);
			try {
				for (Map.Entry<?, ?> entry : map.entrySet()) {
					if (!(entry.getKey() instanceof String)) {
						throw new IllegalArgumentException("Map keys must be strings");
					}
					validate(entry.getValue(), limits, depth + 1, counter, visiting);
				}
			} finally {
				visiting.remove(map);
			}
		}
	}

	private static Object copyWithinLimits(Object value, RuntimeValueLimits limits, int depth, Counter counter, Set<Object> visiting) {
		counter.increment(limits);
		if (value instanceof List<?> list) {
			requireAcyclic(list, visiting);
			requireDepth(depth + 1, limits);
			List<Object> copy = new ArrayList<>(list.size());
			try {
				for (Object element : list) {
					copy.add(copyWithinLimits(element, limits, depth + 1, counter, visiting));
				}
			} finally {
				visiting.remove(list);
			}
			return copy;
		}
		if (value instanceof Map<?, ?> map) {
			requireAcyclic(map, visiting);
			requireDepth(depth + 1, limits);
			LinkedHashMap<String, Object> copy = new LinkedHashMap<>(map.size());
			try {
				for (Map.Entry<?, ?> entry : map.entrySet()) {
					if (!(entry.getKey() instanceof String key)) {
						throw new IllegalArgumentException("Map keys must be strings");
					}
					copy.put(key, copyWithinLimits(entry.getValue(), limits, depth + 1, counter, visiting));
				}
			} finally {
				visiting.remove(map);
			}
			return copy;
		}
		return value;
	}

	private static void requireDepth(int depth, RuntimeValueLimits limits) {
		if (depth > limits.maxDepth()) {
			throw new IllegalArgumentException("Runtime value depth exceeded limit " + limits.maxDepth());
		}
	}

	private static void requireAcyclic(Object value, Set<Object> visiting) {
		if (!visiting.add(value)) {
			throw new IllegalArgumentException("Runtime value contains a cycle");
		}
	}

	private static Set<Object> identitySet() {
		return Collections.newSetFromMap(new IdentityHashMap<>());
	}

	private static final class Counter {
		private int aggregateValues;

		private void increment(RuntimeValueLimits limits) {
			aggregateValues++;
			if (aggregateValues > limits.maxAggregateValues()) {
				throw new IllegalArgumentException("Runtime value size exceeded limit " + limits.maxAggregateValues());
			}
		}
	}
}
