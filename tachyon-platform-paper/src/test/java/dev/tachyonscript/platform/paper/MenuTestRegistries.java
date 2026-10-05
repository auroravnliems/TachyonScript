package dev.tachyonscript.platform.paper;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.Keyed;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.inventory.MenuType;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Paper's InventoryType initializes MenuType through ServiceLoader even without a server.
 * This test-only provider supplies menu identities. No gameplay/registry values are faked. */
@SuppressWarnings({"deprecation", "removal", "unchecked", "rawtypes"})
public final class MenuTestRegistries implements RegistryAccess {
    private final Map<String, Registry<?>> registries = new ConcurrentHashMap<>();

    public MenuTestRegistries() { }

    @Override
    public <T extends Keyed> Registry<T> getRegistry(Class<T> type) {
        return registry(type.getName(), type == MenuType.class);
    }

    @Override
    public <T extends Keyed> Registry<T> getRegistry(RegistryKey<T> key) {
        return registry(key.key().asString(), key == RegistryKey.MENU);
    }

    private <T extends Keyed> Registry<T> registry(String name, boolean menu) {
        return (Registry<T>) registries.computeIfAbsent(name, ignored -> BukkitFakes.fake(Registry.class,
                menu ? Map.of("getOrThrow", a -> {
                    net.kyori.adventure.key.Key key = (net.kyori.adventure.key.Key) a[0];
                    return BukkitFakes.fake(MenuType.Typed.class,
                            Map.of("getKey", unused -> new NamespacedKey(key.namespace(), key.value())));
                }) : Map.of()));
    }
}
