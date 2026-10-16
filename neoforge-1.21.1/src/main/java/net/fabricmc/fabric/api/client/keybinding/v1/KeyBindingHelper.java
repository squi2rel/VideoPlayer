package net.fabricmc.fabric.api.client.keybinding.v1;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.KeyMapping;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

public final class KeyBindingHelper {
    private static final List<KeyMapping> MAPPINGS = new ArrayList<>();

    private KeyBindingHelper() {
    }

    public static KeyMapping registerKeyBinding(KeyMapping mapping) {
        MAPPINGS.add(mapping);
        return mapping;
    }

    public static void registerAll(RegisterKeyMappingsEvent event) {
        for (KeyMapping mapping : MAPPINGS) event.register(mapping);
    }
}
