package com.github.squi2rel.mcng.core;

import com.google.gson.JsonObject;

public interface NodeConfigCodec<C> {
	JsonObject toJson(C config);

	C fromJson(JsonObject json);
}
