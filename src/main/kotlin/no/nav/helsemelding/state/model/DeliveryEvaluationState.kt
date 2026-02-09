package no.nav.helsemelding.state.model

data class DeliveryEvaluationState(
    val transport: TransportStatus,
    val appRec: AppRecStatus?
)

fun DeliveryEvaluationState.resolveDelivery(): DeliveryResolution =
    when (transport) {
        TransportStatus.NEW -> DeliveryResolution(MessageDeliveryState.NEW)

        TransportStatus.PENDING ->
            DeliveryResolution(
                state = MessageDeliveryState.PENDING,
                pendingReason = PendingReason.WAITING_FOR_TRANSPORT_ACKNOWLEDGEMENT
            )

        TransportStatus.ACKNOWLEDGED ->
            when {
                appRec.isNull() ->
                    DeliveryResolution(
                        state = MessageDeliveryState.PENDING,
                        pendingReason = PendingReason.WAITING_FOR_APPREC
                    )

                appRec.isRejected() ->
                    DeliveryResolution(MessageDeliveryState.REJECTED)

                appRec.isOk() || appRec.isOkErrorInMessagePart() ->
                    DeliveryResolution(MessageDeliveryState.COMPLETED)

                else ->
                    DeliveryResolution(MessageDeliveryState.PENDING)
            }

        TransportStatus.REJECTED -> DeliveryResolution(MessageDeliveryState.REJECTED)
        TransportStatus.INVALID -> DeliveryResolution(MessageDeliveryState.INVALID)
    }
