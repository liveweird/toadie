package ch.nokillswit.infra

import kotlinx.coroutines.CancellationException

/**
 * The ONE cancellation-safe catch (ported from Lettuce/Flow): runs [block] and hands any ordinary
 * [Exception] to [onFailure], but rethrows [CancellationException] untouched. Catching a plain
 * [Exception] would also catch cancellation (it extends [Exception]), silently continuing work
 * whose owning coroutine was cancelled instead of unwinding with it — the standard coroutine
 * idiom of never swallowing cancellation. A JVM `Error` is deliberately not caught either.
 * `inline`, so [block] may suspend and [onFailure] may `return@` out of the caller's lambda (the
 * JWT `validate` block does — see `plugins/Security.kt`).
 */
// The one generic catch of this shape: cancellation is rethrown first, everything else goes to the caller's
// onFailure — never swallowed.
@Suppress("TooGenericExceptionCaught")
inline fun <T> catchingFailures(block: () -> T, onFailure: (Exception) -> T): T =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        onFailure(e)
    }
