package no.nav.helsemelding.outbound.model

import kotlin.uuid.Uuid

data class EdiStatusMessage(
    val messageId: Uuid,
    val status: EdiStatusEvent,
    val acknowledge: suspend () -> Unit
)
