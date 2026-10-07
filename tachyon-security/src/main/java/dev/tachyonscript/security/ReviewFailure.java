package dev.tachyonscript.security;

import java.io.IOException;

/** Safe transport/protocol reasons: never retain remote response bodies, endpoints or credentials. */
final class ReviewFailure extends IOException {
    ReviewFailure(String reason) { super(reason); }

    static ReviewFailure status(int status) {
        String hint = switch (status) {
            case 401, 403 -> "check the API key and provider permissions";
            case 402 -> "check provider credits and the output-token budget";
            case 404 -> "check the configured endpoint and model";
            case 429 -> "provider rate limit reached";
            default -> status >= 500 ? "provider temporarily unavailable" : "provider rejected the request";
        };
        return new ReviewFailure("HTTP " + status + "; " + hint + ".");
    }
}
