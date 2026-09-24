package dev.tachyonscript.tests;

import dev.tachyonscript.api.addon.TachyonAddon;
import dev.tachyonscript.api.declaration.EventDeclaration;
import dev.tachyonscript.api.declaration.FunctionDeclaration;
import dev.tachyonscript.api.declaration.PropertyDeclaration;
import dev.tachyonscript.api.natives.NativeFunction;
import dev.tachyonscript.api.registry.Bindings;
import dev.tachyonscript.api.registry.SymbolRegistry;
import dev.tachyonscript.api.type.ClassType;
import dev.tachyonscript.api.type.Types;
import dev.tachyonscript.compiler.InternalErrorHandler;
import dev.tachyonscript.engine.EngineOptions;
import dev.tachyonscript.engine.LoadReport;
import dev.tachyonscript.engine.ScriptEngine;
import dev.tachyonscript.engine.addon.AddonAssembly;
import dev.tachyonscript.engine.spi.EngineLogger;
import dev.tachyonscript.engine.spi.EventBridge;
import dev.tachyonscript.engine.spi.Platform;
import dev.tachyonscript.language.diagnostic.DiagnosticRenderer;
import dev.tachyonscript.runtime.spi.TextService;
import dev.tachyonscript.stdlib.EventApi;
import dev.tachyonscript.stdlib.MathApi;
import dev.tachyonscript.stdlib.MinecraftTypes;
import dev.tachyonscript.stdlib.StandardLibrary;
import dev.tachyonscript.stdlib.StringApi;
import dev.tachyonscript.testkit.Fakes;
import dev.tachyonscript.testkit.InMemoryScripts;
import dev.tachyonscript.testkit.TestPlatform;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AddonTest {

    /** A complete addon: a member property, a global function, a type and an event. */
    static final class CoinsAddon implements TachyonAddon {
        static final PropertyDeclaration COINS = PropertyDeclaration.member(MinecraftTypes.PLAYER, "coins", Types.INT)
                .mutable().doc("Coins of the player.").build();
        static final FunctionDeclaration RICHEST = FunctionDeclaration.global("coins.richest")
                .returns(Types.nullable(MinecraftTypes.PLAYER)).doc("The player with the most coins.").build();
        static final ClassType PAYDAY = ClassType.builder("PaydayEvent").doc("Payday.").build();
        static final EventDeclaration PAYDAY_EVENT = EventDeclaration.builder("coins.payday", PAYDAY)
                .variable("amount", Types.INT, "Coins everyone receives.").doc("Payday.").build();

        /** The event object of coins.payday on the test platform. */
        record Payday(int amount) {
        }

        final Map<Fakes.Player, Integer> coins = new HashMap<>();

        @Override
        public String name() {
            return "Coins";
        }

        @Override
        public void declare(SymbolRegistry.Builder registry) {
            registry.type(PAYDAY).property(COINS).function(RICHEST).event(PAYDAY_EVENT);
        }

        @Override
        public void bind(Bindings.Builder bindings) {
            bindings.bindType(PAYDAY, Payday.class);
            bindings.bindGetter(COINS, (NativeFunction.OfInt) a -> coins.getOrDefault((Fakes.Player) a.getRef(0), 0));
            bindings.bindSetter(COINS, a -> coins.put((Fakes.Player) a.getRef(0), a.getInt(1)));
            bindings.bind(RICHEST, (NativeFunction.OfRef) a -> coins.entrySet().stream()
                    .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null));
            bindings.bind(PAYDAY_EVENT.variable("amount").orElseThrow(),
                    (NativeFunction.OfInt) a -> ((Payday) a.getRef(0)).amount());
        }

        @Override
        public Map<EventDeclaration, Class<?>> eventClasses() {
            return Map.of(PAYDAY_EVENT, Payday.class);
        }
    }

    /** A test addon assembled from lambdas. */
    private record Custom(String name, Consumer<SymbolRegistry.Builder> declarations, Consumer<Bindings.Builder> implementations,
                          Map<EventDeclaration, Class<?>> events) implements TachyonAddon {
        Custom(String name, Consumer<SymbolRegistry.Builder> declarations, Consumer<Bindings.Builder> implementations) {
            this(name, declarations, implementations, Map.of());
        }

        @Override
        public void declare(SymbolRegistry.Builder registry) {
            declarations.accept(registry);
        }

        @Override
        public void bind(Bindings.Builder bindings) {
            implementations.accept(bindings);
        }

        @Override
        public Map<EventDeclaration, Class<?>> eventClasses() {
            return events;
        }
    }

    private final TestPlatform base = new TestPlatform();

    private AddonAssembly.Result assemble(TachyonAddon... addons) {
        return AddonAssembly.assemble(StandardLibrary::register, base.bindings(), List.of(addons), type -> true);
    }

    private ScriptEngine engine(AddonAssembly.Result result) {
        Platform platform = new Platform() {
            @Override
            public Bindings bindings() {
                return result.bindings();
            }

            @Override
            public TextService text() {
                return base.text();
            }

            @Override
            public EventBridge events() {
                return base.events();
            }

            @Override
            public EngineLogger logger() {
                return base.logger();
            }
        };
        return new ScriptEngine(result.registry(), platform, EngineOptions.DEFAULT, InternalErrorHandler.IGNORE);
    }

    private static void assertLoads(LoadReport report) {
        StringBuilder problems = new StringBuilder();
        report.diagnostics().forEach(d -> problems.append(DiagnosticRenderer.plain().render(d)));
        assertTrue(report.activated() && report.failed().isEmpty(), () -> problems + " " + report.linkProblems());
    }

    @Test
    void scriptsUseAddonDeclarations() {
        CoinsAddon addon = new CoinsAddon();
        AddonAssembly.Result result = assemble(addon);
        assertEquals(List.of("Coins"), result.loaded());
        assertEquals(List.of(), result.rejected());
        assertEquals(Map.of(CoinsAddon.PAYDAY_EVENT, CoinsAddon.Payday.class), result.eventClasses());

        ScriptEngine engine = engine(result);
        assertLoads(engine.load(new InMemoryScripts().put("coins.tys", """
                event player.join {
                    player.coins += 10
                    let richest = coins.richest()
                    if richest != null {
                        player.send("{richest.name} is the richest with {richest.coins} coins")
                    }
                }

                event coins.payday {
                    for p in server.players {
                        p.coins += amount
                    }
                }
                """)));
        Fakes.Player steve = base.join("Steve");
        addon.coins.put(steve, 5);
        base.fire(engine, EventApi.PLAYER_JOIN, new Fakes.JoinEvent(steve));
        assertEquals(15, addon.coins.get(steve));
        assertEquals(List.of("Steve is the richest with 15 coins"), steve.messages());

        base.fire(engine, CoinsAddon.PAYDAY_EVENT, new CoinsAddon.Payday(100));
        assertEquals(115, addon.coins.get(steve));
    }

    @Test
    void rejectsConflictingDeclarationsWithoutAffectingOthers() {
        Custom conflicting = new Custom("Conflicting",
                registry -> registry.function(FunctionDeclaration.global("helper.ok").returns(Types.INT).build())
                        .function(MathApi.SQRT),
                bindings -> {
                });
        AddonAssembly.Result result = assemble(conflicting, new CoinsAddon());
        assertEquals(List.of("Coins"), result.loaded());
        assertEquals(1, result.rejected().size());
        assertEquals("Conflicting", result.rejected().getFirst().addon());
        assertTrue(result.rejected().getFirst().reason().contains("math.sqrt"), result.rejected().getFirst().reason());
        assertTrue(result.registry().functions("helper.ok").isEmpty(), "nothing of a rejected addon remains");
        assertLoads(engine(result).load(new InMemoryScripts().put("ok.tys",
                "event player.join {\n    player.send(\"{math.sqrt(16.0)} {player.coins}\")\n}")));
    }

    @Test
    void rejectsMissingImplementations() {
        FunctionDeclaration unbound = FunctionDeclaration.global("lazy.work").returns(Types.INT).build();
        ClassType unboundType = ClassType.builder("LazyThing").build();
        AddonAssembly.Result result = assemble(new Custom("Lazy",
                registry -> registry.type(unboundType).function(unbound), bindings -> {
                }));
        assertEquals(List.of(), result.loaded());
        String reason = result.rejected().getFirst().reason();
        assertTrue(reason.contains("lazy.work") && reason.contains("type LazyThing"), reason);
    }

    @Test
    void rejectsAddonsThatThrowOrRebindBuiltIns() {
        FunctionDeclaration fine = FunctionDeclaration.global("fine.value").returns(Types.INT).build();
        AddonAssembly.Result result = assemble(
                new Custom("Broken", registry -> {
                    throw new IllegalStateException("boom");
                }, bindings -> {
                }),
                new Custom("Hijacker", registry -> {
                }, bindings -> bindings.bind(StringApi.UPPER, (NativeFunction.OfRef) a -> "hijacked")),
                new Custom("Fine", registry -> registry.function(fine),
                        bindings -> bindings.bind(fine, (NativeFunction.OfInt) a -> 42)),
                new Custom("Fine", registry -> {
                }, bindings -> {
                }));
        assertEquals(List.of("Fine"), result.loaded());
        assertEquals(List.of("Broken", "Hijacker", "Fine"),
                result.rejected().stream().map(AddonAssembly.Rejected::addon).toList());
        assertEquals("boom", result.rejected().get(0).reason());
        assertTrue(result.rejected().get(1).reason().contains("does not declare"), result.rejected().get(1).reason());
        assertTrue(result.rejected().get(2).reason().contains("already registered"), result.rejected().get(2).reason());
    }

    @Test
    void requiresEventClassesForNewEvents() {
        ClassType type = ClassType.builder("SilentEvent").build();
        EventDeclaration silent = EventDeclaration.builder("silent.event", type).doc("Never fires.").build();
        Consumer<SymbolRegistry.Builder> declarations = registry -> registry.type(type).event(silent);
        Consumer<Bindings.Builder> implementations = bindings -> bindings.bindType(type, Object.class);

        AddonAssembly.Result missing = assemble(new Custom("Silent", declarations, implementations));
        assertTrue(missing.rejected().getFirst().reason().contains("no platform event class"),
                missing.rejected().getFirst().reason());

        AddonAssembly.Result invalid = AddonAssembly.assemble(StandardLibrary::register, base.bindings(),
                List.of(new Custom("Silent", declarations, implementations, Map.of(silent, String.class))),
                type2 -> type2 != String.class);
        assertTrue(invalid.rejected().getFirst().reason().contains("java.lang.String"),
                invalid.rejected().getFirst().reason());

        AddonAssembly.Result foreign = assemble(new Custom("Foreign", registry -> {
        }, bindings -> {
        }, Map.of(EventApi.PLAYER_JOIN, Object.class)));
        assertTrue(foreign.rejected().getFirst().reason().contains("does not declare"),
                foreign.rejected().getFirst().reason());
    }
}
