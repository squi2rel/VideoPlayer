package com.github.squi2rel.vp.provider;

import com.github.squi2rel.vp.i18n.MinecraftTexts;
import com.github.squi2rel.vp.i18n.VpTranslation;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

public class PlayerProviderSource implements IProviderSource {
    private final ServerPlayerEntity player;

    public PlayerProviderSource(ServerPlayerEntity entity) {
        player = entity;
    }

    @Override
    public String name() {
        return player.getGameProfile().name();
    }

    @Override
    public void reply(String text) {
        player.sendMessage(Text.of(text));
    }

    @Override
    public void reply(VpTranslation text) {
        player.sendMessage(MinecraftTexts.text(text));
    }
}
