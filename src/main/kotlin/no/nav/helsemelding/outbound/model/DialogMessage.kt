package no.nav.helsemelding.outbound.model

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
data class DialogMessage(
    val id: Uuid, // TODO: Might be OriginalMsgId
    val payload: ByteArray
)
