package no.nav.helsemelding.state.model

sealed interface NextStateDecision {
    data object Unchanged : NextStateDecision {
        override fun toString() = "UNCHANGED"
    }

    data class Transition(val to: MessageDeliveryState) : NextStateDecision {
        override fun toString() = "TRANSITION($to)"
    }

    sealed interface Rejected : NextStateDecision {
        data object Transport : Rejected {
            override fun toString() = "REJECTED(TRANSPORT)"
        }

        data object AppRec : Rejected {
            override fun toString() = "REJECTED(APPREC)"
        }
    }
}
