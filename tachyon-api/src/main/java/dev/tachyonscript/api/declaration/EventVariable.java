package dev.tachyonscript.api.declaration;

import dev.tachyonscript.api.doc.Documentation;
import dev.tachyonscript.api.type.Type;

import java.util.Objects;

/**
 * A read-only variable available inside an event handler, such as {@code player} in
 * {@code event player.join}. Its value is produced by the {@link #getter()} operation
 * from the platform event object.
 *
 * @param name          variable name
 * @param type          variable type (nullable when the event may not provide it)
 * @param getter        bindable operation taking the event object as its only parameter
 * @param documentation documentation shown in tooling
 */
public record EventVariable(String name, Type type, NativeDeclaration getter, Documentation documentation) {

    public EventVariable {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(getter, "getter");
        Objects.requireNonNull(documentation, "documentation");
    }
}
