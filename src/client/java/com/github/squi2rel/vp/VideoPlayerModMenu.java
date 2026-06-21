package com.github.squi2rel.vp;

import com.github.squi2rel.vp.creation.StartupGuideScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

public class VideoPlayerModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return StartupGuideScreen::new;
    }
}
