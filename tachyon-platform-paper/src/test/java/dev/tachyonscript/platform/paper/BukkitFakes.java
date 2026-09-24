package dev.tachyonscript.platform.paper;

import net.kyori.adventure.text.Component;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Just enough of the Bukkit API to exercise the Paper bindings without a server: interface
 * fakes are dynamic proxies that answer the methods a test configured and fail loudly on
 * anything else. (Reflection is fine here; this is test code.)
 */
final class BukkitFakes {

    @FunctionalInterface
    interface Answer {
        Object answer(Object[] args);
    }

    private BukkitFakes() {
    }

    /** A proxy implementing {@code type}; {@code answers} is keyed by method name. */
    static <T> T fake(Class<T> type, Map<String, Answer> answers) {
        Object proxy = Proxy.newProxyInstance(BukkitFakes.class.getClassLoader(), new Class<?>[] {type},
                (self, method, args) -> {
                    Object[] arguments = args == null ? new Object[0] : args;
                    Answer answer = answers.get(method.getName() + "/" + arguments.length);
                    if (answer == null) {
                        answer = answers.get(method.getName());
                    }
                    if (answer != null) {
                        return answer.answer(arguments);
                    }
                    return switch (method.getName()) {
                        case "equals" -> self == arguments[0];
                        case "hashCode" -> System.identityHashCode(self);
                        case "toString" -> type.getSimpleName() + "(fake)";
                        default -> throw new UnsupportedOperationException(
                                type.getSimpleName() + "." + method.getName() + " is not faked");
                    };
                });
        return type.cast(proxy);
    }

    static World world(String name) {
        List<Player> players = new ArrayList<>();
        Map<String, Answer> answers = new HashMap<>();
        answers.put("getName", args -> name);
        answers.put("getPlayers", args -> players);
        long[] time = {1000};
        answers.put("getTime", args -> time[0]);
        answers.put("setTime", args -> {
            time[0] = (Long) args[0];
            return null;
        });
        World world = fake(World.class, answers);
        answers.put("getBlockAt/1", args -> block((Location) args[0], Material.STONE));
        return world;
    }

    static Block block(Location location, Material type) {
        Map<String, Answer> answers = new HashMap<>();
        answers.put("getType", args -> type);
        answers.put("getLocation/0", args -> location.clone());
        answers.put("getWorld", args -> location.getWorld());
        return fake(Block.class, answers);
    }

    /** Mutable state behind a fake player. */
    static final class PlayerState {
        final String name;
        final UUID uuid = UUID.randomUUID();
        final List<Component> messages = new ArrayList<>();
        final Set<String> permissions = new HashSet<>();
        Location location;
        double health = 20;
        int food = 20;
        int level;
        GameMode gameMode = GameMode.SURVIVAL;
        Component displayName;
        Component kickReason;
        Location teleportedTo;
        boolean teleportedAsync;
        Player killer;

        PlayerState(String name, Location location) {
            this.name = name;
            this.location = location;
            this.displayName = Component.text(name);
        }
    }

    static Player player(PlayerState state) {
        Map<String, Answer> answers = new HashMap<>();
        answers.put("getName", args -> state.name);
        answers.put("getUniqueId", args -> state.uuid);
        answers.put("sendMessage/1", args -> {
            state.messages.add((Component) args[0]);
            return null;
        });
        answers.put("hasPermission", args -> args[0] instanceof String permission && state.permissions.contains(permission));
        answers.put("isOp", args -> false);
        answers.put("getLocation/0", args -> state.location.clone());
        answers.put("getWorld", args -> state.location.getWorld());
        answers.put("isValid", args -> true);
        answers.put("teleport/1", args -> {
            state.teleportedTo = (Location) args[0];
            state.location = state.teleportedTo.clone();
            return true;
        });
        answers.put("teleportAsync/1", args -> {
            state.teleportedTo = (Location) args[0];
            state.teleportedAsync = true;
            return CompletableFuture.completedFuture(true);
        });
        answers.put("getHealth", args -> state.health);
        answers.put("getFoodLevel", args -> state.food);
        answers.put("setFoodLevel", args -> {
            state.food = (Integer) args[0];
            return null;
        });
        answers.put("getLevel", args -> state.level);
        answers.put("setLevel", args -> {
            state.level = (Integer) args[0];
            return null;
        });
        answers.put("getGameMode", args -> state.gameMode);
        answers.put("setGameMode", args -> {
            state.gameMode = (GameMode) args[0];
            return null;
        });
        answers.put("displayName/0", args -> state.displayName);
        answers.put("displayName/1", args -> {
            state.displayName = (Component) args[0];
            return null;
        });
        answers.put("kick/1", args -> {
            state.kickReason = (Component) args[0];
            return null;
        });
        answers.put("getKiller", args -> state.killer);
        return fake(Player.class, answers);
    }

    /** Threading that runs everything inline and counts forwarded writes. */
    static final class InlineThreading implements Threading {
        private final boolean folia;
        int entityWrites;
        int globalWrites;
        final List<Entity> entities = new ArrayList<>();

        InlineThreading(boolean folia) {
            this.folia = folia;
        }

        @Override
        public boolean folia() {
            return folia;
        }

        @Override
        public void forEntity(Entity entity, Runnable action) {
            entityWrites++;
            entities.add(entity);
            action.run();
        }

        @Override
        public void global(Runnable action) {
            globalWrites++;
            action.run();
        }
    }
}
