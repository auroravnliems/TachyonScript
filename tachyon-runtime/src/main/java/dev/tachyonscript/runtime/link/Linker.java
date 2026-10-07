package dev.tachyonscript.runtime.link;

import dev.tachyonscript.runtime.ExecutionBackend;
import dev.tachyonscript.runtime.bytecode.BytecodeCompiler;
import dev.tachyonscript.runtime.interpreter.BytecodeBody;

import dev.tachyonscript.api.declaration.NativeDeclaration;
import dev.tachyonscript.api.natives.Arguments;
import dev.tachyonscript.api.natives.NativeFunction;
import dev.tachyonscript.api.registry.Bindings;
import dev.tachyonscript.api.storage.KeyedValues;
import dev.tachyonscript.api.type.ClassType;
import dev.tachyonscript.ir.FunctionRef;
import dev.tachyonscript.ir.GlobalRef;
import dev.tachyonscript.ir.RecordRef;
import dev.tachyonscript.runtime.code.AssembledModule;
import dev.tachyonscript.runtime.code.CodeUnit;
import dev.tachyonscript.runtime.code.KeyedConstant;
import dev.tachyonscript.runtime.code.TemplateConstant;
import dev.tachyonscript.runtime.event.CompiledHandler;
import dev.tachyonscript.runtime.interpreter.CompiledFunction;
import dev.tachyonscript.runtime.intrinsics.RuntimeIntrinsics;
import dev.tachyonscript.runtime.spi.MessageTemplate;
import dev.tachyonscript.runtime.spi.TextService;
import dev.tachyonscript.runtime.value.GlobalCell;
import dev.tachyonscript.runtime.value.PlayerDataSlot;
import dev.tachyonscript.runtime.value.RecordType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves an assembled module against platform bindings and a {@link LinkEnvironment}.
 *
 * <p>Everything that depends on the platform happens here, once per load: natives become
 * direct references (built-in operations of the language are provided by
 * {@link RuntimeIntrinsics} when the bindings do not override them), message templates are
 * compiled by the text service, constant messages are rendered into ready components, keyed
 * constants such as {@code Material.DIAMOND} become platform objects, type tests get their
 * runtime classes, top-level variables and player data get their storage, and calls between
 * functions (also of imported modules) are resolved. All problems are collected and reported
 * together.
 */
public final class Linker {

    /** Arguments of a template without arguments. */
    private static final Arguments NO_ARGUMENTS = new Arguments() {
        public int count() {
            return 0;
        }

        public Object getRef(int index) {
            throw new IndexOutOfBoundsException(index);
        }

        public int getInt(int index) {
            throw new IndexOutOfBoundsException(index);
        }

        public long getLong(int index) {
            throw new IndexOutOfBoundsException(index);
        }

        public float getFloat(int index) {
            throw new IndexOutOfBoundsException(index);
        }

        public double getDouble(int index) {
            throw new IndexOutOfBoundsException(index);
        }

        public boolean getBool(int index) {
            throw new IndexOutOfBoundsException(index);
        }
    };

    private Linker() {
    }

    /** Links a module that imports nothing, keeping its variables in a private in-memory environment. */
    public static LinkedModule link(AssembledModule module, Bindings bindings, TextService text) throws LinkException {
        return link(module, bindings, text, ExecutionBackend.INTERPRETER);
    }

    public static LinkedModule link(AssembledModule module, Bindings bindings, TextService text,
                                    ExecutionBackend backend) throws LinkException {
        StandaloneEnvironment environment = new StandaloneEnvironment();
        LinkedModule linked = link(module, bindings, text, environment, backend);
        environment.register(linked);
        return linked;
    }

    public static LinkedModule link(AssembledModule module, Bindings bindings, TextService text,
                                    LinkEnvironment environment) throws LinkException {
        return link(module, bindings, text, environment, ExecutionBackend.INTERPRETER);
    }

    public static LinkedModule link(AssembledModule module, Bindings bindings, TextService text,
                                    LinkEnvironment environment, ExecutionBackend backend) throws LinkException {
        java.util.Objects.requireNonNull(backend, "backend");
        List<String> problems = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        Map<String, CompiledFunction> functions = new LinkedHashMap<>();
        Map<String, GlobalCell> globals = new LinkedHashMap<>();
        Map<String, PlayerDataSlot> playerData = new LinkedHashMap<>();
        for (GlobalRef global : module.globals()) {
            if (global.storage() == GlobalRef.Storage.PLAYERDATA) {
                PlayerDataSlot slot = environment.playerData(global);
                if (slot == null) {
                    problems.add("No storage for playerdata variable '" + global.name() + "'");
                } else {
                    playerData.put(global.name(), slot);
                }
            } else {
                GlobalCell cell = environment.global(global);
                if (cell == null) {
                    problems.add("No storage for variable '" + global.name() + "'");
                } else {
                    globals.put(global.name(), cell);
                }
            }
        }
        Map<String, RecordType> records = new LinkedHashMap<>();
        for (RecordRef record : module.records()) {
            RecordType type = environment.record(record);
            if (type == null || !type.matches(record)) {
                problems.add("No runtime type for record " + record.name());
            } else {
                records.put(record.name(), type);
            }
        }
        for (CodeUnit unit : module.units()) {
            functions.put(unit.key(), linkUnit(unit, module, bindings, text, environment, backend, problems, notes));
        }
        for (CodeUnit unit : module.units()) {
            CompiledFunction[] callees = new CompiledFunction[unit.functions().size()];
            for (int i = 0; i < callees.length; i++) {
                FunctionRef reference = unit.functions().get(i);
                callees[i] = reference.module().equals(module.name()) ? functions.get(reference.key())
                        : environment.function(reference);
                if (callees[i] == null) {
                    problems.add(unit.key() + " calls unknown function " + reference.qualifiedKey());
                } else if (!matches(callees[i], reference)) {
                    problems.add(unit.key() + " calls " + reference.qualifiedKey()
                            + ", whose parameters changed; the calling script must be reloaded too");
                }
            }
            CompiledFunction function = functions.get(unit.key());
            if (function != null) {
                function.resolveCallees(callees);
            }
        }
        List<CompiledHandler> handlers = new ArrayList<>();
        for (AssembledModule.Handler handler : module.handlers()) {
            CompiledFunction function = functions.get(handler.function());
            if (function == null || function.parameterCount() != 1) {
                problems.add("Invalid handler " + handler.function() + " for " + handler.event());
            } else {
                handlers.add(new CompiledHandler(handler.event(), function, module.name(), handler.priority(),
                        handler.ignoreCancelled()));
            }
        }
        if (!problems.isEmpty()) {
            throw new LinkException(module.name(), problems.stream().distinct().toList());
        }
        return new LinkedModule(module.name(), module.source(), functions, handlers, globals, playerData, records,
                environment.owner(), notes);
    }

    /** Whether a resolved function has the parameter and result representations the reference expects. */
    private static boolean matches(CompiledFunction function, FunctionRef reference) {
        CodeUnit unit = function.unit();
        if (unit.parameterSlots().length != reference.parameters().size()
                || unit.returnKind() != reference.returnType().representation()) {
            return false;
        }
        for (int i = 0; i < reference.parameters().size(); i++) {
            boolean reference2 = reference.parameters().get(i).representation() == dev.tachyonscript.api.type.Representation.REF;
            if (unit.parameterIsReference()[i] != reference2) {
                return false;
            }
        }
        return true;
    }

    private static CompiledFunction linkUnit(CodeUnit unit, AssembledModule module, Bindings bindings, TextService text,
                                             LinkEnvironment environment, ExecutionBackend backend, List<String> problems,
                                             List<String> notes) {
        String path = module.source().path();
        NativeFunction[] natives = new NativeFunction[unit.natives().size()];
        for (int i = 0; i < natives.length; i++) {
            NativeDeclaration declaration = unit.natives().get(i);
            natives[i] = bindings.lookup(declaration).orElse(null);
            if (natives[i] == null && declaration.isIntrinsic()) {
                natives[i] = RuntimeIntrinsics.bindings().lookup(declaration).orElse(null);
            }
            if (natives[i] == null) {
                problems.add("The server does not provide '" + declaration.key() + "' (used in " + path + ")");
            }
        }
        Class<?>[] classes = new Class<?>[unit.classes().size()];
        for (int i = 0; i < classes.length; i++) {
            ClassType type = unit.classes().get(i);
            classes[i] = bindings.typeClass(type).orElse(builtInClass(type));
            if (classes[i] == null) {
                problems.add("The server does not provide a runtime class for type " + type.name());
            }
        }
        MessageTemplate[] templates = new MessageTemplate[unit.templates().size()];
        for (int i = 0; i < templates.length; i++) {
            templates[i] = compileTemplate(text, unit.templates().get(i), problems);
        }
        Object[] references = unit.referencePool().clone();
        for (int i = 0; i < references.length; i++) {
            if (references[i] instanceof TemplateConstant constant) {
                MessageTemplate template = compileTemplate(text, constant.segments(), problems);
                try {
                    references[i] = template == null ? null : template.render(NO_ARGUMENTS);
                } catch (RuntimeException error) {
                    problems.add("Cannot build message " + constant.segments().getFirst() + ": " + error.getMessage());
                }
            } else if (references[i] instanceof KeyedConstant constant) {
                references[i] = resolveKey(bindings, constant, path, problems);
            }
        }
        GlobalCell[] globals = new GlobalCell[unit.globals().size()];
        PlayerDataSlot[] playerData = new PlayerDataSlot[unit.globals().size()];
        for (int i = 0; i < globals.length; i++) {
            GlobalRef global = unit.globals().get(i);
            if (global.storage() == GlobalRef.Storage.PLAYERDATA) {
                playerData[i] = environment.playerData(global);
                if (playerData[i] == null) {
                    problems.add(path + " uses playerdata variable " + global + ", which is not loaded");
                }
            } else {
                globals[i] = environment.global(global);
                if (globals[i] == null) {
                    problems.add(path + " uses variable " + global + ", which is not loaded");
                }
            }
        }
        RecordType[] records = new RecordType[unit.records().size()];
        for (int i = 0; i < records.length; i++) {
            records[i] = environment.record(unit.records().get(i));
            if (records[i] == null) {
                problems.add(path + " uses record " + unit.records().get(i) + ", which is not loaded");
            }
        }
        BytecodeBody bytecode = null;
        if (backend == ExecutionBackend.BYTECODE) {
            // The interpreter is the reference implementation, so a function can always run on it
            // with identical behaviour. It does so, visibly (see LinkedModule.notes), when its JVM
            // body would be too large for HotSpot to compile, or if generation fails.
            try {
                byte[] bytes = BytecodeCompiler.generate(unit, module.source());
                int size = BytecodeCompiler.methodSize(bytes);
                if (size > BytecodeCompiler.JIT_LIMIT) {
                    notes.add(unit.displayName() + " runs in the interpreter: its JVM body (" + size
                            + " bytes) would exceed HotSpot's " + BytecodeCompiler.JIT_LIMIT + "-byte compilation limit");
                } else {
                    bytecode = BytecodeBody.define(bytes, unit.key());
                }
            } catch (RuntimeException | LinkageError error) {
                notes.add(unit.displayName() + " runs in the interpreter: bytecode generation failed (" + error
                        + "); this is a TachyonScript bug, please report it");
            }
        }
        return new CompiledFunction(unit, module.source(), references, natives, classes, templates, globals, playerData,
                records, text, environment.owner(), bytecode);
    }

    private static Object resolveKey(Bindings bindings, KeyedConstant constant, String path, List<String> problems) {
        KeyedValues values = bindings.keyedValues(constant.type()).orElse(null);
        if (values == null) {
            problems.add("The server does not provide the values of " + constant.type().name() + " (used in " + path + ")");
            return null;
        }
        Object value;
        try {
            value = values.resolve(constant.key());
        } catch (RuntimeException error) {
            value = null;
        }
        if (value == null) {
            problems.add("Unknown " + constant.type().name() + " '" + constant.key() + "' on this server (used in " + path + ")");
        }
        return value;
    }

    private static MessageTemplate compileTemplate(TextService text, List<String> segments, List<String> problems) {
        try {
            return text.compile(segments);
        } catch (RuntimeException error) {
            problems.add("Cannot compile message template " + String.join("{...}", segments) + ": " + error.getMessage());
            return null;
        }
    }

    /** Classes of the language's own types, when the platform does not bind them. */
    private static Class<?> builtInClass(ClassType type) {
        if (type == dev.tachyonscript.api.type.Types.LIST_VALUE) {
            return java.util.List.class;
        }
        if (type == dev.tachyonscript.api.type.Types.MAP_VALUE) {
            return java.util.Map.class;
        }
        return null;
    }
}
