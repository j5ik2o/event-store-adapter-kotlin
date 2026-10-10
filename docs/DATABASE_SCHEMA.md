# DynamoDB storage layout

The Kotlin factories use the Java public store and the [common DynamoDB profile](https://github.com/j5ik2o/event-store-adapter/blob/main/docs/spec/storage/dynamodb.md). Provision these three independent tables externally and supply their names through `DynamoDbTableConfig`. All tables must be in one region with writes routed to that region.

| Table | Partition key | Sort key | Global secondary index | Streams |
|---|---|---|---|---|
| journal | `aid` (S) | `seq_nr` (N) | none | disabled |
| snapshot | `aid` (S) | `skey` (N) | `aid` (S), `active_history_seq_nr` (N), KEYS_ONLY | disabled |
| head | `aid` (S) | none | none | enabled, NEW_IMAGE |

Configure snapshot time to live on attribute `ttl` when using expiry retention. The index name is supplied as `snapshotAidIndexName`. The library validates or initializes the configuration items; it does not create tables.

Aggregate keys are the exact string `typeName-value`. The type name cannot contain `-`, and the complete UTF-8 key is at most 1024 bytes. No shard, hash, key resolver, or version attribute is used. Event sequences are consecutive from 1, within the common maximum of 9007199254740991.

| Item | Attributes and types |
|---|---|
| Configuration, each table | `aid:S = "__config__"`, `store_id:S`, `layout_version:N = "1"`; journal also `seq_nr:N = "0"`, snapshot also `skey:N = "0"` |
| Journal | `aid:S`, `seq_nr:N`, `occurred_at:N` (integer epoch nanoseconds), `manifest:S`, `payload:B` |
| Current snapshot | `aid:S`, `skey:N = "0"`, `seq_nr:N`, `manifest:S`, `payload:B`, `last_updated_at:N` (event time in epoch milliseconds) |
| Active history | current snapshot attributes with `skey = seq_nr`, plus `active_history_seq_nr:N = seq_nr` |
| Expiry-marked history | history attributes with `ttl:N` (epoch seconds), and without `active_history_seq_nr` |
| Head | `aid:S`, `type_name:S`, `seq_nr:N`, `events:L` containing one `M` with `seq_nr:N`, `occurred_at:N`, `manifest:S`, `payload:B` |

Configuration items never have history-index or expiry attributes. Current snapshots have neither `active_history_seq_nr` nor `ttl`. Binary attributes contain only serialized payload or aggregate state; envelope metadata is separate. An omitted manifest is stored as the empty string.

Generation strongly reads all three configuration items with BatchGetItem, retries only unprocessed keys with bounded exponential backoff, and atomically creates missing configuration only when all three are absent. The generated store identifier and layout version must match across the three tables. Mixed or partial configuration fails with a configuration error.

Append atomically commits journal, head and optional current/history snapshots in one TransactWriteItems. The head's sequence is the optimistic-lock boundary. A failed append exposes no partial commit.

Latest snapshot reads strongly BatchGetItem for head and current snapshot, retrying unprocessed keys. The pair is non-atomic: snapshot sequence can be ahead of or behind head sequence. No head returns null; no snapshot with an existing head remains a present result. Events use strong journal Query with `aid = :aid AND seq_nr >= :seq_nr`, ascending order and every LastEvaluatedKey page.

Retention is optional. Deletion removes older surplus histories in batches of at most 25 and retries unprocessed items. Expiry retention sets `ttl` once and removes `active_history_seq_nr`, preserving already marked deadlines. Retention runs after snapshot/history appends, logs failures separately, and leaves the committed append successful. Journal and head remain intact.

See [migration](MIGRATION.md) before using the new layout with old data.
