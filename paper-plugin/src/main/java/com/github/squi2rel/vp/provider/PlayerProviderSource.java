package com.github.squi2rel.vp.provider;

import com.github.squi2rel.vp.DataHolder;
import com.github.squi2rel.vp.i18n.VpTranslation;
import org.bukkit.entity.Player;

public class PlayerProviderSource implements IProviderSource {
    private final Player player;
    private final String name;

    public PlayerProviderSource(Player player) {
        this.player = player;
        this.name = player.getName();
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public void reply(String text) {
        DataHolder.message(player, text);
    }

    @Override
    public void reply(VpTranslation text) {
        DataHolder.message(player, text);
    }
}
