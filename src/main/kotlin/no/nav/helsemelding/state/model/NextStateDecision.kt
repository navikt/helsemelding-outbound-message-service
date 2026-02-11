package no.nav.helsemelding.state.model

sealed interface NextStateDecision {
    data object Unchanged : NextStateDecision
    data class Transition(val to: MessageDeliveryState) : NextStateDecision
}
