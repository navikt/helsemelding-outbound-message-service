package no.nav.helsemelding.state.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonIgnoreUnknownKeys
import no.nav.helsemelding.ediadapter.model.ApprecInfo
import kotlin.time.Instant
import kotlin.uuid.Uuid

@Serializable
@JsonClassDiscriminator("type")
sealed interface StatusMessage

@Serializable
@SerialName("apprec")
@JsonIgnoreUnknownKeys
data class ApprecStatusMessage(
    val messageId: Uuid,
    val timestamp: Instant,
    val apprec: ApprecInfo
) : StatusMessage

@Serializable
@SerialName("transport")
@JsonIgnoreUnknownKeys
data class TransportStatusMessage(
    val messageId: Uuid,
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
