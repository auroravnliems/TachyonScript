package dev.tachyonscript.platform.paper.lib;

import dev.tachyonscript.security.NetworkPolicy;
import dev.tachyonscript.security.SecurityAnalyzer;
import dev.tachyonscript.security.SecurityOptions;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;

/**
 * HTTP/1.1 for script web requests, on plain JDK sockets so the plugin ships no HTTP library.
 *
 * <p>Why not {@code java.net.http}: the security policy validates the addresses a host name
 * resolves to, and the JDK client would resolve the name again when it connects. A rebinding
 * DNS server can answer the second lookup with a private address. Here the addresses the
 * policy validated are the addresses connected to, and TLS still verifies the certificate
 * against the host name (SNI and HTTPS endpoint identification).
 *
 * <p>Each request uses its own connection ({@code Connection: close}) without a proxy. Status
 * line, headers and body are bounded, one deadline covers connecting, TLS, writing and reading,
 * and redirects are followed by hand so every hop passes the same checks.
 */
public final class SecureWebTransport implements AutoCloseable {

    public record Result(int status, String body) { }

    /** Resolves host names; replaced only by tests, never by scripts or AI output. */
    @FunctionalInterface
    interface Resolver {
        List<InetAddress> lookup(String host) throws UnknownHostException;
    }

    /** Cancels a request in flight by closing its connection; safe from any thread. */
    @FunctionalInterface
    public interface Cancellation {
        void cancel();
    }

    static final int MAX_RESPONSE = 2_097_152;
    private static final int MAX_REDIRECTS = 5;
    private static final int MAX_LINE = 8_192;
    private static final int MAX_HEADER_BYTES = 65_536;
    private static final int MAX_HEADERS = 128;
    private static final int MAX_INTERIM_RESPONSES = 8;
    private static final long CONNECT_MILLIS = 10_000;
    private static final long READ_MILLIS = 30_000;
    private static final long CALL_NANOS = TimeUnit.SECONDS.toNanos(30);
    private static final Resolver SYSTEM = host -> List.of(InetAddress.getAllByName(host));

    /**
     * Closes connections whose request outlived its deadline. Socket reads have a timeout but
     * writes do not, so a server that stops reading could otherwise hold a worker forever.
     * The thread exists only while requests are running.
     */
    private static final class Deadlines {
        static final ScheduledThreadPoolExecutor TIMER = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "TachyonScript-Web-Deadline");
            thread.setDaemon(true);
            return thread;
        });

        static {
            TIMER.setRemoveOnCancelPolicy(true);
            TIMER.setKeepAliveTime(30, TimeUnit.SECONDS);
            TIMER.allowCoreThreadTimeOut(true);
        }
    }

    private final SecurityOptions options;
    private final Resolver resolver;
    private final SSLSocketFactory tls;
    private final long callNanos;
    private final Set<Call> active = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    public SecureWebTransport(SecurityOptions options) {
        this(options, SYSTEM, null, CALL_NANOS);
    }

    SecureWebTransport(SecurityOptions options, Resolver resolver) {
        this(options, resolver, null, CALL_NANOS);
    }

    /**
     * @param tls       the TLS socket factory, or {@code null} for the JDK default trust store
     * @param callNanos the deadline of a whole request, redirects included
     */
    SecureWebTransport(SecurityOptions options, Resolver resolver, SSLSocketFactory tls, long callNanos) {
        this.options = Objects.requireNonNull(options, "options");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.tls = tls;
        this.callNanos = callNanos;
    }

    /** The validated connect addresses of {@code host}; all of them must pass the policy. */
    static List<InetAddress> resolve(SecurityOptions options, Resolver resolver, String host) throws UnknownHostException {
        List<InetAddress> addresses = resolver.lookup(host);
        if (addresses == null || addresses.isEmpty()) {
            throw new UnknownHostException("No address for the web host");
        }
        if (options.enabled() && !options.allowedNetworkHosts().contains(host.toLowerCase(Locale.ROOT))) {
            if (NetworkPolicy.privateHost(host) || addresses.stream().anyMatch(a -> NetworkPolicy.privateAddress(a.getAddress()))) {
                throw new UnknownHostException("Security policy denied a private network destination");
            }
        }
        return List.copyOf(addresses);
    }

    /**
     * Sends one request, following up to five redirects, and returns the final status and the
     * body decoded as UTF-8. {@code track} receives the request's cancellation before any
     * connection is opened.
     */
    public Result request(String method, URI uri, String body, String contentType, Consumer<Cancellation> track)
            throws IOException {
        String verb = method(method);
        byte[] payload = body == null ? null : body.getBytes(StandardCharsets.UTF_8);
        String type = payload == null ? null : mediaType(contentType);
        Call call = new Call();
        active.add(call);
        ScheduledFuture<?> expiry = Deadlines.TIMER.schedule(call::expire, callNanos, TimeUnit.NANOSECONDS);
        try {
            track.accept(call);
            long deadline = System.nanoTime() + callNanos;
            for (int redirect = 0; ; redirect++) {
                if (closed || call.cancelled || Thread.currentThread().isInterrupted()) {
                    throw new IOException("Web request cancelled");
                }
                if (options.enabled() && SecurityAnalyzer.unsafeUrl(uri.toString(), options.allowedNetworkHosts())) {
                    throw new IOException("Security policy denied an unsafe request URL");
                }
                Response response = exchange(call, verb, uri, payload, type, deadline);
                if (response.location() == null) {
                    return new Result(response.status(), response.body());
                }
                if (redirect == MAX_REDIRECTS) {
                    throw new IOException("Web redirect limit exceeded");
                }
                URI next;
                try {
                    next = uri.resolve(response.location());
                } catch (IllegalArgumentException e) {
                    throw new IOException("Invalid web redirect location");
                }
                String scheme = next.getScheme() == null ? "" : next.getScheme().toLowerCase(Locale.ROOT);
                if (!scheme.equals("http") && !scheme.equals("https")) {
                    throw new IOException("Web redirect to an unsupported scheme denied");
                }
                if (uri.getScheme().equalsIgnoreCase("https") && !scheme.equals("https")) {
                    throw new IOException("HTTPS downgrade redirect denied");
                }
                if (payload != null && !Objects.equals(uri.getRawAuthority(), next.getRawAuthority())) {
                    throw new IOException("Cross-origin body redirect denied");
                }
                int status = response.status();
                if (status == 303 && !verb.equals("HEAD") || (status == 301 || status == 302) && verb.equals("POST")) {
                    verb = "GET";
                    payload = null;
                    type = null;
                }
                uri = next;
            }
        } catch (IOException e) {
            if (call.expired) {
                throw new SocketTimeoutException("Web request deadline exceeded");
            }
            throw e;
        } finally {
            expiry.cancel(false);
            active.remove(call);
            call.cancel();
        }
    }

    /** One request/response on a new connection. */
    private Response exchange(Call call, String method, URI uri, byte[] payload, String type, long deadline) throws IOException {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        boolean secure = scheme.equals("https");
        if (!secure && !scheme.equals("http")) {
            throw new IOException("Only HTTP and HTTPS web addresses are supported");
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty() || uri.getRawUserInfo() != null || !hostCharacters(host)) {
            throw new IOException("Invalid web address host");
        }
        String name = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        int port = uri.getPort() == -1 ? (secure ? 443 : 80) : uri.getPort();
        if (port < 1 || port > 65_535) {
            throw new IOException("Invalid web address port");
        }
        String target = target(uri);
        List<InetAddress> addresses = resolve(options, resolver, name);
        Socket socket = connect(call, addresses, port, deadline);
        try {
            if (secure) {
                socket = handshake(socket, name, port, deadline);
            }
            socket.setSoTimeout(readTimeout(deadline));
            OutputStream out = socket.getOutputStream();
            out.write(head(method, target, host, port, secure, payload, type));
            if (payload != null) {
                out.write(payload);
            }
            out.flush();
            InputStream in = new BufferedInputStream(new DeadlineInput(socket, socket.getInputStream(), deadline), 8_192);
            return read(in, method);
        } finally {
            close(socket);
        }
    }

    /** Tries the validated addresses in order; only a failed connect moves on to the next one. */
    private static Socket connect(Call call, List<InetAddress> addresses, int port, long deadline) throws IOException {
        IOException failure = null;
        for (InetAddress address : addresses) {
            Socket socket = new Socket(Proxy.NO_PROXY);
            call.attach(socket);
            try {
                socket.connect(new InetSocketAddress(address, port), (int) Math.min(CONNECT_MILLIS, readTimeout(deadline)));
                if (call.cancelled) {
                    throw new IOException("Web request cancelled");
                }
                return socket;
            } catch (IOException e) {
                close(socket);
                failure = e;
                if (call.cancelled || Thread.currentThread().isInterrupted() || deadline - System.nanoTime() <= 0) {
                    break;
                }
            }
        }
        throw new IOException("Cannot connect to the web host", failure);
    }

    private Socket handshake(Socket plain, String name, int port, long deadline) throws IOException {
        SSLSocketFactory factory = tls != null ? tls : (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocket socket = (SSLSocket) factory.createSocket(plain, name, port, true);
        try {
            SSLParameters parameters = socket.getSSLParameters();
            // Without this, JSSE validates the certificate chain but not that it was issued for this host.
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            if (!literal(name)) {
                parameters.setServerNames(List.of(new SNIHostName(name)));
            }
            socket.setSSLParameters(parameters);
            socket.setSoTimeout(readTimeout(deadline));
            socket.startHandshake();
            return socket;
        } catch (IllegalArgumentException e) {
            close(socket);
            throw new IOException("Invalid web address host");
        } catch (IOException | RuntimeException e) {
            close(socket);
            throw e;
        }
    }

    private static byte[] head(String method, String target, String host, int port, boolean secure, byte[] payload,
                               String type) {
        StringBuilder head = new StringBuilder(256).append(method).append(' ').append(target).append(" HTTP/1.1\r\n");
        head.append("Host: ").append(host);
        if (port != (secure ? 443 : 80)) {
            head.append(':').append(port);
        }
        head.append("\r\nUser-Agent: TachyonScript\r\nAccept: */*\r\nAccept-Encoding: gzip\r\nConnection: close\r\n");
        if (payload != null) {
            head.append("Content-Type: ").append(type).append("\r\nContent-Length: ").append(payload.length).append("\r\n");
        } else if (method.equals("POST") || method.equals("PUT") || method.equals("PATCH")) {
            head.append("Content-Length: 0\r\n");
        }
        return head.append("\r\n").toString().getBytes(StandardCharsets.US_ASCII);
    }

    private static Response read(InputStream in, String method) throws IOException {
        int status;
        Headers headers;
        for (int interim = 0; ; interim++) {
            status = status(line(in));
            headers = headers(in);
            if (status >= 200 || status == 101) {
                break;
            }
            if (interim == MAX_INTERIM_RESPONSES) {
                throw new IOException("Too many interim web responses");
            }
        }
        if (status == 101) {
            throw new IOException("Web protocol switches are not supported");
        }
        String location = headers.first("location");
        if (status >= 300 && status <= 399 && location != null) {
            return new Response(status, location, null);
        }
        if (method.equals("HEAD") || status == 204 || status == 304) {
            return new Response(status, null, "");
        }
        byte[] raw = framed(in, headers).readNBytes(MAX_RESPONSE + 1);
        if (raw.length > MAX_RESPONSE) {
            throw new IOException("Web response exceeds 2 MB limit");
        }
        String coding = headers.first("content-encoding");
        if (coding != null && raw.length > 0) {
            switch (coding.strip().toLowerCase(Locale.ROOT)) {
                case "gzip", "x-gzip" -> {
                    // Decompression is bounded by the same limit: a small "gzip bomb" cannot grow past it.
                    try (InputStream unzipped = new GZIPInputStream(new ByteArrayInputStream(raw), 8_192)) {
                        raw = unzipped.readNBytes(MAX_RESPONSE + 1);
                    }
                    if (raw.length > MAX_RESPONSE) {
                        throw new IOException("Web response exceeds 2 MB limit");
                    }
                }
                case "identity", "" -> { }
                default -> throw new IOException("Unsupported web response encoding");
            }
        }
        return new Response(status, null, new String(raw, StandardCharsets.UTF_8));
    }

    /** The response body as sent: chunked, with a length, or until the server closes. */
    private static InputStream framed(InputStream in, Headers headers) throws IOException {
        List<String> transfer = headers.all("transfer-encoding");
        if (!transfer.isEmpty()) {
            if (transfer.size() != 1 || !transfer.getFirst().strip().equalsIgnoreCase("chunked")) {
                throw new IOException("Unsupported web transfer encoding");
            }
            return new ChunkedInput(in);
        }
        List<String> lengths = headers.all("content-length");
        if (!lengths.isEmpty()) {
            long length = length(lengths.getFirst());
            for (String other : lengths) {
                if (length(other) != length) {
                    throw new IOException("Conflicting web response lengths");
                }
            }
            if (length > MAX_RESPONSE) {
                throw new IOException("Web response exceeds 2 MB limit");
            }
            return new LimitedInput(in, length);
        }
        return in;
    }

    private static long length(String value) throws IOException {
        String digits = value.strip();
        if (digits.isEmpty() || digits.length() > 18) {
            throw new IOException("Invalid web response length");
        }
        long length = 0;
        for (int i = 0; i < digits.length(); i++) {
            char c = digits.charAt(i);
            if (c < '0' || c > '9') {
                throw new IOException("Invalid web response length");
            }
            length = length * 10 + (c - '0');
        }
        return length;
    }

    private static int status(String line) throws IOException {
        // "HTTP/1.1 200 OK"; HTTP/2 is never negotiated (no ALPN), so only 1.x can answer.
        if (!line.startsWith("HTTP/1.") || line.length() < 12 || line.charAt(8) != ' ') {
            throw new IOException("Malformed web response status");
        }
        int status = 0;
        for (int i = 9; i < 12; i++) {
            char c = line.charAt(i);
            if (c < '0' || c > '9') {
                throw new IOException("Malformed web response status");
            }
            status = status * 10 + (c - '0');
        }
        if (line.length() > 12 && line.charAt(12) != ' ' || status < 100) {
            throw new IOException("Malformed web response status");
        }
        return status;
    }

    private static Headers headers(InputStream in) throws IOException {
        Map<String, List<String>> values = new HashMap<>();
        int bytes = 0;
        int count = 0;
        while (true) {
            String line = line(in);
            if (line.isEmpty()) {
                return new Headers(values);
            }
            bytes += line.length() + 2;
            if (++count > MAX_HEADERS || bytes > MAX_HEADER_BYTES) {
                throw new IOException("Web response headers exceed limits");
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                throw new IOException("Malformed web response header");
            }
            for (int i = 0; i < colon; i++) {
                char c = line.charAt(i);
                // Header names are tokens; this also rejects obsolete line folding.
                if (c <= ' ' || c >= 0x7F || "\"(),/;<=>?@[\\]{}".indexOf(c) >= 0) {
                    throw new IOException("Malformed web response header");
                }
            }
            values.computeIfAbsent(line.substring(0, colon).toLowerCase(Locale.ROOT), key -> new ArrayList<>(1))
                    .add(line.substring(colon + 1).strip());
        }
    }

    /** One CRLF (or bare LF) terminated line in ISO-8859-1, without the terminator. */
    private static String line(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder(64);
        while (true) {
            int next = in.read();
            if (next < 0) {
                throw new EOFException("Web connection closed early");
            }
            if (next == '\n') {
                int length = line.length();
                if (length > 0 && line.charAt(length - 1) == '\r') {
                    line.setLength(length - 1);
                }
                return line.toString();
            }
            if (line.length() == MAX_LINE) {
                throw new IOException("Web response line exceeds limit");
            }
            line.append((char) next);
        }
    }

    private static String method(String method) throws IOException {
        String upper = method == null ? "" : method.toUpperCase(Locale.ROOT);
        return switch (upper) {
            case "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD" -> upper;
            default -> throw new IOException("Unsupported web request method");
        };
    }

    /**
     * The Content-Type of a body: script-controlled, so restricted to visible ASCII (no header
     * injection). The body is always UTF-8, which a type without a charset then states.
     */
    static String mediaType(String contentType) throws IOException {
        String type = contentType == null || contentType.isBlank() ? "text/plain; charset=utf-8" : contentType.strip();
        if (type.length() > 256 || type.indexOf('/') <= 0) {
            throw new IOException("Invalid content type");
        }
        for (int i = 0; i < type.length(); i++) {
            char c = type.charAt(i);
            if (c < 0x20 && c != '\t' || c >= 0x7F) {
                throw new IOException("Invalid content type");
            }
        }
        return type.toLowerCase(Locale.ROOT).contains("charset=") ? type : type + "; charset=utf-8";
    }

    /** Origin-form request target in ASCII: path (or "/") and query, never the fragment. */
    static String target(URI uri) throws IOException {
        URI ascii;
        try {
            ascii = URI.create(uri.toASCIIString());
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid web address path");
        }
        String path = ascii.getRawPath();
        String target = (path == null || path.isEmpty() ? "/" : path) + (ascii.getRawQuery() == null ? "" : "?" + ascii.getRawQuery());
        if (target.charAt(0) != '/') {
            throw new IOException("Invalid web address path");
        }
        for (int i = 0; i < target.length(); i++) {
            char c = target.charAt(i);
            if (c <= ' ' || c >= 0x7F) {
                throw new IOException("Invalid web address path");
            }
        }
        return target;
    }

    private static boolean hostCharacters(String host) {
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (!(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '.' || c == '-'
                    || c == ':' || c == '[' || c == ']' || c == '_')) {
                return false;
            }
        }
        return true;
    }

    private static boolean literal(String name) {
        return name.indexOf(':') >= 0 || name.chars().allMatch(c -> c == '.' || c >= '0' && c <= '9');
    }

    private static int readTimeout(long deadline) throws SocketTimeoutException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            throw new SocketTimeoutException("Web request deadline exceeded");
        }
        return (int) Math.max(1, Math.min(READ_MILLIS, TimeUnit.NANOSECONDS.toMillis(remaining)));
    }

    private static void close(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Closing is best effort; the connection is abandoned either way.
        }
    }

    /** Cancels every request still running on this transport. */
    @Override
    public void close() {
        closed = true;
        for (Call call : active) {
            call.cancel();
        }
    }

    private record Response(int status, String location, String body) { }

    private record Headers(Map<String, List<String>> values) {
        List<String> all(String name) {
            return values.getOrDefault(name, List.of());
        }

        String first(String name) {
            List<String> found = values.get(name);
            return found == null ? null : found.getFirst();
        }
    }

    /** A request's cancellation: closes whichever connection the request is using. */
    private static final class Call implements Cancellation {
        private volatile boolean cancelled;
        private volatile boolean expired;
        private volatile Socket socket;

        void attach(Socket next) throws IOException {
            socket = next;
            if (cancelled) {
                close(next);
                throw new IOException("Web request cancelled");
            }
        }

        void expire() {
            expired = true;
            cancel();
        }

        @Override
        public void cancel() {
            cancelled = true;
            Socket current = socket;
            if (current != null) {
                close(current);
            }
        }
    }

    /** Re-arms the socket's read timeout with the time left before every read. */
    private static final class DeadlineInput extends FilterInputStream {
        private final Socket socket;
        private final long deadline;

        DeadlineInput(Socket socket, InputStream in, long deadline) {
            super(in);
            this.socket = socket;
            this.deadline = deadline;
        }

        @Override
        public int read() throws IOException {
            socket.setSoTimeout(readTimeout(deadline));
            return super.read();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            socket.setSoTimeout(readTimeout(deadline));
            return super.read(buffer, offset, length);
        }
    }

    /** Exactly {@code remaining} bytes; a connection that closes earlier is an error. */
    private static final class LimitedInput extends InputStream {
        private final InputStream in;
        private long remaining;

        LimitedInput(InputStream in, long length) {
            this.in = in;
            this.remaining = length;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 255;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (remaining == 0) {
                return -1;
            }
            if (length == 0) {
                return 0;
            }
            int read = in.read(buffer, offset, (int) Math.min(length, remaining));
            if (read < 0) {
                throw new EOFException("Web response ended early");
            }
            remaining -= read;
            return read;
        }
    }

    /** Transfer-Encoding: chunked, with extensions ignored and trailers bounded and dropped. */
    private static final class ChunkedInput extends InputStream {
        private final InputStream in;
        private long remaining;
        private boolean started;
        private boolean finished;

        ChunkedInput(InputStream in) {
            this.in = in;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 255;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (finished) {
                return -1;
            }
            if (length == 0) {
                return 0;
            }
            if (remaining == 0) {
                if (started && !line(in).isEmpty()) {
                    throw new IOException("Malformed chunked web response");
                }
                started = true;
                remaining = size(line(in));
                if (remaining == 0) {
                    headers(in);
                    finished = true;
                    return -1;
                }
            }
            int read = in.read(buffer, offset, (int) Math.min(length, remaining));
            if (read < 0) {
                throw new EOFException("Web response ended inside a chunk");
            }
            remaining -= read;
            return read;
        }

        private static long size(String line) throws IOException {
            int extension = line.indexOf(';');
            String hex = (extension < 0 ? line : line.substring(0, extension)).strip();
            if (hex.isEmpty() || hex.length() > 8) {
                throw new IOException("Malformed chunked web response");
            }
            long size = 0;
            for (int i = 0; i < hex.length(); i++) {
                int digit = Character.digit(hex.charAt(i), 16);
                if (digit < 0) {
                    throw new IOException("Malformed chunked web response");
                }
                size = size * 16 + digit;
            }
            return size;
        }
    }
}
