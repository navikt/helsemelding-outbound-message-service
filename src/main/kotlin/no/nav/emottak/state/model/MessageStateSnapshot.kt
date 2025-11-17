package no.nav.emottak.state.model

data class MessageStateSnapshot(
    val messageState: MessageState,
    val messageStateChange: List<MessageStateChange>
)
