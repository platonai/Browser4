package ai.platon.pulsar.rest.api.support

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import org.slf4j.Logger
import org.springframework.http.codec.ServerSentEvent
import reactor.core.publisher.Flux

/**
 * Bridge a task-status [Flow] onto the SSE stream an endpoint returns.
 *
 * `/api/x/{id}/stream` and `/api/commands/{id}/stream` watch a task the same way — poll the
 * owner's cache, push each change, stop when the task is done — and both used to spell out
 * the same create → collect → dispose dance, including the same error log line.
 *
 * Two things are deliberate:
 *
 * - **The collection belongs to the subscription.** A `Flux` is subscribed per client, so
 *   the polling coroutine is launched into [scope] but cancelled from `FluxSink.onDispose`:
 *   a client that goes away stops the polling instead of leaving a coroutine behind, and a
 *   second subscriber gets its own.
 * - **The event shape stays with the caller.** The command stream sends a bare `data`
 *   payload — its JavaScript client parses only that, so an `id` or `event` name would be
 *   noise — while the scrape stream also carries the response's own `id` and `event`. That
 *   difference is a contract, not an oversight, which is why it stays visible at the call
 *   site instead of being decided here.
 *
 * @param scope collects the flow; the long-lived scope of whoever owns the cache, never a
 *   request scope — the task outlives the request that started watching it.
 * @param logger where a failed status flow is reported before the failure reaches the sink.
 * @param map the per-endpoint event shape.
 * @return the stream, one event per value, completed when the flow completes.
 */
fun <T : Any> Flow<T>.toServerSentEvents(
    scope: CoroutineScope,
    logger: Logger,
    map: (T) -> ServerSentEvent<T> = { ServerSentEvent.builder(it).build() },
): Flux<ServerSentEvent<T>> {
    val values: Flux<T> = Flux.create { sink ->
        val job = onEach { sink.next(it) }
            // The stream ends for exactly one reason here, and the cause says which: `null`
            // is the flow finishing on its own, anything else is a failure that the `catch`
            // below is about to report. Completing on a failure too would terminate the sink
            // first and the error would be dropped — a broken task would reach the client as
            // a clean end, which is the one answer it must not get.
            .onCompletion { cause -> if (cause == null) sink.complete() }
            .catch { throwable ->
                // The failure is the status flow's; saying so keeps the log about the task
                // rather than about the client that happened to be watching. Catching it
                // here also keeps it out of the collecting coroutine, where an uncaught
                // failure would only be logged as an abandoned collector.
                logger.error("Error in command status flow", throwable)
                sink.error(throwable)
            }
            .launchIn(scope)

        sink.onDispose { job.cancel() }
    }

    return values.map(map)
}
