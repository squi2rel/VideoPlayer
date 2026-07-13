package com.github.squi2rel.mcng.core.builtin;

import com.github.squi2rel.mcng.core.MCNGPortTypes;
import com.github.squi2rel.mcng.core.NumericTypes;
import com.github.squi2rel.mcng.core.PortTypeRegistry;

public final class BuiltinConverterRegistrar {
	private BuiltinConverterRegistrar() {
	}

	public static void registerNumericWideningConverters(PortTypeRegistry registry) {
		registry.registerConverter(MCNGPortTypes.INT, MCNGPortTypes.LONG, Integer::longValue, true);
		registry.registerConverter(MCNGPortTypes.INT, MCNGPortTypes.DOUBLE, Integer::doubleValue, true);
	}

	public static void registerBooleanNumericConverters(PortTypeRegistry registry) {
		registry.registerConverter(MCNGPortTypes.INT, MCNGPortTypes.BOOLEAN, NumericTypes::numericToBoolean, true);
		registry.registerConverter(MCNGPortTypes.LONG, MCNGPortTypes.BOOLEAN, NumericTypes::numericToBoolean, true);
		registry.registerConverter(MCNGPortTypes.DOUBLE, MCNGPortTypes.BOOLEAN, NumericTypes::numericToBoolean, true);
		registry.registerConverter(MCNGPortTypes.BOOLEAN, MCNGPortTypes.INT, value -> (Integer) NumericTypes.booleanToNumeric(value, MCNGPortTypes.INT), true);
		registry.registerConverter(MCNGPortTypes.BOOLEAN, MCNGPortTypes.LONG, value -> (Long) NumericTypes.booleanToNumeric(value, MCNGPortTypes.LONG), true);
		registry.registerConverter(MCNGPortTypes.BOOLEAN, MCNGPortTypes.DOUBLE, value -> (Double) NumericTypes.booleanToNumeric(value, MCNGPortTypes.DOUBLE), true);
	}

	public static void registerCoreConverters(PortTypeRegistry registry) {
		registerNumericWideningConverters(registry);
		registerBooleanNumericConverters(registry);
	}
}
