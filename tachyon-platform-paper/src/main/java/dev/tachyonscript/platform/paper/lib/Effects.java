package dev.tachyonscript.platform.paper.lib;

import dev.tachyonscript.api.natives.ScriptError;
import dev.tachyonscript.api.value.Values;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/** Potion effect and particle helpers of the standard library bindings. */
public final class Effects {

    private Effects() {
    }

    public static PotionEffect of(PotionEffectType type, long durationMillis, int level) {
        return of(type, durationMillis, level, false, true);
    }

    /** A potion effect; level 1 is the normal strength, a negative duration lasts forever. */
    public static PotionEffect of(PotionEffectType type, long durationMillis, int level, boolean ambient, boolean particles) {
        if (level < 1 || level > 256) {
            throw new ScriptError("Potion effect levels go from 1 to 256, not " + level + ".");
        }
        int ticks = durationMillis < 0 ? PotionEffect.INFINITE_DURATION : Math.max(1, Ticks.of(durationMillis));
        return new PotionEffect(type, ticks, level - 1, ambient, particles);
    }

    public static String describe(PotionEffect effect) {
        String duration = effect.isInfinite() ? "forever" : Values.durationToString(effect.getDuration() * 50L);
        return Texts.keyText(effect.getType()) + " " + (effect.getAmplifier() + 1) + " (" + duration + ")";
    }

    /** Shows particles to one player, or to everyone near the location when {@code player} is null. */
    public static void particle(Player player, Particle particle, Location location, int count, double offsetX,
                                double offsetY, double offsetZ, double speed) {
        if (count < 0 || count > 10_000) {
            throw new ScriptError("Particle counts go from 0 to 10000, not " + count + ".");
        }
        if (particle.getDataType() != Void.class) {
            throw new ScriptError("The particle " + Texts.keyText(particle) + " needs extra data (a color, a block or an"
                    + " item) and cannot be shown this way; use spawnDust for colored dust.");
        }
        if (player != null) {
            player.spawnParticle(particle, location, count, offsetX, offsetY, offsetZ, speed);
        } else {
            Worlds.world(location).spawnParticle(particle, location, count, offsetX, offsetY, offsetZ, speed);
        }
    }

    public static void dust(Player player, Color color, double size, Location location, int count) {
        Particle.DustOptions options = new Particle.DustOptions(color, (float) Math.max(0.01, Math.min(4, size)));
        if (player != null) {
            player.spawnParticle(Particle.DUST, location, count, 0, 0, 0, 0, options);
        } else {
            World world = Worlds.world(location);
            world.spawnParticle(Particle.DUST, location, count, 0, 0, 0, 0, options);
        }
    }
}
