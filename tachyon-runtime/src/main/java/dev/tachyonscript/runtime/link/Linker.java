package dev.tachyonscript.runtime.link;

import dev.tachyonscript.api.declaration.NativeDeclaration;
import dev.tachyonscript.api.natives.Arguments;
import dev.tachyonscript.api.natives.NativeFunction;
import dev.tachyonscript.api.registry.Bindings;
import dev.tachyonscript.api.type.ClassType;
import dev.tachyonscript.runtime.code.AssembledModule;
import dev.tachyonscript.runtime.code.CodeUnit;
import dev.tachyonscript.runtime.code.TemplateConstant;
import dev.tachyonscript.runtime.event.CompiledHandler;
import dev.tachyonscript.runtime.interpreter.CompiledFunction;
import dev.tachyonscript.runtime.spi.MessageTemplate;
import dev.tachyonscript.runtime.spi.TextService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves an assembled module against platform bindings.
 *
 * <p>Everything that depends on the platform happens here, once per load: natives become
 * direct references, message templates are compiled by the text service, constant messages
 * are rendered into ready components, type tests get their runtime classes, and calls
 * between functions are resolved. All problems are collected and reported together.
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

    public static LinkedModule link(AssembledModule module, Bindings bindings, TextService text) throws LinkException {
        List<String> problems = new ArrayList<>();
        Map<String, CompiledFunction> functions = new LinkedHashMap<>();
        for (CodeUnit unit : module.units()) {
            functions.put(unit.key(), linkUnit(unit, module, bindings, text, problems));
        }
        for (CodeUnit unit : module.units()) {
            CompiledFunction[] callees = new CompiledFunction[unit.functions().size()];
            for (int i = 0; i < callees.length; i++) {
                callees[i] = functions.get(unit.functions().get(i));
                if (callees[i] == null) {
                    problems.add(unit.key() + " calls unknown function " + unit.functions().get(i));
                }
            }
            functions.get(unit.key()).resolveCallees(callees);
        }
        List<CompiledHandler> handlers = new ArrayList<>();
        for (AssembledModule.Handler handler : module.handlers()) {
            CompiledFunction function = functions.get(handler.function());
            if (function == null || function.parameterCount() != 1) {
                problems.add("Invalid handler " + handler.function() + " for " + handler.event());
            } else {
                handlers.add(new CompiledHandler(handler.event(), function, module.name()));
            }
        }
        if (!problems.isEmpty()) {
            throw new LinkException(module.name(), problems.stream().distinct().toList());
        }
        return new LinkedModule(module.name(), module.source(), functions, handlers);
    }

    private static CompiledFunction linkUnit(CodeUnit unit, AssembledModule module, Bindings bindings, TextService text,
                                             List<String> problems) {
        NativeFunction[] natives = new NativeFunction[unit.natives().size()];
        for (int i = 0; i < natives.length; i++) {
            NativeDeclaration declaration = unit.natives().get(i);
            natives[i] = bindings.lookup(declaration).orElse(null);
            if (natives[i] == null) {
                problems.add("The server does not provide '" + declaration.key() + "' (used in " + module.source().path() + ")");
            }
        }
        Class<?>[] classes = new Class<?>[unit.classes().size()];
        for (int i = 0; i < classes.length; i++) {
            ClassType type = unit.classes().get(i);
            classes[i] = bindings.typeClass(type).orElse(null);
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
            }
        }
        return new CompiledFunction(unit, module.source(), references, natives, classes, templates, text);
    }

    private static MessageTemplate compileTemplate(TextService text, List<String> segments, List<String> problems) {
        try {
            return text.compile(segments);
        } catch (RuntimeException error) {
            problems.add("Cannot compile message template " + String.join("{...}", segments) + ": " + error.getMessage());
            return null;
        }
    }
}
