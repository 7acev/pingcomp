package dev.pingcomp;

import io.papermc.paper.event.player.PlayerStopUsingItemEvent;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.entity.Trident;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Capped, ping-scaled projectile + consumable compensation.
 * Players under min_ping are never touched. Main-thread only (not Folia-safe).
 */
public final class PingComp extends JavaPlugin implements Listener {

    private static final class Tracked {
        final Projectile proj;
        final UUID shooter;
        int life;
        Tracked(Projectile proj, UUID shooter, int life) {
            this.proj = proj; this.shooter = shooter; this.life = life;
        }
    }

    // ---- config ----
    private int minPing, maxPing;
    private boolean pearls, arrows, potions, hitGrace;
    private double fwdMult, capPearl, capArrow, capPotion, graceMax, bufferScale;
    private int trackedTicks, maxBufferTicks;
    private final Set<Material> consumables = EnumSet.noneOf(Material.class);

    // ---- temporary state (RAM only, cleaned on quit / hit / death / disable) ----
    private final Map<UUID, Tracked> tracked = new HashMap<>();   // projectile uuid -> tracking info
    private final Map<UUID, Integer> lastFinish = new HashMap<>(); // player uuid -> server tick

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        getServer().getPluginManager().registerEvents(this, this);
        if (hitGrace) {
            getServer().getScheduler().runTaskTimer(this, this::tickTracked, 1L, 1L);
        }
    }

    @Override
    public void onDisable() {
        tracked.clear();
        lastFinish.clear();
    }

    private void loadSettings() {
        FileConfiguration c = getConfig();
        minPing = Math.max(0, c.getInt("min_ping", 70));
        maxPing = Math.max(minPing + 1, c.getInt("max_ping", 250)); // hard cap
        pearls = c.getBoolean("projectiles.ender_pearl", true);
        arrows = c.getBoolean("projectiles.arrow", true);
        potions = c.getBoolean("projectiles.splash_potion", true);
        fwdMult = c.getDouble("projectiles.forward_multiplier", 0.35);
        capPearl = c.getDouble("projectiles.max_offset_blocks.ender_pearl", 1.5);
        capArrow = c.getDouble("projectiles.max_offset_blocks.arrow", 2.0);
        capPotion = c.getDouble("projectiles.max_offset_blocks.splash_potion", 1.5);
        hitGrace = c.getBoolean("projectiles.hit_grace.enabled", true);
        graceMax = c.getDouble("projectiles.hit_grace.max_blocks", 0.35);
        trackedTicks = c.getInt("projectiles.hit_grace.max_tracked_ticks", 100);
        bufferScale = c.getDouble("consumables.buffer_scale", 1.0);
        maxBufferTicks = Math.max(1, Math.min(3, c.getInt("consumables.max_buffer_ticks", 3)));
        consumables.clear();
        for (String s : c.getStringList("consumables.items")) {
            Material m = Material.matchMaterial(s);
            if (m != null) consumables.add(m);
        }
    }

    /** @return ms above baseline, clamped to [0, max-min]; -1 if player is under min_ping (ignored). */
    private double effPing(Player p) {
        int ping = p.getPing();
        if (ping < minPing) return -1;
        return Math.min(ping, maxPing) - minPing;
    }

    // ===================== PROJECTILES =====================

    // MONITOR + ignoreCancelled: protection/anticheat plugins act first; we never alter the event itself.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent e) {
        Projectile proj = e.getEntity();
        if (!(proj.getShooter() instanceof Player p)) return;
        double eff = effPing(p);
        if (eff < 0) return;

        final double cap;
        final boolean track;
        if (proj instanceof EnderPearl) {
            if (!pearls) return;
            cap = capPearl; track = false;
        } else if (proj instanceof Trident) {
            return;
        } else if (proj instanceof AbstractArrow) {
            if (!arrows) return;
            cap = capArrow; track = true;
        } else if (proj instanceof ThrownPotion) {
            if (!potions) return;
            cap = capPotion; track = true;
        } else {
            return;
        }

        // The entity is not fully in the world during this event, so adjust next tick.
        getServer().getScheduler().runTask(this, () -> {
            if (!proj.isValid() || !p.isOnline()) return;
            advance(proj, eff, cap);
            if (track && hitGrace) {
                tracked.put(proj.getUniqueId(), new Tracked(proj, p.getUniqueId(), trackedTicks));
            }
        });
    }

    /** Moves the projectile forward along its flight path (never through blocks). Velocity is untouched. */
    private void advance(Projectile proj, double eff, double cap) {
        Vector vel = proj.getVelocity();
        double speed = vel.length();
        if (speed < 1e-4) return;

        double dist = Math.min(speed * (eff / 50.0) * fwdMult, cap);
        if (dist < 0.05) return;

        Location loc = proj.getLocation();
        Vector dir = vel.clone().multiply(1.0 / speed);

        RayTraceResult hit = loc.getWorld().rayTraceBlocks(
                loc, dir, dist + 0.6, FluidCollisionMode.NEVER, true);
        if (hit != null) {
            double toWall = hit.getHitPosition().distance(loc.toVector());
            dist = Math.min(dist, toWall - 0.6);
            if (dist < 0.05) return;
        }

        loc.add(dir.multiply(dist));
        proj.teleport(loc);
        proj.setVelocity(vel);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onHit(ProjectileHitEvent e) {
        tracked.remove(e.getEntity().getUniqueId());
    }

    /**
     * Hit grace: if the projectile's next-tick path passes within `grace` blocks of a living entity
     * that vanilla would miss, and there's clear line of sight, relocate the projectile into that
     * entity so VANILLA collision/damage/splash logic does the actual hit. We never apply damage ourselves.
     */
    private void tickTracked() {
        if (tracked.isEmpty()) return;
        Iterator<Tracked> it = tracked.values().iterator();
        while (it.hasNext()) {
            Tracked t = it.next();
            Projectile p = t.proj;
            if (!p.isValid() || --t.life <= 0 || (p instanceof AbstractArrow a && a.isInBlock())) {
                it.remove();
                continue;
            }
            Player shooter = Bukkit.getPlayer(t.shooter);
            if (shooter == null) { it.remove(); continue; }
            double eff = effPing(shooter);
            if (eff <= 0) continue;

            double grace = graceMax * eff / (maxPing - minPing);
            Vector vel = p.getVelocity();
            double len = vel.length();
            if (len < 0.05) continue;

            Location loc = p.getLocation();
            Vector start = loc.toVector();
            Vector dir = vel.clone().multiply(1.0 / len);
            double reach = len + grace + 1.0;

            LivingEntity best = null;
            double bestDist = Double.MAX_VALUE;
            for (Entity e : p.getNearbyEntities(reach, reach, reach)) {
                if (!(e instanceof LivingEntity le) || e instanceof ArmorStand) continue;
                if (e.getUniqueId().equals(t.shooter)) continue;
                if (le instanceof Player pl && pl.getGameMode() == GameMode.SPECTATOR) continue;

                BoundingBox real = le.getBoundingBox();
                // vanilla already inflates projectile collision by 0.3: if it would hit, leave it alone
                if (real.clone().expand(0.3).rayTrace(start, dir, len) != null) { best = null; break; }

                RayTraceResult r = real.clone().expand(0.3 + grace).rayTrace(start, dir, len);
                if (r == null) continue;
                double d = r.getHitPosition().distance(start);
                if (d < bestDist) { bestDist = d; best = le; }
            }
            if (best == null) continue;

            Vector target = best.getBoundingBox().getCenter();
            Vector toT = target.clone().subtract(start);
            double dd = toT.length();
            if (dd > 1e-4) {
                RayTraceResult wall = loc.getWorld().rayTraceBlocks(
                        loc, toT.clone().multiply(1.0 / dd), dd, FluidCollisionMode.NEVER, true);
                if (wall != null) continue; // never assist through blocks
            }
            Location to = target.toLocation(loc.getWorld(), loc.getYaw(), loc.getPitch());
            p.teleport(to);
            p.setVelocity(vel);
            it.remove(); // one assist per projectile
        }
    }

    // ===================== CONSUMABLES =====================

    /**
     * Paper-only event. Fired when the client releases/stops using an item. If the player is within
     * `grace` ticks of finishing a food/potion, complete it now instead of cancelling the animation.
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onStopUsing(PlayerStopUsingItemEvent e) {
        Player p = e.getPlayer();
        double eff = effPing(p);
        if (eff < 0) return;

        ItemStack item = e.getItem();
        if (!consumables.contains(item.getType())) return;

        int remaining = p.getItemUseRemainingTime();
        if (remaining <= 0) return;

        int grace = (int) Math.ceil(eff / 50.0 * bufferScale);
        grace = Math.max(1, Math.min(maxBufferTicks, grace));
        if (remaining > grace) return; // genuine early release -> vanilla cancel

        int now = Bukkit.getCurrentTick();
        Integer last = lastFinish.get(p.getUniqueId());
        if (last != null && now - last <= 3) return; // anti double-eat
        lastFinish.put(p.getUniqueId(), now);

        p.completeUsingActiveItem(); // fires PlayerItemConsumeEvent normally
    }

    // ===================== CLEANUP =====================

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        lastFinish.remove(id);
        tracked.values().removeIf(t -> t.shooter.equals(id));
    }
}
