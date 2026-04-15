package no.nav.helsemelding.outbound.model

import kotlinx.serialization.Serializable

@Serializable
enum class MessageStatus {
    NEW,
    PENDING_TRANSPORT,
    PENDING_APPREC,
    COMPLETED,
    REJECTED_APPREC,
    REJECTED_TRANSPORT,
    INVALID
}
