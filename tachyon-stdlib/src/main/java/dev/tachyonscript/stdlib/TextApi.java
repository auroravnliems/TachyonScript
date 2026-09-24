package dev.tachyonscript.stdlib;

import dev.tachyonscript.api.declaration.Effect;
import dev.tachyonscript.api.declaration.FunctionDeclaration;
import dev.tachyonscript.api.type.Types;

import java.util.List;

/** The {@code text} namespace: explicit MiniMessage and plain-text conversions. */
public final class TextApi {

    public static final FunctionDeclaration MINI = FunctionDeclaration.global("text.mini")
            .parameter("miniMessage", Types.STRING).returns(Types.COMPONENT).effects(Effect.PURE)
            .doc("Parses MiniMessage text into a component (tags are formatted).").build();

    public static final FunctionDeclaration PLAIN = FunctionDeclaration.global("text.plain")
            .parameter("component", Types.COMPONENT).returns(Types.STRING).effects(Effect.PURE)
            .doc("The plain text of a component, without formatting.").build();

    public static final FunctionDeclaration ESCAPE = FunctionDeclaration.global("text.escape")
            .parameter("text", Types.STRING).returns(Types.STRING).effects(Effect.PURE)
            .doc("Escapes MiniMessage tags so that untrusted text is shown literally.").build();

    public static final List<FunctionDeclaration> FUNCTIONS = List.of(MINI, PLAIN, ESCAPE);

    private TextApi() {
    }
}
