package no.nav.helsemelding.state.model

data class MessageStateSnapshot(
    val messageState: MessageState,
    val messageStateChange: List<MessageStateChange>
)
