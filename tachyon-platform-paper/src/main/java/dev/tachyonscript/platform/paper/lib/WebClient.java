package dev.tachyonscript.platform.paper.lib;

import dev.tachyonscript.api.natives.ScriptError;
import dev.tachyonscript.api.natives.ScriptFunction;
import dev.tachyonscript.platform.paper.PaperContext;
import java.net.URI;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Bounded background HTTP; requests are owned/cancelled by their script and callbacks obey retirement. */
public final class WebClient implements AutoCloseable {
    private final PaperContext context;
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(256), runnable -> {
                Thread thread = new Thread(runnable, "TachyonScript-Web"); thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    public WebClient(PaperContext context) { this.context = context; }
    public void request(String method, String url, String body, String contentType, ScriptFunction callback) {
        URI uri;
        try { uri = URI.create(url); }
        catch (IllegalArgumentException e) { throw new ScriptError("Invalid web address (value withheld)."); }
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme()))
            throw new ScriptError("Only HTTP and HTTPS web addresses are supported.");
        var options = context.securityOptions();
        var call = new java.util.concurrent.atomic.AtomicReference<okhttp3.Call>();
        try {
            var future = workers.submit(() -> {
                try (SecureWebTransport transport = new SecureWebTransport(options)) {
                    var result = transport.request(method, uri, body, contentType, active -> {
                        call.set(active);
                        if (Thread.currentThread().isInterrupted()) active.cancel();
                    });
                    context.global(() -> context.callback(callback, result.status(), result.body()));
                } catch (java.io.IOException | RuntimeException e) {
                    context.global(() -> context.callback(callback, -1, "Web request failed or was denied by security policy (details withheld)."));
                }
            });
            context.own(future, value -> {
                ((java.util.concurrent.Future<?>) value).cancel(true);
                okhttp3.Call active = call.get(); if (active != null) active.cancel();
            });
        } catch (java.util.concurrent.RejectedExecutionException e) { throw new ScriptError("Web request queue limit exceeded."); }
    }
    @Override public void close() { workers.shutdownNow(); }
}
