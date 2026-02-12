package no.nav.helsemelding.state.model

sealed interface NextStateDecision {
    data object Unchanged : NextStateDecision {
        override fun toString() = "UNCHANGED"
    }

    data class Transition(val to: MessageDeliveryState) : NextStateDecision {
        override fun toString() = to.name
    }
}
