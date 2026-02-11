package no.nav.helsemelding.state.model

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import no.nav.helsemelding.state.model.AppRecStatus.OK
import no.nav.helsemelding.state.model.AppRecStatus.OK_ERROR_IN_MESSAGE_PART
import no.nav.helsemelding.state.model.MessageDeliveryState.COMPLETED
import no.nav.helsemelding.state.model.MessageDeliveryState.INVALID
import no.nav.helsemelding.state.model.MessageDeliveryState.NEW
import no.nav.helsemelding.state.model.MessageDeliveryState.PENDING
import no.nav.helsemelding.state.model.MessageDeliveryState.REJECTED
import no.nav.helsemelding.state.model.TransportStatus.ACKNOWLEDGED

class DeliveryEvaluationStateSpec : StringSpec(
    {
        fun toDelivery(transport: TransportStatus, appRec: AppRecStatus?) =
            DeliveryEvaluationState(
                transport = transport,
                appRec = appRec
            )

        "NEW transport + null apprec → NEW" {
            toDelivery(TransportStatus.NEW, null).resolveDelivery().state shouldBe NEW
        }

        "ACK transport + null apprec → PENDING" {
            toDelivery(ACKNOWLEDGED, null).resolveDelivery().state shouldBe PENDING
        }

        "ACK transport + OK apprec → COMPLETED" {
            toDelivery(ACKNOWLEDGED, OK).resolveDelivery().state shouldBe COMPLETED
        }

        "ACK transport + OK_ERROR_IN_MESSAGE_PART apprec → COMPLETED" {
            toDelivery(ACKNOWLEDGED, OK_ERROR_IN_MESSAGE_PART).resolveDelivery().state shouldBe COMPLETED
        }

        "ACK transport + REJECTED apprec → REJECTED" {
            toDelivery(ACKNOWLEDGED, AppRecStatus.REJECTED).resolveDelivery().state shouldBe REJECTED
        }

        "REJECTED transport + null apprec → REJECTED" {
            toDelivery(TransportStatus.REJECTED, null).resolveDelivery().state shouldBe REJECTED
        }

        "INVALID transport + null apprec → INVALID" {
            toDelivery(TransportStatus.INVALID, null).resolveDelivery().state shouldBe INVALID
        }
    }
)
