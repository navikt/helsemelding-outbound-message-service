package no.nav.helsemelding.outbound.model

data class MessageStateSnapshot(
    val messageState: MessageState,
    val messageStateChanges: List<MessageStateChange>
)
