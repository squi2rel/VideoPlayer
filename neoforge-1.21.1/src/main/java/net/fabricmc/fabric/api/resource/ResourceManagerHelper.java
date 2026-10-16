package net.fabricmc.fabric.api.resource;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;

public final class ResourceManagerHelper {
    private static final ResourceManagerHelper CLIENT = new ResourceManagerHelper();
    private final List<SimpleSynchronousResourceReloadListener> listeners = new ArrayList<>();

    private ResourceManagerHelper() {
    }

    public static ResourceManagerHelper get(PackType type) {
        return CLIENT;
    }

    public void registerReloadListener(SimpleSynchronousResourceReloadListener listener) {
        listeners.add(listener);
    }

    public static void reload(ResourceManager manager) {
        for (SimpleSynchronousResourceReloadListener listener : List.copyOf(CLIENT.listeners)) {
            listener.onResourceManagerReload(manager);
        }
    }
}
