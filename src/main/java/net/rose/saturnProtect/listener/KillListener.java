package net.rose.saturnProtect.listener;

import net.rose.saturnProtect.config.ProtectConfig;
import net.rose.saturnProtect.core.LogService;
import net.rose.saturnProtect.model.EventAction;
import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;

import java.util.Locale;
import java.util.UUID;

/**
 * Logs anything a player kills, and deaths of players that were not caused by another player.
 * Deaths without a player killer use {@code #cause} as the actor, e.g. {@code #fall} or {@code #zombie}.
 */
public final class KillListener implements Listener {

    private final ProtectConfig config;
    private final LogService logService;

    public KillListener(ProtectConfig config, LogService logService) {
        this.config = config;
        this.logService = logService;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim instanceof ArmorStand) {
            return;
        }

        Player killer = victim.getKiller();
        if (killer != null) {
            if (config.logKills()) {
                log(killer.getName(), killer.getUniqueId(), victim);
            }
            return;
        }

        EntityDamageEvent last = victim.getLastDamageCause();
        if (last != null && last.getCause() == EntityDamageEvent.DamageCause.LAVA) {
            logLavaDeath(victim);
            return;
        }
        if (victim instanceof Player && config.logPlayerDeaths()) {
            log("#" + causeOf(victim), null, victim);
        }
    }

    /** Blames the player who placed the nearest recent lava, falling back to {@code #lava} for player deaths. */
    private void logLavaDeath(LivingEntity victim) {
        boolean fallback = victim instanceof Player && config.logPlayerDeaths();
        if (!config.logKills() && !fallback) {
            return;
        }
        Location location = victim.getLocation();
        String name = victim instanceof Player player ? player.getName() : victim.getType().getKey().getKey();
        logService.logLavaDeath(name, victim.getType().getKey().toString(), location.getWorld().getName(),
                location.getBlockX(), location.getBlockY(), location.getBlockZ(), config.logKills(), fallback);
    }

    private void log(String actor, UUID actorUuid, LivingEntity victim) {
        Location location = victim.getLocation();
        String type = victim.getType().getKey().toString();
        String name = victim instanceof Player player ? player.getName() : victim.getType().getKey().getKey();
        logService.logEvent(actor, actorUuid, location.getWorld().getName(),
                location.getBlockX(), location.getBlockY(), location.getBlockZ(),
                EventAction.KILL, name, type);
    }

    private static String causeOf(LivingEntity victim) {
        EntityDamageEvent last = victim.getLastDamageCause();
        if (last instanceof EntityDamageByEntityEvent byEntity) {
            Entity damager = byEntity.getDamager();
            if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) {
                damager = shooter;
            }
            return damager.getType().getKey().getKey();
        }
        return last == null ? "unknown" : last.getCause().name().toLowerCase(Locale.ROOT);
    }
}
