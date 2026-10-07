package dev.tachyonscript.runtime.interpreter;

import dev.tachyonscript.api.natives.NativeFunction;
import dev.tachyonscript.api.registry.Bindings;
import dev.tachyonscript.compiler.CompilationResult;
import dev.tachyonscript.compiler.Compiler;
import dev.tachyonscript.ir.FunctionRef;
import dev.tachyonscript.ir.GlobalRef;
import dev.tachyonscript.ir.RecordRef;
import dev.tachyonscript.language.source.SourceFile;
import dev.tachyonscript.runtime.code.Assembler;
import dev.tachyonscript.runtime.error.ScriptRuntimeException;
import dev.tachyonscript.runtime.link.LinkEnvironment;
import dev.tachyonscript.runtime.link.LinkedModule;
import dev.tachyonscript.runtime.link.Linker;
import dev.tachyonscript.runtime.link.StandaloneEnvironment;
import dev.tachyonscript.runtime.spi.SimpleTextService;
import dev.tachyonscript.runtime.value.GlobalCell;
import dev.tachyonscript.runtime.value.PlayerDataSlot;
import dev.tachyonscript.runtime.value.RecordType;
import dev.tachyonscript.stdlib.ServerApi;
import dev.tachyonscript.stdlib.StandardLibrary;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The interpreter checks revocation at function entry, before every native call and with the
 * watchdog's loop checks, not on every instruction. These tests pin down what that guarantees.
 */
class RevocationPointsTest {

    /** A guard that a native can trip while the script is running. */
    private static final class Switch implements ExecutionGuard {
        volatile boolean revoked;

        @Override
        public boolean securityRevoked() {
            return revoked;
        }
    }

    private final List<String> log = new ArrayList<>();
    private final Switch guard = new Switch();

    @AfterEach
    void reset() {
        ExecutionStack.configure(RuntimeLimits.DEFAULT);
    }

    private LinkedModule load(String source) throws Exception {
        CompilationResult result = new Compiler(StandardLibrary.registry())
                .compile(List.of(new SourceFile("guarded.tys", source)));
        assertTrue(result.succeeded(), result.diagnostics().diagnostics()::toString);
        Bindings bindings = Bindings.builder()
                .include(StandardLibrary.coreBindings())
                .bind(ServerApi.LOG_STRING, (NativeFunction.OfVoid) arguments -> {
                    String message = arguments.getString(0);
                    synchronized (log) {
                        log.add(message);
                    }
                    if (message.equals("revoke")) {
                        guard.revoked = true;
                    }
                })
                .build();
        StandaloneEnvironment memory = new StandaloneEnvironment();
        LinkEnvironment owned = new LinkEnvironment() {
            @Override public Object owner() { return guard; }
            @Override public GlobalCell global(GlobalRef ref) { return memory.global(ref); }
            @Override public PlayerDataSlot playerData(GlobalRef ref) { return memory.playerData(ref); }
            @Override public RecordType record(RecordRef ref) { return memory.record(ref); }
            @Override public CompiledFunction function(FunctionRef ref) { return memory.function(ref); }
        };
        return Linker.link(Assembler.assemble(result.modules().getFirst().ir()), bindings,
                SimpleTextService.INSTANCE, owned);
    }

    @Test
    void noNativeRunsAfterRevocationEvenInStraightLineCode() throws Exception {
        LinkedModule module = load("""
                function run() {
                    log("before")
                    log("revoke")
                    log("after")
                }
                """);
        ScriptRuntimeException error = assertThrows(ScriptRuntimeException.class,
                () -> Interpreter.call(module.function("run()").orElseThrow()));
        assertEquals(ScriptRuntimeException.Kind.SECURITY_REVOKED, error.kind());
        assertEquals(List.of("before", "revoke"), log);
    }

    @Test
    void revokedFunctionsCannotStartAndNestedCallsStopAtTheirNextNative() throws Exception {
        LinkedModule module = load("""
                function helper(): int {
                    log("helper")
                    return 1
                }
                function caller(): int {
                    log("revoke")
                    return helper() + 1
                }
                """);
        ScriptRuntimeException error = assertThrows(ScriptRuntimeException.class,
                () -> Interpreter.call(module.function("caller()").orElseThrow()));
        assertEquals(ScriptRuntimeException.Kind.SECURITY_REVOKED, error.kind());
        assertTrue(error.render(false).contains("helper"), error.render(false));
        assertEquals(List.of("revoke"), log, "the nested native never ran");
        // A new execution checks at entry, even for a function that calls no native.
        guard.revoked = false;
        LinkedModule pure = load("""
                function value(): int {
                    return 7
                }
                """);
        assertEquals(7, Interpreter.call(pure.function("value()").orElseThrow()));
        guard.revoked = true;
        ScriptRuntimeException again = assertThrows(ScriptRuntimeException.class,
                () -> Interpreter.call(pure.function("value()").orElseThrow()));
        assertEquals(ScriptRuntimeException.Kind.SECURITY_REVOKED, again.kind());
        assertEquals(0, ExecutionStack.current().depth(), "the stack is reset after an error");
    }

    @Test
    void aRunningLoopStopsWithinOneWatchdogInterval() throws Exception {
        ExecutionStack.configure(new RuntimeLimits(128, 60_000_000_000L, 1024));
        LinkedModule module = load("""
                function spin(limit: int): int {
                    log("entered")
                    var n = 0
                    var total = 0
                    while n < limit {
                        n += 1
                        total += n % 7
                    }
                    return total
                }
                """);
        CompiledFunction spin = module.function("spin(int)").orElseThrow();
        CompletableFuture<ScriptRuntimeException.Kind> running = CompletableFuture.supplyAsync(() -> {
            try {
                Interpreter.call(spin, Integer.MAX_VALUE);
                return null;
            } catch (ScriptRuntimeException exception) {
                return exception.kind();
            }
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            synchronized (log) {
                if (log.contains("entered")) {
                    break;
                }
            }
            Thread.sleep(1);
        }
        assertFalse(running.isDone(), "the loop is still running");
        guard.revoked = true;
        assertEquals(ScriptRuntimeException.Kind.SECURITY_REVOKED, running.get(2, TimeUnit.SECONDS));
    }
}
