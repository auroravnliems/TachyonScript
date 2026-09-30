package dev.tachyonscript.api.storage;

/**
 * Resolves the named constants of a keyed type (for example {@code Material.DIAMOND}, whose
 * key is {@code minecraft:diamond}) to platform objects and back.
 *
 * <p>The linker calls {@link #resolve} once per constant used by a script, when the script is
 * loaded; running scripts never look keys up.
 */
public interface KeyedValues {

    /** The platform object for a key such as {@code minecraft:diamond}, or {@code null} if unknown. */
    Object resolve(String key);

    /** The key of a value of the type, such as {@code minecraft:diamond}. */
    String keyOf(Object value);
}
