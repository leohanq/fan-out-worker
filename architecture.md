# Caseware — Current Systems (As-Is)

```
┌─────────────────────────────────────────────────────────────────────┐
│                        CASEWARE PLATFORM                            │
│                                                                     │
│  ┌───────────────────────────────────────────────────────────────┐  │
│  │              PRODUCT TEMPLATE DATABASE                        │  │
│  │                    (Global / Shared)                          │  │
│  │                                                               │  │
│  │  ┌─────────┐  ┌─────────┐  ┌─────────┐       ┌─────────┐   │  │
│  │  │ Tmpl A  │  │ Tmpl B  │  │ Tmpl C  │  ...  │ Tmpl N  │   │  │
│  │  │ v1,v2,v3│  │ v1,v2   │  │ v1..v7  │       │ v1,v2   │   │  │
│  │  └─────────┘  └─────────┘  └─────────┘       └─────────┘   │  │
│  │                                                               │  │
│  │  • ~40 products                                               │  │
│  │  • Updated ~1x/week per product by content teams              │  │
│  │  • Lookup: (template_id, version) → zip of JSON               │  │
│  │  • No events emitted today                                    │  │
│  │  • No knowledge of engagements                                │  │
│  └───────────────────────────────────────────────────────────────┘  │
│                                                                     │
│                    ╔═══════════════════╗                             │
│                    ║   NO CONNECTION   ║                             │
│                    ║   between these   ║                             │
│                    ║   two systems     ║                             │
│                    ╚═══════════════════╝                             │
│                                                                     │
│  ┌──────────────────────────┐  ┌──────────────────────────┐        │
│  │  ENGAGEMENT MANAGEMENT   │  │  ENGAGEMENT MANAGEMENT   │        │
│  │  SYSTEM — Region US/CA   │  │  SYSTEM — Region EU      │        │
│  │                          │  │                          │        │
│  │  • Creates engagements   │  │  • Same responsibilities │        │
│  │  • Loads into memory     │  │  • Data stays in-region  │        │
│  │    pods (~1 min each)    │  │    (legal/contractual)   │        │
│  │  • Handles accept/       │  │                          │        │
│  │    decline decisions     │  │                          │        │
│  │                          │  │                          │        │
│  │  ┌────────────────────┐  │  │  ┌────────────────────┐  │        │
│  │  │    Firm A DB       │  │  │  │    Firm C DB       │  │        │
│  │  │  ┌──┐ ┌──┐ ┌──┐   │  │  │  │  ┌──┐ ┌──┐ ┌──┐   │  │        │
│  │  │  │E1│ │E2│ │E3│   │  │  │  │  │E7│ │E8│ │E9│   │  │        │
│  │  │  └──┘ └──┘ └──┘   │  │  │  │  └──┘ └──┘ └──┘   │  │        │
│  │  └────────────────────┘  │  │  └────────────────────┘  │        │
│  │  ┌────────────────────┐  │  │  ┌────────────────────┐  │        │
│  │  │    Firm B DB       │  │  │  │    Firm D DB       │  │        │
│  │  │  ┌──┐ ┌──┐        │  │  │  │  ┌──┐ ┌──┐ ┌──┐   │  │        │
│  │  │  │E4│ │E5│  ...   │  │  │  │  │E10│ │E11│ │..│  │  │        │
│  │  │  └──┘ └──┘        │  │  │  │  └──┘ └──┘ └──┘   │  │        │
│  │  └────────────────────┘  │  │  └────────────────────┘  │        │
│  └──────────────────────────┘  └──────────────────────────┘        │
│                                                                     │
│  Each engagement (E1, E2...) is an opaque blob:                     │
│  • Contains template_id + version inside, but NOT directly          │
│    queryable — must load into a pod to read it (~1 min)             │
│  • Contains confidential audit workpaper data                       │
│  • ~800,000 total across ~4,000 firms                               │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

## Key Observations

| Aspect | Current State |
|--------|--------------|
| **Template → Engagement link** | Does not exist. No index, no reverse lookup. |
| **Events on publish** | None. Content team writes to DB, nothing else happens. |
| **Events on engagement actions** | None. But hooks can be added. |
| **Engagement metadata access** | Requires full load (~1 min). Not queryable at rest. |
| **Data residency** | Engagements are region-bound. Templates are global. |
| **Scale** | ~800K engagements, ~4K firms, ~40 products |

## The Gap

There is no system today that can answer:
- *"Which engagements use template X?"*
- *"Is there a newer version available for engagement Y?"*
- *"What changed between the version I'm on and the latest?"*

This is what the new system needs to provide.

---

# Caseware — Proposed System (To-Be)

## General Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                     CASEWARE PLATFORM (To-Be)                           │
│                                                                         │
│  ┌────────────────────────────────────────────┐                         │
│  │       PRODUCT TEMPLATE DATABASE            │                         │
│  │            (Global / Shared)               │                         │
│  │                                            │                         │
│  │  ┌────────┐ ┌────────┐      ┌────────┐    │                         │
│  │  │ Tmpl A │ │ Tmpl B │ ...  │ Tmpl N │    │                         │
│  │  │v1,v2,v3│ │v1,v2   │      │v1,v2   │    │                         │
│  │  └────────┘ └────────┘      └────────┘    │                         │
│  │                                            │                         │
│  │  • Owns version graph (lineage, withdrawals)                         │
│  │  • NEW: Emits "template-published" event   │                         │
│  └──────────────────┬─────────────────────────┘                         │
│                     │ JMS message                                       │
│                     ▼                                                   │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │              NEW: UPDATE NOTIFICATION SERVICE                     │   │
│  │                       (Spring Boot)                               │   │
│  │                                                                  │   │
│  │  ┌──────────────┐  ┌──────────────┐  ┌───────────────────────┐  │   │
│  │  │  Fan-Out     │  │  Engagement  │  │  Summary Generator    │  │   │
│  │  │  Worker      │  │  Template    │  │                       │  │   │
│  │  │  (JMS        │  │  Index (DB)  │  │  • JSON diff tool     │  │   │
│  │  │  Listener)   │  │              │  │  • LLM for human-     │  │   │
│  │  │              │  │  • Central   │  │    readable text      │  │   │
│  │  │  • Receives  │  │  • engagement│  │  • Cached per         │  │   │
│  │  │    publish   │  │    _id → tmpl│  │    (tmpl, v_old,     │  │   │
│  │  │    events    │──│    _id +     │  │     v_new)           │  │   │
│  │  │  • Queries   │  │    version   │  │  • Stored permanently │  │   │
│  │  │    index     │  │              │  │    for audit trail    │  │   │
│  │  │  • Enqueues  │  │  Also:       │  │                       │  │   │
│  │  │    work per  │  │  decline_    │  └───────────────────────┘  │   │
│  │  │    engagement│  │  history     │                              │   │
│  │  │  • Bounded   │  │              │                              │   │
│  │  │    concurr.  │  │              │                              │   │
│  │  └──────┬───────┘  └──────▲───▲──┘                              │   │
│  │         │                 │   │                                   │   │
│  │         │           writes│   │reads                              │   │
│  └─────────┼─────────────────┼───┼──────────────────────────────────┘   │
│            │                 │   │                                       │
│            │ JMS messages    │   │ on-access capture                     │
│            ▼                 │   │                                       │
│  ┌─────────────────────┐    │   │    ┌──────────────────────────┐       │
│  │  ACTIVEMQ           │    │   │    │  BACKFILL                │       │
│  │  (Work Queue)       │    │   │    │  (Spring Batch)          │       │
│  │                     │    │   │    │                          │       │
│  │  • One message per  │    │   │    │  • ItemReader: list of   │       │
│  │    affected engage- │    │   │    │    engagement IDs        │       │
│  │    ment             │    │   │    │  • ItemProcessor: load   │       │
│  │  • Redelivery +     │    │   │    │    engagement, extract   │       │
│  │    DLQ on failure   │    │   │    │    (tmpl_id, version)    │       │
│  │  • Prefetch limit   │    │   │    │  • ItemWriter: upsert   │       │
│  │    for rate control │    │   │    │    into index            │       │
│  └──────────┬──────────┘    │   │    │  • Chunk-based, rate-    │       │
│             │               │   │    │    limited, restartable  │       │
│             │ consume       │   │    └───────────┬──────────────┘       │
│             ▼               │   │                │                      │
│  ┌──────────────────────────┐   │    ┌───────────▼──────────┐           │
│  │  ENGAGEMENT MANAGEMENT   │   │    │  ENGAGEMENT MGMT     │           │
│  │  SYSTEM — Region US/CA   │   │    │  SYSTEM — Region EU  │           │
│  │                          │   │    │                      │           │
│  │  • Loads engagements     │   │    │  • Same, data stays  │           │
│  │  • Handles accept/decline│   │    │    in-region          │           │
│  │  • NEW: Emits JMS events:│   │    │  • NEW: Emits JMS    │           │
│  │    - engagement created  ├───┘    │    events (same)      │           │
│  │    - update applied      │        │                      │           │
│  │    - update declined     │        │                      │           │
│  │                          │        │                      │           │
│  │  ┌──────────┐ ┌────────┐│        │  ┌──────────┐        │           │
│  │  │ Firm A   │ │ Firm B ││        │  │ Firm C   │        │           │
│  │  │ eng1,2,3 │ │ eng4,5 ││        │  │ eng6,7,8 │        │           │
│  │  └──────────┘ └────────┘│        │  └──────────┘        │           │
│  └──────────────────────────┘        └──────────────────────┘           │
│                                                                         │
│            User opens engagement → on-access capture writes to index     │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### Technology Choices

| Component | Technology | Why |
|-----------|-----------|-----|
| Update Notification Service | **Spring Boot** | Team's core framework |
| Work Queue | **ActiveMQ** | Already in the stack, JMS integration with Spring |
| Fan-Out Worker | **Spring JMS Listener** | `@JmsListener` for consuming publish events |
| Backfill Job | **Spring Batch** | Chunk-based processing, restartable, built-in throttling |
| Index Database | **PostgreSQL / existing RDBMS** | Simple relational table, no new infra |
| Summary cache | Same DB, separate table | Immutable once generated |

### Data Flow (End to End)

1. Content team publishes template → JMS message to ActiveMQ
2. Fan-out worker (JMS listener) receives event → queries index for affected engagements
3. For each affected engagement → JMS message enqueued to ActiveMQ work queue
4. Queue consumers process at bounded rate (prefetch limit) → marks pending update, triggers summary
5. User sees pending updates on dashboard → reads stored summary → applies or declines
6. Apply/decline → engagement system emits JMS event → index updated

---

## Component 1: The Engagement-Template Index

```
                HOW THE INDEX GETS POPULATED AND STAYS CURRENT

  ┌─────────────────────────────────────────────────────────────────┐
  │                ENGAGEMENT-TEMPLATE INDEX                        │
  │                     (Central DB)                                │
  │                                                                 │
  │  engagement_template_index       decline_history                │
  │  ┌─────────────┬──────────┐     ┌──────────────┬────────────┐  │
  │  │engagement_id│ PK       │     │engagement_id │            │  │
  │  │firm_id      │          │     │template_id   │ composite  │  │
  │  │region       │          │     │declined_ver  │ PK         │  │
  │  │template_id  │          │     │declined_at   │            │  │
  │  │current_ver  │          │     └──────────────┴────────────┘  │
  │  │updated_at   │          │                                    │
  │  └─────────────┴──────────┘                                    │
  └──────────▲──────────▲──────────▲──────────▲────────────────────┘
             │          │          │          │
             │          │          │          │
    ┌────────┴───┐ ┌────┴────┐ ┌──┴───┐ ┌───┴──────────┐
    │ BACKFILL   │ │ ON-     │ │CREATE│ │ APPLY /      │
    │ (Spring    │ │ ACCESS  │ │ HOOK │ │ DECLINE HOOK │
    │  Batch)    │ │ CAPTURE │ │      │ │              │
    └────────────┘ └─────────┘ └──────┘ └──────────────┘
         │              │          │           │
         │              │          │           │
    Loads engagement  User opens  New eng.   User decides
    in background,    engagement, created,   on update,
    extracts metadata captures    writes     updates version
    UPSERT to index   metadata   INSERT     or writes to
                      UPSERT               decline_history
```

### Write Paths

| # | Source | Trigger | Operation |
|---|--------|---------|-----------|
| 1 | Spring Batch backfill (one-time) | Background job | engagement IDs → load each → extract (tmpl_id, ver) → UPSERT |
| 2 | On-access capture (ongoing) | User opens engagement | System extracts metadata → UPSERT |
| 3 | Engagement created hook (ongoing) | New engagement | INSERT with (tmpl_id, initial version) |
| 4 | Update applied hook (ongoing) | User applies v6 | UPDATE current_version = v6 |
| 5 | Update declined hook (ongoing) | User declines v6 | INSERT into decline_history |

### Read Path

```sql
-- Fan-out worker queries this on template publish
SELECT engagement_id, firm_id, region, current_version
FROM engagement_template_index
WHERE template_id = ?
  AND current_version IN (superseded versions from Template DB)
```

---

## Component 2: The Fan-Out Worker

```
              HOW A TEMPLATE PUBLISH IS PROCESSED

  Content team publishes Template T v6
             │
             ▼
  ┌─────────────────────┐
  │  PRODUCT TEMPLATE   │
  │  DATABASE           │
  │                     │
  │  Writes v6, emits   │
  │  JMS message:       │
  │  {template_id: T,   │
  │   version: v6}      │
  └──────────┬──────────┘
             │ JMS message
             ▼
  ┌──────────────────────────────────────────────────────────┐
  │  ACTIVEMQ                                                │
  │  topic: template.published                               │
  └──────────┬───────────────────────────────────────────────┘
             │
             ▼
  ┌──────────────────────────────────────────────────────────┐
  │  FAN-OUT WORKER (Spring Boot / @JmsListener)             │
  │                                                          │
  │  Step 1: Idempotency check                               │
  │  ┌────────────────────────────────────────────────────┐  │
  │  │ Have I already processed (template T, v6)?         │  │
  │  │ → Check processed_events table                     │  │
  │  │ → If yes: ACK message, stop                        │  │
  │  │ → If no: continue                                  │  │
  │  └────────────────────────────────────────────────────┘  │
  │                                                          │
  │  Step 2: Resolve affected versions                       │
  │  ┌────────────────────────────────────────────────────┐  │
  │  │ Ask Template DB:                                   │  │
  │  │ "Which versions are superseded by v6?"             │  │
  │  │ → Returns [v1, v2, v3, v4, v5]                     │  │
  │  └────────────────────────────────────────────────────┘  │
  │                                                          │
  │  Step 3: Query the index                                 │
  │  ┌────────────────────────────────────────────────────┐  │
  │  │ SELECT engagement_id, firm_id, current_version     │  │
  │  │ FROM engagement_template_index                     │  │
  │  │ WHERE template_id = 'T'                            │  │
  │  │   AND current_version IN ('v1','v2','v3','v4','v5')│  │
  │  │                                                    │  │
  │  │ → Returns list of affected engagements             │  │
  │  └────────────────────────────────────────────────────┘  │
  │                                                          │
  │  Step 4: Enqueue work (one message per engagement)       │
  │  ┌────────────────────────────────────────────────────┐  │
  │  │ For each engagement:                               │  │
  │  │   Send JMS message to queue:                       │  │
  │  │   engagement.process-update                        │  │
  │  │   {                                                │  │
  │  │     engagement_id: "eng_42",                       │  │
  │  │     template_id: "T",                              │  │
  │  │     from_version: "v3",                            │  │
  │  │     to_version: "v6"                               │  │
  │  │   }                                                │  │
  │  └────────────────────────────────────────────────────┘  │
  │                                                          │
  │  Step 5: Record completion                               │
  │  ┌────────────────────────────────────────────────────┐  │
  │  │ INSERT into processed_events                       │  │
  │  │ (template_id, version, engagement_count, timestamp)│  │
  │  └────────────────────────────────────────────────────┘  │
  └──────────────────────────────────────────────────────────┘
             │
             │ JMS messages (one per engagement)
             ▼
  ┌──────────────────────────────────────────────────────────┐
  │  ACTIVEMQ                                                │
  │  queue: engagement.process-update                        │
  │                                                          │
  │  • Prefetch limit: controls how many each consumer takes │
  │  • Redelivery policy: retry N times with backoff         │
  │  • DLQ: failed messages after retries go here            │
  └──────────┬───────────────────────────────────────────────┘
             │
             │ Consumed by N concurrent listeners
             ▼
  ┌──────────────────────────────────────────────────────────┐
  │  ENGAGEMENT UPDATE CONSUMER                              │
  │  (@JmsListener, concurrency = "5")                       │
  │                                                          │
  │  For each message:                                       │
  │  1. Check decline_history — was this version declined?   │
  │  2. If not declined → create pending_update record       │
  │  3. Trigger summary generation (or check cache)          │
  │  4. ACK message                                          │
  │                                                          │
  │  Bounded concurrency = 5 threads max                     │
  │  → Protects engagement system from overload              │
  └──────────────────────────────────────────────────────────┘
```

### Fan-Out Worker: Key Properties

| Property | How It's Handled |
|----------|-----------------|
| **Idempotency** | `processed_events` table checked before processing; duplicate messages are ACKed and skipped |
| **Bounded concurrency** | ActiveMQ prefetch limit + `@JmsListener(concurrency = "5")` |
| **Failure / retries** | ActiveMQ redelivery policy with exponential backoff; DLQ for messages that fail N times |
| **Downstream protection** | Consumer concurrency caps how many engagements are processed simultaneously |
| **Large fan-out** | One publish → thousands of individual JMS messages; queue absorbs the burst |

---

## Component 3: The Summary Generator

```
            HOW HUMAN-READABLE SUMMARIES ARE CREATED

  Pending update identified:
  engagement eng_42 needs to go from v3 → v6
             │
             ▼
  ┌──────────────────────────────────────────────────────────┐
  │  SUMMARY GENERATOR                                       │
  │                                                          │
  │  Step 1: Check cache                                     │
  │  ┌────────────────────────────────────────────────────┐  │
  │  │ SELECT * FROM change_summaries                     │  │
  │  │ WHERE template_id = 'T'                            │  │
  │  │   AND from_version = 'v3'                          │  │
  │  │   AND to_version = 'v6'                            │  │
  │  │                                                    │  │
  │  │ → If found: reuse it (same diff = same summary)    │  │
  │  │ → If not found: generate new                       │  │
  │  └─────────────────────────┬──────────────────────────┘  │
  │                            │ cache miss                   │
  │                            ▼                              │
  │  Step 2: Get the JSON diff                               │
  │  ┌────────────────────────────────────────────────────┐  │
  │  │ Fetch Template T v3 from Template DB               │  │
  │  │ Fetch Template T v6 from Template DB               │  │
  │  │ Run JSON diff tool → structured diff               │  │
  │  │                                                    │  │
  │  │ Output: machine-readable list of changes           │  │
  │  │ {                                                  │  │
  │  │   added: ["Audit procedure 4.2.1"],                │  │
  │  │   modified: ["Risk assessment checklist"],          │  │
  │  │   removed: ["Deprecated form B-7"]                 │  │
  │  │ }                                                  │  │
  │  └─────────────────────────┬──────────────────────────┘  │
  │                            │                              │
  │                            ▼                              │
  │  Step 3: Generate human-readable summary (LLM)           │
  │  ┌────────────────────────────────────────────────────┐  │
  │  │ Prompt:                                            │  │
  │  │ "Summarize these template changes for an auditor.  │  │
  │  │  Be specific about what was added, changed, or     │  │
  │  │  removed. Use plain language."                     │  │
  │  │                                                    │  │
  │  │ Input: structured JSON diff                        │  │
  │  │ Output: plain English summary                      │  │
  │  │                                                    │  │
  │  │ Example output:                                    │  │
  │  │ "This update adds a new audit procedure (4.2.1)    │  │
  │  │  for revenue recognition, updates the risk         │  │
  │  │  assessment checklist with two new risk factors,    │  │
  │  │  and removes the deprecated Form B-7."             │  │
  │  └─────────────────────────┬──────────────────────────┘  │
  │                            │                              │
  │                            ▼                              │
  │  Step 4: Store permanently                               │
  │  ┌────────────────────────────────────────────────────┐  │
  │  │ INSERT INTO change_summaries                       │  │
  │  │ (template_id, from_version, to_version,            │  │
  │  │  json_diff, llm_summary, llm_model, llm_prompt,    │  │
  │  │  generated_at)                                     │  │
  │  │                                                    │  │
  │  │ Stores:                                            │  │
  │  │ • The raw JSON diff (reproducible, verifiable)     │  │
  │  │ • The LLM-generated summary (what user sees)       │  │
  │  │ • Which LLM model + prompt version was used        │  │
  │  │ • Timestamp                                        │  │
  │  │                                                    │  │
  │  │ WHY: regulatory review in November needs to        │  │
  │  │ explain what a user was shown in March             │  │
  │  └────────────────────────────────────────────────────┘  │
  └──────────────────────────────────────────────────────────┘
```

### Caching Strategy

Summary is keyed by `(template_id, from_version, to_version)`.

| Scenario | What happens |
|----------|-------------|
| 500 engagements on v3, template publishes v6 | JSON diff v3→v6 computed **once**, LLM summary generated **once**, all 500 share it |
| 200 engagements on v4 | Different diff (v4→v6), different summary — also computed once, shared across all 200 |

### Audit Trail Table

```
change_summaries
┌───────────────┬──────────────────────────────────────┐
│ template_id   │ PK (composite)                       │
│ from_version  │ PK                                   │
│ to_version    │ PK                                   │
│ json_diff     │ raw structured diff (immutable)      │
│ llm_summary   │ human-readable text (immutable)      │
│ llm_model     │ e.g. "claude-sonnet-4-..."           │
│ llm_prompt_ver│ prompt template version used         │
│ generated_at  │ timestamp                            │
└───────────────┴──────────────────────────────────────┘
```
