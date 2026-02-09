package no.nav.helsemelding.model

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import no.nav.helsemelding.state.evaluator.TransportStatusTranslator
import no.nav.helsemelding.state.model.AppRecStatus
import no.nav.helsemelding.state.model.AppRecStatus.OK
import no.nav.helsemelding.state.model.AppRecStatus.OK_ERROR_IN_MESSAGE_PART
import no.nav.helsemelding.state.model.AppRecStatus.REJECTED
import no.nav.helsemelding.state.model.DeliveryEvaluationState
import no.nav.helsemelding.state.model.ExternalDeliveryState
import no.nav.helsemelding.state.model.ExternalDeliveryState.ACKNOWLEDGED
import no.nav.helsemelding.state.model.MessageDeliveryState
import no.nav.helsemelding.state.model.MessageDeliveryState.COMPLETED
import no.nav.helsemelding.state.model.MessageDeliveryState.NEW
import no.nav.helsemelding.state.model.MessageDeliveryState.PENDING
import no.nav.helsemelding.state.model.resolveDelivery

class DeliveryEvaluationStateSpec : StringSpec(
    {
        val translator = TransportStatusTranslator()

        fun eval(external: ExternalDeliveryState?, appRec: AppRecStatus?): DeliveryEvaluationState =
            DeliveryEvaluationState(
                transport = translator.translate(external),
                appRec = appRec
            )

        "Null delivery + null apprec → NEW" {
            eval(null, null).resolveDelivery().state shouldBe NEW
        }

        "ACK delivery + null apprec → PENDING" {
            eval(ACKNOWLEDGED, null).resolveDelivery().state shouldBe PENDING
        }

        "UNCONFIRMED delivery + null apprec → PENDING" {
            eval(ExternalDeliveryState.UNCONFIRMED, null).resolveDelivery().state shouldBe PENDING
        }

        "ACK delivery + OK apprec → COMPLETED" {
            eval(
                ACKNOWLEDGED,
                OK
            )
                .resolveDelivery().state shouldBe COMPLETED
        }

        "ACK delivery + OK_ERROR_IN_MESSAGE_PART apprec → COMPLETED" {
            eval(
                ACKNOWLEDGED,
                OK_ERROR_IN_MESSAGE_PART
            )
                .resolveDelivery().state shouldBe COMPLETED
        }

        "ACK delivery + REJECTED apprec → REJECTED" {
            eval(
                ACKNOWLEDGED,
                REJECTED
            )
                .resolveDelivery().state shouldBe MessageDeliveryState.REJECTED
        }

        "REJECTED delivery + null apprec → REJECTED" {
            eval(ExternalDeliveryState.REJECTED, null).resolveDelivery().state shouldBe MessageDeliveryState.REJECTED
        }
    }
)
