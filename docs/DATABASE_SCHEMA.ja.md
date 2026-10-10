# DynamoDBの保存配置

Kotlinの生成入口はJava新公開ストアと[共通DynamoDBプロファイル](https://github.com/j5ik2o/event-store-adapter/blob/main/docs/spec/storage/dynamodb.md)を使います。次の独立3表を外部で作成し、名前を `DynamoDbTableConfig` へ指定してください。3表は同じリージョンに置き、そのリージョンだけから書き込みます。

| 表 | パーティションキー | ソートキー | グローバルセカンダリインデックス | Streams |
|---|---|---|---|---|
| journal | `aid` (S) | `seq_nr` (N) | なし | 無効 |
| snapshot | `aid` (S) | `skey` (N) | `aid` (S)、`active_history_seq_nr` (N)、射影KEYS_ONLY | 無効 |
| head | `aid` (S) | なし | なし | 有効、NEW_IMAGE |

期限切れ方式を使う場合はsnapshot表の属性 `ttl` で期限切れを有効にします。インデックス名は `snapshotAidIndexName` に渡します。ライブラリは設定項目を照合・初期化しますが、表は作成しません。Sは文字列、Nは数値、Bはバイナリ、Lはリスト、Mは属性マップです。

集約キーは `typeName-value` の完全一致です。型名は `-` を含めず、全体のUTF-8は1024バイト以下にします。シャード、ハッシュ、key resolver、version属性は使いません。イベント番号は1から連続し、共通上限9007199254740991までです。

| 項目 | 属性と型 |
|---|---|
| 各表の設定 | `aid:S = "__config__"`、`store_id:S`、`layout_version:N = "1"`。journalは `seq_nr:N = "0"`、snapshotは `skey:N = "0"` も持つ |
| ジャーナル | `aid:S`、`seq_nr:N`、`occurred_at:N`（エポックからの整数ナノ秒）、`manifest:S`、`payload:B` |
| 現在のスナップショット | `aid:S`、`skey:N = "0"`、`seq_nr:N`、`manifest:S`、`payload:B`、`last_updated_at:N`（イベント時刻のエポックミリ秒） |
| 印のない履歴 | 現在の属性の `skey` を `seq_nr` にし、`active_history_seq_nr:N = seq_nr` を追加 |
| 期限を付けた履歴 | 履歴の属性に `ttl:N`（エポック秒）を持ち、`active_history_seq_nr` は持たない |
| ヘッド | `aid:S`、`type_name:S`、`seq_nr:N`、`events:L`。1件の `M` が `seq_nr:N`、`occurred_at:N`、`manifest:S`、`payload:B` を持つ |

設定項目は履歴索引用属性と期限を持ちません。現在のスナップショットも `active_history_seq_nr` と `ttl` を持ちません。バイナリはペイロードまたは集約状態だけを直列化し、封筒メタデータを分けます。manifest省略時は空文字列を保存します。

生成時はBatchGetItemで3設定項目を強整合で読み、未処理キーだけを上限付き指数バックオフで再要求します。3件とも不存在の場合だけ設定を原子的に作ります。3表のストア識別子と配置版は一致が必要で、一部欠落・混在・版違いは設定エラーです。

追記は1回のTransactWriteItemsでジャーナル、ヘッド、任意の現在・履歴スナップショットを同時確定します。楽観ロックの境界はヘッド番号で、失敗した追記の部分確定はありません。

最新読取はヘッドと現在の封筒をBatchGetItemで強整合に読み、未処理キーを再要求します。組は非原子的なので封筒の番号がヘッド番号の前後にずれ得ます。ヘッドがなければnull、ヘッドだけなら封筒なしの結果です。イベントはjournalの `aid = :aid AND seq_nr >= :seq_nr` を強整合でQueryし、番号の昇順で全ページを読み切ります。

保持は任意です。削除方式は古い超過履歴を最大25件ずつ削除し、未処理項目を再送します。期限切れ方式は `ttl` を1回付けて `active_history_seq_nr` を取り除き、既存の期限を先送りしません。保持は履歴を伴う追記の後に行い、失敗を別経路で通知して確定した追記の成功を保ちます。ジャーナルとヘッドは削除しません。

旧データには[移行案内](MIGRATION.ja.md)を適用してください。
