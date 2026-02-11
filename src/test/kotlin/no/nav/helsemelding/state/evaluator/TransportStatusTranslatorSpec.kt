package no.nav.helsemelding.state.evaluator

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import no.nav.helsemelding.state.model.ExternalDeliveryState
import no.nav.helsemelding.state.model.isAcknowledged
import no.nav.helsemelding.state.model.isNew
import no.nav.helsemelding.state.model.isPending
import no.nav.helsemelding.state.model.isRejected

class TransportStatusTranslatorSpec : StringSpec(
    {
        val translator = TransportStatusTranslator()

        "null → NEW" {
            translator.translate(null).isNew() shouldBe true
        }

        "UNCONFIRMED → PENDING" {
            translator.translate(ExternalDeliveryState.UNCONFIRMED).isPending() shouldBe true
        }

        "ACKNOWLEDGED → ACKNOWLEDGED" {
            translator.translate(ExternalDeliveryState.ACKNOWLEDGED).isAcknowledged() shouldBe true
        }

        "REJECTED → REJECTED" {
            translator.translate(ExternalDeliveryState.REJECTED).isRejected() shouldBe true
        }
    }
)
