package no.nav.helsemelding.outbound.model

data class MessageRecord(
    val key: String?,
    val payload: ByteArray,
    val offset: Long,
    val occuredAt: Long
)
