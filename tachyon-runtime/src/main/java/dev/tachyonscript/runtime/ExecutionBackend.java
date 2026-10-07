package dev.tachyonscript.runtime;

/** Execution strategy selected explicitly when a module is linked. */
public enum ExecutionBackend {
    INTERPRETER("interpreter"),
    BYTECODE("bytecode");

    private final String id;

    ExecutionBackend(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }
}
