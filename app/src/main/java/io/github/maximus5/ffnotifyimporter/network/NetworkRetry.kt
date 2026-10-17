package io.github.maximus5.ffnotifyimporter.network

import kotlinx.coroutines.delay
import java.io.IOException

/**
 * Retries [block] up to [maxAttempts] times on [IOException] - a connection failure, timeout, or
 * DNS error, the actual "network error" this exists for - with exponential backoff between
 * attempts (500ms, 1000ms, ...). Deliberately does *not* retry on an HTTP error response (4xx/5xx)
 * - the request reached the server and got an answer, retrying blindly wouldn't change that, and
 * for anything POST-like it risks the exact double-submission this is meant to avoid (see
 * [FireflyApiClient.createTransaction], which never calls this - not retried at all, since
 * Firefly's own API isn't idempotent and a lost response after a successful write would retry
 * into a genuine duplicate). Only used for read-only/idempotent calls: a repeated GET is always
 * safe to retry.
 *
 * [onFailedAttempt] sees every failed attempt (the diagnostic log records them all, so a retry that
 * finally succeeds does not hide the failure before it).
 *
 * The last attempt's [IOException] propagates uncaught if every attempt fails - callers already
 * catch [IOException] around their own call site and turn it into a `Result.Failure`, same as
 * before this existed.
 */
internal suspend inline fun <T> withNetworkRetry(
    maxAttempts: Int = 3,
    onFailedAttempt: (attempt: Int, error: IOException) -> Unit = { _, _ -> },
    block: () -> T,
): T {
    var attempt = 1
    while (true) {
        try {
            return block()
        } catch (e: CleartextBlockedException) {
            throw e
        } catch (e: IOException) {
            onFailedAttempt(attempt, e)
            if (attempt >= maxAttempts) throw e
            delay(RETRY_BASE_DELAY_MILLIS shl (attempt - 1))
            attempt++
        }
    }
}

internal const val RETRY_BASE_DELAY_MILLIS = 500L
