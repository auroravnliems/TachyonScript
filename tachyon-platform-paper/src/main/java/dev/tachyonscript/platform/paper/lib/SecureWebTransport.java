package dev.tachyonscript.platform.paper.lib;

import dev.tachyonscript.security.NetworkPolicy;
import dev.tachyonscript.security.SecurityAnalyzer;
import dev.tachyonscript.security.SecurityOptions;
import okhttp3.Call;
import okhttp3.Dns;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

/** The resolver's validated addresses are the actual connect addresses, closing the rebinding race. */
public final class SecureWebTransport implements AutoCloseable {
    public record Result(int status, String body) { }
    private static final int MAX_RESPONSE = 2_097_152;
    private final OkHttpClient client;
    private final SecurityOptions options;
    public SecureWebTransport(SecurityOptions options) { this(options, Dns.SYSTEM); }
    // Resolver injection is only for integration tests, never accepted from scripts or AI.
    SecureWebTransport(SecurityOptions options, Dns resolver) {
        this.options = options;
        client = new OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(10)).readTimeout(Duration.ofSeconds(30))
                .callTimeout(Duration.ofSeconds(30)).followRedirects(false).followSslRedirects(false).proxy(Proxy.NO_PROXY)
                .dns(host -> resolve(options, resolver, host)).build();
    }
    static List<InetAddress> resolve(SecurityOptions options, Dns resolver, String host) throws UnknownHostException {
        List<InetAddress> addresses = resolver.lookup(host);
        if (options.enabled() && !options.allowedNetworkHosts().contains(host.toLowerCase(java.util.Locale.ROOT))) {
            if (NetworkPolicy.privateHost(host) || addresses.stream().anyMatch(a -> NetworkPolicy.privateAddress(a.getAddress())))
                throw new UnknownHostException("Security policy denied a private network destination");
        }
        return List.copyOf(addresses);
    }
    public Result request(String method, URI uri, String body, String contentType, Consumer<Call> track) throws IOException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        for (int redirect = 0; redirect <= 5; redirect++) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Web request cancelled");
            if (options.enabled() && SecurityAnalyzer.unsafeUrl(uri.toString(), options.allowedNetworkHosts()))
                throw new IOException("Security policy denied an unsafe request URL");
            Request.Builder builder = new Request.Builder().url(uri.toString()).header("User-Agent", "TachyonScript");
            builder.method(method, body == null ? null : RequestBody.create(body, MediaType.get(contentType == null || contentType.isBlank() ? "text/plain; charset=utf-8" : contentType)));
            Call call = client.newCall(builder.build());
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new IOException("Web request deadline exceeded");
            call.timeout().timeout(remaining, java.util.concurrent.TimeUnit.NANOSECONDS);
            track.accept(call);
            try (Response response = call.execute()) {
                if (response.code() >= 300 && response.code() <= 399 && response.header("Location") != null) {
                    if (redirect == 5) throw new IOException("Web redirect limit exceeded");
                    URI next = uri.resolve(response.header("Location"));
                    if (uri.getScheme().equals("https") && !next.getScheme().equals("https")) throw new IOException("HTTPS downgrade redirect denied");
                    if (body != null && !uri.getAuthority().equals(next.getAuthority())) throw new IOException("Cross-origin body redirect denied");
                    if (response.code() == 303 || (response.code() == 301 || response.code() == 302) && method.equals("POST")) { method = "GET"; body = null; }
                    uri = next; continue;
                }
                if (response.body() == null) return new Result(response.code(), "");
                try (var stream = response.body().byteStream()) {
                    byte[] bytes = stream.readNBytes(MAX_RESPONSE + 1);
                    if (bytes.length > MAX_RESPONSE) throw new IOException("Web response exceeds 2 MB limit");
                    return new Result(response.code(), new String(bytes, StandardCharsets.UTF_8));
                }
            }
        }
        throw new IOException("Web redirect limit exceeded");
    }
    @Override public void close() { client.dispatcher().cancelAll(); client.connectionPool().evictAll(); client.dispatcher().executorService().shutdownNow(); }
}
