package no.nav.helsemelding.state.evaluator

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import no.nav.helsemelding.state.model.ExternalDeliveryState
import no.nav.helsemelding.state.model.TransportStatus

class TransportStatusTranslatorSpec : StringSpec(
    {
        val translator = TransportStatusTranslator()

        "null → NEW" {
            translator.translate(null) shouldBe TransportStatus.NEW
        }

        "UNCONFIRMED → PENDING" {
            translator.translate(ExternalDeliveryState.UNCONFIRMED) shouldBe TransportStatus.PENDING
        }

        "ACKNOWLEDGED → ACKNOWLEDGED" {
            translator.translate(ExternalDeliveryState.ACKNOWLEDGED) shouldBe TransportStatus.ACKNOWLEDGED
        }

        "REJECTED → REJECTED" {
            translator.translate(ExternalDeliveryState.REJECTED) shouldBe TransportStatus.REJECTED
        }
    }
)
