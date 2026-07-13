package com.github.squi2rel.mcng.core;

import com.github.squi2rel.mcng.core.builtin.BuiltinConverterRegistrar;
import com.github.squi2rel.mcng.core.builtin.BuiltinPortTypeRegistrar;

public final class MCNGPortTypes {
	public static final PortType<Object> ANY = new PortType<>("mcng:any", Object.class);
	public static final PortType<Integer> INT = new PortType<>("mcng:int", Integer.class);
	public static final PortType<Long> LONG = new PortType<>("mcng:long", Long.class);
	public static final PortType<Double> DOUBLE = new PortType<>("mcng:double", Double.class);
	public static final PortType<String> STRING = new PortType<>("mcng:string", String.class);
	public static final PortType<Boolean> BOOLEAN = new PortType<>("mcng:boolean", Boolean.class);
	public static final PortType<java.util.List> LIST = new PortType<>("mcng:list", java.util.List.class);
	public static final PortType<java.util.Map> MAP = new PortType<>("mcng:map", java.util.Map.class);
	public static final PortType<PortType> CLASS = new PortType<>("mcng:class", PortType.class);

	public static final int ANY_COLOR = 0xFF90A0B8;
	public static final int INT_COLOR = 0xFFE0A33A;
	public static final int LONG_COLOR = 0xFFD8C35A;
	public static final int DOUBLE_COLOR = 0xFF5BA7E8;
	public static final int STRING_COLOR = 0xFF52C7A5;
	public static final int BOOLEAN_COLOR = 0xFFE1707C;
	public static final int LIST_COLOR = 0xFF6F8EF7;
	public static final int MAP_COLOR = 0xFFC56EE0;
	public static final int CLASS_COLOR = 0xFF9A9A9A;

	private MCNGPortTypes() {
	}

	public static PortTypeRegistry createRegistry() {
		PortTypeRegistry registry = new PortTypeRegistry();
		registerAll(registry);
		return registry;
	}

	public static void registerAll(PortTypeRegistry registry) {
		BuiltinPortTypeRegistrar.registerCoreTypes(registry);
		BuiltinConverterRegistrar.registerCoreConverters(registry);
	}
}
