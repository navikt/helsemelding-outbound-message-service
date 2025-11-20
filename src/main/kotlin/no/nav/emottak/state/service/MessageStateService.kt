package no.nav.emottak.state.service

import no.nav.emottak.state.model.CreateState
import no.nav.emottak.state.model.MessageState
import no.nav.emottak.state.model.MessageStateSnapshot
import no.nav.emottak.state.model.UpdateState
import no.nav.emottak.state.repository.MessageRepository
import no.nav.emottak.state.repository.MessageStateHistoryRepository
import no.nav.emottak.state.repository.MessageStateTransactionRepository
import kotlin.uuid.Uuid

interface MessageStateService {
    /**
     * Registers a newly accepted message and initializes its tracked external state.
     *
     * This is called when the adapter confirms that a message has been created in the external
     * system. At this stage, no external delivery information is available, so both the
     * external delivery state and AppRecStatus are recorded as `null`. This provides a clean
     * baseline before the poller begins retrieving real state from the external API.
     *
     * An initial history entry is also created so that the message’s lifecycle is tracked
     * from a well-defined starting point.
     *
     * The operation is transactional — the message and its initial history entry are persisted
     * atomically.
     *
     * @param createState A value object containing the type of message, external reference,
     *        external URL, and the timestamp at which the message was created.
     * @return A [MessageStateSnapshot] containing the stored message and its initial history entry.
     */
    suspend fun createInitialState(createState: CreateState): MessageStateSnapshot

    /**
     * Records an update to the external delivery state or application receipt status
     * for an existing message.
     *
     * Called when the external system reports a new status for a previously registered
     * message. If either the delivery state or AppRecStatus has changed compared to the
     * currently stored values, the message is updated and a new history entry is recorded.
     *
     * Both tracked aspects of external state may change independently — the transport-level
     * delivery state and the application-level receipt status. The external message URL
     * remains immutable and is not modified.
     *
     * The operation is transactional — the message update and its corresponding history
     * entry are committed atomically to ensure consistency.
     *
     * @param updateState A value object containing the message type, external reference id,
     *        previous and new external states, and the timestamp at which the change occurred.
     * @return A [MessageStateSnapshot] containing the updated message and its complete history.
     */
    suspend fun recordStateChange(updateState: UpdateState): MessageStateSnapshot

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
     * A message is considered pollable when no external delivery state has been recorded yet
     * (i.e., `externalDeliveryState` is `null`). These messages require periodic polling so
     * the system can obtain their initial delivery and processing information from the remote
     * API.
     *
     * Only messages that have not been polled recently are included. This is determined by
     * comparing each message’s `lastPolledAt` timestamp against the configured minimum polling
     * interval (see [PollerConfig.minAgeSeconds]).
     *
     * To avoid excessive polling load, the number of returned messages is limited by the
     * configured fetch limit (see [PollerConfig.fetchLimit]).
     *
     * Default configuration values:
     * - `minAgeSeconds`: 30 seconds — a message must be at least this old since it was last polled
     * - `fetchLimit`: 100 messages — the maximum number of messages fetched per polling cycle
     *
     * These defaults can be overridden through application configuration or environment variables.
     *
     * @return a list of messages that are eligible for polling based on external state and timing.
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
    override suspend fun createInitialState(createState: CreateState): MessageStateSnapshot =
        transactionRepository.createInitialState(createState)

    override suspend fun recordStateChange(updateState: UpdateState): MessageStateSnapshot =
        transactionRepository.recordStateChange(updateState)

    override suspend fun getMessageSnapshot(messageId: Uuid): MessageStateSnapshot? {
        val state = messageRepository.findOrNull(messageId) ?: return null
        val history = historyRepository.findAll(messageId)
        return MessageStateSnapshot(state, history)
    }

    override suspend fun findPollableMessages(): List<MessageState> = messageRepository.findForPolling()

    override suspend fun markAsPolled(externalRefIds: List<Uuid>): Int = messageRepository.markPolled(externalRefIds)
}
