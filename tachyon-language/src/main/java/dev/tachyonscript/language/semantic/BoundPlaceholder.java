package dev.tachyonscript.language.semantic;

import dev.tachyonscript.language.source.Span;

/**
 * A {@code placeholder} declaration, available to other plugins through PlaceholderAPI as
 * {@code tys_NAME} and {@code tys_NAME_ARGUMENT}.
 *
 * @param name     placeholder name
 * @param function the body; parameters {@code player: OfflinePlayer?} and {@code argument: string}, returns string
 * @param span     the declaration
 */
public record BoundPlaceholder(String name, BoundFunction function, Span span) {
}
