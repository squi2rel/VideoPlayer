package com.github.squi2rel.mcng.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Objects;

public final class NumericTypes {
	private NumericTypes() {
	}

	public static boolean isNumericType(PortType<?> type) {
		return MCNGPortTypes.INT.equals(type)
			|| MCNGPortTypes.LONG.equals(type)
			|| MCNGPortTypes.DOUBLE.equals(type);
	}

	public static boolean isNumericValue(Object value) {
		return value instanceof Integer || value instanceof Long || value instanceof Double;
	}

	public static PortType<?> typeOf(Object value) {
		Objects.requireNonNull(value, "value");
		if (value instanceof Integer) {
			return MCNGPortTypes.INT;
		}
		if (value instanceof Long) {
			return MCNGPortTypes.LONG;
		}
		if (value instanceof Double) {
			return MCNGPortTypes.DOUBLE;
		}
		throw new IllegalArgumentException("Unsupported numeric value type: " + value.getClass().getSimpleName());
	}

	public static ParsedLiteral parseLiteral(String text) {
		String normalized = normalizeLiteralText(text);
		try {
			if (normalized.indexOf('.') >= 0 || normalized.indexOf('e') >= 0 || normalized.indexOf('E') >= 0) {
				double value = Double.parseDouble(normalized);
				if (!Double.isFinite(value)) {
					throw new IllegalArgumentException("Numeric literal must be finite: " + text);
				}
				return new ParsedLiteral(normalized, value, MCNGPortTypes.DOUBLE);
			}

			long parsed = Long.parseLong(normalized);
			if (parsed >= Integer.MIN_VALUE && parsed <= Integer.MAX_VALUE) {
				return new ParsedLiteral(normalized, (int) parsed, MCNGPortTypes.INT);
			}
			return new ParsedLiteral(normalized, parsed, MCNGPortTypes.LONG);
		} catch (NumberFormatException exception) {
			throw new IllegalArgumentException("Invalid numeric literal: " + text);
		}
	}

	public static String normalizeLiteralText(String text) {
		if (text == null) {
			throw new IllegalArgumentException("Numeric literal must not be null");
		}
		String normalized = text.trim();
		if (normalized.isEmpty()) {
			throw new IllegalArgumentException("Numeric literal must not be blank");
		}
		return normalized;
	}

	public static PortType<?> widen(PortType<?> left, PortType<?> right) {
		Objects.requireNonNull(left, "left");
		Objects.requireNonNull(right, "right");
		if (!isNumericType(left) || !isNumericType(right)) {
			throw new IllegalArgumentException("Cannot widen non-numeric types: " + left + ", " + right);
		}
		if (MCNGPortTypes.DOUBLE.equals(left) || MCNGPortTypes.DOUBLE.equals(right)) {
			return MCNGPortTypes.DOUBLE;
		}
		if (MCNGPortTypes.LONG.equals(left) || MCNGPortTypes.LONG.equals(right)) {
			return MCNGPortTypes.LONG;
		}
		return MCNGPortTypes.INT;
	}

	public static Object add(Object left, Object right) {
		if (!isNumericValue(left) || !isNumericValue(right)) {
			throw new IllegalArgumentException("Add expects numeric values");
		}
		PortType<?> target = widen(typeOf(left), typeOf(right));
		return switch (target.id()) {
			case "mcng:int" -> ((Integer) left) + ((Integer) right);
			case "mcng:long" -> asLong(left) + asLong(right);
			case "mcng:double" -> asDouble(left) + asDouble(right);
			default -> throw new IllegalStateException("Unsupported numeric type: " + target);
		};
	}

	public static Object subtract(Object left, Object right) {
		if (!isNumericValue(left) || !isNumericValue(right)) {
			throw new IllegalArgumentException("Subtract expects numeric values");
		}
		PortType<?> target = widen(typeOf(left), typeOf(right));
		return switch (target.id()) {
			case "mcng:int" -> ((Integer) left) - ((Integer) right);
			case "mcng:long" -> asLong(left) - asLong(right);
			case "mcng:double" -> asDouble(left) - asDouble(right);
			default -> throw new IllegalStateException("Unsupported numeric type: " + target);
		};
	}

	public static Object multiply(Object left, Object right) {
		if (!isNumericValue(left) || !isNumericValue(right)) {
			throw new IllegalArgumentException("Multiply expects numeric values");
		}
		PortType<?> target = widen(typeOf(left), typeOf(right));
		return switch (target.id()) {
			case "mcng:int" -> ((Integer) left) * ((Integer) right);
			case "mcng:long" -> asLong(left) * asLong(right);
			case "mcng:double" -> asDouble(left) * asDouble(right);
			default -> throw new IllegalStateException("Unsupported numeric type: " + target);
		};
	}

	public static Object cast(Object value, PortType<?> targetType) {
		Objects.requireNonNull(value, "value");
		Objects.requireNonNull(targetType, "targetType");
		if (!isNumericValue(value)) {
			throw new IllegalArgumentException("Cast expects a numeric value");
		}
		return switch (targetType.id()) {
			case "mcng:int" -> toIntByCast(value);
			case "mcng:long" -> toLongByCast(value);
			case "mcng:double" -> asDouble(value);
			default -> throw new IllegalArgumentException("Unsupported cast target: " + targetType);
		};
	}

	public static Object round(Object value, PortType<?> targetType) {
		Objects.requireNonNull(value, "value");
		Objects.requireNonNull(targetType, "targetType");
		if (!isNumericValue(value)) {
			throw new IllegalArgumentException("Round expects a numeric value");
		}
		if (MCNGPortTypes.DOUBLE.equals(targetType)) {
			throw new IllegalArgumentException("Round target must be int or long");
		}
		BigDecimal rounded = BigDecimal.valueOf(asDouble(value)).setScale(0, RoundingMode.HALF_UP);
		return switch (targetType.id()) {
			case "mcng:int" -> toIntExact(rounded);
			case "mcng:long" -> toLongExact(rounded);
			default -> throw new IllegalArgumentException("Unsupported round target: " + targetType);
		};
	}

	public static Object booleanToNumeric(boolean value, PortType<?> targetType) {
		return switch (targetType.id()) {
			case "mcng:int" -> value ? 1 : 0;
			case "mcng:long" -> value ? 1L : 0L;
			case "mcng:double" -> value ? 1.0D : 0.0D;
			default -> throw new IllegalArgumentException("Unsupported boolean conversion target: " + targetType);
		};
	}

	public static boolean numericToBoolean(Object value) {
		if (!isNumericValue(value)) {
			throw new IllegalArgumentException("Boolean conversion expects a numeric value");
		}
		return switch (typeOf(value).id()) {
			case "mcng:int" -> ((Integer) value) != 0;
			case "mcng:long" -> ((Long) value) != 0L;
			case "mcng:double" -> ((Double) value) != 0.0D;
			default -> throw new IllegalStateException("Unsupported numeric type: " + typeOf(value));
		};
	}

	public static String displayText(Object value) {
		if (value == null) {
			return "";
		}
		if (value instanceof Integer || value instanceof Long) {
			return String.valueOf(value);
		}
		if (value instanceof Double number) {
			if (Math.rint(number) == number) {
				return String.format(Locale.ROOT, "%.1f", number);
			}
			return String.valueOf(number);
		}
		return String.valueOf(value);
	}

	public static final class ParsedLiteral {
		private final String text;
		private final Object value;
		private final PortType<?> type;

		private ParsedLiteral(String text, Object value, PortType<?> type) {
			this.text = text;
			this.value = value;
			this.type = type;
		}

		public String text() {
			return text;
		}

		public Object value() {
			return value;
		}

		public PortType<?> type() {
			return type;
		}
	}

	private static long asLong(Object value) {
		return switch (typeOf(value).id()) {
			case "mcng:int" -> ((Integer) value).longValue();
			case "mcng:long" -> (Long) value;
			default -> throw new IllegalArgumentException("Cannot treat " + typeOf(value) + " as long");
		};
	}

	private static double asDouble(Object value) {
		return switch (typeOf(value).id()) {
			case "mcng:int" -> ((Integer) value).doubleValue();
			case "mcng:long" -> ((Long) value).doubleValue();
			case "mcng:double" -> (Double) value;
			default -> throw new IllegalArgumentException("Cannot treat " + typeOf(value) + " as double");
		};
	}

	private static int toIntByCast(Object value) {
		if (value instanceof Integer integer) {
			return integer;
		}
		if (value instanceof Long longValue) {
			if (longValue < Integer.MIN_VALUE || longValue > Integer.MAX_VALUE) {
				throw new IllegalArgumentException("Value is out of int range");
			}
			return longValue.intValue();
		}
		double doubleValue = asDouble(value);
		if (!Double.isFinite(doubleValue)) {
			throw new IllegalArgumentException("Value must be finite");
		}
		double truncated = doubleValue < 0 ? Math.ceil(doubleValue) : Math.floor(doubleValue);
		if (truncated < Integer.MIN_VALUE || truncated > Integer.MAX_VALUE) {
			throw new IllegalArgumentException("Value is out of int range");
		}
		return (int) truncated;
	}

	private static long toLongByCast(Object value) {
		if (value instanceof Integer integer) {
			return integer.longValue();
		}
		if (value instanceof Long longValue) {
			return longValue;
		}
		double doubleValue = asDouble(value);
		if (!Double.isFinite(doubleValue)) {
			throw new IllegalArgumentException("Value must be finite");
		}
		double truncated = doubleValue < 0 ? Math.ceil(doubleValue) : Math.floor(doubleValue);
		if (truncated < Long.MIN_VALUE || truncated > Long.MAX_VALUE) {
			throw new IllegalArgumentException("Value is out of long range");
		}
		return (long) truncated;
	}

	private static int toIntExact(BigDecimal value) {
		try {
			return value.intValueExact();
		} catch (ArithmeticException exception) {
			throw new IllegalArgumentException("Rounded value is out of int range");
		}
	}

	private static long toLongExact(BigDecimal value) {
		try {
			return value.longValueExact();
		} catch (ArithmeticException exception) {
			throw new IllegalArgumentException("Rounded value is out of long range");
		}
	}
}
