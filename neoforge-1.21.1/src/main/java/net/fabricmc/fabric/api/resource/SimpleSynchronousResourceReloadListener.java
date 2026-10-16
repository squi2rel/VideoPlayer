package net.fabricmc.fabric.api.resource;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

public interface SimpleSynchronousResourceReloadListener {
    ResourceLocation getFabricId();

    void onResourceManagerReload(ResourceManager resourceManager);
}
