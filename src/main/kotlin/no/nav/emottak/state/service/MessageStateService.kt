package no.nav.emottak.state.service

import no.nav.emottak.state.model.MessageDeliveryState
import no.nav.emottak.state.model.MessageDeliveryState.NEW
import no.nav.emottak.state.model.MessageState
import no.nav.emottak.state.model.MessageStateSnapshot
import no.nav.emottak.state.model.MessageType
import no.nav.emottak.state.repository.MessageRepository
import no.nav.emottak.state.repository.MessageStateHistoryRepository
import no.nav.emottak.state.repository.MessageStateTransactionRepository
import java.net.URL
import kotlin.time.Clock
import kotlin.uuid.Uuid

interface MessageStateService {
    /**
     * Registers a newly accepted message and establishes its initial delivery state.
     *
     * Called when the adapter confirms that a message has been accepted by the external system.
     * The message and its initial [initialState] are recorded together with the external reference
     * and message URL. This marks the message as tracked within the internal domain model.
     *
     * The operation also records an initial state change event so that the message history
     * starts from a consistent baseline.
     *
     * This method is transactional — the message and its history are guaranteed to be updated atomically.
     *
     * @param messageType The domain category of the message (e.g., [MessageType.DIALOG]).
     * @param externalRefId The UUID reference returned from the external API.
     * @param externalMessageUrl The URL that identifies the message in the external system.
     * @param initialState The first known delivery state. Defaults to [MessageDeliveryState.NEW].
     * @return A [MessageStateSnapshot] representing the persisted state and its current history.
     */
    suspend fun createInitialState(
        messageType: MessageType,
        externalRefId: Uuid,
        externalMessageUrl: URL,
        initialState: MessageDeliveryState = NEW
    ): MessageStateSnapshot

    /**
     * Records a new delivery state for an existing message.
     *
     * Called when the external system reports a change in processing or delivery status.
     * The service updates the tracked message’s current state and adds a corresponding
     * history entry describing the transition from [oldState] to [newState].
     *
     * The external message URL provided during creation remains immutable.
     *
     * This method is transactional — both the message state and its history are updated as one logical unit.
     *
     * @param messageType The domain category of the message.
     * @param oldState The message’s previous state
     * @param newState The latest state reported by the external system.
     * @param externalRefId The external system’s unique message identifier.
     * @return A [MessageStateSnapshot] representing the updated message and its full history.
     */
    suspend fun recordStateChange(
        messageType: MessageType,
        oldState: MessageDeliveryState,
        newState: MessageDeliveryState,
        externalRefId: Uuid
    ): MessageStateSnapshot

    /**
     * Retrieves the current snapshot of a tracked message, including its delivery state and full history.
     *
     * Used when inspecting a specific message’s lifecycle — for example, in diagnostics, API queries,
     * or internal monitoring. The returned snapshot includes both the current delivery state
     * and all previously recorded state transitions.
     *
     * @param messageId The unique identifier of the tracked message.
     * @return A [MessageStateSnapshot] containing the message’s current state and full history,
     *         or `null` if no message with the given ID is being tracked.
     */
    suspend fun getMessageSnapshot(messageId: Uuid): MessageStateSnapshot?

    /**
     * Finds messages that are candidates for polling against the external system.
     *
     * This function returns messages that are considered "in flight" — for example,
     * those in intermediate delivery states such as `NEW`. These messages require
     * periodic polling to verify whether their state has changed in the external system.
     *
     * The poller will only include messages that have not been polled recently.
     * This is determined by comparing each message’s `lastPolledAt` [Instant]
     * against the configured minimum polling interval (see [PollerConfig.minAgeSeconds]).
     *
     * The number of messages returned is limited by the configured fetch limit
     * (see [PollerConfig.fetchLimit]) to prevent oversized batches.
     *
     * Default configuration values:
     * - `minAgeSeconds`: 30 seconds — a message must be at least this old since last polling
     * - `fetchLimit`: 100 messages — the maximum number of messages fetched per batch
     *
     * These defaults can be overridden via environment variables or application configuration
     * without requiring code changes.
     *
     * @return a list of messages that are currently eligible for polling.
     */
    suspend fun findPollableMessages(): List<MessageState>

    /**
     * Marks one or more messages as having been polled.
     *
     * This function updates the `lastPolledAt` [Instant] for the given messages,
     * indicating that their external delivery status has recently been checked.
     *
     * It is typically called by the poller component after each polling cycle,
     * once messages have been evaluated and any necessary state changes recorded.
     *
     * Updating this [Instant] ensures that the same messages will not be picked up
     * again in the next polling iteration until the configured minimum polling interval
     * has elapsed (see [PollerConfig.minAgeSeconds]).
     *
     * Default configuration values:
     * - `minAgeSeconds`: 30 seconds — a message must be at least this old since last polling
     *
     * @param externalRefIds A list of external reference id's corresponding to the messages
     *        that have just been polled successfully.
     *
     * @return number of messages marked as polled.
     */
    suspend fun markAsPolled(externalRefIds: List<Uuid>): Int
}

class TransactionalMessageStateService(
    private val messageRepository: MessageRepository,
    private val historyRepository: MessageStateHistoryRepository,
    private val transactionRepository: MessageStateTransactionRepository
) : MessageStateService {
    override suspend fun createInitialState(
        messageType: MessageType,
        externalRefId: Uuid,
        externalMessageUrl: URL,
        initialState: MessageDeliveryState
    ): MessageStateSnapshot = transactionRepository
        .createInitialState(
            messageType = messageType,
            initialState = initialState,
            externalRefId = externalRefId,
            externalMessageUrl = externalMessageUrl,
            occurredAt = Clock.System.now()
        )

    override suspend fun recordStateChange(
        messageType: MessageType,
        oldState: MessageDeliveryState,
        newState: MessageDeliveryState,
        externalRefId: Uuid
    ): MessageStateSnapshot = transactionRepository
        .recordStateChange(
            messageType = messageType,
            oldState = oldState,
            newState = newState,
            externalRefId = externalRefId,
            occurredAt = Clock.System.now()
        )

    override suspend fun getMessageSnapshot(messageId: Uuid): MessageStateSnapshot? {
        val state = messageRepository.findOrNull(messageId) ?: return null
        val history = historyRepository.findAll(messageId)

        return MessageStateSnapshot(state, history)
    }

    override suspend fun findPollableMessages(): List<MessageState> = messageRepository.findForPolling()

    override suspend fun markAsPolled(externalRefIds: List<Uuid>): Int = messageRepository.markPolled(externalRefIds)
}
