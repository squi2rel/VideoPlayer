package net.fabricmc.fabric.api.client.event.lifecycle.v1;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;

public final class ClientTickEvents {
    public static final EndTickEvent END_CLIENT_TICK = new EndTickEvent();

    private ClientTickEvents() {
    }

    @FunctionalInterface
    public interface EndTick {
        void onEndTick(Minecraft client);
    }

    public static final class EndTickEvent {
        private final List<EndTick> callbacks = new ArrayList<>();

        public void register(EndTick callback) {
            callbacks.add(callback);
        }

        public void invoke(Minecraft client) {
            for (EndTick callback : List.copyOf(callbacks)) callback.onEndTick(client);
        }
    }
}
