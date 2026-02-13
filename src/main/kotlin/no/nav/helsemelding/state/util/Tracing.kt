package no.nav.helsemelding.state.util

import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import io.opentelemetry.extension.kotlin.asContextElement
import kotlinx.coroutines.withContext

suspend inline fun <T> Tracer.withSpan(
    spanName: String,
    crossinline block: suspend () -> T
): T {
    val span = spanBuilder(spanName).startSpan()
    return try {
        val otelContext = Context.current().with(span)
        withContext(otelContext.asContextElement()) {
            block()
        }
    } finally {
        span.end()
    }
}
