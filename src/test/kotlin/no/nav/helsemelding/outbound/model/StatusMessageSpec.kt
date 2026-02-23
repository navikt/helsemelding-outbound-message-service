package no.nav.helsemelding.outbound.model

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import no.nav.helsemelding.ediadapter.model.AppRecStatus.OK
import no.nav.helsemelding.ediadapter.model.ApprecInfo
import no.nav.helsemelding.outbound.model.TransportStatusMessage.TransportError
import kotlin.time.Instant
import kotlin.uuid.Uuid

class StatusMessageSpec : StringSpec(
    {
        fun StatusMessage.asJson(): JsonElement = Json.parseToJsonElement(toJson())

        fun jsonOf(rawJson: String): JsonElement = Json.parseToJsonElement(rawJson.trimIndent())

        "ApprecStatusMessage -> JSON" {
            val statusMessage: StatusMessage =
                ApprecStatusMessage(
                    messageId = Uuid.parse("033345ba-9475-4d12-8c0e-528d28e3e4f8"),
                    timestamp = Instant.parse("2026-02-10T16:34:40.625765228Z"),
                    apprec = ApprecInfo(
                        receiverHerId = 8142519,
                        appRecStatus = OK,
                        appRecErrorList = emptyList()
                    )
                )

            statusMessage.asJson() shouldBe jsonOf(
                """{
                      "type": "apprec",
                      "messageId": "033345ba-9475-4d12-8c0e-528d28e3e4f8",
                      "timestamp": "2026-02-10T16:34:40.625765228Z",
                      "apprec": {
                        "receiverHerId": 8142519,
                        "appRecStatus": "1",
                        "appRecErrorList": []
                      }
                    }"""
            )
        }

        "TransportStatusMessage -> JSON" {
            val statusMessage: StatusMessage =
                TransportStatusMessage(
                    messageId = Uuid.parse("033345ba-9475-4d12-8c0e-528d28e3e4f8"),
                    timestamp = Instant.parse("2026-02-10T16:34:40.625765228Z"),
                    error = TransportError(
                        code = "REJECTED",
                        details = "Message was REJECTED"
                    )
                )

            statusMessage.asJson() shouldBe jsonOf(
                """{
                      "type": "transport",
                      "messageId": "033345ba-9475-4d12-8c0e-528d28e3e4f8",
                      "timestamp": "2026-02-10T16:34:40.625765228Z",
                      "error": {
                        "code": "REJECTED",
                        "details": "Message was REJECTED"
                      }
                    }"""
            )
        }
    }
)
