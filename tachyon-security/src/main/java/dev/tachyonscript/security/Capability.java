package dev.tachyonscript.security;

/** Resolved host capabilities, independent of source spelling or aliases. */
public enum Capability {
    CONSOLE_COMMAND, PLAYER_COMMAND, PRIVILEGE, SERVER_CONTROL, NETWORK,
    FILE_READ, FILE_WRITE, FILE_DELETE, DATABASE_QUERY, DATABASE_OPEN,
    SCHEDULE, EVENT_CANCEL, PERMISSION_CHECK, EXTERNAL_DATA, WORLD_READ, WORLD_WRITE, IO, PURE, UNKNOWN
}
