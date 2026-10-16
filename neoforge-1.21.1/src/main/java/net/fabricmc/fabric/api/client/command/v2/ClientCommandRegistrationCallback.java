package net.fabricmc.fabric.api.client.command.v2;

import com.mojang.brigadier.CommandDispatcher;
import java.util.ArrayList;
import java.util.List;

@FunctionalInterface
public interface ClientCommandRegistrationCallback {
    Event EVENT = new Event();

    void register(CommandDispatcher<FabricClientCommandSource> dispatcher, Object registryAccess);

    final class Event {
        private final List<ClientCommandRegistrationCallback> callbacks = new ArrayList<>();

        public void register(ClientCommandRegistrationCallback callback) {
            callbacks.add(callback);
            callback.register(ClientCommandManager.getActiveDispatcher(), null);
        }
    }
}
