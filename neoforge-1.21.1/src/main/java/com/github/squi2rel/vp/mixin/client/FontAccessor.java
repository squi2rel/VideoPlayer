package com.github.squi2rel.vp.mixin.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.FontSet;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Font.class)
public interface FontAccessor {
    @Invoker("getFontSet") FontSet videoplayer$getFontSet(ResourceLocation font);
}
