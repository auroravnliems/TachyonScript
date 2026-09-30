package dev.tachyonscript.language.semantic;

import dev.tachyonscript.api.registry.SymbolRegistry;
import dev.tachyonscript.api.type.ClassType;
import dev.tachyonscript.api.type.PrimitiveType;
import dev.tachyonscript.api.type.Type;
import dev.tachyonscript.api.type.Types;
import dev.tachyonscript.language.diagnostic.Diagnostic;
import dev.tachyonscript.language.diagnostic.DiagnosticCode;
import dev.tachyonscript.language.syntax.TypeRef;
import dev.tachyonscript.language.util.Suggestions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Resolves written types ({@link TypeRef}) to {@link Type}s. */
final class TypeResolver {

    private static final Map<String, PrimitiveType> PRIMITIVES = Map.of(
            "int", PrimitiveType.INT,
            "long", PrimitiveType.LONG,
            "float", PrimitiveType.FLOAT,
            "double", PrimitiveType.DOUBLE,
            "bool", PrimitiveType.BOOL,
            "void", PrimitiveType.VOID,
            "Duration", PrimitiveType.DURATION,
            "Instant", PrimitiveType.INSTANT);

    /** Names from other languages, mapped to the TachyonScript spelling. */
    private static final Map<String, String> FOREIGN_NAMES = Map.ofEntries(
            Map.entry("String", "string"),
            Map.entry("Integer", "int"),
            Map.entry("Int", "int"),
            Map.entry("Long", "long"),
            Map.entry("Double", "double"),
            Map.entry("Float", "float"),
            Map.entry("boolean", "bool"),
            Map.entry("Boolean", "bool"),
            Map.entry("Void", "void"),
            Map.entry("Object", "any"),
            Map.entry("Any", "any"),
            Map.entry("ArrayList", "List"),
            Map.entry("HashMap", "Map"),
            Map.entry("Exception", "Error"),
            Map.entry("Throwable", "Error"),
            Map.entry("ItemType", "Material"),
            Map.entry("Item", "ItemStack"),
            Map.entry("Function", "function(...): ..."),
            Map.entry("Runnable", "function()"),
            Map.entry("Date", "Instant"),
            Map.entry("Time", "Instant"));

    private final ModuleContext context;

    TypeResolver(ModuleContext context) {
        this.context = context;
    }

    /** Resolves {@code ref}; reports problems and returns the error type on failure. */
    Type resolve(TypeRef ref, boolean allowVoid) {
        Type type = switch (ref) {
            case TypeRef.Nullable nullable -> {
                Type inner = resolve(nullable.inner(), false);
                yield inner.isError() ? inner : Types.nullable(inner);
            }
            case TypeRef.Named named -> resolveNamed(named);
            case TypeRef.Function function -> {
                List<Type> parameters = new ArrayList<>();
                boolean failed = false;
                for (TypeRef parameter : function.parameters()) {
                    Type resolved = resolve(parameter, false);
                    failed |= resolved.isError();
                    parameters.add(resolved);
                }
                Type returnType = function.returnType() == null ? PrimitiveType.VOID : resolve(function.returnType(), true);
                yield failed || returnType.isError() ? Types.ERROR : Types.function(parameters, returnType);
            }
        };
        if (type == PrimitiveType.VOID && !allowVoid) {
            context.error(DiagnosticCode.TYPE_MISMATCH, ref.span(), "'void' can only be used as a return type.");
            return Types.ERROR;
        }
        return type;
    }

    private Type resolveNamed(TypeRef.Named named) {
        String name = named.name().text();
        List<TypeRef> arguments = named.arguments();
        if (name.equals("List") || name.equals("Map")) {
            int expected = name.equals("List") ? 1 : 2;
            if (arguments.size() != expected) {
                context.report(Diagnostic.builder(DiagnosticCode.INVALID_TYPE_ARGUMENTS, context.file(), named.span(),
                                "'" + name + "' needs " + expected + " type argument" + (expected == 1 ? "" : "s") + ".")
                        .note(name.equals("List") ? "Example: List<string>" : "Example: Map<string, int>").build());
                return Types.ERROR;
            }
            List<Type> resolved = new ArrayList<>();
            for (TypeRef argument : arguments) {
                resolved.add(resolve(argument, false));
            }
            if (resolved.stream().anyMatch(Type::isError)) {
                return Types.ERROR;
            }
            if (name.equals("List")) {
                return Types.list(resolved.getFirst());
            }
            if (resolved.getFirst().isNullable()) {
                context.error(DiagnosticCode.INVALID_TYPE_ARGUMENTS, arguments.getFirst().span(), "Map keys cannot be nullable.");
                return Types.ERROR;
            }
            return Types.map(resolved.get(0), resolved.get(1));
        }
        Type type = PRIMITIVES.get(name);
        if (type == null) {
            type = recordType(name);
        }
        if (type == null) {
            type = context.registry().type(name).orElse(null);
        }
        if (type == null) {
            reportUnknown(named, name);
            return Types.ERROR;
        }
        if (!arguments.isEmpty()) {
            context.error(DiagnosticCode.INVALID_TYPE_ARGUMENTS, named.span(), "Type '" + name + "' has no type parameters.");
            return Types.ERROR;
        }
        return type;
    }

    /** A record of this module, an imported record ({@code Warp}) or a record of an imported module ({@code shop.Warp}). */
    private ClassType recordType(String name) {
        RecordSymbol own = context.records().get(name);
        if (own != null) {
            return own.type();
        }
        if (context.importedNames().get(name) instanceof RecordSymbol imported) {
            return imported.type();
        }
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            BoundModule module = context.moduleAliases().get(name.substring(0, dot));
            if (module != null && module.member(name.substring(dot + 1)) instanceof RecordSymbol record) {
                return record.type();
            }
        }
        return null;
    }

    private void reportUnknown(TypeRef.Named named, String name) {
        Diagnostic.Builder builder = Diagnostic.builder(DiagnosticCode.UNKNOWN_TYPE, context.file(), named.span(),
                "Unknown type '" + name + "'.");
        String foreign = FOREIGN_NAMES.get(name);
        if (foreign != null) {
            builder.suggestions(List.of(foreign));
        } else {
            builder.suggestions(Suggestions.closest(name, allTypeNames(), 3));
        }
        context.report(builder.build());
    }

    private List<String> allTypeNames() {
        List<String> names = new ArrayList<>(PRIMITIVES.keySet());
        names.add("List");
        names.add("Map");
        for (ClassType type : context.registry().types()) {
            names.add(type.name());
        }
        names.addAll(context.records().keySet());
        return names;
    }

    /** Whether {@code name} denotes a type (used to explain "type used as a value"). */
    boolean isTypeName(String name) {
        return PRIMITIVES.containsKey(name) || name.equals("List") || name.equals("Map")
                || context.registry().type(name).isPresent() || SymbolRegistry.isReservedTypeName(name)
                || recordType(name) != null;
    }
}
