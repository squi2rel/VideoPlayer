package com.github.squi2rel.vp.compat.input;

public record CharacterEvent(int codepoint, int modifiers) {
    public String codepointAsString() { return new String(Character.toChars(codepoint)); }
    public boolean isAllowedChatCharacter() { return net.minecraft.util.StringUtil.isAllowedChatCharacter((char) codepoint); }
}
