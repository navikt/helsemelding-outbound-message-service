package no.nav.helsemelding.state.model

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import no.nav.helsemelding.state.model.AppRecStatus.OK
import no.nav.helsemelding.state.model.AppRecStatus.OK_ERROR_IN_MESSAGE_PART
import no.nav.helsemelding.state.model.MessageDeliveryState.COMPLETED
import no.nav.helsemelding.state.model.MessageDeliveryState.INVALID
import no.nav.helsemelding.state.model.MessageDeliveryState.NEW
import no.nav.helsemelding.state.model.MessageDeliveryState.PENDING
import no.nav.helsemelding.state.model.TransportStatus.ACKNOWLEDGED

class DeliveryEvaluationStateSpec : StringSpec(
    {
        fun toDelivery(transport: TransportStatus, appRec: AppRecStatus?) =
            DeliveryEvaluationState(
                transport = transport,
                appRec = appRec
            )

        "NEW transport + null apprec → NEW" {
            toDelivery(TransportStatus.NEW, null).resolveDelivery().decision shouldBe
                NextStateDecision.Transition(NEW)

            toDelivery(TransportStatus.NEW, null).resolveDelivery().pendingReason shouldBe null
        }

        "PENDING transport + null apprec → PENDING(WAITING_FOR_TRANSPORT_ACKNOWLEDGEMENT)" {
            toDelivery(TransportStatus.PENDING, null).resolveDelivery().decision shouldBe
                NextStateDecision.Transition(PENDING)

            toDelivery(TransportStatus.PENDING, null).resolveDelivery().pendingReason shouldBe
                PendingReason.WAITING_FOR_TRANSPORT_ACKNOWLEDGEMENT
        }

        "ACK transport + null apprec → PENDING(WAITING_FOR_APPREC)" {
            toDelivery(ACKNOWLEDGED, null).resolveDelivery().decision shouldBe
                NextStateDecision.Transition(PENDING)

            toDelivery(ACKNOWLEDGED, null).resolveDelivery().pendingReason shouldBe
                PendingReason.WAITING_FOR_APPREC
        }

        "ACK transport + OK apprec → COMPLETED" {
            toDelivery(ACKNOWLEDGED, OK).resolveDelivery().decision shouldBe
                NextStateDecision.Transition(COMPLETED)

            toDelivery(ACKNOWLEDGED, OK).resolveDelivery().pendingReason shouldBe null
        }

        "ACK transport + OK_ERROR_IN_MESSAGE_PART apprec → COMPLETED" {
            toDelivery(ACKNOWLEDGED, OK_ERROR_IN_MESSAGE_PART).resolveDelivery().decision shouldBe
                NextStateDecision.Transition(COMPLETED)

            toDelivery(ACKNOWLEDGED, OK_ERROR_IN_MESSAGE_PART).resolveDelivery().pendingReason shouldBe null
        }

        "ACK transport + REJECTED apprec → REJECTED_APPREC" {
            toDelivery(ACKNOWLEDGED, AppRecStatus.REJECTED).resolveDelivery().decision shouldBe
                NextStateDecision.Rejected.AppRec

            toDelivery(ACKNOWLEDGED, AppRecStatus.REJECTED).resolveDelivery().pendingReason shouldBe null
        }

        "REJECTED transport + null apprec → REJECTED_TRANSPORT" {
            toDelivery(TransportStatus.REJECTED, null).resolveDelivery().decision shouldBe
                NextStateDecision.Rejected.Transport

            toDelivery(TransportStatus.REJECTED, null).resolveDelivery().pendingReason shouldBe null
        }

        "INVALID transport + null apprec → INVALID" {
            toDelivery(TransportStatus.INVALID, null).resolveDelivery().decision shouldBe
                NextStateDecision.Transition(INVALID)

            toDelivery(TransportStatus.INVALID, null).resolveDelivery().pendingReason shouldBe null
        }
    }
)
