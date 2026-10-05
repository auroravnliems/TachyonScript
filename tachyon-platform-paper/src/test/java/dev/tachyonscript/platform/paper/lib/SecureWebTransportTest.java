package dev.tachyonscript.platform.paper.lib;

import com.sun.net.httpserver.HttpServer;
import dev.tachyonscript.security.SecurityOptions;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class SecureWebTransportTest {
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
        var defaults = SecurityOptions.defaults();
        var options = new SecurityOptions(true, defaults.ai(), defaults.discord(), Set.of("public.test"), Map.of(), 25_000, 500);
        try (var transport = new SecureWebTransport(options, ignored -> List.of(InetAddress.getByName("127.0.0.1")))) {
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
        var defaults = SecurityOptions.defaults();
        var options = new SecurityOptions(true, defaults.ai(), defaults.discord(), Set.of("127.0.0.1"), Map.of(), 25_000, 500);
        try (var transport = new SecureWebTransport(options)) {
            var result = transport.request("GET", URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"), null, null, ignored -> { });
            assertEquals(200, result.status()); assertEquals("Unicode 😀 response", result.body());
        } finally { server.stop(0); }
    }
    @Test void mixedPublicAndPrivateDnsAnswersFailClosed() throws Exception {
        assertThrows(UnknownHostException.class, () -> SecureWebTransport.resolve(SecurityOptions.defaults(), ignored -> List.of(
                InetAddress.getByName("93.184.216.34"), InetAddress.getByName("169.254.169.254")), "public.test"));
    }
}
