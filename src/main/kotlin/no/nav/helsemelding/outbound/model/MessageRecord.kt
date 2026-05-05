package no.nav.helsemelding.outbound.model

import kotlin.time.Instant

data class MessageRecord(
    val key: String?,
    val payload: ByteArray,
    val offset: Long,
    val createdAt: Instant
)
