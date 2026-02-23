package no.nav.helsemelding.outbound.util

import no.nav.helsemelding.ediadapter.model.DeliveryState
import no.nav.helsemelding.ediadapter.model.StatusInfo
import no.nav.helsemelding.outbound.model.AppRecStatus
import no.nav.helsemelding.outbound.model.ExternalDeliveryState
import no.nav.helsemelding.outbound.model.ExternalStatus
import no.nav.helsemelding.ediadapter.model.AppRecStatus as ExternalAppRecStatus

fun StatusInfo.translate(): ExternalStatus =
    ExternalStatus(
        deliveryState = transportDeliveryState.translate(),
        appRecStatus = appRecStatus?.translate()
    )

private fun DeliveryState.translate(): ExternalDeliveryState = when (this) {
    DeliveryState.UNCONFIRMED -> ExternalDeliveryState.UNCONFIRMED
    DeliveryState.ACKNOWLEDGED -> ExternalDeliveryState.ACKNOWLEDGED
    DeliveryState.REJECTED -> ExternalDeliveryState.REJECTED
    DeliveryState.UNKNOWN -> ExternalDeliveryState.REJECTED
}

private fun ExternalAppRecStatus.translate(): AppRecStatus =
    when (this) {
        ExternalAppRecStatus.OK -> AppRecStatus.OK
        ExternalAppRecStatus.OK_ERROR_IN_MESSAGE_PART -> AppRecStatus.OK_ERROR_IN_MESSAGE_PART
        ExternalAppRecStatus.REJECTED -> AppRecStatus.REJECTED
        ExternalAppRecStatus.UNKNOWN -> AppRecStatus.REJECTED
    }
