package com.github.squi2rel.mcng.core;

import java.util.List;
import java.util.Objects;

public sealed interface NodeEditorControl permits NodeEditorControl.TextControl, NodeEditorControl.NumericTextControl, NodeEditorControl.BooleanControl, NodeEditorControl.CycleControl {
	String key();

	String label();

	Kind kind();

	record TextControl(String key, String label, String defaultValue) implements NodeEditorControl {
		public TextControl {
			requireKeyAndLabel(key, label);
			Objects.requireNonNull(defaultValue, "defaultValue");
		}

		@Override
		public Kind kind() {
			return Kind.TEXT;
		}
	}

	record NumericTextControl(String key, String label, String defaultValue) implements NodeEditorControl {
		public NumericTextControl {
			requireKeyAndLabel(key, label);
			Objects.requireNonNull(defaultValue, "defaultValue");
			NumericTypes.parseLiteral(defaultValue);
		}

		@Override
		public Kind kind() {
			return Kind.NUMERIC_TEXT;
		}
	}

	record BooleanControl(String key, String label, boolean defaultValue) implements NodeEditorControl {
		public BooleanControl {
			requireKeyAndLabel(key, label);
		}

		@Override
		public Kind kind() {
			return Kind.BOOLEAN;
		}
	}

	record CycleControl(String key, String label, List<Option> options, String defaultOptionId) implements NodeEditorControl {
		public CycleControl {
			requireKeyAndLabel(key, label);
			options = List.copyOf(options);
			if (options.isEmpty()) {
				throw new IllegalArgumentException("Cycle control options must not be empty");
			}
			Objects.requireNonNull(defaultOptionId, "defaultOptionId");
			boolean hasDefault = options.stream().anyMatch(option -> option.id().equals(defaultOptionId));
			if (!hasDefault) {
				throw new IllegalArgumentException("Cycle control default option must exist in options");
			}
		}

		@Override
		public Kind kind() {
			return Kind.CYCLE;
		}
	}

	record Option(String id, String displayName) {
		public Option {
			if (id == null || id.isBlank()) {
				throw new IllegalArgumentException("Option id must not be blank");
			}
			if (displayName == null || displayName.isBlank()) {
				throw new IllegalArgumentException("Option displayName must not be blank");
			}
		}
	}

	enum Kind {
		TEXT,
		NUMERIC_TEXT,
		BOOLEAN,
		CYCLE
	}

	private static void requireKeyAndLabel(String key, String label) {
		if (key == null || key.isBlank()) {
			throw new IllegalArgumentException("Control key must not be blank");
		}
		if (label == null || label.isBlank()) {
			throw new IllegalArgumentException("Control label must not be blank");
		}
	}
}
