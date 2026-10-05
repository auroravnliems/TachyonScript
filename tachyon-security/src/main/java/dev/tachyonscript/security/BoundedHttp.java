package dev.tachyonscript.security;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class BoundedHttp {
    private BoundedHttp() { }

    static String post(HttpClient client, URI uri, Map<String, String> headers, String body,
                       Duration timeout, int limit, int retries) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(timeout)
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        headers.forEach(builder::header);
        HttpRequest request = builder.build();
        for (int attempt = 0; ; attempt++) {
            var future = client.sendAsync(request, ignored -> new LimitedBody(limit));
            HttpResponse<byte[]> response;
            try { response = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS); }
            catch (ExecutionException | TimeoutException e) {
                future.cancel(true);
                if (attempt < retries) { Thread.sleep(Math.min(2_000, 250L << attempt)); continue; }
                throw new IOException("Security HTTP request failed or timed out");
            } catch (InterruptedException e) { future.cancel(true); throw e; }
            if (response.statusCode() >= 200 && response.statusCode() < 300)
                return new String(response.body(), StandardCharsets.UTF_8);
            if (attempt < retries && (response.statusCode() == 429 || response.statusCode() >= 500)) {
                long delay = Math.min(2_000, 250L << attempt);
                try { delay = Math.min(5_000, Math.max(delay, (long) (Double.parseDouble(response.headers().firstValue("Retry-After").orElse("0")) * 1000))); }
                catch (NumberFormatException ignored) { }
                Thread.sleep(delay);
                continue;
            }
            throw new IOException("Security HTTP status " + response.statusCode());
        }
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private Flow.Subscription subscription;
        LimitedBody(int limit) { this.limit = limit; }
        @Override public CompletionStage<byte[]> getBody() { return body; }
        @Override public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if ((long) out.size() + buffer.remaining() > limit) {
                    subscription.cancel(); body.completeExceptionally(new IOException("Security response exceeds size limit")); return;
                }
                byte[] bytes = new byte[buffer.remaining()]; buffer.get(bytes); out.writeBytes(bytes);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable failure) { body.completeExceptionally(failure); }
        @Override public void onComplete() { body.complete(out.toByteArray()); }
    }
}
