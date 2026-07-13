package com.github.squi2rel.mcng.core;

@FunctionalInterface
public interface PortValueConverter<S, T> {
	T convert(S value);
}
