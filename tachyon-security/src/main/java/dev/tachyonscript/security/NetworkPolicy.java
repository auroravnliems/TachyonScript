package dev.tachyonscript.security;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;

/** DNS/address validation runs only on a network worker. Redirects must pass the same policy. */
public final class NetworkPolicy {
    private final Set<String> allowed;
    public NetworkPolicy(Set<String> allowed) { this.allowed = Set.copyOf(allowed); }

    public void validate(URI uri) throws IOException {
        if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null)
            throw new IOException("Unsupported or credential-bearing request URL");
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (allowed.contains(host)) return;
        if (privateHost(host)) throw new IOException("Private network destination denied");
        for (InetAddress address : InetAddress.getAllByName(host))
            if (privateAddress(address.getAddress())) throw new IOException("Private resolved destination denied");
    }

    public static boolean privateHost(String host) {
        String value = host.toLowerCase(Locale.ROOT).replaceFirst("\\.$", "");
        if (Set.of("localhost", "metadata.google.internal", "instance-data.ec2.internal").contains(value)
                || value.endsWith(".localhost") || value.endsWith(".local") || value.endsWith(".internal")) return true;
        if (value.startsWith("[") && value.endsWith("]")) value = value.substring(1, value.length() - 1);
        if (value.indexOf(':') >= 0 || value.matches("[0-9.]+")) {
            try { return privateAddress(InetAddress.getByName(value).getAddress()); }
            catch (UnknownHostException e) { return true; }
        }
        // Obfuscated numeric IPv4 forms accepted by some HTTP stacks are not permitted.
        return value.matches("(?i)(0x[0-9a-f]+|[0-9]+)(\\.(0x[0-9a-f]+|[0-9]+))*");
    }

    public static boolean privateAddress(byte[] address) {
        if (address.length == 4) {
            int a = address[0] & 255, b = address[1] & 255;
            return a == 0 || a == 10 || a == 127 || a >= 224 || a == 169 && b == 254
                    || a == 172 && b >= 16 && b <= 31 || a == 192 && (b == 168 || b == 0)
                    || a == 100 && b >= 64 && b <= 127 || a == 198 && (b == 18 || b == 19);
        }
        if (address.length != 16) return true;
        boolean zero = true;
        for (int i = 0; i < 15; i++) zero &= address[i] == 0;
        int first = address[0] & 255, second = address[1] & 255;
        return zero || (first & 254) == 252 || first == 254 && (second & 192) >= 128 || first == 255;
    }
}
