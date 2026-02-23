package no.nav.helsemelding.outbound.model

import no.nav.helsemelding.outbound.model.NextStateDecision.Transition
import java.net.URL
import kotlin.time.Instant
import kotlin.uuid.Uuid

data class MessageState(
    val id: Uuid,
    val messageType: MessageType,
    val externalRefId: Uuid,
    val externalMessageUrl: URL,
    val externalDeliveryState: ExternalDeliveryState?,
    val appRecStatus: AppRecStatus?,
    val lastStateChange: Instant,
    val lastPolledAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant
)

fun MessageState.formatUnchanged(): String = "${logPrefix()} No state transition (no persisted change)"

fun MessageState.formatNew(): String = "${logPrefix()} Transition → NEW (no external status received yet)"

fun MessageState.formatTransition(to: NextStateDecision): String = "${logPrefix()} Transition → $to"

fun MessageState.formatTransition(to: MessageDeliveryState): String = formatTransition(Transition(to))

fun MessageState.formatInvalidState(): String = "${logPrefix()} Entered INVALID state"

fun MessageState.formatExternal(
    newState: ExternalDeliveryState?,
    newAppRecStatus: AppRecStatus?
): String = "${logPrefix()} External update (transport=$newState, appRec=$newAppRecStatus)"

fun MessageState.logPrefix(): String = "Message(type=$messageType, ref=$externalRefId)"
