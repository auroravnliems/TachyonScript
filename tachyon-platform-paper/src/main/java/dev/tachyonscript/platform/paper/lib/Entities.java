package dev.tachyonscript.platform.paper.lib;

import dev.tachyonscript.api.natives.ScriptError;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Entity helpers of the standard library bindings. */
@SuppressWarnings({"deprecation", "removal"})
public final class Entities {

    private Entities() {
    }

    public static void remove(Entity entity) {
        if (entity instanceof Player player) {
            throw new ScriptError("Players cannot be removed; use " + player.getName() + ".kick(reason) instead.");
        }
        entity.remove();
    }

    public static List<Object> nearby(Entity center, double radius) {
        List<Object> result = new ArrayList<>();
        Location origin = center.getLocation();
        double squared = radius * radius;
        for (Entity entity : center.getNearbyEntities(radius, radius, radius)) {
            if (entity.getLocation().distanceSquared(origin) <= squared) {
                result.add(entity);
            }
        }
        return result;
    }

    public static List<Object> nearby(Location center, double radius) {
        List<Object> result = new ArrayList<>();
        World world = Worlds.world(center);
        double squared = radius * radius;
        for (Entity entity : world.getNearbyEntities(center, radius, radius, radius)) {
            if (entity.getLocation().distanceSquared(center) <= squared) {
                result.add(entity);
            }
        }
        return result;
    }

    public static List<Object> nearbyPlayers(Entity center, double radius) {
        List<Object> result = new ArrayList<>();
        for (Object entity : nearby(center, radius)) {
            if (entity instanceof Player) {
                result.add(entity);
            }
        }
        return result;
    }

    public static List<Object> nearbyPlayers(Location center, double radius) {
        List<Object> result = new ArrayList<>();
        World world = Worlds.world(center);
        double squared = radius * radius;
        for (Player player : world.getPlayers()) {
            if (player.getLocation().distanceSquared(center) <= squared) {
                result.add(player);
            }
        }
        return result;
    }

    public static double distance(Entity from, Entity to) {
        return distance(from.getLocation(), to.getLocation());
    }

    /** The distance, or {@code Double.MAX_VALUE} for locations in different worlds. */
    public static double distance(Location from, Location to) {
        if (from.getWorld() == null || from.getWorld() != to.getWorld()) {
            return Double.MAX_VALUE;
        }
        return from.distance(to);
    }

    public static double maxHealth(LivingEntity entity) {
        AttributeInstance attribute = entity.getAttribute(Attribute.MAX_HEALTH);
        return attribute != null ? attribute.getValue() : 20.0;
    }

    public static void setMaxHealth(LivingEntity entity, double value) {
        AttributeInstance attribute = entity.getAttribute(Attribute.MAX_HEALTH);
        if (attribute == null) {
            throw new ScriptError(entity.getName() + " has no maximum health.");
        }
        attribute.setBaseValue(Math.max(1, value));
        if (entity.getHealth() > attribute.getValue()) {
            entity.setHealth(attribute.getValue());
        }
    }

    public static ItemStack equipment(LivingEntity entity, EquipmentSlot slot) {
        EntityEquipment equipment = entity.getEquipment();
        return equipment == null ? null : Items.orNull(equipment.getItem(slot));
    }

    public static void setEquipment(LivingEntity entity, EquipmentSlot slot, ItemStack item) {
        EntityEquipment equipment = entity.getEquipment();
        if (equipment == null) {
            throw new ScriptError(entity.getName() + " cannot wear or hold items.");
        }
        equipment.setItem(slot, item);
    }

    public static double attribute(LivingEntity entity, Attribute attribute) {
        AttributeInstance instance = entity.getAttribute(attribute);
        return instance == null ? 0 : instance.getValue();
    }

    public static double attributeBase(LivingEntity entity, Attribute attribute) {
        AttributeInstance instance = entity.getAttribute(attribute);
        return instance == null ? 0 : instance.getBaseValue();
    }

    public static void setAttributeBase(LivingEntity entity, Attribute attribute, double value) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance == null) {
            throw new ScriptError(entity.getName() + " has no attribute " + Texts.keyText(attribute) + ".");
        }
        instance.setBaseValue(value);
    }

    public static Projectile launch(LivingEntity shooter, EntityType type) {
        Class<? extends Entity> type0 = type.getEntityClass();
        if (type0 == null || !Projectile.class.isAssignableFrom(type0)) {
            throw new ScriptError(Texts.keyText(type) + " is not a projectile.");
        }
        return shooter.launchProjectile(type0.asSubclass(Projectile.class));
    }

    public static OfflinePlayer owner(Tameable animal) {
        UUID owner = animal.getOwnerUniqueId();
        return owner == null ? null : Bukkit.getOfflinePlayer(owner);
    }

    /** Who is responsible for damage: the shooter of a projectile, the igniter of TNT, or the damager itself. */
    public static Entity attacker(Entity damager) {
        if (damager instanceof Projectile projectile) {
            return projectile.getShooter() instanceof Entity shooter ? shooter : null;
        }
        if (damager instanceof TNTPrimed tnt) {
            return tnt.getSource() != null ? tnt.getSource() : damager;
        }
        return damager;
    }

    public static Entity shooter(Projectile projectile) {
        return projectile.getShooter() instanceof Entity shooter ? shooter : null;
    }

    /** The value as a player, or null. */
    public static Player player(Object value) {
        return value instanceof Player player ? player : null;
    }

    public static double scale(TextDisplay display) {
        return display.getTransformation().getScale().x();
    }

    public static void setScale(TextDisplay display, double scale) {
        Transformation current = display.getTransformation();
        float size = (float) Math.max(0.01, scale);
        display.setTransformation(new Transformation(current.getTranslation(), current.getLeftRotation(),
                new Vector3f(size, size, size), current.getRightRotation()));
    }
}
