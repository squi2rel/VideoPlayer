package net.fabricmc.loader.api;

import java.nio.file.Path;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;

public final class FabricLoader {
    private static final FabricLoader INSTANCE = new FabricLoader();

    private FabricLoader() {
    }

    public static FabricLoader getInstance() {
        return INSTANCE;
    }

    public Path getConfigDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    public boolean isModLoaded(String modId) {
        return ModList.get().isLoaded(modId);
    }
}
