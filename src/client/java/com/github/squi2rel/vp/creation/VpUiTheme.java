package com.github.squi2rel.vp.creation;

record VpUiTheme(
        int canvasBackgroundColor,
        int gridColor,
        int nodeBodyColor,
        int nodeHeaderColor,
        int nodeBorderColor,
        int panelBackgroundColor,
        int panelBorderColor,
        int primaryTextColor,
        int secondaryTextColor,
        int accentColor,
        int controlFlowColor,
        int executionColor,
        int errorColor,
        boolean textShadow
) {
    static VpUiTheme classic() {
        return new VpUiTheme(
                0xFF10151F,
                0xFF1E2430,
                0xFF1A2230,
                0xFF223047,
                0xFF4B5D78,
                0xD0101520,
                0xFF4B5D78,
                0xFFF6F7FB,
                0xFFF6F7FB,
                0xFFFFCC55,
                0xFF7AC4FF,
                0xFF7CFF8A,
                0xFFFF8C8C,
                true
        );
    }
}
