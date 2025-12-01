package no.nav.emottak.state.model

import kotlin.uuid.Uuid

data class DialogMessage(
    val id: Uuid,
    val payload: ByteArray
)
