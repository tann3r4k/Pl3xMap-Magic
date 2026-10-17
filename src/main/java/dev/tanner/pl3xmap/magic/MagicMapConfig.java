package dev.tanner.pl3xmap.magic;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

final class MagicMapConfig {
    final boolean spells;
    final boolean playersOnly;
    final boolean hideVanished;
    final int spellLifetimeSeconds;
    final double spellRadius;
    final String spellLabel;
    final boolean wands;
    final String wandLabel;
    final boolean warps;
    final String warpLabel;

    private MagicMapConfig(
            boolean spells,
            boolean playersOnly,
            boolean hideVanished,
            int spellLifetimeSeconds,
            double spellRadius,
            String spellLabel,
            boolean wands,
            String wandLabel,
            boolean warps,
            String warpLabel
    ) {
        this.spells = spells;
        this.playersOnly = playersOnly;
        this.hideVanished = hideVanished;
        this.spellLifetimeSeconds = spellLifetimeSeconds;
        this.spellRadius = spellRadius;
        this.spellLabel = spellLabel;
        this.wands = wands;
        this.wandLabel = wandLabel;
        this.warps = warps;
        this.warpLabel = warpLabel;
    }

    static MagicMapConfig load(JavaPlugin plugin) {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        FileConfiguration config = plugin.getConfig();
        return new MagicMapConfig(
                config.getBoolean("spells.enabled", true),
                config.getBoolean("spells.players-only", false),
                config.getBoolean("spells.hide-vanished", true),
                Math.max(0, config.getInt("spells.lifetime-seconds", 12)),
                Math.max(1.0D, config.getDouble("spells.radius", 8.0D)),
                config.getString("spells.label", "Spells"),
                config.getBoolean("wands.enabled", true),
                config.getString("wands.label", "Lost Wands"),
                config.getBoolean("warps.enabled", true),
                config.getString("warps.label", "Warps")
        );
    }
}
