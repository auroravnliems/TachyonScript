package dev.tachyonscript.platform.paper.lib;

import dev.tachyonscript.api.natives.ScriptError;
import dev.tachyonscript.api.natives.ScriptFunction;
import dev.tachyonscript.platform.paper.PaperContext;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Web requests for scripts. Requests run in the background; the script's function is then
 * called on the global region thread (the main thread on Paper) with the status code and the
 * body, or with status -1 and the error message when the request failed.
 */
public final class WebClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final PaperContext context;
    private volatile HttpClient client;

    public WebClient(PaperContext context) {
        this.context = context;
    }

    private HttpClient client() {
        HttpClient current = client;
        if (current == null) {
            current = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NORMAL).build();
            client = current;
        }
        return current;
    }

    public void request(String method, String url, String body, String contentType, ScriptFunction callback) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new ScriptError("Invalid web address '" + url + "'.");
        }
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new ScriptError("Only http and https addresses are supported, not '" + url + "'.");
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(TIMEOUT)
                .header("User-Agent", "TachyonScript (" + context.plugin().getName() + ")");
        if (body != null) {
            request.header("Content-Type", contentType == null || contentType.isBlank() ? "text/plain" : contentType);
            request.method(method, HttpRequest.BodyPublishers.ofString(body));
        } else {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        }
        client().sendAsync(request.build(), HttpResponse.BodyHandlers.ofString()).whenComplete((response, error) -> {
            int status = response != null ? response.statusCode() : -1;
            String text = response != null ? response.body()
                    : String.valueOf(error.getCause() != null ? error.getCause().getMessage() : error.getMessage());
            context.global(() -> context.callback(callback, status, text));
        });
    }
}
