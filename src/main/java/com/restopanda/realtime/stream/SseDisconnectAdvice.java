package com.restopanda.realtime.stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;

/**
 * Silences the routine SSE churn that is not an error: every tablet that closes
 * a tab, loses Wi-Fi, or reconnects tears its stream down mid-write, which
 * surfaces as {@link AsyncRequestNotUsableException} (broken pipe). Without this
 * advice each disconnect fell through to the commons catch-all handler, logging
 * a full ERROR stack trace plus a secondary converter failure (it tried to write
 * an {@code ErrorResponse} into a {@code text/event-stream}) — hundreds of
 * misleading errors per day in a busy restaurant, drowning real failures.
 *
 * <p>Ordered ahead of the commons advice so it wins for exactly these types;
 * everything else still reaches the shared handler.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SseDisconnectAdvice {

    private static final Logger log = LoggerFactory.getLogger(SseDisconnectAdvice.class);

    /** Client went away mid-stream — normal SSE lifecycle, log quietly and stop. */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void clientDisconnected(AsyncRequestNotUsableException e) {
        log.debug("SSE client disconnected mid-write: {}", e.getMessage());
    }

    /** Stream hit its configured timeout — the emitter's onTimeout already completed it. */
    @ExceptionHandler(AsyncRequestTimeoutException.class)
    public void streamTimedOut(AsyncRequestTimeoutException e) {
        log.debug("SSE stream timed out (client will re-subscribe)");
    }
}
