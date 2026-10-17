package dev.tanner.pl3xmap.magic;

import net.pl3x.map.core.Pl3xMap;
import org.bukkit.plugin.java.JavaPlugin;

public final class MagicMapPlugin extends JavaPlugin {
    private MagicMarkers markers;

    @Override
    public void onEnable() {
        this.markers = new MagicMarkers(this, MagicMapConfig.load(this));
        Pl3xMap api = Pl3xMap.api();
        if (api == null) {
            getSLF4JLogger().error("Pl3xMap is not initialized");
            return;
        }
        api.getEventRegistry().register(this.markers);
        getServer().getPluginManager().registerEvents(this.markers, this);
        if (api.isEnabled()) {
            this.markers.start();
        }
        getSLF4JLogger().info("Magic map enabled");
    }

    @Override
    public void onDisable() {
        if (this.markers != null) {
            this.markers.shutdown();
            this.markers = null;
        }
    }

    public void reload() {
        if (this.markers == null) {
            return;
        }
        this.markers.applyConfig(MagicMapConfig.load(this));
    }
}
