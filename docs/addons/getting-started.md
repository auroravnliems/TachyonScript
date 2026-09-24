# Writing an addon

An addon is a Paper plugin that teaches TachyonScript new types, functions,
properties and events. Scripts use them exactly like the standard library: the
compiler checks their types, suggests them in "did you mean" messages, and calls
them without reflection.

> Status: the addon API is new and may still change before 1.0. TachyonScript's
> modules are not published to a Maven repository yet; compile against the plugin
> jar (see below).

## 1. Set up the project

```kotlin
// build.gradle.kts of your plugin
dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly(files("libs/TachyonScript-0.1.0-SNAPSHOT.jar"))
}
```

```yaml
# plugin.yml of your plugin
depend: [TachyonScript]
```

`depend` makes TachyonScript enable first, so its services exist when your plugin
enables. TachyonScript loads scripts on the first tick after the server has
started, which gives every plugin the chance to register addons in `onEnable`.

## 2. Declare and implement

Everything an addon adds is two things: a **declaration** (name, types,
documentation — what the compiler sees) and a **binding** (the Java code that runs).
See [ADR 0003](../decisions/0003-declarations-and-bindings.md) for why.

This addon adds a `coins` property to players, a `pay` method, and a
`player.sneak` event backed by Bukkit's `PlayerToggleSneakEvent`:

```java
package com.example.coins;

import dev.tachyonscript.api.addon.TachyonAddon;
import dev.tachyonscript.api.declaration.EventDeclaration;
import dev.tachyonscript.api.declaration.FunctionDeclaration;
import dev.tachyonscript.api.declaration.PropertyDeclaration;
import dev.tachyonscript.api.declaration.ThreadingRequirement;
import dev.tachyonscript.api.natives.NativeFunction;
import dev.tachyonscript.api.natives.ScriptError;
import dev.tachyonscript.api.registry.Bindings;
import dev.tachyonscript.api.registry.SymbolRegistry;
import dev.tachyonscript.api.type.ClassType;
import dev.tachyonscript.api.type.Types;
import dev.tachyonscript.stdlib.MinecraftTypes;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerToggleSneakEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class CoinsAddon implements TachyonAddon {

    // Declarations are constants: declare() may be called more than once.
    static final PropertyDeclaration COINS = PropertyDeclaration
            .member(MinecraftTypes.PLAYER, "coins", Types.INT)
            .mutable()
            .doc("Coins of the player.")
            .build();

    static final FunctionDeclaration PAY = FunctionDeclaration.method(MinecraftTypes.PLAYER, "pay")
            .parameter("target", MinecraftTypes.PLAYER)
            .parameter("amount", Types.INT)
            .returns(Types.BOOL)
            .doc("Gives coins to another player; false if the player has too few.")
            .build();

    static final ClassType SNEAK_EVENT = ClassType.builder("PlayerSneakEvent")
            .supertypes(MinecraftTypes.CANCELLABLE)
            .doc("A player started or stopped sneaking.")
            .build();

    static final EventDeclaration PLAYER_SNEAK = EventDeclaration.builder("player.sneak", SNEAK_EVENT)
            .variable("player", MinecraftTypes.PLAYER, "The player.")
            .variable("sneaking", Types.BOOL, "Whether the player is now sneaking.")
            .cancellable()
            .threading(ThreadingRequirement.ENTITY)
            .doc("A player started or stopped sneaking.")
            .build();

    // Handlers run on many threads on Folia: use thread-safe state.
    private final Map<UUID, Integer> coins = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return "Coins";
    }

    @Override
    public void declare(SymbolRegistry.Builder registry) {
        registry.property(COINS).function(PAY).type(SNEAK_EVENT).event(PLAYER_SNEAK);
    }

    @Override
    public void bind(Bindings.Builder bindings) {
        bindings.bindGetter(COINS, (NativeFunction.OfInt) a -> coins.getOrDefault(uuid(a.getRef(0)), 0));
        bindings.bindSetter(COINS, a -> coins.put(uuid(a.getRef(0)), Math.max(0, a.getInt(1))));
        bindings.bind(PAY, (NativeFunction.OfBool) a -> {
            int amount = a.getInt(2);
            if (amount < 0) {
                throw new ScriptError("pay: the amount must not be negative, got " + amount);
            }
            UUID from = uuid(a.getRef(0));
            UUID to = uuid(a.getRef(1));
            synchronized (coins) {
                int balance = coins.getOrDefault(from, 0);
                if (balance < amount) {
                    return false;
                }
                coins.put(from, balance - amount);
                coins.merge(to, amount, Integer::sum);
                return true;
            }
        });
        bindings.bindType(SNEAK_EVENT, PlayerToggleSneakEvent.class);
        bindings.bind(PLAYER_SNEAK.variable("player").orElseThrow(),
                (NativeFunction.OfRef) a -> ((PlayerToggleSneakEvent) a.getRef(0)).getPlayer());
        bindings.bind(PLAYER_SNEAK.variable("sneaking").orElseThrow(),
                (NativeFunction.OfBool) a -> ((PlayerToggleSneakEvent) a.getRef(0)).isSneaking());
    }

    @Override
    public Map<EventDeclaration, Class<?>> eventClasses() {
        return Map.of(PLAYER_SNEAK, PlayerToggleSneakEvent.class);
    }

    private static UUID uuid(Object player) {
        return ((Player) player).getUniqueId();
    }
}
```

The rules the binding code follows:

* **Shapes.** A binding's `NativeFunction` shape matches the declared result:
  `OfInt` for `int`, `OfDouble` for `double`, `OfBool` for `bool`, `OfRef` for
  reference and nullable types, `OfVoid` for none. A wrong shape is rejected when
  the addon is registered.
* **Arguments.** `a.getRef(0)` is the receiver of a method or property; the
  parameters follow. The compiler guarantees the types, so casts need no checks.
  Read primitives with the matching accessor (`getInt`, `getDouble`, ...). Do not
  keep the `Arguments` object after the call returns.
* **Errors.** Throw `ScriptError` with a message for problems the script author
  should see; it becomes a runtime error pointing at the script line. Any other
  exception is reported as a failure of your native, with its key.
* **Threads.** A binding runs on the thread of the handler that called it: on
  Folia, the region thread that owns the event's player or block. Changing an
  entity or block that the current thread may not own must be scheduled on its
  owner (`entity.getScheduler().run(...)`, `Bukkit.getRegionScheduler()`), as in
  any Folia plugin.
* **Speed.** Bindings are called directly, often thousands of times per second.
  Do the lookups in `bind` (or earlier), not in the lambda.

## 3. Register the addon

```java
public final class CoinsPlugin extends JavaPlugin {
    @Override
    public void onEnable() {
        AddonRegistrar registrar = getServer().getServicesManager().load(AddonRegistrar.class);
        registrar.register(new CoinsAddon());
    }
}
```

After the server starts, the console lists the addon:

```text
[TachyonScript] TachyonScript 0.1.0-SNAPSHOT (...) on Paper 1.21.11; addons: Coins
```

Scripts can now use it:

```tys-addon
event player.sneak {
    if sneaking {
        player.coins += 1
        player.send("<gold>+1 coin ({player.coins} total)")
    }
}
```

## What is checked

When scripts are first loaded, each addon is checked on its own, in registration
order. An addon is **rejected as a whole** — none of its declarations become
visible, and the console says why — when:

* another addon already uses its name;
* `declare` or `bind` throws;
* a declaration conflicts with the standard library or an earlier addon (same
  type name, same function signature, same property or event name);
* something it declares has no binding (every function, getter, setter, event
  variable, and the Java class of every type);
* it binds something it did not declare (for example, replacing a standard
  function);
* an event it declares has no event class, or the class is not a Bukkit event.

Registering after scripts have been loaded throws an `IllegalStateException`.
Addons cannot be added or removed while the server runs; `/tys reload` reloads
scripts, not addons.
