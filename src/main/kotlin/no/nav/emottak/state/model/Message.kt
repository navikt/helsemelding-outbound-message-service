package no.nav.emottak.state.model

import kotlin.uuid.Uuid

data class Message(
    val messageId: Uuid,
    val envelope: ByteArray
)
