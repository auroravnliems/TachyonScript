package dev.tachyonscript.language.semantic;

import dev.tachyonscript.api.type.ClassType;
import dev.tachyonscript.api.type.Type;
import dev.tachyonscript.language.syntax.Declaration;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A {@code record} declared in a script: an immutable data type with named fields and optional
 * methods. Its {@link #type()} is a script-defined class type; values are compared by content.
 */
public final class RecordSymbol {

    /** A field of the record. */
    public record Field(String name, Type type, int index, Declaration.RecordField syntax) {
    }

    private final String module;
    private final ClassType type;
    private final Declaration.Record syntax;
    private final List<Field> fields = new ArrayList<>();
    private final List<FunctionSymbol> methods = new ArrayList<>();

    RecordSymbol(String module, ClassType type, Declaration.Record syntax) {
        this.module = module;
        this.type = type;
        this.syntax = syntax;
    }

    public String module() {
        return module;
    }

    public String name() {
        return type.name();
    }

    public ClassType type() {
        return type;
    }

    public Declaration.Record syntax() {
        return syntax;
    }

    public List<Field> fields() {
        return List.copyOf(fields);
    }

    public Optional<Field> field(String name) {
        for (Field field : fields) {
            if (field.name().equals(name)) {
                return Optional.of(field);
            }
        }
        return Optional.empty();
    }

    public List<FunctionSymbol> methods() {
        return List.copyOf(methods);
    }

    List<FunctionSymbol> methods(String name) {
        return methods.stream().filter(method -> method.name().equals(name)).toList();
    }

    void addField(Field field) {
        fields.add(field);
    }

    void addMethod(FunctionSymbol method) {
        methods.add(method);
    }

    @Override
    public String toString() {
        return "record " + type.name();
    }
}
