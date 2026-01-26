package no.nav.helsemelding.state.plugin

import io.ktor.server.application.Application
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.micrometer.prometheus.PrometheusMeterRegistry
import no.nav.helsemelding.payloadsigning.client.HttpPayloadSigningClient
import no.nav.helsemelding.payloadsigning.client.scopedAuthHttpClient
import no.nav.helsemelding.payloadsigning.model.Direction
import no.nav.helsemelding.payloadsigning.model.PayloadRequest
import org.slf4j.LoggerFactory

fun Application.configureRoutes(
    registry: PrometheusMeterRegistry
) {
    routing { internalRoutes(registry) }
}

fun Route.internalRoutes(registry: PrometheusMeterRegistry) {
    get("/prometheus") {
        call.respond(registry.scrape())
    }
    route("/internal") {
        get("/health/liveness") {
            call.respondText("I'm alive! :)")
        }
        get("/health/readiness") {
            call.respondText("I'm ready! :)")
        }
    }

    get("/signing-test") {
        val log = LoggerFactory.getLogger("no.nav.helsemelding.state.App")

        val scope = "api://dev-gcp.helsemelding.payload-signing-service/.default"
        val payloadSigningServiceUrl = "https://helsemelding-payload-signing-service.intern.dev.nav.no"

        val scopedClient = scopedAuthHttpClient(scope)
        val payloadSigningClient = HttpPayloadSigningClient(scopedClient, payloadSigningServiceUrl)

        // 1
        val payloadRequestOut = PayloadRequest(
            direction = Direction.OUT,
            bytes = "<MsgHead><Body>hello world</Body></MsgHead>".toByteArray()
        )

        try {
            val response = payloadSigningClient.signPayload(payloadRequestOut)
            log.info("Signing test: 1 Response from signing service: Left: ${response.leftOrNull()} Right: ${response.getOrNull()}")
            log.info("Signing test: 1 test succeeded signPayload(OUT)")
        } catch (e: Exception) {
            log.error("Signing test: 1 Exception occurred while calling signing service", e)
            log.info("Signing test: 1 test failed signPayload(OUT)")
        }

        // 2
        val payloadRequestIn = PayloadRequest(
            direction = Direction.IN,
            bytes = "<MsgHead><Body>hello world</Body></MsgHead>".toByteArray()
        )

        try {
            val response = payloadSigningClient.signPayload(payloadRequestIn)
            log.info("Signing test: 1 Response from signing service: Left: ${response.leftOrNull()} Right: ${response.getOrNull()}")
            log.info("Signing test: 1 test succeeded signPayload(IN)")
        } catch (e: Exception) {
            log.error("Signing test: 1 Exception occurred while calling signing service", e)
            log.info("Signing test: 1 test failed signPayload(IN)")
        }
    }
}
