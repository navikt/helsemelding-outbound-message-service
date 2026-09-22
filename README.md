# helsemelding-outbound-message-service

The service manages the lifecycle of outgoing healthcare messages. It sends messages through the EDI adapter, follows their delivery progress, and publishes status updates so other services can track the outcome.

It connects outgoing messages on Kafka with the external messaging system and maintains a local record of each message's state and history.

## Message flow

1. **Receive and send.** An outgoing dialog message arrives on Kafka. The service sends it through the EDI adapter and registers it for tracking. Messages already registered are recognized and are not sent again.
2. **Follow progress.** Notifications from the adapter trigger a lookup of the message's current status. The service compares that status with its locally recorded state and evaluates the transition.
3. **Report and record.** Status updates are published to Kafka, then the new state and its history are recorded. Consumers receive updates while delivery is pending as well as when it completes or is rejected.

Notifications for incoming or untracked messages are ignored. Once a tracked message is completed or rejected, further notifications for it are skipped.

## Delivery model

Delivery has two distinct stages: transport and application processing. Transport confirmation means the message has been delivered, while an application receipt (AppRec) describes whether the receiving application accepted it. Keeping these stages separate makes it clear what the service is waiting for and where a rejection occurred.

| Status | Meaning |
| --- | --- |
| `NEW` | Registered and awaiting delivery status |
| `PENDING_TRANSPORT` | Waiting for transport confirmation |
| `PENDING_APPREC` | Transport confirmed; waiting for an application receipt |
| `COMPLETED` | The application receipt indicates acceptance |
| `REJECTED_TRANSPORT` | The receiver rejected transport, or NHN gave up after failed sending attempts |
| `REJECTED_APPREC` | The receiving application rejected the message |
| `INVALID` | An observed transition or combination of statuses violates the domain rules |

Completed and rejected messages are terminal. `INVALID` reports a state evaluation failure and is not treated as a terminal delivery outcome by notification processing.

When NHN gives up delivery, the external status `ABANDONED` is preserved in state and history. The published outcome remains `REJECTED_TRANSPORT`, with error code `TRANSPORT_ABANDONED` to distinguish it from a receiver rejection.

The service evaluates the latest available status, so it does not need to observe every intermediate stage. A message may already be completed when its first notification is processed.

## Design

**Notification-driven updates.** Notifications identify messages that may have changed. Fetching their current status keeps evaluation based on the external system's latest view.

**Restart recovery.** The last handled notification offset is stored in PostgreSQL after successful processing or an intentional skip. Consumption resumes from that offset after restart, or starts from `0` when no offset exists. Stream and processing failures stop the application without advancing past the failed notification.

**Explicit state rules.** Transport status and application receipt status determine the delivery outcome. Transition rules validate their consistency and prevent invalid progressions. Unchanged outcomes do not produce new status events.

**State and history.** PostgreSQL holds the current external status and a history of changes. They are recorded together in a transaction, supporting both ongoing processing and investigation of a message's lifecycle.

**Status events.** Kafka separates message submission from delivery tracking. Published updates carry the delivery status and relevant receipt or error information, allowing downstream services to react independently. Publication happens before local state is saved so failed publications can be retried.
