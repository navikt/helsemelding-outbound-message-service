package no.nav.helsemelding.outbound.model

sealed interface NextStateDecision {
    data object Unchanged : NextStateDecision {
        override fun toString() = "UNCHANGED"
    }

    data class Transition(val to: MessageDeliveryState) : NextStateDecision {
        override fun toString() = to.toString()
    }

    sealed interface Pending : NextStateDecision {
        data object Transport : Pending {
            override fun toString() = "PENDING(TRANSPORT)"
        }

        data object AppRec : Pending {
            override fun toString() = "PENDING(APPREC)"
        }
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
