package net.rose.saturnProtect.listener;

import io.papermc.paper.event.player.PlayerItemFrameChangeEvent;
import net.rose.saturnProtect.config.ProtectConfig;
import net.rose.saturnProtect.core.LogService;
import net.rose.saturnProtect.model.EventAction;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Painting;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.inventory.ItemStack;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Item frames, glow item frames, paintings and armor stands. These are entities, not blocks, so they are
 * logged as events ({@code /sp events a:entity-place|entity-break|entity-item}) rather than block changes,
 * and are not touched by rollbacks.
 * <p>
 * Placing and breaking a frame, painting or stand is logged, as is every item put in or taken out of a frame and
 * every item put on or taken off a stand. Rotating a frame is not logged. Armor stand deaths are handled here
 * because {@link KillListener} skips them.
 */
public final class FrameStandListener implements Listener {

    private static final int MAX_LISTED_DROPS = 6;

    private record Actor(String name, UUID uuid) {
    }

    private final ProtectConfig config;
    private final LogService logService;

    public FrameStandListener(ProtectConfig config, LogService logService) {
        this.config = config;
        this.logService = logService;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent event) {
        Player player = event.getPlayer();
        Hanging hanging = event.getEntity();
        if (player == null || !framesEnabled(hanging)) {
            return;
        }
        log(new Actor(player.getName(), player.getUniqueId()), hanging, EventAction.ENTITY_PLACE, name(hanging));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakEvent event) {
        Hanging hanging = event.getEntity();
        if (!framesEnabled(hanging)) {
            return;
        }

        Actor actor;
        if (event instanceof HangingBreakByEntityEvent byEntity && byEntity.getRemover() != null) {
            actor = actorOf(byEntity.getRemover());
        } else {
            actor = new Actor("#" + event.getCause().name().toLowerCase(Locale.ROOT), null);
        }

        String subject = name(hanging);
        if (hanging instanceof ItemFrame frame && !frame.getItem().getType().isAir()) {
            subject += " holding " + name(frame.getItem());
        }
        log(actor, hanging, EventAction.ENTITY_BREAK, subject);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFrameChange(PlayerItemFrameChangeEvent event) {
        if (!config.logItemFrames()) {
            return;
        }
        String subject = switch (event.getAction()) {
            case PLACE -> "put " + name(event.getItemStack()) + " in " + name(event.getItemFrame());
            case REMOVE -> "took " + name(event.getItemStack()) + " from " + name(event.getItemFrame());
            default -> null; // rotating a frame is not worth a row
        };
        if (subject == null) {
            return;
        }
        Player player = event.getPlayer();
        log(new Actor(player.getName(), player.getUniqueId()), event.getItemFrame(), EventAction.ENTITY_ITEM, subject);
    }

    /** Armor stands are placed by an item, which Bukkit reports as an entity placement. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityPlace(EntityPlaceEvent event) {
        Player player = event.getPlayer();
        if (player == null || !config.logArmorStands() || !(event.getEntity() instanceof ArmorStand stand)) {
            return;
        }
        log(new Actor(player.getName(), player.getUniqueId()), stand, EventAction.ENTITY_PLACE, name(stand));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onStandManipulate(PlayerArmorStandManipulateEvent event) {
        if (!config.logArmorStands()) {
            return;
        }
        ItemStack onStand = event.getArmorStandItem();
        ItemStack inHand = event.getPlayerItem();
        boolean took = !onStand.getType().isAir();
        boolean put = !inHand.getType().isAir();
        if (!took && !put) {
            return;
        }

        String stand = name(event.getRightClicked());
        String slot = event.getSlot().name().toLowerCase(Locale.ROOT);
        String subject;
        if (took && put) {
            subject = "swapped " + name(onStand) + " for " + name(inHand) + " on " + stand + " (" + slot + ")";
        } else if (took) {
            subject = "took " + name(onStand) + " from " + stand + " (" + slot + ")";
        } else {
            subject = "put " + name(inHand) + " on " + stand + " (" + slot + ")";
        }
        Player player = event.getPlayer();
        log(new Actor(player.getName(), player.getUniqueId()), event.getRightClicked(), EventAction.ENTITY_ITEM, subject);
    }

    /** {@link KillListener} ignores armor stands, so their destruction is logged here. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onStandDeath(EntityDeathEvent event) {
        if (!config.logArmorStands() || !(event.getEntity() instanceof ArmorStand stand)) {
            return;
        }

        Actor actor;
        Player killer = stand.getKiller();
        EntityDamageEvent last = stand.getLastDamageCause();
        if (killer != null) {
            actor = new Actor(killer.getName(), killer.getUniqueId());
        } else if (last instanceof EntityDamageByEntityEvent byEntity) {
            actor = actorOf(byEntity.getDamager());
        } else if (last != null) {
            actor = new Actor("#" + last.getCause().name().toLowerCase(Locale.ROOT), null);
        } else {
            actor = new Actor("#unknown", null);
        }

        // whatever it was wearing or holding drops, which is a fair record of what was on it
        Map<String, Integer> carried = new LinkedHashMap<>();
        for (ItemStack drop : event.getDrops()) {
            if (drop == null || drop.getType().isAir() || drop.getType() == Material.ARMOR_STAND) {
                continue;
            }
            carried.merge(name(drop), drop.getAmount(), Integer::sum);
        }
        StringBuilder subject = new StringBuilder(name(stand));
        if (!carried.isEmpty()) {
            subject.append(" holding ");
            int listed = 0;
            for (Map.Entry<String, Integer> entry : carried.entrySet()) {
                if (listed == MAX_LISTED_DROPS) {
                    subject.append(", ...");
                    break;
                }
                subject.append(listed++ == 0 ? "" : ", ")
                        .append(entry.getValue() > 1 ? entry.getValue() + "x " : "")
                        .append(entry.getKey());
            }
        }
        log(actor, stand, EventAction.ENTITY_BREAK, subject.toString());
    }

    private boolean framesEnabled(Hanging hanging) {
        return config.logItemFrames() && (hanging instanceof ItemFrame || hanging instanceof Painting);
    }

    /** The player behind a projectile or lit TNT, otherwise the entity itself as {@code #type}. */
    private static Actor actorOf(Entity entity) {
        if (entity instanceof Player player) {
            return new Actor(player.getName(), player.getUniqueId());
        }
        if (entity instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) {
            return new Actor(shooter.getName(), shooter.getUniqueId());
        }
        if (entity instanceof TNTPrimed tnt && tnt.getSource() instanceof Player source) {
            return new Actor(source.getName(), source.getUniqueId());
        }
        return new Actor("#" + name(entity), null);
    }

    private void log(Actor actor, Entity entity, EventAction action, String subject) {
        Location location = entity.getLocation();
        logService.logEvent(actor.name(), actor.uuid(), location.getWorld().getName(),
                location.getBlockX(), location.getBlockY(), location.getBlockZ(),
                action, subject, entity.getType().getKey().toString());
    }

    private static String name(Entity entity) {
        return entity.getType().getKey().getKey();
    }

    private static String name(ItemStack item) {
        return item.getType().getKey().getKey();
    }
}
