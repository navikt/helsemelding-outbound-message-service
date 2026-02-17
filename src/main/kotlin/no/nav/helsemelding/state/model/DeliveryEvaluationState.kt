package no.nav.helsemelding.state.model

import no.nav.helsemelding.state.model.NextStateDecision.Transition

data class DeliveryEvaluationState(
    val transport: TransportStatus,
    val appRec: AppRecStatus?
)

data class DeliveryResolution(
    val decision: NextStateDecision,
    val pendingReason: PendingReason? = null
)

fun DeliveryEvaluationState.resolveDelivery(): DeliveryResolution =
    when (transport) {
        TransportStatus.NEW -> DeliveryResolution(Transition(MessageDeliveryState.NEW))

        TransportStatus.PENDING ->
            DeliveryResolution(
                decision = Transition(MessageDeliveryState.PENDING),
                pendingReason = PendingReason.WAITING_FOR_TRANSPORT_ACKNOWLEDGEMENT
            )

        TransportStatus.ACKNOWLEDGED ->
            when {
                appRec.isNull() ->
                    DeliveryResolution(
                        decision = Transition(MessageDeliveryState.PENDING),
                        pendingReason = PendingReason.WAITING_FOR_APPREC
                    )

                appRec.isRejected() -> DeliveryResolution(NextStateDecision.Rejected.AppRec)

                appRec.isOk() || appRec.isOkErrorInMessagePart() ->
                    DeliveryResolution(Transition(MessageDeliveryState.COMPLETED))

                else -> DeliveryResolution(Transition(MessageDeliveryState.PENDING))
            }

        TransportStatus.REJECTED -> DeliveryResolution(NextStateDecision.Rejected.Transport)
        TransportStatus.INVALID -> DeliveryResolution(Transition(MessageDeliveryState.INVALID))
    }

fun DeliveryResolution.toDeliveryState(): MessageDeliveryState =
    when (decision) {
        is Transition -> decision.to
        is NextStateDecision.Rejected -> MessageDeliveryState.REJECTED
        NextStateDecision.Unchanged -> error("Unchanged is not a resolvable delivery state")
    }
