package com.github.squi2rel.vp;

import com.github.squi2rel.vp.provider.bilibili.BiliQuality;
import com.github.squi2rel.vp.provider.youtube.YouTubeQuality;

public class Config {
    public int volume = 100;
    public int brightness = 100;
    public boolean alwaysConnected = false;
    public String videoBackend = "vlc";
    public String nativeVlcPlatform = "";
    public String nativeMpvPlatform = "";
    public String nativeDownloadProxy = "";
    public String mpvYtdlPath = "";
    public Boolean startupGuideShown = null;
    public transient NativeDownloadConfig nativeDownloadUrls = NativeDownloadConfig.load();
    public boolean danmakuDefaultEnabled = true;
    public boolean danmakuBlockRolling = false;
    public boolean danmakuBlockFixed = false;
    public boolean danmakuBlockColored = false;
    public int danmakuRollingRangePercent = 50;
    public int danmakuSpeedPreset = 2;
    public int danmakuDensityPreset = 0;
    public int danmakuOpacity = 80;
    public int danmakuScalePercent = 100;
    public boolean danmakuBottomGuard = false;
    public int bilibiliQuality = BiliQuality.DEFAULT_QN;
    public int youtubeQuality = YouTubeQuality.AUTO;
}
