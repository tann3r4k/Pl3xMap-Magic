package dev.tanner.pl3xmap.magic;

import com.elmakers.mine.bukkit.api.event.CastEvent;
import com.elmakers.mine.bukkit.api.magic.Mage;
import com.elmakers.mine.bukkit.api.magic.MageController;
import com.elmakers.mine.bukkit.api.magic.MagicAPI;
import com.elmakers.mine.bukkit.api.spell.Spell;
import com.elmakers.mine.bukkit.api.wand.LostWand;
import com.elmakers.mine.bukkit.api.warp.Warp;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.pl3x.map.core.Pl3xMap;
import net.pl3x.map.core.event.EventListener;
import net.pl3x.map.core.event.server.Pl3xMapDisabledEvent;
import net.pl3x.map.core.event.server.Pl3xMapEnabledEvent;
import net.pl3x.map.core.event.world.WorldLoadedEvent;
import net.pl3x.map.core.event.world.WorldUnloadedEvent;
import net.pl3x.map.core.markers.Point;
import net.pl3x.map.core.markers.Vector;
import net.pl3x.map.core.markers.layer.WorldLayer;
import net.pl3x.map.core.markers.marker.Circle;
import net.pl3x.map.core.markers.marker.Icon;
import net.pl3x.map.core.markers.marker.Marker;
import net.pl3x.map.core.markers.marker.Polyline;
import net.pl3x.map.core.markers.option.Fill;
import net.pl3x.map.core.markers.option.Options;
import net.pl3x.map.core.markers.option.Stroke;
import net.pl3x.map.core.markers.option.Tooltip;
import net.pl3x.map.core.util.Colors;
import net.pl3x.map.core.world.World;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/**
 * Pl3xMap reads {@link WorldLayer#getMarkers()} on its own marker task and, when live
 * update is on, pushes changes over SSE. Marker edits stay on {@link WorldLayer}'s
 * concurrent map. Reads of Magic stay on the global region thread.
 */
final class MagicMarkers implements Listener, EventListener {
    static final String SPELLS = "magic_spells";
    static final String WANDS = "magic_wands";
    static final String WARPS = "magic_warps";
    private static final int FALLBACK_COLOR = 0x7B2CBF;

    private final MagicMapPlugin plugin;
    private final Map<String, SpellMark> spells = new ConcurrentHashMap<>();
    private final Map<String, WorldLayer> spellLayers = new ConcurrentHashMap<>();
    private final Map<String, WorldLayer> wandLayers = new ConcurrentHashMap<>();
    private final Map<String, WorldLayer> warpLayers = new ConcurrentHashMap<>();
    private volatile MagicMapConfig config;
    private volatile boolean running;
    private @Nullable ScheduledTask pollTask;

    MagicMarkers(MagicMapPlugin plugin, MagicMapConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    void applyConfig(MagicMapConfig config) {
        this.config = config;
        if (!this.running) {
            return;
        }
        restyle();
        if (!config.spells) {
            clear(this.spellLayers);
            cancelSpells();
        }
        if (!config.wands) {
            clear(this.wandLayers);
        }
        if (!config.warps) {
            clear(this.warpLayers);
        }
        restartPoll();
        refreshTracked();
    }

    void start() {
        Pl3xMap api = Pl3xMap.api();
        if (api == null || !api.isEnabled()) {
            return;
        }
        this.running = true;
        MapIcons.register(api);
        for (World world : api.getWorldRegistry()) {
            ensureLayers(world);
        }
        restartPoll();
        refreshTracked();
    }

    void shutdown() {
        this.running = false;
        if (this.pollTask != null) {
            this.pollTask.cancel();
            this.pollTask = null;
        }
        cancelSpells();
        unregister(this.spellLayers);
        unregister(this.wandLayers);
        unregister(this.warpLayers);
    }

    @EventHandler
    public void onCast(CastEvent event) {
        if (!this.running || !this.config.spells || event.getSpellResult() == null || !event.getSpellResult().isSuccess()) {
            return;
        }
        Mage mage = event.getMage();
        Spell spell = event.getSpell();
        if (mage == null || spell == null) {
            return;
        }
        if (this.config.playersOnly && !mage.isPlayer()) {
            return;
        }
        if (this.config.hideVanished && mage.isVanished()) {
            return;
        }
        Location location = spell.getLocation();
        if ((location == null || location.getWorld() == null) && event.getContext() != null) {
            location = event.getContext().getLocation();
        }
        if (location == null || location.getWorld() == null) {
            return;
        }
        showCast(mage, spell, location);
    }

    @net.pl3x.map.core.event.EventHandler
    public void onMapEnabled(Pl3xMapEnabledEvent event) {
        start();
    }

    @net.pl3x.map.core.event.EventHandler
    public void onMapDisabled(Pl3xMapDisabledEvent event) {
        this.running = false;
        if (this.pollTask != null) {
            this.pollTask.cancel();
            this.pollTask = null;
        }
        cancelSpells();
        this.spellLayers.clear();
        this.wandLayers.clear();
        this.warpLayers.clear();
    }

    @net.pl3x.map.core.event.EventHandler
    public void onWorldLoaded(WorldLoadedEvent event) {
        if (!this.running) {
            return;
        }
        ensureLayers(event.getWorld());
        refreshTracked();
    }

    @net.pl3x.map.core.event.EventHandler
    public void onWorldUnloaded(WorldUnloadedEvent event) {
        String name = event.getWorld().getName();
        this.spellLayers.remove(name);
        this.wandLayers.remove(name);
        this.warpLayers.remove(name);
        this.spells.values().removeIf(mark -> mark.world.equals(name));
    }

    private void showCast(Mage mage, Spell spell, Location location) {
        WorldLayer layer = this.spellLayers.get(location.getWorld().getName());
        if (layer == null) {
            return;
        }
        String mageId = safeKey(mage.getId());
        SpellMark previous = this.spells.remove(mageId);
        if (previous != null) {
            if (previous.expiry != null) {
                previous.expiry.cancel();
            }
            WorldLayer oldLayer = this.spellLayers.get(previous.world);
            if (oldLayer != null) {
                oldLayer.removeMarker(previous.circleKey);
                oldLayer.removeMarker(previous.lineKey);
            }
        }

        int spellRgb = MapText.rgb(spell.getEffectColor(), MapText.rgb(mage.getEffectColor(), FALLBACK_COLOR));
        int mageRgb = MapText.rgb(mage.getEffectColor(), spellRgb);
        String label = MapText.escape(spell.getName()) + " : " + MapText.escape(mage.getName());
        String circleKey = "spell-" + mageId;
        String lineKey = "spell-line-" + mageId;
        double radius = this.config.spellRadius;

        Circle circle = Marker.circle(circleKey, location.getX(), location.getZ(), radius);
        circle.setOptions(Options.builder()
                .stroke(new Stroke(2, Colors.setAlpha(230, spellRgb)))
                .fill(new Fill(Colors.setAlpha(96, mageRgb)))
                .tooltip(new Tooltip(label))
                .build());
        layer.addMarker(circle);

        Location target = spell.getTargetLocation();
        if (target == null || target.getWorld() == null || !target.getWorld().equals(location.getWorld())) {
            org.bukkit.util.Vector direction = location.getDirection();
            if (direction.lengthSquared() < 1.0E-6D) {
                direction = new org.bukkit.util.Vector(0, 0, 1);
            }
            target = location.clone().add(direction.normalize().multiply(Math.max(4.0D, radius)));
        }
        Polyline line = Marker.polyline(
                lineKey,
                Point.of(location.getX(), location.getZ()),
                Point.of(target.getX(), target.getZ())
        );
        line.setOptions(Options.builder()
                .stroke(new Stroke(2, Colors.setAlpha(210, spellRgb)))
                .tooltip(new Tooltip(label))
                .build());
        layer.addMarker(line);

        this.spells.put(mageId, new SpellMark(
                location.getWorld().getName(),
                circleKey,
                lineKey,
                scheduleExpiry(mageId, circleKey, lineKey, location.getWorld().getName())
        ));
    }

    private @Nullable ScheduledTask scheduleExpiry(String mageId, String circleKey, String lineKey, String world) {
        int seconds = this.config.spellLifetimeSeconds;
        if (seconds <= 0) {
            return null;
        }
        return this.plugin.getServer().getGlobalRegionScheduler().runDelayed(this.plugin, task -> {
            SpellMark current = this.spells.get(mageId);
            if (current == null || current.expiry != task) {
                return;
            }
            this.spells.remove(mageId, current);
            WorldLayer layer = this.spellLayers.get(world);
            if (layer != null) {
                layer.removeMarker(circleKey);
                layer.removeMarker(lineKey);
            }
        }, seconds * 20L);
    }

    private void restartPoll() {
        if (this.pollTask != null) {
            this.pollTask.cancel();
            this.pollTask = null;
        }
        if (!this.config.wands && !this.config.warps) {
            return;
        }
        this.pollTask = this.plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(
                this.plugin,
                task -> refreshTracked(),
                40L,
                40L
        );
    }

    private void refreshTracked() {
        if (!this.running) {
            return;
        }
        MageController controller = controller();
        if (controller == null) {
            return;
        }
        if (this.config.wands) {
            refreshWands(controller);
        }
        if (this.config.warps) {
            refreshWarps(controller);
        }
    }

    private void refreshWands(MageController controller) {
        Set<String> seen = new HashSet<>();
        for (LostWand wand : controller.getLostWands()) {
            if (wand == null || wand.getId() == null) {
                continue;
            }
            Location location = wand.getLocation();
            if (location == null || location.getWorld() == null) {
                continue;
            }
            WorldLayer layer = this.wandLayers.get(location.getWorld().getName());
            if (layer == null) {
                continue;
            }
            String key = "wand-" + safeKey(wand.getId());
            seen.add(location.getWorld().getName() + ":" + key);
            String label = MapText.escape(wand.getName());
            String owner = MapText.escape(wand.getOwner());
            if (!owner.isEmpty()) {
                label = label + "<br>" + owner;
            }
            putIcon(layer, key, location.getX(), location.getZ(), MapIcons.WAND, label);
        }
        removeMissing(this.wandLayers, "wand-", seen);
    }

    private void refreshWarps(MageController controller) {
        Set<String> seen = new HashSet<>();
        for (Warp warp : controller.getMagicWarps()) {
            if (warp == null) {
                continue;
            }
            Location location = warp.getLocation();
            if (location == null || location.getWorld() == null) {
                continue;
            }
            WorldLayer layer = this.warpLayers.get(location.getWorld().getName());
            if (layer == null) {
                continue;
            }
            String key = "warp-" + safeKey(warp.getKey());
            seen.add(location.getWorld().getName() + ":" + key);
            String label = MapText.escape(warp.getName());
            String description = MapText.escape(warp.getDescription());
            if (!description.isEmpty()) {
                label = label + "<br>" + description;
            }
            putIcon(layer, key, location.getX(), location.getZ(), MapIcons.WARP, label);
        }
        removeMissing(this.warpLayers, "warp-", seen);
    }

    private static void putIcon(WorldLayer layer, String key, double x, double z, String image, String label) {
        Icon icon = Marker.icon(key, x, z, image, 16);
        icon.setAnchor(Vector.of(8, 8));
        icon.setOptions(Options.builder()
                .tooltipContent(label)
                .tooltipDirection(Tooltip.Direction.TOP)
                .build());
        layer.addMarker(icon);
    }

    private static void removeMissing(Map<String, WorldLayer> layers, String prefix, Set<String> seen) {
        for (Map.Entry<String, WorldLayer> entry : layers.entrySet()) {
            for (String key : Set.copyOf(entry.getValue().registeredMarkers().keySet())) {
                if (key.startsWith(prefix) && !seen.contains(entry.getKey() + ":" + key)) {
                    entry.getValue().removeMarker(key);
                }
            }
        }
    }

    private MageController controller() {
        Plugin magic = this.plugin.getServer().getPluginManager().getPlugin("Magic");
        if (magic instanceof MagicAPI api) {
            return api.getController();
        }
        return null;
    }

    private void ensureLayers(World world) {
        this.spellLayers.computeIfAbsent(world.getName(), name -> register(world, new SpellLayer(world, () -> this.config.spellLabel)));
        this.wandLayers.computeIfAbsent(world.getName(), name -> register(world, new WandLayer(world, () -> this.config.wandLabel)));
        this.warpLayers.computeIfAbsent(world.getName(), name -> register(world, new WarpLayer(world, () -> this.config.warpLabel)));
    }

    private static WorldLayer register(World world, WorldLayer layer) {
        var existing = world.getLayerRegistry().get(layer.getKey());
        if (existing instanceof WorldLayer current) {
            return current;
        }
        world.getLayerRegistry().register(layer);
        return layer;
    }

    private void restyle() {
        this.spellLayers.values().forEach(layer -> layer.setLabel(this.config.spellLabel));
        this.wandLayers.values().forEach(layer -> layer.setLabel(this.config.wandLabel));
        this.warpLayers.values().forEach(layer -> layer.setLabel(this.config.warpLabel));
    }

    private void clear(Map<String, WorldLayer> layers) {
        layers.values().forEach(WorldLayer::clearMarkers);
    }

    private void cancelSpells() {
        for (SpellMark mark : this.spells.values()) {
            if (mark.expiry != null) {
                mark.expiry.cancel();
            }
        }
        this.spells.clear();
    }

    private void unregister(Map<String, WorldLayer> layers) {
        Pl3xMap api = Pl3xMap.api();
        if (api != null) {
            for (Map.Entry<String, WorldLayer> entry : layers.entrySet()) {
                World world = api.getWorldRegistry().get(entry.getKey());
                if (world != null) {
                    world.getLayerRegistry().unregister(entry.getValue().getKey());
                }
            }
        }
        layers.clear();
    }

    private static String safeKey(String raw) {
        if (raw == null || raw.isBlank()) {
            return "unknown";
        }
        return raw.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private record SpellMark(String world, String circleKey, String lineKey, @Nullable ScheduledTask expiry) {
    }

    private static final class SpellLayer extends WorldLayer {
        SpellLayer(World world, Supplier<String> label) {
            super(SPELLS, world, label);
            setUpdateInterval(1);
            setLiveUpdate(true);
            setShowControls(true);
            setDefaultHidden(false);
            setPriority(15);
            setZIndex(800);
        }
    }

    private static final class WandLayer extends WorldLayer {
        WandLayer(World world, Supplier<String> label) {
            super(WANDS, world, label);
            setUpdateInterval(2);
            setLiveUpdate(true);
            setShowControls(true);
            setDefaultHidden(false);
            setPriority(16);
            setZIndex(700);
        }
    }

    private static final class WarpLayer extends WorldLayer {
        WarpLayer(World world, Supplier<String> label) {
            super(WARPS, world, label);
            setUpdateInterval(30);
            setLiveUpdate(true);
            setShowControls(true);
            setDefaultHidden(false);
            setPriority(17);
            setZIndex(600);
        }
    }
}
