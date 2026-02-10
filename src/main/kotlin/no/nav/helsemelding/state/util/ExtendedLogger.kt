package no.nav.helsemelding.state.util

import io.github.oshai.kotlinlogging.KLogger
import io.opentelemetry.api.trace.Span
import org.slf4j.MDC

class ExtendedLogger(private val logger: KLogger) {

    fun debug(msg: () -> Any?) {
        val ctx = Span.current().spanContext
        MDC.putCloseable("trace_id", ctx.traceId).use {
            logger.debug(msg)
        }
    }

    fun info(msg: () -> Any?) {
        val ctx = Span.current().spanContext
        MDC.putCloseable("trace_id", ctx.traceId).use {
            logger.info(msg)
        }
    }

    fun warn(msg: () -> Any?) {
        val ctx = Span.current().spanContext
        MDC.putCloseable("trace_id", ctx.traceId).use {
            logger.warn(msg)
        }
    }

    fun error(msg: () -> Any?) {
        val ctx = Span.current().spanContext
        MDC.putCloseable("trace_id", ctx.traceId).use {
            logger.error(msg)
        }
    }

    fun error(err: Throwable?, msg: () -> Any?) {
        val ctx = Span.current().spanContext
        MDC.putCloseable("trace_id", ctx.traceId).use {
            logger.error(err) { msg }
        }
    }
}
