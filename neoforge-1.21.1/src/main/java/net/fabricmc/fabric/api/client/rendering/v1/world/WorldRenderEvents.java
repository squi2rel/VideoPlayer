package net.fabricmc.fabric.api.client.rendering.v1.world;

import java.util.ArrayList;
import java.util.List;

public final class WorldRenderEvents {
    public static final RenderEvent START_MAIN = new RenderEvent();
    public static final RenderEvent AFTER_ENTITIES = new RenderEvent();

    private WorldRenderEvents() {
    }

    @FunctionalInterface
    public interface Render {
        void render(WorldRenderContext context);
    }

    public static final class RenderEvent {
        private final List<Render> callbacks = new ArrayList<>();

        public void register(Render callback) {
            callbacks.add(callback);
        }

        public void invoke(WorldRenderContext context) {
            for (Render callback : List.copyOf(callbacks)) callback.render(context);
        }
    }
}
