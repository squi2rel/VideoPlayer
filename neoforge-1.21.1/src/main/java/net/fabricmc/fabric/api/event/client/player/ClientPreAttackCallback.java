package net.fabricmc.fabric.api.event.client.player;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

@FunctionalInterface
public interface ClientPreAttackCallback {
    Event EVENT = new Event();

    boolean onPreAttack(Minecraft client, LocalPlayer player, int clickCount);

    final class Event {
        private final List<ClientPreAttackCallback> callbacks = new ArrayList<>();

        public void register(ClientPreAttackCallback callback) {
            callbacks.add(callback);
        }

        public boolean invoke(Minecraft client, LocalPlayer player, int clickCount) {
            for (ClientPreAttackCallback callback : List.copyOf(callbacks)) {
                if (callback.onPreAttack(client, player, clickCount)) return true;
            }
            return false;
        }
    }
}
