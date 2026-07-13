package com.github.squi2rel.mcng.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class PortTypeRegistry {
	private static final int FALLBACK_COLOR = 0xFF90A0B8;

	private final Map<String, RegisteredPortType<?>> types = new LinkedHashMap<>();
	private final Map<ConversionKey, RegisteredConverter<?, ?>> converters = new LinkedHashMap<>();

	public synchronized <T> PortTypeRegistry registerType(PortType<T> type, int color) {
		Objects.requireNonNull(type, "type");
		RegisteredPortType<?> existing = types.putIfAbsent(type.id(), new RegisteredPortType<>(type, color));
		if (existing != null) {
			throw new IllegalArgumentException("Duplicate port type id: " + type.id());
		}
		return this;
	}

	public synchronized <S, T> PortTypeRegistry registerConverter(PortType<S> from, PortType<T> to, PortValueConverter<S, T> converter) {
		return registerConverter(from, to, converter, true);
	}

	public synchronized <S, T> PortTypeRegistry registerConverter(PortType<S> from, PortType<T> to, PortValueConverter<S, T> converter, boolean implicit) {
		Objects.requireNonNull(from, "from");
		Objects.requireNonNull(to, "to");
		Objects.requireNonNull(converter, "converter");
		requireRegistered(from);
		requireRegistered(to);
		ConversionKey key = new ConversionKey(from.id(), to.id());
		RegisteredConverter<?, ?> existing = converters.putIfAbsent(key, new RegisteredConverter<>(converter, implicit));
		if (existing != null) {
			throw new IllegalArgumentException("Duplicate converter: " + from + " -> " + to);
		}
		return this;
	}

	public boolean canConnect(PortType<?> from, PortType<?> to) {
		Objects.requireNonNull(from, "from");
		Objects.requireNonNull(to, "to");
		if (isDirectlyCompatible(from, to)) {
			return true;
		}
		RegisteredConverter<?, ?> converter = converters.get(new ConversionKey(from.id(), to.id()));
		return converter != null && converter.implicit();
	}

	public int colorOf(PortType<?> type) {
		Objects.requireNonNull(type, "type");
		RegisteredPortType<?> registered = types.get(type.id());
		return registered != null ? registered.color() : FALLBACK_COLOR;
	}

	public Optional<PortType<?>> findType(String id) {
		Objects.requireNonNull(id, "id");
		RegisteredPortType<?> registered = types.get(id);
		return registered != null ? Optional.of(registered.type()) : Optional.empty();
	}

	public Optional<PortType<?>> typeOfValue(Object value) {
		if (value == null) {
			return Optional.of(MCNGPortTypes.ANY);
		}

		PortType<?> best = null;
		for (RegisteredPortType<?> registered : types.values()) {
			PortType<?> candidate = registered.type();
			if (MCNGPortTypes.ANY.equals(candidate) || !candidate.accepts(value)) {
				continue;
			}
			if (best == null || best.javaType().isAssignableFrom(candidate.javaType())) {
				best = candidate;
			}
		}
		return Optional.ofNullable(best);
	}

	public Object convert(Object value, PortType<?> from, PortType<?> to) {
		Objects.requireNonNull(from, "from");
		Objects.requireNonNull(to, "to");
		if (value == null) {
			return null;
		}
		if (!from.equals(MCNGPortTypes.ANY) && !from.accepts(value)) {
			throw new IllegalArgumentException("Value of type " + value.getClass().getSimpleName() + " is not valid for source type " + from);
		}
		if (isDirectlyCompatible(from, to)) {
			return coerceDirectValue(value, from, to);
		}
		PortValueConverter<Object, Object> converter = lookupConverter(from, to);
		Object converted;
		try {
			converted = converter.convert(value);
		} catch (RuntimeException exception) {
			throw new IllegalArgumentException(exception.getMessage(), exception);
		}
		if (!to.accepts(converted)) {
			throw new IllegalArgumentException("Converted value is not valid for target type " + to);
		}
		return converted;
	}

	private boolean isDirectlyCompatible(PortType<?> from, PortType<?> to) {
		return from.id().equals(to.id()) || from.equals(MCNGPortTypes.ANY) || to.equals(MCNGPortTypes.ANY);
	}

	private Object coerceDirectValue(Object value, PortType<?> from, PortType<?> to) {
		if (to.equals(MCNGPortTypes.ANY)) {
			return value;
		}
		if (to.accepts(value)) {
			return value;
		}
		throw new IllegalArgumentException("Value of type " + value.getClass().getSimpleName() + " is not valid for " + to + " from " + from);
	}

	@SuppressWarnings("unchecked")
	private PortValueConverter<Object, Object> lookupConverter(PortType<?> from, PortType<?> to) {
		RegisteredConverter<?, ?> converter = converters.get(new ConversionKey(from.id(), to.id()));
		if (converter == null) {
			throw new IllegalArgumentException("No converter registered for " + from + " -> " + to);
		}
		return (PortValueConverter<Object, Object>) converter.converter();
	}

	private void requireRegistered(PortType<?> type) {
		if (!types.containsKey(type.id())) {
			throw new IllegalArgumentException("Unknown port type: " + type.id());
		}
	}

	private record RegisteredPortType<T>(PortType<T> type, int color) {
	}

	private record RegisteredConverter<S, T>(PortValueConverter<S, T> converter, boolean implicit) {
	}

	private record ConversionKey(String fromId, String toId) {
	}
}
