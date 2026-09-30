package dev.tachyonscript.tests;

import dev.tachyonscript.api.declaration.NativeDeclaration;
import dev.tachyonscript.api.registry.SymbolRegistry;
import dev.tachyonscript.api.type.ClassType;
import dev.tachyonscript.stdlib.MinecraftTypes;
import dev.tachyonscript.stdlib.StandardLibrary;
import dev.tachyonscript.stdlib.generated.GeneratedLibrary;
import dev.tachyonscript.testkit.TestPlatform;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlatformCompletenessTest {

    @Test
    void testPlatformBindsEveryStandardDeclaration() {
        TestPlatform platform = new TestPlatform();
        List<String> engine = StandardLibrary.engineDeclarations().stream().map(NativeDeclaration::key).toList();
        List<String> missing = StandardLibrary.registry().natives().stream()
                .filter(declaration -> !declaration.isIntrinsic() && !engine.contains(declaration.key()))
                .filter(declaration -> platform.bindings().lookup(declaration).isEmpty())
                .map(NativeDeclaration::key)
                .toList();
        assertEquals(List.of(), missing);
    }

    /** Only generated declarations may be stubs: the hand-written core of the library is faked for real. */
    @Test
    void testPlatformFakesTheHandWrittenLibrary() {
        SymbolRegistry.Builder builder = SymbolRegistry.builder();
        for (ClassType type : MinecraftTypes.ALL) {
            builder.type(type);
        }
        GeneratedLibrary.registerTypes(builder);
        GeneratedLibrary.registerMembers(builder);
        Set<String> generated = builder.build().natives().stream().map(NativeDeclaration::key).collect(Collectors.toSet());
        List<String> handWrittenStubs = new TestPlatform().stubbed().stream().filter(key -> !generated.contains(key)).toList();
        assertEquals(List.of(), handWrittenStubs);
        assertTrue(generated.size() > 500, "the generated library should declare hundreds of members");
    }
}
