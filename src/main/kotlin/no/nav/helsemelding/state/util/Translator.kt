package no.nav.helsemelding.state.util

import no.nav.emottak.ediadapter.model.DeliveryState
import no.nav.emottak.ediadapter.model.StatusInfo
import no.nav.helsemelding.state.model.AppRecStatus
import no.nav.helsemelding.state.model.ExternalDeliveryState
import no.nav.helsemelding.state.model.ExternalStatus
import no.nav.emottak.ediadapter.model.AppRecStatus as ExternalAppRecStatus

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
