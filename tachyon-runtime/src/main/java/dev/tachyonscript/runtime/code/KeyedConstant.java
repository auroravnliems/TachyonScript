package dev.tachyonscript.runtime.code;

import dev.tachyonscript.api.type.ClassType;

/**
 * Placeholder in a reference pool for a named constant of a keyed type, such as
 * {@code Material.DIAMOND}. The linker replaces it with the platform object, so using the
 * constant costs nothing while the script runs.
 *
 * @param type the keyed type
 * @param key  the constant's key, e.g. {@code minecraft:diamond}
 */
public record KeyedConstant(ClassType type, String key) {
}
