package dev.tachyonscript.language.semantic;

import dev.tachyonscript.api.type.Type;
import dev.tachyonscript.language.source.Span;
import dev.tachyonscript.language.syntax.Declaration;

/**
 * A top-level variable of a script: {@code let}/{@code var} (kept in memory while the script is
 * loaded), {@code persistent var} (saved, one value) or {@code playerdata var} (saved, one value
 * per player, read as {@code player.name}). Compared by identity.
 */
public final class GlobalSymbol {

    private final String module;
    private final String name;
    private final Type type;
    private final boolean mutable;
    private final Declaration.Storage storage;
    private final Span declaration;
    private final Declaration.Global syntax;
    private final int order;
    private boolean initialized;

    GlobalSymbol(String module, String name, Type type, boolean mutable, Declaration.Storage storage, Span declaration,
                 Declaration.Global syntax, int order) {
        this.module = module;
        this.name = name;
        this.type = type;
        this.mutable = mutable;
        this.storage = storage;
        this.declaration = declaration;
        this.syntax = syntax;
        this.order = order;
    }

    /** Name of the module declaring the variable. */
    public String module() {
        return module;
    }

    public String name() {
        return name;
    }

    public Type type() {
        return type;
    }

    public boolean isMutable() {
        return mutable;
    }

    public Declaration.Storage storage() {
        return storage;
    }

    public boolean isPlayerData() {
        return storage == Declaration.Storage.PLAYERDATA;
    }

    public Span declaration() {
        return declaration;
    }

    public Declaration.Global syntax() {
        return syntax;
    }

    /** Position among the module's top-level variables. */
    public int order() {
        return order;
    }

    /** Stable identifier: {@code module::name}. */
    public String key() {
        return module + "::" + name;
    }

    /** Whether the initializer of the variable has been bound (used-before-declaration check). */
    boolean isInitialized() {
        return initialized;
    }

    void markInitialized() {
        initialized = true;
    }

    @Override
    public String toString() {
        String prefix = switch (storage) {
            case SCRIPT -> "";
            case PERSISTENT -> "persistent ";
            case PLAYERDATA -> "playerdata ";
        };
        return prefix + (mutable ? "var " : "let ") + name + ": " + type.displayName();
    }
}
