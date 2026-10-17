package com.github.squi2rel.vp.permission;

import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ResidenceFlagRefreshFilterTest {
    @Test
    void ignoresUnrelatedFlagsButTracksAllCurrentAndLegacyFlags() {
        assertFalse(ResidenceFlagRefreshFilter.affectsPermissions("nofly"));
        assertFalse(ResidenceFlagRefreshFilter.affectsPermissions("build"));
        assertTrue(ResidenceFlagRefreshFilter.affectsPermissions(" ADMIN "));
        for (String flag : VideoPermissionNodes.residenceFlags()) assertTrue(ResidenceFlagRefreshFilter.affectsPermissions(flag));
    }

    @Test
    void filtersEventAndSkipsCancelledChanges() {
        ResidenceFlagRefreshFilter filter = new ResidenceFlagRefreshFilter(FlagEvent.class);
        FlagEvent event = new FlagEvent();
        event.flag = "nofly";
        assertFalse(filter.shouldRefresh(event));
        event.flag = "videoplayer.action.play";
        assertTrue(filter.shouldRefresh(event));
        event.setCancelled(true);
        assertFalse(filter.shouldRefresh(event));
    }

    @Test
    void missingOrBrokenAccessorConservativelyRefreshes() {
        assertTrue(ResidenceFlagRefreshFilter.affectsPermissions(null));
        assertTrue(ResidenceFlagRefreshFilter.affectsPermissions(" "));
        assertTrue(new ResidenceFlagRefreshFilter(PlainEvent.class).shouldRefresh(new PlainEvent()));
        FlagEvent broken = new FlagEvent();
        broken.broken = true;
        assertTrue(new ResidenceFlagRefreshFilter(FlagEvent.class).shouldRefresh(broken));
    }

    public static class PlainEvent extends Event {
        @Override
        public HandlerList getHandlers() {
            return new HandlerList();
        }
    }

    public static class FlagEvent extends PlainEvent implements Cancellable {
        private String flag;
        private boolean cancelled;
        private boolean broken;

        public String getFlag() {
            if (broken) throw new IllegalStateException("unavailable");
            return flag;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public void setCancelled(boolean cancelled) {
            this.cancelled = cancelled;
        }
    }
}
