# API の形の照合（kotlin）

共通契約 v5 が公開 API に求める「形」を、ハブの [`api-surface/surface.json`](https://github.com/j5ik2o/event-store-adapter/tree/main/api-surface)（形式 0.1.0）と照合します。

| ファイル | 内容 |
|---|---|
| `surface.json` | ハブの定義をそのまま写したもの。SHA-256 は `37c91f8f94e10c73513a9c1deef990ea4deea96d5001ae700d6bf799658d0e32` |
| `kotlin.json` | 共通の名前と kotlin の公開 API の対応 |

## 照合のしかた

```sh
echo "37c91f8f94e10c73513a9c1deef990ea4deea96d5001ae700d6bf799658d0e32  api-surface/surface.json" | sha256sum -c -
./gradlew test --tests '*apisurface*'
```

SHA-256 の照合は CI の `release-checks` ジョブでも実行します。形の照合は `src/test/kotlin/.../apisurface/` の試験で、`kotlin-reflect` により同期の `EventStore<P, A>` と非同期の `EventStoreAsync<P, A>` を読み、`surface.json` と `kotlin.json` を突き合わせます。

## 確かめていること

- 4 つの操作（`persistEvent`・`persistEventAndSnapshot`・`getLatestSnapshotById`・`getEventsByIdSinceSeqNr`）の引数と戻り値。`none` は `Unit`、`optional<T>` は null 許容の `T?`、`list<T>` は `List<T>`。
- 非同期のストアの操作がすべて `suspend` であり、同期のストアの操作は `suspend` でないこと。
- 型の引数が `P`・`A` で、上限の制約がないこと。
- 封筒・`SnapshotReadResult`・例外の分類のクラスが、対応表の名前どおりに java のパッケージで解決できること。
- 対応表の抜けは、黙って飛ばさず報告します。
- 否定の試験（`ApiSurfaceNegativeTest`）で、形をわざとずらした 5 つのインターフェイスを、それぞれ報告し、関係のないずれは報告しないこと。

## 確かめられないこと

- 封筒（`EventEnvelope`・`SnapshotEnvelope`）・`SnapshotReadResult`・例外の形そのもの。kotlin は java のものをそのまま使うため、java の照合に任せます。
- 振る舞い（適合テストデータ `conformance/` で確かめます）。
- メモリ・DynamoDB の実装。公開の型と操作だけが対象です。
