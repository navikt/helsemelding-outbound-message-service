# State Service

The **state-service** is responsible for tracking the lifecycle of outgoing messages sent via the `edi-adapter`.  
It maintains local state, polls the external system for updates, evaluates domain state transitions, and publishes application receipts when transitions complete.

The service is structured around three core components:

---

## 1. Message Consumption & Initial State Registration

Messages are consumed from Kafka (starting with **dialog messages**, but the system is designed to support additional message types).  
Each message type is expected to have its own topic.

For each consumed message:

1. The message is handed to a **message processor**.
2. The processor posts the payload to the `edi-adapter` using the `edi-adapter-client`.
3. The adapter returns:
   - an **external reference ID**
   - a **URL** to the external message resource
4. These are persisted through:

```kotlin
messageStateService.createInitialState(CreateState(...))
```

This establishes a baseline internal representation of the message in domain state NEW.

## 2. Poller — State Reconciliation & Domain State Machine

The **poller** runs periodically and synchronizes local state with the external system.

### Polling Workflow

For each message that should be polled:

1. The poller calls the `edi-adapter-client`.
2. The response provides:
   - transport-level delivery status
   - application-level receipt status (AppRec)
3. The values are mapped to internal enums:
   - `ExternalDeliveryState`
   - `AppRecStatus`
4. The system computes the internal domain state using the `StateEvaluator`.

### Domain State Derivation

The internal domain state results from combinations of external states:

NEW → PENDING → COMPLETED
↘
REJECTED


Examples:

- `ACKNOWLEDGED + null` → `PENDING`
- `ACKNOWLEDGED + OK` → `COMPLETED`
- `UNCONFIRMED + null` → `PENDING`
- Any `REJECTED` → `REJECTED`

Illegal combinations (e.g., `UNCONFIRMED + REJECTED`) result in an `UnresolvableState` error.

### State Transition Validation

The `StateTransitionValidator` ensures transitions follow defined business rules.

Valid transitions include:

- `NEW → PENDING`
- `PENDING → COMPLETED`
- `PENDING → REJECTED`
- `NEW → REJECTED`
- `COMPLETED → COMPLETED` (idempotent)

Invalid transitions include:

- `PENDING → NEW`
- `COMPLETED → PENDING`
- transitions out of `REJECTED`
- transitions out of `INVALID`

Illegal transitions raise `IllegalTransition`, and no state is persisted.

### Persisting & Publishing

If a valid transition occurs:

- Persisted using:

```kotlin
messageStateService.recordStateChange(UpdateState(...))
```

- A history entry is appended.
- If entering a terminal state:
   - `COMPLETED → publish AppRec` (`OK` / `OK_ERROR_IN_MESSAGE_PART`)
   - `REJECTED → publish AppRec`(`REJECTED`)
If the state is `INVALID`, no writes or publications occur.

## 3. Persistence Model

The persistence layer provides a durable and transparent view of message lifecycle state.  
It consists of a **rich domain model** stored in two tables: a *current state table* and a *state history table*.

### Current Message State

Each message tracked in the system has a single **current state record**, which stores:

- the external reference ID (UUID from the external system)
- the external message URL (link to the resource in the external system)
- the latest resolved domain state (`MessageDeliveryState`)
- the raw external data:
   - `ExternalDeliveryState?`
   - `AppRecStatus?`
- timestamps for:
   - last state change
   - last poll time
   - creation and update time

This enables the system to determine:
- which messages must be polled,
- whether a new external change occurred,
- and what the next domain state should be.

### State History

Every time a message changes state, a **state history entry** is appended.  
History entries store:

- old delivery state (raw external)
- new delivery state (raw external)
- old AppRec value
- new AppRec value
- the timestamp of when the change was detected

This history allows:

- complete auditability,
- debugging of incorrect external systems,
- verification of state machine correctness,
- and future observability/analytics.

### Domain Separation

The persistence model deliberately separates:

- **raw external state** (transport + apprec),
- **derived internal domain state**, and
- **validated state transitions**.

This ensures:

- external inconsistencies do not corrupt domain flow,
- the poller can detect invalid or illegal transitions,
- the domain model remains stable even if the external API evolves.

Together, the current state + history tables form a fully traceable and deterministic message state engine.