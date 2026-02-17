package no.nav.helsemelding.state.model

sealed interface NextStateDecision {
    data object Unchanged : NextStateDecision {
        override fun toString() = "UNCHANGED"
    }

    data class Transition(val to: MessageDeliveryState) : NextStateDecision

    sealed interface Rejected : NextStateDecision {
        data object Transport : Rejected {
            override fun toString() = "TRANSPORT_REJECTED"
        }

        data object AppRec : Rejected {
            override fun toString() = "APP_REC_REJECTED"
        }
    }
}
