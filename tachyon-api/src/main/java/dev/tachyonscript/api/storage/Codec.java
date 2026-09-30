package dev.tachyonscript.api.storage;

/**
 * Converts values of one storable type to text and back, for {@code persistent} and
 * {@code playerdata} variables.
 *
 * <p>The text is what the database stores, so an encoding must stay readable by later
 * versions: change it only in backward-compatible ways. Codecs must be thread-safe; they run
 * on storage threads.
 */
public interface Codec {

    /** Encodes a non-null value of the type. */
    String encode(Object value);

    /**
     * Decodes text produced by {@link #encode}.
     *
     * @throws IllegalArgumentException if the text is not a valid encoding (for example a
     *                                  world that no longer exists)
     */
    Object decode(String text);
}
