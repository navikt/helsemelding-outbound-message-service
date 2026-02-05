package no.nav.helsemelding.state.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import no.nav.helsemelding.ediadapter.model.ApprecInfo
import kotlin.time.Instant
import kotlin.uuid.Uuid

interface StatusMessage

@Serializable
data class ApprecStatusMessage(
    val messageId: Uuid,
    val source: String = "apprec",
    val timestamp: Instant,
    val apprec: ApprecInfo
) : StatusMessage

@Serializable
data class TransportStatusMessage(
    val messageId: Uuid,
    val source: String = "transport",
    val timestamp: Instant,
    val error: TransportError
) : StatusMessage {
    @Serializable
    data class TransportError(
        val code: String,
        val details: String
    )
}

fun StatusMessage.toJson(): String =
    when (this) {
        is ApprecStatusMessage -> toJson(ApprecStatusMessage.serializer(), this)
        is TransportStatusMessage -> toJson(TransportStatusMessage.serializer(), this)
        else -> error("Unknown StatusMessage type: ${this::class}")
    }

private fun <T> toJson(serializer: KSerializer<T>, value: T): String = Json.encodeToString(serializer, value)
