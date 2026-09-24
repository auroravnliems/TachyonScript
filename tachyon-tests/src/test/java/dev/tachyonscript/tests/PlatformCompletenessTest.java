package dev.tachyonscript.tests;

import dev.tachyonscript.api.declaration.NativeDeclaration;
import dev.tachyonscript.stdlib.StandardLibrary;
import dev.tachyonscript.testkit.TestPlatform;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlatformCompletenessTest {

    @Test
    void testPlatformBindsEveryStandardDeclaration() {
        TestPlatform platform = new TestPlatform();
        List<String> missing = StandardLibrary.registry().natives().stream()
                .filter(declaration -> platform.bindings().lookup(declaration).isEmpty())
                .map(NativeDeclaration::key)
                .toList();
        assertEquals(List.of(), missing);
    }
}
