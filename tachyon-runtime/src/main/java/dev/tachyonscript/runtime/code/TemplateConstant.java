package dev.tachyonscript.runtime.code;

import java.util.List;

/**
 * Placeholder in a reference pool for a message template without runtime arguments. The
 * linker replaces it with the rendered component, so constant messages are built once per
 * load instead of on every execution.
 */
public record TemplateConstant(List<String> segments) {

    public TemplateConstant {
        segments = List.copyOf(segments);
    }
}
