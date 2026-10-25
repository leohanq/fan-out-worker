# Fan-Out Worker — Template Publish Notification

Implements the template-publish fan-out worker from the architecture design. When a product template is published, this worker identifies all affected engagements and dispatches individual update tasks to a work queue.

## How It Works

```
template.published (JMS)
       │
       ▼
  FanOutWorker
       │
       ├─ 1. Idempotency check (IdempotencyStore)
       ├─ 2. Resolve superseded versions (TemplateVersionResolver)
       ├─ 3. Page through affected engagements (EngagementIndex)
       ├─ 4. Dispatch per-engagement tasks (UpdateTaskQueue)
       └─ 5. Mark processed (IdempotencyStore)
```

## Key Implementation Tradeoffs

### At-least-once delivery, not exactly-once

If the worker crashes between dispatching tasks (step 4) and marking the event as processed (step 5), the JMS broker redelivers the event. The fan-out replays from scratch, producing duplicate tasks for engagements already dispatched. This is acceptable because:

- **Downstream consumers must be idempotent anyway** — the engagement management system checks whether a pending update already exists before creating one.
- **Exactly-once would require XA transactions** across JMS and the database, adding complexity and latency for a problem that at-least-once + idempotent consumers solves more simply.

### Paged index queries

The `EngagementIndex` is queried in pages of 500 to bound memory for large fan-outs. The worst case — a single template used by ~40,000 engagements — would require ~80 pages. This adds round-trips but keeps heap usage predictable regardless of fan-out size.

### Queue-based decoupling from downstream

The worker does **not** call the engagement management system directly. Instead, it writes tasks to a work queue. Capacity protection happens at the consumer level:

- `@JmsListener(concurrency = "2-5")` on the consumer caps parallel processing
- ActiveMQ prefetch limit controls how many messages each consumer thread buffers
- Redelivery policy with exponential backoff handles transient downstream failures

This lets the fan-out complete in seconds (just index reads + queue writes) while the actual ~1 min/engagement processing drains at a safe rate.

### Single-threaded fan-out

The `@JmsListener` runs with `concurrency = "1"`. At ~40 template publishes per week, serial processing is more than sufficient. A single fan-out of 40K engagements at 500/page completes in seconds (limited by index query latency, not CPU). Single threading avoids contention on the idempotency store and simplifies reasoning about correctness.

### Ports & interfaces over concrete implementations

All external dependencies are interfaces (`port/` package):

| Port | Responsibility |
|------|---------------|
| `TemplateVersionResolver` | Asks the template DB which versions a new publish supersedes |
| `EngagementIndex` | Queries the engagement-template index |
| `IdempotencyStore` | Deduplicates processed events |
| `UpdateTaskQueue` | Sends tasks to the work queue |

This keeps the fan-out logic testable with mocks and decoupled from infrastructure choices (JPA vs. JDBC, ActiveMQ vs. SQS, etc.).

## Running Tests

```bash
./mvnw test
```

Tests cover: happy path, idempotency, pagination, empty cases, failure propagation at each stage, and correct task content mapping.

## What's Not Included (by design)

- **Downstream consumer** — out of scope; the exercise focuses on the fan-out worker
- **JPA/JDBC adapters** for the port interfaces — implementation detail, not the focus
- **JMS broker configuration** — ActiveMQ redelivery policies, DLQ config, and prefetch limits belong in `application.yaml` per environment
- **Metrics/observability** — in production, counters for events processed, engagements dispatched, and failures would feed into the SLO dashboard