package no.nav.helsemelding.outbound.model

sealed interface CreateStateResult {
    data class Created(val state: MessageState) : CreateStateResult
    data class Existing(val state: MessageState) : CreateStateResult
}
