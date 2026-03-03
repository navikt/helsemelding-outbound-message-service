package no.nav.helsemelding.outbound.service

import no.nav.helsemelding.outbound.evaluator.TransportStatusTranslator
import no.nav.helsemelding.outbound.model.AppRecStatus
import no.nav.helsemelding.outbound.model.AppRecStatus.OK
import no.nav.helsemelding.outbound.model.AppRecStatus.REJECTED
import no.nav.helsemelding.outbound.model.DeliveryEvaluationState
import no.nav.helsemelding.outbound.model.MessageDeliveryState
import no.nav.helsemelding.outbound.model.TransportStatus
import no.nav.helsemelding.outbound.model.resolveDelivery
import no.nav.helsemelding.outbound.model.toDeliveryState
import no.nav.helsemelding.outbound.repository.MessageRepository

interface MetricsService {
    /**
     * Counts the number of messages in each transport state.
     *
     * This function returns a map where the keys are the transport delivery states
     * (as defined by [TransportStatus]) and the values are the counts of messages currently
     * in each state.
     *
     * @return a map of transport delivery states to their respective message counts.
     */
    suspend fun countByTransportState(): Map<TransportStatus, Long>

    /**
     * Counts the number of messages in each application receipt state.
     *
     * This function returns a map where the keys are the transport delivery states
     * (as defined by [TransportStatus]) and the values are the counts of messages currently
     * in each state.
     *
     * @return a map of transport delivery states to their respective message counts.
     */
    suspend fun countByAppRecState(): Map<AppRecStatus?, Long>

    /**
     * Counts the number of messages in each delivery state.
     *
     * This function returns a map where the keys are the resolved message delivery states
     * (as defined by [MessageDeliveryState]) and the values are the counts of messages currently
     * in each state. The delivery state is derived from the combination of transport delivery state
     * and application receipt status.
     *
     * @return a map of resolved message delivery states to their respective message counts.
     */
    suspend fun countByMessageDeliveryState(): Map<MessageDeliveryState, Long>
}

class PrometheusMetricsService(
    private val messageRepository: MessageRepository
) : MetricsService {
    override suspend fun countByTransportState(): Map<TransportStatus, Long> {
        val messagesCountByExternalDeliveryState = messageRepository.countByExternalDeliveryState()

        return messagesCountByExternalDeliveryState.mapKeys {
            TransportStatusTranslator().translate(it.key)
        }
    }

    override suspend fun countByAppRecState(): Map<AppRecStatus?, Long> {
        return messageRepository.countByAppRecState()
    }

    override suspend fun countByMessageDeliveryState(): Map<MessageDeliveryState, Long> {
        val counts = messageRepository.countByExternalDeliveryStateAndAppRecStatus()
        return counts.mapKeys {
            val transportStatus = TransportStatusTranslator().translate(it.key.first)
            val appRecStatus = it.key.second

            DeliveryEvaluationState(transportStatus, appRecStatus)
                .resolveDelivery()
                .toDeliveryState()
        }
    }
}

class FakeMetricsService : MetricsService {
    override suspend fun countByTransportState(): Map<TransportStatus, Long> =
        mapOf(
            TransportStatus.ACKNOWLEDGED to 123,
            TransportStatus.REJECTED to 234
        )

    override suspend fun countByAppRecState(): Map<AppRecStatus?, Long> =
        mapOf(
            OK to 123,
            REJECTED to 234
        )

    override suspend fun countByMessageDeliveryState(): Map<MessageDeliveryState, Long> =
        mapOf(
            MessageDeliveryState.COMPLETED to 123,
            MessageDeliveryState.REJECTED to 234
        )
}
