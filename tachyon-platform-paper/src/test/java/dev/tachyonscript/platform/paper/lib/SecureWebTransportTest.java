package dev.tachyonscript.platform.paper.lib;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import dev.tachyonscript.security.SecurityOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class SecureWebTransportTest {

    private static final SecureWebTransport.Resolver LOOPBACK = ignored -> List.of(InetAddress.getByName("127.0.0.1"));

    private static SecurityOptions allowing(String... hosts) {
        var defaults = SecurityOptions.defaults();
        return new SecurityOptions(true, defaults.ai(), defaults.discord(), Set.of(hosts), Map.of(), 25_000, 500);
    }

    @Test void privateDnsAnswerIsRejectedAtTheResolverUsedForConnecting() throws Exception {
        AtomicInteger contacts = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> { contacts.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close(); });
        server.start();
        try (var transport = new SecureWebTransport(SecurityOptions.defaults(), ignored -> List.of(InetAddress.getByName("127.0.0.1")))) {
            assertThrows(IOException.class, () -> transport.request("GET", URI.create("http://public.test:" + server.getAddress().getPort() + "/"), null, null, ignored -> { }));
            assertEquals(0, contacts.get());
        } finally { server.stop(0); }
    }

    @Test void redirectToPrivateNetworkIsRejectedBeforeRequestingTheTarget() throws Exception {
        AtomicInteger contacts = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/start", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + server.getAddress().getPort() + "/private");
            exchange.sendResponseHeaders(302, -1); exchange.close();
        });
        server.createContext("/private", exchange -> { contacts.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close(); });
        server.start();
        try (var transport = new SecureWebTransport(allowing("public.test"), ignored -> List.of(InetAddress.getByName("127.0.0.1")))) {
            assertThrows(IOException.class, () -> transport.request("GET", URI.create("http://public.test:" + server.getAddress().getPort() + "/start"), null, null, ignored -> { }));
            assertEquals(0, contacts.get());
        } finally { server.stop(0); }
    }

    @Test void legitimateAllowlistedRequestRetainsBodyAndHttpStatus() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = "Unicode 😀 response".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try (var transport = new SecureWebTransport(allowing("127.0.0.1"))) {
            var result = transport.request("GET", URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"), null, null, ignored -> { });
            assertEquals(200, result.status()); assertEquals("Unicode 😀 response", result.body());
        } finally { server.stop(0); }
    }

    @Test void mixedPublicAndPrivateDnsAnswersFailClosed() throws Exception {
        assertThrows(UnknownHostException.class, () -> SecureWebTransport.resolve(SecurityOptions.defaults(), ignored -> List.of(
                InetAddress.getByName("93.184.216.34"), InetAddress.getByName("169.254.169.254")), "public.test"));
    }

    // ------------------------------------------------------------------ protocol

    @Test void chunkedGzipBodiesAreDecodedAndStatusesKept() throws Exception {
        byte[] text = "chunked and compressed ✓".repeat(50).getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream zipped = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(zipped)) { gzip.write(text); }
        byte[] body = zipped.toByteArray();
        int half = body.length / 2;
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        response.writeBytes(("HTTP/1.1 201 Created\r\nTransfer-Encoding: chunked\r\nContent-Encoding: gzip\r\n\r\n"
                + Integer.toHexString(half) + ";ext=1\r\n").getBytes(StandardCharsets.US_ASCII));
        response.writeBytes(java.util.Arrays.copyOfRange(body, 0, half));
        response.writeBytes(("\r\n" + Integer.toHexString(body.length - half) + "\r\n").getBytes(StandardCharsets.US_ASCII));
        response.writeBytes(java.util.Arrays.copyOfRange(body, half, body.length));
        response.writeBytes("\r\n0\r\nX-Trailer: ignored\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        try (RawServer server = new RawServer(response.toByteArray());
             var transport = new SecureWebTransport(SecurityOptions.disabled(), LOOPBACK)) {
            var result = transport.request("GET", server.uri("/"), null, null, ignored -> { });
            assertEquals(201, result.status());
            assertEquals(new String(text, StandardCharsets.UTF_8), result.body());
        }
    }

    @Test void requestsUseAnAsciiOriginFormAndDeclareTheirBody() throws Exception {
        try (RawServer server = new RawServer("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok".getBytes(StandardCharsets.US_ASCII));
             var transport = new SecureWebTransport(SecurityOptions.disabled(), LOOPBACK)) {
            var result = transport.request("POST", URI.create("http://public.test:" + server.port() + "/café/x?q=1#fragment"),
                    "{\"a\":\"é\"}", "application/json", ignored -> { });
            assertEquals("ok", result.body());
            String request = server.requests().getFirst();
            assertTrue(request.startsWith("POST /caf%C3%A9/x?q=1 HTTP/1.1\r\n"), request);
            assertTrue(request.contains("\r\nHost: public.test:" + server.port() + "\r\n"), request);
            assertTrue(request.contains("\r\nConnection: close\r\n"), request);
            assertTrue(request.contains("\r\nContent-Type: application/json; charset=utf-8\r\n"), request);
            assertTrue(request.contains("\r\nContent-Length: 10\r\n"), request);
        }
    }

    @Test void headerInjectionThroughTheContentTypeIsRejectedBeforeConnecting() throws Exception {
        try (RawServer server = new RawServer("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
             var transport = new SecureWebTransport(SecurityOptions.disabled(), LOOPBACK)) {
            assertThrows(IOException.class, () -> transport.request("POST", server.uri("/"), "x",
                    "text/plain\r\nX-Injected: yes", ignored -> { }));
            assertThrows(IOException.class, () -> transport.request("TRACE", server.uri("/"), null, null, ignored -> { }));
            assertEquals(0, server.connections());
        }
    }

    @Test void truncatedAndOversizedBodiesFail() throws Exception {
        try (RawServer truncated = new RawServer("HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\nabc".getBytes(StandardCharsets.US_ASCII));
             RawServer declared = new RawServer("HTTP/1.1 200 OK\r\nContent-Length: 3000000\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
             RawServer streamed = new RawServer(oversizedUntilClose());
             RawServer malformed = new RawServer("HTTP/1.1 2xx Broken\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
             var transport = new SecureWebTransport(SecurityOptions.disabled(), LOOPBACK)) {
            assertThrows(IOException.class, () -> transport.request("GET", truncated.uri("/"), null, null, ignored -> { }));
            assertThrows(IOException.class, () -> transport.request("GET", declared.uri("/"), null, null, ignored -> { }));
            assertThrows(IOException.class, () -> transport.request("GET", streamed.uri("/"), null, null, ignored -> { }));
            assertThrows(IOException.class, () -> transport.request("GET", malformed.uri("/"), null, null, ignored -> { }));
        }
    }

    private static byte[] oversizedUntilClose() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes("HTTP/1.1 200 OK\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(new byte[SecureWebTransport.MAX_RESPONSE + 1]);
        return out.toByteArray();
    }

    @Test void redirectsFollowTheMethodRulesAndRefuseCrossOriginBodies() throws Exception {
        List<String> seen = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            seen.add(exchange.getRequestMethod() + " " + exchange.getRequestURI() + " " + body);
            switch (exchange.getRequestURI().getPath()) {
                case "/found" -> { exchange.getResponseHeaders().add("Location", "/landing"); exchange.sendResponseHeaders(302, -1); }
                case "/temporary" -> { exchange.getResponseHeaders().add("Location", "/landing"); exchange.sendResponseHeaders(307, -1); }
                case "/elsewhere" -> {
                    exchange.getResponseHeaders().add("Location", "http://other.test:" + server.getAddress().getPort() + "/landing");
                    exchange.sendResponseHeaders(307, -1);
                }
                default -> { byte[] ok = "done".getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(200, ok.length); exchange.getResponseBody().write(ok); }
            }
            exchange.close();
        });
        server.start();
        String base = "http://public.test:" + server.getAddress().getPort();
        try (var transport = new SecureWebTransport(SecurityOptions.disabled(), LOOPBACK)) {
            assertEquals("done", transport.request("POST", URI.create(base + "/found"), "payload", null, ignored -> { }).body());
            assertEquals(List.of("POST /found payload", "GET /landing "), seen);
            seen.clear();
            assertEquals("done", transport.request("POST", URI.create(base + "/temporary"), "payload", null, ignored -> { }).body());
            assertEquals(List.of("POST /temporary payload", "POST /landing payload"), seen);
            seen.clear();
            assertThrows(IOException.class, () -> transport.request("POST", URI.create(base + "/elsewhere"), "secret", null, ignored -> { }));
            assertEquals(List.of("POST /elsewhere secret"), seen, "the body never reaches the other origin");
        } finally { server.stop(0); }
    }

    @Test void silentServersHitTheRequestDeadline() throws Exception {
        try (ServerSocket silent = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
             var transport = new SecureWebTransport(SecurityOptions.disabled(), LOOPBACK, null, TimeUnit.MILLISECONDS.toNanos(400))) {
            Thread accept = holdOpen(silent);
            long start = System.nanoTime();
            assertThrows(SocketTimeoutException.class, () -> transport.request("GET",
                    URI.create("http://public.test:" + silent.getLocalPort() + "/"), null, null, ignored -> { }));
            assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(5), "the deadline is enforced");
            accept.interrupt();
        }
    }

    @Test void cancellationClosesTheConnectionFromAnotherThread() throws Exception {
        try (ServerSocket silent = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
             var transport = new SecureWebTransport(SecurityOptions.disabled(), LOOPBACK)) {
            Thread accept = holdOpen(silent);
            AtomicReference<SecureWebTransport.Cancellation> handle = new AtomicReference<>();
            Thread canceller = Thread.ofVirtual().start(() -> {
                try {
                    while (handle.get() == null) Thread.sleep(5);
                    Thread.sleep(200);
                    handle.get().cancel();
                } catch (InterruptedException ignored) { }
            });
            long start = System.nanoTime();
            assertThrows(IOException.class, () -> transport.request("GET",
                    URI.create("http://public.test:" + silent.getLocalPort() + "/"), null, null, handle::set));
            assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(5), "cancellation is prompt");
            canceller.join();
            accept.interrupt();
        }
    }

    /** Accepts one connection and never answers; returns when the client closes it. */
    private static Thread holdOpen(ServerSocket server) {
        return Thread.ofVirtual().start(() -> {
            try (Socket client = server.accept()) {
                client.getInputStream().readAllBytes();
            } catch (IOException ignored) {
                // The client closed or reset the connection.
            }
        });
    }

    // ------------------------------------------------------------------ TLS

    @Test void tlsVerifiesTheCertificateAgainstTheHostName(@TempDir Path folder) throws Exception {
        SSLContext matching = selfSigned(folder.resolve("localhost.p12"), "dns:localhost");
        SSLContext other = selfSigned(folder.resolve("other.p12"), "dns:other.test");
        for (SSLContext context : List.of(matching, other)) {
            HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setHttpsConfigurator(new HttpsConfigurator(context));
            server.createContext("/", exchange -> {
                byte[] body = "secure".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
            });
            server.start();
            try (var transport = new SecureWebTransport(allowing("localhost"), LOOPBACK, context.getSocketFactory(),
                    TimeUnit.SECONDS.toNanos(30))) {
                URI uri = URI.create("https://localhost:" + server.getAddress().getPort() + "/");
                if (context == matching) {
                    assertEquals("secure", transport.request("GET", uri, null, null, ignored -> { }).body());
                } else {
                    // The certificate is trusted but was issued for another name: endpoint identification refuses it.
                    assertThrows(IOException.class, () -> transport.request("GET", uri, null, null, ignored -> { }));
                }
            } finally { server.stop(0); }
        }
    }

    /** A key store with one self-signed certificate (made by the JDK's keytool) as both key and trust material. */
    private static SSLContext selfSigned(Path store, String subjectAlternativeName) throws Exception {
        boolean windows = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win");
        Path keytool = Path.of(System.getProperty("java.home"), "bin", windows ? "keytool.exe" : "keytool");
        Process process = new ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "test", "-keyalg", "EC",
                "-groupname", "secp256r1", "-dname", "CN=TachyonScript test", "-ext", "SAN=" + subjectAlternativeName,
                "-validity", "2", "-keystore", store.toString(), "-storetype", "PKCS12",
                "-storepass", "changeit", "-keypass", "changeit")
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0, output);
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(store)) { keys.load(in, "changeit".toCharArray()); }
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keys, "changeit".toCharArray());
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(keys);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagers.getKeyManagers(), trust.getTrustManagers(), null);
        return context;
    }

    /** Answers every connection with fixed bytes after reading the request head, recording the head. */
    private static final class RawServer implements AutoCloseable {
        private final ServerSocket socket;
        private final List<String> requests = new CopyOnWriteArrayList<>();
        private final AtomicInteger connections = new AtomicInteger();
        private final Thread thread;

        RawServer(byte[] response) throws IOException {
            socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            thread = Thread.ofVirtual().start(() -> {
                while (!socket.isClosed()) {
                    try (Socket client = socket.accept()) {
                        connections.incrementAndGet();
                        client.setSoTimeout(5_000);
                        requests.add(head(client.getInputStream()));
                        OutputStream out = client.getOutputStream();
                        out.write(response);
                        out.flush();
                    } catch (IOException ignored) {
                        // Closed by the test, or the client gave up early.
                    }
                }
            });
        }

        private static String head(InputStream in) throws IOException {
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            int matched = 0;
            int length = -1;
            while (matched < 4) {
                int next = in.read();
                if (next < 0) break;
                head.write(next);
                matched = (next == '\r' && (matched == 0 || matched == 2)) || (next == '\n' && (matched == 1 || matched == 3)) ? matched + 1 : 0;
            }
            String text = head.toString(StandardCharsets.ISO_8859_1);
            for (String line : text.split("\r\n")) {
                if (line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) length = Integer.parseInt(line.substring(15).strip());
            }
            if (length > 0) in.readNBytes(length);
            return text;
        }

        int port() { return socket.getLocalPort(); }
        URI uri(String path) { return URI.create("http://public.test:" + port() + path); }
        List<String> requests() { return requests; }
        int connections() { return connections.get(); }

        @Override public void close() throws IOException {
            socket.close();
            thread.interrupt();
        }
    }
}
