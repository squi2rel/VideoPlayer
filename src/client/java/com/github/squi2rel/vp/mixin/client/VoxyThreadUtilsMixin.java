package com.github.squi2rel.vp.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

import java.nio.file.Files;
import java.nio.file.Path;

@Pseudo
@Mixin(targets = "me.cortex.voxy.common.util.ThreadUtils", remap = false)
public class VoxyThreadUtilsMixin {
    @ModifyConstant(method = "<clinit>", constant = @Constant(stringValue = "libc.so.6"))
    private static String useAndroidLibc(String library) {
        return Files.exists(Path.of("/system/build.prop")) ? "libc.so" : library;
    }
}
