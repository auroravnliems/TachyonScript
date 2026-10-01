package dev.tachyonscript.testkit;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * In-memory stand-ins for server objects. They model just enough state to observe what
 * scripts do (messages received, health, cancelled events, ...).
 */
public final class Fakes {

    private Fakes() {
    }

    /** Something that receives messages and has permissions. */
    public interface Sender {
        String name();

        void send(Object message);

        boolean hasPermission(String permission);

        boolean isOp();
    }

    public enum GameMode {
        SURVIVAL, CREATIVE, ADVENTURE, SPECTATOR
    }

    public static final class World {
        private final String name;
        private long time;
        private double borderSize = 59_999_968;
        private final List<Player> players = new ArrayList<>();

        public World(String name) {
            this.name = name;
        }

        public String name() {
            return name;
        }

        public long time() {
            return time;
        }

        public void time(long value) {
            time = value;
        }

        /** Width of the world border (Minecraft's default is 59,999,968 blocks). */
        public double borderSize() {
            return borderSize;
        }

        public void borderSize(double value) {
            borderSize = value;
        }

        public List<Player> players() {
            return players;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /** Immutable, like a copied Bukkit location. */
    public record Location(World world, double x, double y, double z, float yaw, float pitch) {
        public Location add(double dx, double dy, double dz) {
            return new Location(world, x + dx, y + dy, z + dz, yaw, pitch);
        }

        public double distance(Location other) {
            double dx = x - other.x;
            double dy = y - other.y;
            double dz = z - other.z;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }

    /** A block; {@code type} is its material key, e.g. {@code minecraft:stone}. */
    public record Block(Location location, String type) {
    }

    /**
     * Something a player saw or heard outside the chat: {@code actionbar} (the text above the
     * hotbar), {@code title} (with its {@code subtitle}) or {@code sound} (the sound's key).
     */
    public record Shown(String kind, Object text, Object subtitle) {
        @Override
        public String toString() {
            return String.valueOf(text);
        }
    }

    /**
     * The value of a constant of a keyed type ({@code Material.DIAMOND}): the type name and the
     * key. Text of it is the key without {@code minecraft:}, like on a server.
     */
    public record Keyed(String type, String key) {
        public String text() {
            return key.startsWith("minecraft:") ? key.substring("minecraft:".length()) : key;
        }

        @Override
        public String toString() {
            return text();
        }
    }

    public static class Entity {
        private final UUID uuid = UUID.randomUUID();
        private final String name;
        private Location location;
        private boolean valid = true;

        public Entity(String name, Location location) {
            this.name = name;
            this.location = location;
        }

        public UUID uuid() {
            return uuid;
        }

        public String name() {
            return name;
        }

        public Location location() {
            return location;
        }

        public void teleport(Location destination) {
            location = destination;
        }

        public boolean valid() {
            return valid;
        }

        public void invalidate() {
            valid = false;
        }

        /** Makes the entity valid again (a player who rejoins). */
        public void revalidate() {
            valid = true;
        }
    }

    public static class LivingEntity extends Entity {
        private double health = 20;
        private double maxHealth = 20;

        public LivingEntity(String name, Location location) {
            super(name, location);
        }

        public double health() {
            return health;
        }

        public void health(double value) {
            if (value < 0 || value > maxHealth) {
                throw new IllegalArgumentException("Health must be between 0 and " + maxHealth + ", but was " + value);
            }
            health = value;
        }

        public double maxHealth() {
            return maxHealth;
        }

        public void maxHealth(double value) {
            maxHealth = value;
        }
    }

    public static final class Player extends LivingEntity implements Sender {
        private final List<Object> messages = new ArrayList<>();
        private final Set<String> permissions = new HashSet<>();
        private int food = 20;
        private int level;
        private GameMode gameMode = GameMode.SURVIVAL;
        private Object displayName;
        private Object kickReason;
        private boolean op;
        private boolean playedBefore = true;

        /** Revoke a permission while a menu is open, as a permissions plugin can do. */
        public void revoke(String permission) {
            permissions.remove(permission);
        }

        public Player(String name, Location location) {
            super(name, location);
            this.displayName = name;
        }

        /** Whether the player had joined before (false for a first join); true unless set. */
        public boolean playedBefore() {
            return playedBefore;
        }

        public Player playedBefore(boolean value) {
            playedBefore = value;
            return this;
        }

        @Override
        public void send(Object message) {
            messages.add(message);
        }

        /** Chat messages, and (as {@link Shown}) what the player saw or heard outside the chat, in order. */
        public List<Object> messages() {
            return messages;
        }

        /** Records an action bar, a title or a sound for this player. */
        public void show(String kind, Object text, Object subtitle) {
            messages.add(new Shown(kind, text, subtitle));
        }

        public Player grant(String permission) {
            permissions.add(permission);
            return this;
        }

        @Override
        public boolean hasPermission(String permission) {
            return op || permissions.contains(permission);
        }

        @Override
        public boolean isOp() {
            return op;
        }

        public void op(boolean value) {
            op = value;
        }

        public int food() {
            return food;
        }

        public void food(int value) {
            food = value;
        }

        public int level() {
            return level;
        }

        public void level(int value) {
            level = value;
        }

        public GameMode gameMode() {
            return gameMode;
        }

        public void gameMode(GameMode value) {
            gameMode = value;
        }

        public Object displayName() {
            return displayName;
        }

        public void displayName(Object value) {
            displayName = value;
        }

        public void kick(Object reason) {
            kickReason = reason;
            invalidate();
        }

        public Object kickReason() {
            return kickReason;
        }

        @Override
        public String toString() {
            return "Player(" + name() + ")";
        }
    }

    public static final class Console implements Sender {
        private final List<Object> messages = new ArrayList<>();

        @Override
        public String name() {
            return "CONSOLE";
        }

        @Override
        public void send(Object message) {
            messages.add(message);
        }

        public List<Object> messages() {
            return messages;
        }

        @Override
        public boolean hasPermission(String permission) {
            return true;
        }

        @Override
        public boolean isOp() {
            return true;
        }
    }

    // ------------------------------------------------------------------ events

    public interface Cancellable {
        boolean isCancelled();

        void setCancelled(boolean cancelled);
    }

    public abstract static class CancellableEvent implements Cancellable {
        private boolean cancelled;

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public void setCancelled(boolean value) {
            cancelled = value;
        }
    }

    public static final class JoinEvent {
        public final Player player;
        public Object joinMessage;

        public JoinEvent(Player player) {
            this.player = player;
            this.joinMessage = player.name() + " joined the game";
        }
    }

    public static final class QuitEvent {
        public final Player player;
        public Object quitMessage;

        public QuitEvent(Player player) {
            this.player = player;
            this.quitMessage = player.name() + " left the game";
        }
    }

    public static final class DeathEvent extends CancellableEvent {
        public final Player victim;
        public final Player killer;
        public Object deathMessage;
        public boolean keepInventory;

        public DeathEvent(Player victim, Player killer) {
            this.victim = victim;
            this.killer = killer;
            this.deathMessage = victim.name() + " died";
        }
    }

    public static final class ChatEvent extends CancellableEvent {
        public final Player player;
        public final String message;
        /** What everyone sees ({@code event.message}); the typed message unless a script changed it. */
        public Object shown;

        public ChatEvent(Player player, String message) {
            this.player = player;
            this.message = message;
            this.shown = message;
        }
    }

    public static final class MoveEvent extends CancellableEvent {
        public final Player player;
        public final Location from;
        public final Location to;

        public MoveEvent(Player player, Location from, Location to) {
            this.player = player;
            this.from = from;
            this.to = to;
        }
    }

    public static final class BlockBreakEvent extends CancellableEvent {
        public final Player player;
        public final Block block;

        public BlockBreakEvent(Player player, Block block) {
            this.player = player;
            this.block = block;
        }
    }

    public static final class BlockPlaceEvent extends CancellableEvent {
        public final Player player;
        public final Block block;

        public BlockPlaceEvent(Player player, Block block) {
            this.player = player;
            this.block = block;
        }
    }

    public static final class DamageEvent extends CancellableEvent {
        public final Entity entity;
        public final String cause;
        public double damage;

        public DamageEvent(Entity entity, String cause, double damage) {
            this.entity = entity;
            this.cause = cause;
            this.damage = damage;
        }
    }
}
