package no.nav.helsemelding.outbound.model

data class ReceivedDialogMessage(
    val message: DialogMessage,
    val acknowledge: suspend () -> Unit
)
