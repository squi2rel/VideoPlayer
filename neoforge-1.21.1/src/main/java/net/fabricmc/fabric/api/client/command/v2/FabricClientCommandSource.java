package net.fabricmc.fabric.api.client.command.v2;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public final class FabricClientCommandSource {
    public void sendFeedback(Component message) {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) client.player.displayClientMessage(message, false);
    }
}
