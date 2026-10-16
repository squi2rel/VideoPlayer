package net.fabricmc.fabric.api.client.command.v2;

import net.minecraft.network.chat.Component;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.github.squi2rel.vp.VideoPlayerMain;

public final class ClientCommandManager {
    private static final CommandDispatcher<FabricClientCommandSource> DISPATCHER = new CommandDispatcher<>();
    private static final FabricClientCommandSource SOURCE = new FabricClientCommandSource();

    private ClientCommandManager() {
    }

    public static LiteralArgumentBuilder<FabricClientCommandSource> literal(String name) {
        return LiteralArgumentBuilder.literal(name);
    }

    public static <T> RequiredArgumentBuilder<FabricClientCommandSource, T> argument(String name, ArgumentType<T> type) {
        return RequiredArgumentBuilder.argument(name, type);
    }

    public static CommandDispatcher<FabricClientCommandSource> getActiveDispatcher() {
        return DISPATCHER;
    }

    public static FabricClientCommandSource getSource() {
        return SOURCE;
    }

    public static int execute(String command) {
        try {
            return DISPATCHER.execute(command, SOURCE);
        } catch (CommandSyntaxException exception) {
            SOURCE.sendFeedback(Component.literal(exception.getMessage()));
            VideoPlayerMain.LOGGER.debug("Client command failed: {}", command, exception);
            return 0;
        }
    }
}
