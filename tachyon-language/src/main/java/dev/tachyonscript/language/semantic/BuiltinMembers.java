package dev.tachyonscript.language.semantic;

import dev.tachyonscript.api.declaration.NativeDeclaration;
import dev.tachyonscript.api.intrinsic.Intrinsics;
import dev.tachyonscript.api.type.FunctionType;
import dev.tachyonscript.api.type.ListType;
import dev.tachyonscript.api.type.MapType;
import dev.tachyonscript.api.type.PrimitiveType;
import dev.tachyonscript.api.type.Representation;
import dev.tachyonscript.api.type.Type;
import dev.tachyonscript.api.type.Types;
import dev.tachyonscript.language.diagnostic.Diagnostic;
import dev.tachyonscript.language.diagnostic.DiagnosticCode;
import dev.tachyonscript.language.source.Span;
import dev.tachyonscript.language.syntax.AssignmentOperator;
import dev.tachyonscript.language.syntax.BinaryOperator;
import dev.tachyonscript.language.syntax.Expression;
import dev.tachyonscript.language.syntax.Identifier;
import dev.tachyonscript.language.syntax.Statement;
import dev.tachyonscript.language.util.Suggestions;

import java.util.ArrayList;
import java.util.List;

/**
 * Members of the built-in structural types: lists, maps, caught errors, durations and
 * instants, plus the {@code in} operator and iteration over maps.
 *
 * <p>Most operations compile to intrinsics with erased signatures; this class checks each use
 * against the element types of the actual list or map, boxes arguments and re-types results,
 * so a {@code List<int>} method returns an {@code int}, not an {@code any?}.
 */
final class BuiltinMembers {

    private static final List<String> LIST_PROPERTIES = List.of("size", "isEmpty", "isNotEmpty");
    private static final List<String> LIST_METHODS = List.of("add", "insert", "contains", "get", "remove", "removeAt",
            "indexOf", "lastIndexOf", "clear", "addAll", "copy", "reverse", "reversed", "sort", "sorted", "sortBy",
            "sortedBy", "sortByDescending", "sortedByDescending", "sortWith", "shuffle", "random", "first", "last",
            "filter", "map", "forEach", "any", "all", "none", "count", "find", "findIndex", "join", "sum", "average",
            "min", "max", "minBy", "maxBy", "take", "drop", "subList", "distinct", "groupBy", "associateBy");
    private static final List<String> MAP_PROPERTIES = List.of("size", "isEmpty", "isNotEmpty", "keys", "values");
    private static final List<String> MAP_METHODS = List.of("get", "getOrDefault", "put", "putIfAbsent", "remove",
            "containsKey", "containsValue", "clear", "putAll", "copy", "filter", "forEach", "keysSortedByValue",
            "keysSortedByValueDescending");

    private final BodyBinder binder;

    BuiltinMembers(BodyBinder binder) {
        this.binder = binder;
    }

    private ModuleContext module() {
        return binder.module();
    }

    // =================================================================== properties

    /** A property of a built-in type, or null if {@code receiver}'s type has none. */
    BoundExpression property(BoundExpression receiver, Identifier member, Span span) {
        Type type = receiver.type();
        String name = member.name();
        if (type instanceof ListType) {
            return switch (name) {
                case "size" -> new BoundExpression.ListSize(receiver, span);
                case "isEmpty" -> sizeCompare(new BoundExpression.ListSize(receiver, span), true, span);
                case "isNotEmpty" -> sizeCompare(new BoundExpression.ListSize(receiver, span), false, span);
                default -> unknown(type, member, LIST_PROPERTIES, LIST_METHODS, span);
            };
        }
        if (type instanceof MapType mapType) {
            return switch (name) {
                case "size" -> intrinsic(Intrinsics.MAP_SIZE, PrimitiveType.INT, span, receiver);
                case "isEmpty" -> sizeCompare(intrinsic(Intrinsics.MAP_SIZE, PrimitiveType.INT, span, receiver), true, span);
                case "isNotEmpty" -> sizeCompare(intrinsic(Intrinsics.MAP_SIZE, PrimitiveType.INT, span, receiver), false, span);
                case "keys" -> intrinsic(Intrinsics.MAP_KEYS, Types.list(mapType.key()), span, receiver);
                case "values" -> intrinsic(Intrinsics.MAP_VALUES, Types.list(mapType.value()), span, receiver);
                default -> unknown(type, member, MAP_PROPERTIES, MAP_METHODS, span);
            };
        }
        if (type == PrimitiveType.DURATION) {
            long unit = switch (name) {
                case "millis", "milliseconds" -> 1L;
                case "ticks" -> 50L;
                case "seconds" -> 1_000L;
                case "minutes" -> 60_000L;
                case "hours" -> 3_600_000L;
                case "days" -> 86_400_000L;
                default -> -1L;
            };
            if (unit < 0) {
                return unknown(type, member, List.of("millis", "ticks", "seconds", "minutes", "hours", "days"), List.of(), span);
            }
            BoundExpression millis = new BoundExpression.Conversion(ConversionKind.REINTERPRET, receiver, PrimitiveType.LONG, span);
            if (unit == 1L) {
                return millis;
            }
            return new BoundExpression.Arithmetic(ArithmeticOp.DIVIDE, Representation.LONG, millis,
                    new BoundExpression.Literal(unit, PrimitiveType.LONG, span), PrimitiveType.LONG, span);
        }
        if (type == PrimitiveType.INSTANT) {
            if (name.equals("epochMillis")) {
                return new BoundExpression.Conversion(ConversionKind.REINTERPRET, receiver, PrimitiveType.LONG, span);
            }
            return unknown(type, member, List.of("epochMillis"), List.of("format"), span);
        }
        return null;
    }

    private static BoundExpression sizeCompare(BoundExpression size, boolean empty, Span span) {
        return new BoundExpression.Compare(empty ? ComparisonOp.EQUAL : ComparisonOp.NOT_EQUAL, Representation.INT, size,
                new BoundExpression.Literal(0, PrimitiveType.INT, span), span);
    }

    private BoundExpression unknown(Type type, Identifier member, List<String> properties, List<String> methods, Span span) {
        List<String> all = new ArrayList<>(properties);
        all.addAll(methods);
        Diagnostic.Builder builder = module().diagnostic(DiagnosticCode.UNKNOWN_MEMBER, member.span(),
                "Unknown member '" + member.name() + "' on " + type.displayName() + ".");
        if (methods.contains(member.name())) {
            builder.note("'" + member.name() + "' is a method; call it with parentheses: " + member.name() + "(...)");
        } else {
            builder.suggestions(Suggestions.closest(member.name(), all, 3));
        }
        module().report(builder.build());
        return new BoundExpression.Error(span);
    }

    /** {@code error.message}, {@code error.kind}, {@code error.location}. */
    BoundExpression errorProperty(BoundExpression receiver, Identifier member, Span span) {
        return switch (member.name()) {
            case "message" -> intrinsic(Intrinsics.ERROR_MESSAGE, Types.STRING, span, receiver);
            case "kind" -> intrinsic(Intrinsics.ERROR_KIND, Types.STRING, span, receiver);
            case "location" -> intrinsic(Intrinsics.ERROR_LOCATION, Types.STRING, span, receiver);
            default -> unknown(Types.EXCEPTION, member, List.of("message", "kind", "location"), List.of(), span);
        };
    }

    // =================================================================== methods

    /** A method of a built-in type, or null if {@code receiver}'s type has none. */
    BoundExpression method(BoundExpression receiver, Identifier method, Expression.Call call) {
        Type type = receiver.type();
        if (type instanceof ListType listType) {
            return listMethod(receiver, listType, method, call);
        }
        if (type instanceof MapType mapType) {
            return mapMethod(receiver, mapType, method, call);
        }
        return null;
    }

    private BoundExpression listMethod(BoundExpression list, ListType type, Identifier method, Expression.Call call) {
        String name = method.name();
        List<Expression> args = call.arguments();
        Type element = type.element();
        Span span = call.span();
        switch (name) {
            case "add" -> {
                if (!arity(name, "(element: " + element.displayName() + ")", call, 1)) {
                    return error(span);
                }
                return new BoundExpression.ListAdd(list, element(args.getFirst(), element), span);
            }
            case "insert" -> {
                if (!arity(name, "(index: int, element: " + element.displayName() + ")", call, 2)) {
                    return error(span);
                }
                return intrinsic(Intrinsics.LIST_INSERT, PrimitiveType.VOID, span, list, index(args.get(0)),
                        element(args.get(1), element));
            }
            case "contains" -> {
                if (!arity(name, "(element: " + element.displayName() + ")", call, 1)) {
                    return error(span);
                }
                return new BoundExpression.ListContains(list, element(args.getFirst(), element), span);
            }
            case "get" -> {
                if (!arity(name, "(index: int)", call, 1)) {
                    return error(span);
                }
                return new BoundExpression.ListGet(list, index(args.getFirst()), element, span);
            }
            case "remove" -> {
                if (!arity(name, "(element: " + element.displayName() + "): bool", call, 1)) {
                    return error(span);
                }
                return intrinsic(Intrinsics.LIST_REMOVE, PrimitiveType.BOOL, span, list, element(args.getFirst(), element));
            }
            case "removeAt" -> {
                if (!arity(name, "(index: int): " + element.displayName(), call, 1)) {
                    return error(span);
                }
                return intrinsic(Intrinsics.LIST_REMOVE_AT, element, span, list, index(args.getFirst()));
            }
            case "indexOf", "lastIndexOf" -> {
                if (!arity(name, "(element: " + element.displayName() + "): int", call, 1)) {
                    return error(span);
                }
                return intrinsic(name.equals("indexOf") ? Intrinsics.LIST_INDEX_OF : Intrinsics.LIST_LAST_INDEX_OF,
                        PrimitiveType.INT, span, list, element(args.getFirst(), element));
            }
            case "clear", "reverse", "shuffle" -> {
                if (!arity(name, "()", call, 0)) {
                    return error(span);
                }
                NativeDeclaration target = switch (name) {
                    case "clear" -> Intrinsics.LIST_CLEAR;
                    case "reverse" -> Intrinsics.LIST_REVERSE;
                    default -> Intrinsics.LIST_SHUFFLE;
                };
                return intrinsic(target, PrimitiveType.VOID, span, list);
            }
            case "addAll" -> {
                if (!arity(name, "(other: " + type.displayName() + ")", call, 1)) {
                    return error(span);
                }
                BoundExpression other = binder.convert(binder.bindValue(args.getFirst(), type), type,
                        args.getFirst().span(), "the list to add");
                return intrinsic(Intrinsics.LIST_ADD_ALL, PrimitiveType.VOID, span, list, other);
            }
            case "copy", "reversed", "distinct" -> {
                if (!arity(name, "(): " + type.displayName(), call, 0)) {
                    return error(span);
                }
                NativeDeclaration target = switch (name) {
                    case "copy" -> Intrinsics.LIST_COPY;
                    case "reversed" -> Intrinsics.LIST_REVERSED;
                    default -> Intrinsics.LIST_DISTINCT;
                };
                return intrinsic(target, type, span, list);
            }
            case "sort", "sorted" -> {
                if (!arity(name, "()", call, 0) || !comparable(element, call.span(), name)) {
                    return error(span);
                }
                return name.equals("sort") ? intrinsic(Intrinsics.LIST_SORT, PrimitiveType.VOID, span, list)
                        : intrinsic(Intrinsics.LIST_SORTED, type, span, list);
            }
            case "sortBy", "sortedBy", "sortByDescending", "sortedByDescending", "minBy", "maxBy" -> {
                if (!arity(name, "(key: function(" + element.displayName() + "): ...)", call, 1)) {
                    return error(span);
                }
                BoundExpression key = functionArgument(args.getFirst(), List.of(element), null, "the sort key");
                if (key.type().isError() || !comparable(((FunctionType) key.type()).returnType(), args.getFirst().span(), name)) {
                    return error(span);
                }
                return switch (name) {
                    case "sortBy" -> intrinsic(Intrinsics.LIST_SORT_BY, PrimitiveType.VOID, span, list, key);
                    case "sortedBy" -> intrinsic(Intrinsics.LIST_SORTED_BY, type, span, list, key);
                    case "sortByDescending" -> intrinsic(Intrinsics.LIST_SORT_BY_DESCENDING, PrimitiveType.VOID, span, list, key);
                    case "sortedByDescending" -> intrinsic(Intrinsics.LIST_SORTED_BY_DESCENDING, type, span, list, key);
                    case "minBy" -> intrinsic(Intrinsics.LIST_MIN_BY, Types.nullable(element), span, list, key);
                    default -> intrinsic(Intrinsics.LIST_MAX_BY, Types.nullable(element), span, list, key);
                };
            }
            case "sortWith" -> {
                if (!arity(name, "(comparator: function(" + element.displayName() + ", " + element.displayName() + "): int)",
                        call, 1)) {
                    return error(span);
                }
                BoundExpression comparator = functionArgument(args.getFirst(), List.of(element, element),
                        PrimitiveType.INT, "the comparator");
                return intrinsic(Intrinsics.LIST_SORT_WITH, PrimitiveType.VOID, span, list, comparator);
            }
            case "random", "first", "last" -> {
                if (!arity(name, "(): " + Types.nullable(element).displayName(), call, 0)) {
                    return error(span);
                }
                NativeDeclaration target = switch (name) {
                    case "random" -> Intrinsics.LIST_RANDOM;
                    case "first" -> Intrinsics.LIST_FIRST;
                    default -> Intrinsics.LIST_LAST;
                };
                return intrinsic(target, Types.nullable(element), span, list);
            }
            case "filter" -> {
                if (!arity(name, "(predicate: function(" + element.displayName() + "): bool)", call, 1)) {
                    return error(span);
                }
                BoundExpression predicate = functionArgument(args.getFirst(), List.of(element), PrimitiveType.BOOL, "the filter");
                return intrinsic(Intrinsics.LIST_FILTER, type, span, list, predicate);
            }
            case "map" -> {
                if (!arity(name, "(transform: function(" + element.displayName() + "): ...)", call, 1)) {
                    return error(span);
                }
                BoundExpression transform = functionArgument(args.getFirst(), List.of(element), null, "the transform");
                if (transform.type().isError()) {
                    return error(span);
                }
                Type result = ((FunctionType) transform.type()).returnType();
                if (result == PrimitiveType.VOID) {
                    module().error(DiagnosticCode.TYPE_MISMATCH, args.getFirst().span(), "The transform must produce a value.");
                    return error(span);
                }
                return intrinsic(Intrinsics.LIST_MAP, Types.list(result), span, list, transform);
            }
            case "forEach" -> {
                if (!arity(name, "(action: function(" + element.displayName() + "))", call, 1)) {
                    return error(span);
                }
                BoundExpression action = functionArgument(args.getFirst(), List.of(element), PrimitiveType.VOID, "the action");
                return intrinsic(Intrinsics.LIST_FOR_EACH, PrimitiveType.VOID, span, list, action);
            }
            case "any", "all", "none", "count", "find", "findIndex" -> {
                if (!arity(name, "(predicate: function(" + element.displayName() + "): bool)", call, 1)) {
                    return error(span);
                }
                BoundExpression predicate = functionArgument(args.getFirst(), List.of(element), PrimitiveType.BOOL, "the condition");
                return switch (name) {
                    case "any" -> intrinsic(Intrinsics.LIST_ANY, PrimitiveType.BOOL, span, list, predicate);
                    case "all" -> intrinsic(Intrinsics.LIST_ALL, PrimitiveType.BOOL, span, list, predicate);
                    case "none" -> intrinsic(Intrinsics.LIST_NONE, PrimitiveType.BOOL, span, list, predicate);
                    case "count" -> intrinsic(Intrinsics.LIST_COUNT, PrimitiveType.INT, span, list, predicate);
                    case "find" -> intrinsic(Intrinsics.LIST_FIND, Types.nullable(element), span, list, predicate);
                    default -> intrinsic(Intrinsics.LIST_FIND_INDEX, PrimitiveType.INT, span, list, predicate);
                };
            }
            case "join" -> {
                if (args.size() > 1) {
                    arity(name, "(separator: string = \", \")", call, 1);
                    return error(span);
                }
                BoundExpression separator = args.isEmpty() ? new BoundExpression.Literal(", ", Types.STRING, span)
                        : binder.convert(binder.bindValue(args.getFirst(), Types.STRING), Types.STRING,
                        args.getFirst().span(), "the separator");
                BoundExpression text = element == Types.STRING ? new BoundExpression.Literal(null, Types.NULL, span)
                        : binder.textLambda(element, span);
                return intrinsic(Intrinsics.LIST_JOIN, Types.STRING, span, list, separator, text);
            }
            case "sum", "average" -> {
                if (!arity(name, "()", call, 0)) {
                    return error(span);
                }
                if (!(element instanceof PrimitiveType primitive && primitive.isNumeric())) {
                    module().report(module().diagnostic(DiagnosticCode.INVALID_OPERATOR, method.span(),
                                    "'" + name + "' needs a list of numbers, not " + type.displayName() + ".")
                            .note("Turn the elements into numbers first, e.g. list.map(p => p.level)." + name + "()").build());
                    return error(span);
                }
                if (name.equals("average")) {
                    return intrinsic(Intrinsics.LIST_AVERAGE, PrimitiveType.DOUBLE, span, list);
                }
                boolean integral = primitive == PrimitiveType.INT || primitive == PrimitiveType.LONG;
                BoundExpression sum = intrinsic(integral ? Intrinsics.LIST_SUM_LONG : Intrinsics.LIST_SUM_DOUBLE,
                        integral ? PrimitiveType.LONG : PrimitiveType.DOUBLE, span, list);
                if (primitive == PrimitiveType.INT || primitive == PrimitiveType.FLOAT) {
                    return new BoundExpression.Conversion(ConversionKind.NUMERIC, sum, primitive, span);
                }
                return sum;
            }
            case "min", "max" -> {
                if (!arity(name, "(): " + Types.nullable(element).displayName(), call, 0) || !comparable(element, span, name)) {
                    return error(span);
                }
                return intrinsic(name.equals("min") ? Intrinsics.LIST_MIN : Intrinsics.LIST_MAX, Types.nullable(element),
                        span, list);
            }
            case "take", "drop" -> {
                if (!arity(name, "(count: int): " + type.displayName(), call, 1)) {
                    return error(span);
                }
                return intrinsic(name.equals("take") ? Intrinsics.LIST_TAKE : Intrinsics.LIST_DROP, type, span, list,
                        index(args.getFirst()));
            }
            case "subList" -> {
                if (!arity(name, "(from: int, to: int): " + type.displayName(), call, 2)) {
                    return error(span);
                }
                return intrinsic(Intrinsics.LIST_SUB_LIST, type, span, list, index(args.get(0)), index(args.get(1)));
            }
            case "groupBy", "associateBy" -> {
                if (!arity(name, "(key: function(" + element.displayName() + "): ...)", call, 1)) {
                    return error(span);
                }
                BoundExpression key = functionArgument(args.getFirst(), List.of(element), null, "the key");
                if (key.type().isError()) {
                    return error(span);
                }
                Type keyType = ((FunctionType) key.type()).returnType();
                if (keyType == PrimitiveType.VOID || keyType.isNullable()) {
                    module().error(DiagnosticCode.TYPE_MISMATCH, args.getFirst().span(),
                            "The key must be a value that is never null.");
                    return error(span);
                }
                return name.equals("groupBy")
                        ? intrinsic(Intrinsics.LIST_GROUP_BY, Types.map(keyType, type), span, list, key)
                        : intrinsic(Intrinsics.LIST_ASSOCIATE_BY, Types.map(keyType, element), span, list, key);
            }
            case "size", "isEmpty", "isNotEmpty" -> {
                binder.bindArgumentsForErrors(args);
                module().error(DiagnosticCode.NOT_CALLABLE, method.span(),
                        "'" + name + "' is a property, not a method; remove the parentheses.");
                return error(span);
            }
            case "set" -> {
                binder.bindArgumentsForErrors(args);
                module().report(module().diagnostic(DiagnosticCode.UNKNOWN_MEMBER, method.span(),
                        "Lists have no method 'set'.").note("Assign the element instead: list[index] = value").build());
                return error(span);
            }
            default -> {
                binder.bindArgumentsForErrors(args);
                module().report(module().diagnostic(DiagnosticCode.UNKNOWN_MEMBER, method.span(),
                                "Unknown method '" + name + "' on " + type.displayName() + ".")
                        .suggestions(Suggestions.closest(name, LIST_METHODS, 3)).build());
                return error(span);
            }
        }
    }

    private BoundExpression mapMethod(BoundExpression map, MapType type, Identifier method, Expression.Call call) {
        String name = method.name();
        List<Expression> args = call.arguments();
        Type key = type.key();
        Type value = type.value();
        Span span = call.span();
        switch (name) {
            case "get" -> {
                if (!arity(name, "(key: " + key.displayName() + "): " + Types.nullable(value).displayName(), call, 1)) {
                    return error(span);
                }
                return intrinsic(Intrinsics.MAP_GET, Types.nullable(value), span, map, element(args.getFirst(), key));
            }
            case "getOrDefault" -> {
                if (!arity(name, "(key: " + key.displayName() + ", fallback: " + value.displayName() + "): "
                        + value.displayName(), call, 2)) {
                    return error(span);
                }
                return intrinsic(Intrinsics.MAP_GET_OR_DEFAULT, value, span, map, element(args.get(0), key),
                        element(args.get(1), value));
            }
            case "put", "set" -> {
                if (!arity(name, "(key: " + key.displayName() + ", value: " + value.displayName() + ")", call, 2)) {
                    return error(span);
                }
                return intrinsic(Intrinsics.MAP_PUT, PrimitiveType.VOID, span, map, element(args.get(0), key),
                        element(args.get(1), value));
            }
            case "putIfAbsent" -> {
                if (!arity(name, "(key: " + key.displayName() + ", value: " + value.displayName() + "): "
                        + Types.nullable(value).displayName(), call, 2)) {
                    return error(span);
                }
                return intrinsic(Intrinsics.MAP_PUT_IF_ABSENT, Types.nullable(value), span, map, element(args.get(0), key),
                        element(args.get(1), value));
            }
            case "remove" -> {
                if (!arity(name, "(key: " + key.displayName() + "): " + Types.nullable(value).displayName(), call, 1)) {
                    return error(span);
                }
                return intrinsic(Intrinsics.MAP_REMOVE, Types.nullable(value), span, map, element(args.getFirst(), key));
            }
            case "containsKey" -> {
                if (!arity(name, "(key: " + key.displayName() + "): bool", call, 1)) {
                    return error(span);
                }
                return intrinsic(Intrinsics.MAP_CONTAINS_KEY, PrimitiveType.BOOL, span, map, element(args.getFirst(), key));
            }
            case "containsValue" -> {
                if (!arity(name, "(value: " + value.displayName() + "): bool", call, 1)) {
                    return error(span);
                }
                return intrinsic(Intrinsics.MAP_CONTAINS_VALUE, PrimitiveType.BOOL, span, map, element(args.getFirst(), value));
            }
            case "clear" -> {
                if (!arity(name, "()", call, 0)) {
                    return error(span);
                }
                return intrinsic(Intrinsics.MAP_CLEAR, PrimitiveType.VOID, span, map);
            }
            case "putAll" -> {
                if (!arity(name, "(other: " + type.displayName() + ")", call, 1)) {
                    return error(span);
                }
                BoundExpression other = binder.convert(binder.bindValue(args.getFirst(), type), type,
                        args.getFirst().span(), "the map to add");
                return intrinsic(Intrinsics.MAP_PUT_ALL, PrimitiveType.VOID, span, map, other);
            }
            case "copy" -> {
                if (!arity(name, "(): " + type.displayName(), call, 0)) {
                    return error(span);
                }
                return intrinsic(Intrinsics.MAP_COPY, type, span, map);
            }
            case "filter" -> {
                if (!arity(name, "(predicate: function(" + key.displayName() + ", " + value.displayName() + "): bool)",
                        call, 1)) {
                    return error(span);
                }
                BoundExpression predicate = functionArgument(args.getFirst(), List.of(key, value), PrimitiveType.BOOL,
                        "the filter");
                return intrinsic(Intrinsics.MAP_FILTER, type, span, map, predicate);
            }
            case "forEach" -> {
                if (!arity(name, "(action: function(" + key.displayName() + ", " + value.displayName() + "))", call, 1)) {
                    return error(span);
                }
                BoundExpression action = functionArgument(args.getFirst(), List.of(key, value), PrimitiveType.VOID,
                        "the action");
                return intrinsic(Intrinsics.MAP_FOR_EACH, PrimitiveType.VOID, span, map, action);
            }
            case "keysSortedByValue", "keysSortedByValueDescending" -> {
                if (!arity(name, "(): " + Types.list(key).displayName(), call, 0) || !comparable(value, span, name)) {
                    return error(span);
                }
                return intrinsic(Intrinsics.MAP_SORTED_KEYS_BY_VALUE, Types.list(key), span, map,
                        new BoundExpression.Literal(name.endsWith("Descending"), PrimitiveType.BOOL, span));
            }
            case "size", "isEmpty", "isNotEmpty", "keys", "values" -> {
                binder.bindArgumentsForErrors(args);
                module().error(DiagnosticCode.NOT_CALLABLE, method.span(),
                        "'" + name + "' is a property, not a method; remove the parentheses.");
                return error(span);
            }
            default -> {
                binder.bindArgumentsForErrors(args);
                module().report(module().diagnostic(DiagnosticCode.UNKNOWN_MEMBER, method.span(),
                                "Unknown method '" + name + "' on " + type.displayName() + ".")
                        .suggestions(Suggestions.closest(name, MAP_METHODS, 3)).build());
                return error(span);
            }
        }
    }

    // =================================================================== maps

    /** {@code map[key]}: the value, or null when the key is missing. */
    BoundExpression mapGet(BoundExpression map, MapType type, Expression keySyntax, Span span) {
        return intrinsic(Intrinsics.MAP_GET, Types.nullable(type.value()), span, map, element(keySyntax, type.key()));
    }

    /**
     * {@code map[key] = value} and compound forms. {@code +=} and {@code -=} on numbers are
     * atomic and treat a missing entry as 0; other compound operators read and write the entry.
     */
    BoundStatement assignMapEntry(BoundExpression map, MapType type, Expression.Index index, AssignmentOperator operator,
                                  Expression valueSyntax, Span span) {
        Type key = type.key();
        Type value = type.value();
        if (!operator.isCompound()) {
            BoundExpression keyValue = element(index.index(), key);
            BoundExpression newValue = element(valueSyntax, value);
            return new BoundStatement.ExpressionStatement(intrinsic(Intrinsics.MAP_PUT, PrimitiveType.VOID, span, map,
                    keyValue, newValue), span);
        }
        boolean numeric = value == PrimitiveType.INT || value == PrimitiveType.LONG || value == PrimitiveType.DOUBLE;
        if (numeric && (operator == AssignmentOperator.ADD || operator == AssignmentOperator.SUBTRACT)) {
            BoundExpression keyValue = element(index.index(), key);
            BoundExpression delta = binder.convert(binder.bindValue(valueSyntax, value), value, valueSyntax.span(),
                    "the change");
            if (operator == AssignmentOperator.SUBTRACT && !delta.type().isError()) {
                delta = new BoundExpression.Negate(value.representation(), delta, value, delta.span());
            }
            NativeDeclaration target = value == PrimitiveType.INT ? Intrinsics.MAP_ADD_INT
                    : value == PrimitiveType.LONG ? Intrinsics.MAP_ADD_LONG : Intrinsics.MAP_ADD_DOUBLE;
            return new BoundStatement.ExpressionStatement(intrinsic(target, value, span, map, keyValue, delta), span);
        }
        BoundExpression fallback;
        if (value instanceof PrimitiveType primitive && primitive.isNumeric()) {
            fallback = new BoundExpression.Literal(zero(primitive), value, span);
        } else if (value == Types.STRING) {
            fallback = new BoundExpression.Literal("", Types.STRING, span);
        } else {
            binder.bindValue(index.index(), key);
            binder.bindValue(valueSyntax, null);
            module().report(module().diagnostic(DiagnosticCode.INVALID_OPERATOR, span,
                            "'" + operator.symbol() + "' needs a current value, but the map may not have this key.")
                    .note("Read the value first: let current = map[key] ?? ... then assign map[key] = ...").build());
            return BodyBinder.errorStatement(span);
        }
        List<BoundStatement> prefix = new ArrayList<>();
        BoundExpression hoistedMap = binder.hoist(map, prefix);
        BoundExpression keyValue = binder.hoist(element(index.index(), key), prefix);
        BoundExpression current = intrinsic(Intrinsics.MAP_GET_OR_DEFAULT, value, span, hoistedMap, keyValue, fallback);
        BoundExpression combined = binder.convert(binder.binaryOperation(operator.binary(), current,
                binder.bindValue(valueSyntax, value), span), value, valueSyntax.span(), "the map value");
        prefix.add(new BoundStatement.ExpressionStatement(intrinsic(Intrinsics.MAP_PUT, PrimitiveType.VOID, span,
                hoistedMap, keyValue, combined), span));
        return new BoundStatement.Block(prefix, span);
    }

    private static Object zero(PrimitiveType type) {
        return switch (type) {
            case INT -> 0;
            case LONG -> 0L;
            case FLOAT -> 0f;
            default -> 0d;
        };
    }

    /**
     * {@code for key, value in map { }}: iterates over a snapshot of the entries taken when the
     * loop starts, so the body may change the map.
     */
    BoundStatement forEachEntry(Statement.For statement, BoundExpression map, MapType type) {
        Span span = statement.span();
        Type flat = Types.list(Intrinsics.REF);
        LocalSymbol entries = module().newTemporary(flat, span);
        LocalSymbol position = module().newTemporary(PrimitiveType.INT, span);
        LocalSymbol keyVariable = binder.declareLoopVariable(statement.variable(), type.key());
        LocalSymbol valueVariable = statement.second() != null
                ? binder.declareLoopVariable(statement.second(), type.value()) : null;
        BoundStatement body = binder.bindLoopBody(statement.body());

        BoundExpression positionLoad = new BoundExpression.LocalLoad(position, PrimitiveType.INT, span);
        BoundExpression entriesLoad = new BoundExpression.LocalLoad(entries, flat, span);
        List<BoundStatement> loop = new ArrayList<>();
        loop.add(new BoundStatement.LocalDeclaration(keyVariable, Conversions.reinterpret(
                new BoundExpression.ListGet(entriesLoad, positionLoad, Intrinsics.REF, span), type.key()), span));
        if (valueVariable != null) {
            BoundExpression next = new BoundExpression.Arithmetic(ArithmeticOp.ADD, Representation.INT, positionLoad,
                    new BoundExpression.Literal(1, PrimitiveType.INT, span), PrimitiveType.INT, span);
            loop.add(new BoundStatement.LocalDeclaration(valueVariable, Conversions.reinterpret(
                    new BoundExpression.ListGet(entriesLoad, next, Intrinsics.REF, span), type.value()), span));
        }
        loop.add(new BoundStatement.LocalAssignment(position, new BoundExpression.Arithmetic(ArithmeticOp.ADD,
                Representation.INT, positionLoad, new BoundExpression.Literal(2, PrimitiveType.INT, span), PrimitiveType.INT,
                span), span));
        loop.add(body);
        BoundExpression condition = new BoundExpression.Compare(ComparisonOp.LESS, Representation.INT, positionLoad,
                new BoundExpression.ListSize(entriesLoad, span), span);
        return new BoundStatement.Block(List.of(
                new BoundStatement.LocalDeclaration(entries, intrinsic(Intrinsics.MAP_ENTRIES, flat, span, map), span),
                new BoundStatement.LocalDeclaration(position, new BoundExpression.Literal(0, PrimitiveType.INT, span), span),
                new BoundStatement.While(condition, new BoundStatement.Block(loop, span), span)), span);
    }

    // =================================================================== membership

    /** {@code element in container}: lists, map keys, text and ranges. */
    BoundExpression membership(Expression.Binary binary) {
        Span span = binary.span();
        Expression right = BodyBinder.unwrap(binary.right());
        if (right instanceof Expression.Range range) {
            return rangeMembership(binary.left(), range, span);
        }
        BoundExpression element = binder.bindValue(binary.left(), null);
        BoundExpression container = binder.bindValue(binary.right(), null);
        if (element.type().isError() || container.type().isError()) {
            return new BoundExpression.Error(span);
        }
        Type type = container.type();
        if (type instanceof ListType list) {
            return new BoundExpression.ListContains(container, binder.convert(element, list.element(),
                    binary.left().span(), "the value to look for"), span);
        }
        if (type instanceof MapType map) {
            return intrinsic(Intrinsics.MAP_CONTAINS_KEY, PrimitiveType.BOOL, span, container,
                    binder.convert(element, map.key(), binary.left().span(), "the key to look for"));
        }
        if (type == Types.STRING) {
            return intrinsic(Intrinsics.STRING_CONTAINS, PrimitiveType.BOOL, span, container,
                    binder.convert(element, Types.STRING, binary.left().span(), "the text to look for"));
        }
        if (type.isNullable()) {
            binder.reportNullableAccess(container, binary.right(), "in");
            return new BoundExpression.Error(span);
        }
        module().report(module().diagnostic(DiagnosticCode.INVALID_OPERATOR, span,
                        "'" + binary.operator().symbol() + "' needs a list, a map, a text or a range on the right, not "
                                + type.displayName() + ".")
                .note("Examples: name in names   key in map   \"x\" in message   level in 1..10").build());
        return new BoundExpression.Error(span);
    }

    private BoundExpression rangeMembership(Expression elementSyntax, Expression.Range range, Span span) {
        BoundExpression element = binder.bindValue(elementSyntax, null);
        BoundExpression start = binder.bindValue(range.start(), null);
        BoundExpression end = binder.bindValue(range.end(), null);
        if (element.type().isError() || start.type().isError() || end.type().isError()) {
            return new BoundExpression.Error(span);
        }
        PrimitiveType type = BodyBinder.promote(element.type(), start.type());
        type = type == null ? null : BodyBinder.promote(type, end.type());
        if (type == null) {
            module().report(module().diagnostic(DiagnosticCode.INVALID_OPERATOR, span,
                    "'in' with a range needs numbers on both sides.").build());
            return new BoundExpression.Error(span);
        }
        LocalSymbol temporary = module().newTemporary(type, element.span());
        BoundExpression value = new BoundExpression.LocalLoad(temporary, type, element.span());
        BoundExpression low = new BoundExpression.Compare(ComparisonOp.GREATER_EQUAL, type.representation(), value,
                Conversions.apply(start, type), span);
        BoundExpression high = new BoundExpression.Compare(range.inclusive() ? ComparisonOp.LESS_EQUAL : ComparisonOp.LESS,
                type.representation(), value, Conversions.apply(end, type), span);
        return new BoundExpression.Let(temporary, Conversions.apply(element, type),
                new BoundExpression.Logical(true, low, high, span), span);
    }

    // =================================================================== helpers

    /**
     * Binds an argument expected to be a function: a lambda (typed by {@code parameters}), the
     * name of a script function, or any expression of a matching function type.
     * {@code result} {@code null} means the result type is inferred.
     */
    BoundExpression functionArgument(Expression syntax, List<Type> parameters, Type result, String what) {
        Expression expression = BodyBinder.unwrap(syntax);
        if (expression instanceof Expression.Lambda lambda) {
            BoundExpression bound = binder.bindLambdaAgainst(lambda, parameters, result, result != null);
            if (bound.type().isError()) {
                return bound;
            }
            if (result == null) {
                return bound;
            }
            return bound;
        }
        if (expression instanceof Expression.Name name) {
            List<FunctionSymbol> overloads = module().functions(name.name());
            if (!overloads.isEmpty() && module().constants().get(name.name()) == null) {
                for (FunctionSymbol symbol : overloads) {
                    if (symbol.parameterTypes().equals(parameters)
                            && (result == null ? symbol.returnType() != PrimitiveType.VOID || parameters.isEmpty()
                            : Conversions.isAssignable(symbol.returnType(), result))) {
                        return new BoundExpression.FunctionReference(symbol,
                                Types.function(symbol.parameterTypes(), symbol.returnType()), syntax.span());
                    }
                }
            }
        }
        BoundExpression value = binder.bindValue(syntax, result == null ? null : Types.function(parameters, result));
        if (value.type().isError()) {
            return value;
        }
        if (value.type() instanceof FunctionType function && function.parameters().equals(parameters)
                && (result == null || Conversions.isAssignable(function.returnType(), result))) {
            return value;
        }
        module().report(module().diagnostic(DiagnosticCode.TYPE_MISMATCH, syntax.span(), "Type mismatch for " + what + ".")
                .expectedReceived(Types.function(parameters, result == null ? Types.ANY : result).displayName()
                        .replace(": any", ": ..."), value.type().displayName())
                .note("Pass a lambda, for example: x => ...").build());
        return new BoundExpression.Error(syntax.span());
    }

    /** Whether values of {@code type} have a natural order (numbers, text, durations, instants). */
    private boolean comparable(Type type, Span span, String what) {
        Type base = type.nonNullable();
        boolean ok = base == Types.STRING || base == PrimitiveType.DURATION || base == PrimitiveType.INSTANT
                || base instanceof PrimitiveType primitive && primitive.isNumeric() || type.isError();
        if (!ok) {
            module().report(module().diagnostic(DiagnosticCode.INVALID_OPERATOR, span,
                            "'" + what + "' needs values that can be ordered (numbers, text, durations or instants), not "
                                    + type.displayName() + ".")
                    .note("Sort by a property instead, e.g. players.sortBy(p => p.level)").build());
        }
        return ok;
    }

    private boolean arity(String name, String signature, Expression.Call call, int expected) {
        if (call.arguments().size() == expected) {
            return true;
        }
        binder.bindArgumentsForErrors(call.arguments());
        module().report(module().diagnostic(DiagnosticCode.WRONG_ARGUMENT_COUNT, call.span(),
                        "'" + name + "' expects " + expected + " argument" + (expected == 1 ? "" : "s") + ", but "
                                + call.arguments().size() + (call.arguments().size() == 1 ? " was" : " were") + " given.")
                .note("Signature:\n    " + name + signature).build());
        return false;
    }

    private BoundExpression element(Expression syntax, Type type) {
        return binder.convert(binder.bindValue(syntax, type), type, syntax.span(), "the value");
    }

    private BoundExpression index(Expression syntax) {
        return binder.convert(binder.bindValue(syntax, PrimitiveType.INT), PrimitiveType.INT, syntax.span(), "the index");
    }

    private static BoundExpression error(Span span) {
        return new BoundExpression.Error(span);
    }

    /**
     * Calls an intrinsic: reference parameters get boxed values, primitive parameters converted
     * values, and the erased result is re-typed as {@code result}.
     */
    static BoundExpression intrinsic(NativeDeclaration target, Type result, Span span, BoundExpression... arguments) {
        List<BoundExpression> converted = new ArrayList<>();
        for (int i = 0; i < arguments.length; i++) {
            BoundExpression argument = arguments[i];
            if (argument.type().isError()) {
                return new BoundExpression.Error(span);
            }
            Type parameter = target.parameters().get(i).type();
            converted.add(parameter.representation() == Representation.REF
                    ? (argument.type().representation().isPrimitive() ? Conversions.apply(argument, Intrinsics.REF) : argument)
                    : Conversions.apply(argument, parameter));
        }
        BoundExpression call = new BoundExpression.NativeCall(target, converted, target.returnType(), span);
        if (result == PrimitiveType.VOID || result.equals(target.returnType())) {
            return call;
        }
        return Conversions.reinterpret(call, result);
    }
}
