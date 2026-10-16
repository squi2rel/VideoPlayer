package net.fabricmc.fabric.api.client.event.lifecycle.v1;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;

public final class ClientLifecycleEvents {
    public static final StoppingEvent CLIENT_STOPPING = new StoppingEvent();

    private ClientLifecycleEvents() {
    }

    @FunctionalInterface
    public interface Stopping {
        void onStopping(Minecraft client);
    }

    public static final class StoppingEvent {
        private final List<Stopping> callbacks = new ArrayList<>();

        public void register(Stopping callback) {
            callbacks.add(callback);
        }

        public void invoke(Minecraft client) {
            for (Stopping callback : List.copyOf(callbacks)) callback.onStopping(client);
        }
    }
}
