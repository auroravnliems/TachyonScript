package dev.tachyonscript.language.semantic;

import dev.tachyonscript.language.source.Span;

/**
 * A task started when the script loads: {@code every 5 minutes { }} ({@code intervalMillis > 0})
 * or {@code at "20:00" { }} ({@code dailyMinute >= 0}).
 *
 * @param intervalMillis repeat interval, or 0 for a daily task
 * @param dailyMinute    minute of the day (server time) for {@code at} tasks, or -1
 * @param async          whether the task runs off the server thread ({@code @async})
 * @param function       the body (no parameters)
 * @param span           the declaration
 */
public record BoundTask(long intervalMillis, int dailyMinute, boolean async, BoundFunction function, Span span) {
}
