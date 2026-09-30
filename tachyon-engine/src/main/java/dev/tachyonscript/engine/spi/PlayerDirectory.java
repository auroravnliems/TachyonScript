package dev.tachyonscript.engine.spi;

import java.util.UUID;

/**
 * Identifies players for {@code playerdata} variables: the platform's player and offline player
 * objects are keyed by their UUID.
 */
public interface PlayerDirectory {

    /** The UUID of a player or offline player object. */
    UUID id(Object player);

    /** The name of a player or offline player object, or its UUID as text if unknown. */
    String name(Object player);
}
