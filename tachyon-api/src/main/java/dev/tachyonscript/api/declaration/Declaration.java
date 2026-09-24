package dev.tachyonscript.api.declaration;

import dev.tachyonscript.api.doc.Deprecation;
import dev.tachyonscript.api.doc.Documentation;

import java.util.Optional;

/** Common metadata of every declaration visible to scripts. */
public sealed interface Declaration permits FunctionDeclaration, PropertyDeclaration, EventDeclaration {

    /** Name as used in source: simple for members, qualified for globals and events. */
    String name();

    Documentation documentation();

    Optional<Deprecation> deprecation();
}
