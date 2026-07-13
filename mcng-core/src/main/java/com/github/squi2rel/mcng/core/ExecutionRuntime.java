package com.github.squi2rel.mcng.core;

public interface ExecutionRuntime {
	boolean hasVariable(String id);

	PortType<?> variableType(String id);

	Object readVariable(String id);

	void writeVariable(String id, Object value);

	Object readLocalState(NodeId nodeId, String slot);

	void writeLocalState(NodeId nodeId, String slot, Object value);

	void consumeControlStep();

	void consumeLoopIteration(NodeId nodeId);
}
