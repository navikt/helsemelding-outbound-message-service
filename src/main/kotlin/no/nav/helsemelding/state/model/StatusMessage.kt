package no.nav.helsemelding.state.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonIgnoreUnknownKeys
import no.nav.helsemelding.ediadapter.model.ApprecInfo
import kotlin.time.Instant
import kotlin.uuid.Uuid

@Serializable
sealed interface StatusMessage

@Serializable
@JsonIgnoreUnknownKeys
data class ApprecStatusMessage(
    val messageId: Uuid,
    val source: String = "apprec",
    val timestamp: Instant,
    val apprec: ApprecInfo
) : StatusMessage

@Serializable
@JsonIgnoreUnknownKeys
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

fun StatusMessage.toJson(): String = Json.encodeToString(StatusMessage.serializer(), this)
