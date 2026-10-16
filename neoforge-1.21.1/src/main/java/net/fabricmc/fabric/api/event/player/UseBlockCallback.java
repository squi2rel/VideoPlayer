package net.fabricmc.fabric.api.event.player;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;

@FunctionalInterface
public interface UseBlockCallback {
    Event EVENT = new Event();

    InteractionResult interact(LocalPlayer player, ClientLevel level, InteractionHand hand, BlockHitResult hitResult);

    final class Event {
        private final List<UseBlockCallback> callbacks = new ArrayList<>();

        public void register(UseBlockCallback callback) {
            callbacks.add(callback);
        }

        public InteractionResult invoke(LocalPlayer player, ClientLevel level, InteractionHand hand, BlockHitResult hitResult) {
            for (UseBlockCallback callback : List.copyOf(callbacks)) {
                InteractionResult result = callback.interact(player, level, hand, hitResult);
                if (result != InteractionResult.PASS) return result;
            }
            return InteractionResult.PASS;
        }
    }
}
