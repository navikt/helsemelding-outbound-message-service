package no.nav.helsemelding.outbound.service

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import no.nav.helsemelding.outbound.model.AppRecStatus
import no.nav.helsemelding.outbound.model.ExternalDeliveryState
import no.nav.helsemelding.outbound.model.MessageDeliveryState
import no.nav.helsemelding.outbound.model.TransportStatus
import no.nav.helsemelding.outbound.repository.FakeMessageRepository

class MetricsServiceSpec : StringSpec({
    val messageRepository = FakeMessageRepository()

    val metricsService = PrometheusMetricsService(
        messageRepository
    )

    "countByTransportState should return correct counts for each TransportStatus" {

        messageRepository.setCountByExternalDeliveryState(
            mapOf(
                ExternalDeliveryState.ACKNOWLEDGED to 123,
                ExternalDeliveryState.UNCONFIRMED to 234
            )
        )

        val result = metricsService.countByTransportState()

        result[TransportStatus.ACKNOWLEDGED] shouldBe 123
        result[TransportStatus.PENDING] shouldBe 234
    }

    "countByAppRecState should return correct counts for each AppRecStatus" {

        messageRepository.setCountByAppRecState(
            mapOf(
                AppRecStatus.OK to 123,
                AppRecStatus.REJECTED to 234
            )
        )

        val result = metricsService.countByAppRecState()

        result[AppRecStatus.OK] shouldBe 123
        result[AppRecStatus.REJECTED] shouldBe 234
    }

    "countByMessageDeliveryState should return correct counts for each MessageDeliveryState" {

        messageRepository.setCountByExternalDeliveryStateAndAppRecStatus(
            mapOf(
                Pair(ExternalDeliveryState.ACKNOWLEDGED, AppRecStatus.OK) to 123,
                Pair(ExternalDeliveryState.ACKNOWLEDGED, AppRecStatus.REJECTED) to 234
            )
        )

        val result = metricsService.countByMessageDeliveryState()

        result[MessageDeliveryState.COMPLETED] shouldBe 123
        result[MessageDeliveryState.REJECTED] shouldBe 234
    }
})
