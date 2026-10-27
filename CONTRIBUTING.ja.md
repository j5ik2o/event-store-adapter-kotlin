> この文書は [CONTRIBUTING.md](CONTRIBUTING.md) の日本語版です。英語版が正本で、食い違うときは英語版に従います。

# event-store-adapter-kotlin への貢献

## 概要

この案内は、このリポジトリを変更する開発者向けです。ライブラリの使い方は [README.ja.md](README.ja.md) を参照してください。

このライブラリは、Java 配布の上にある薄い Kotlin の包みです。ビルドは `build.gradle.kts` の `api("io.github.j5ik2o:event-store-adapter-java:5.1.0")` でそれに依存します。Maven Central で公開済みの Java `5.1.0` が SQLite・Spanner・Bigtable の保存先を提供します。

## 共有する契約と設計判断

このライブラリは共通契約 v5 に従います。共有する仕様と設計判断（ADR）はハブのリポジトリにあります。

- 仕様: <https://github.com/j5ik2o/event-store-adapter/tree/main/docs/spec>
- 設計判断: <https://github.com/j5ik2o/event-store-adapter/tree/main/docs/adr>

このリポジトリには、ハブから取り込んだ写しが2つあります。この案内では、どちらも読むだけのものとして扱います。

- `conformance/` の[配布適合データ](conformance/README.md)
- `api-surface/` の [API の形の照合](api-surface/README.md)

## ローカルの準備

- Gradle: Gradle Wrapper の `./gradlew` を使います。`gradle/wrapper/gradle-wrapper.properties` で Gradle 9.8.2 に固定されています。
- JDK: CI は test ジョブで JDK 11、試験の行列の JDK、JDK 25 を入れ、最後の JDK（25）で Gradle を動かします。ビルドは Java 11 向けにコンパイルします（`build.gradle.kts` の `jvmToolchain(11)` と `options.release.set(11)`）。試験を動かす JVM は `-PtestJavaVersion` で選び、既定は 11 です。CI は試験の行列を JDK 11、17、19 で実行します。
- Docker: DynamoDB・Spanner・Bigtable の試験には Docker が必要です。固定ダイジェストの DynamoDB Local 3.3.1、Cloud Spanner エミュレーター 1.5.58、Cloud Bigtable エミュレーターを使います（[README.ja.md](README.ja.md) の「適合」を参照。後ろの2つのダイジェストは `src/test/java` の `SpannerEmulator` と `BigtableEmulator` にあり、CI が試験の前に取得します）。SQLite の試験は一時ディレクトリの実ファイルを使います。エミュレーターを起動できない試験は失敗し、飛ばされることはありません。
- Python 3: 文書と適合データの検査を動かします。CI は Python 3.15 を使います。 `tools/user-docs/check.py` は Python 3.10 以上が必要です。

## 検査

次は、CI（`.github/workflows/ci.yml`）が実行するか、README に書かれているコマンドです。

Lint:

```
./gradlew spotlessCheck
```

試験。試験用の JVM の版を指定します（CI は 11、17、19）。適合の結果は `build/reports/conformance/report.json` に出ます。

```
./gradlew test -PtestJavaVersion=11
```

ビルドと Lint をまとめて実行します（[README.ja.md の「適合」](README.ja.md#適合)）。

```
./gradlew build spotlessCheck
```

API の形の照合だけを実行します（[api-surface/README.md](api-surface/README.md) を参照）。

```
./gradlew test --tests '*apisurface*'
```

リリース自動化の検査（CI の `release-checks` ジョブ）:

```
python3 tools/conformance/manifest.py verify
python3 tools/user-docs/check.py
python3 -m unittest discover -s .github/tests -v
```

同じジョブは、`conformance/manifest.json`、`user-docs/template.json`、`tools/user-docs/check.py`、`api-surface/surface.json` の SHA-256 も `sha256sum -c -` で照合し、ワークフローのファイルを `actionlint`（`go install` で入れる `actionlint@v1.7.12`）で検査します。期待するハッシュの値は `.github/workflows/ci.yml` にあります。

`build.gradle.kts` では `JavaCompile` のタスクが `spotlessApply` に依存しているため、コンパイルでソースの整形が書き換わることがあります。

## 設計の経緯

このリポジトリには独自の設計文書がありません。Kotlin の設計判断は、ハブの実装計画に記録されています。

- [実装計画](https://github.com/j5ik2o/event-store-adapter/blob/main/docs/plan/implementation-plan.md)
- [4.2 言語ごとの主な作業](https://github.com/j5ik2o/event-store-adapter/blob/main/docs/plan/implementation-plan.md#42-言語ごとの主な作業): Kotlin は `api(...)` で Java の新しい API に依存し、`kotlinx.coroutines` の `await` を使います。
- [決めたこと（2026-10-13）](https://github.com/j5ik2o/event-store-adapter/blob/main/docs/plan/implementation-plan.md#決めたこと2026-10-13): Kotlin と Scala は Java の封筒の型をそのまま公開し、包む層は言語で違う部分（Kotlin の null 許容型とコルーチン）だけを合わせます。Kotlin には設計文書がないので、この記録をもって設計段階のレビューとします。

## 変更とリリース

- プルリクエストは `main` へ出します。CI の集約ジョブは `CI Success` で、lint、test、release-checks のジョブを待ちます。
- `version` ファイルが版を持ちます（リリースの間は開発版）。手で版を上げないでください。
- 版上げは、手動で実行する `Bump-Version` ワークフロー（`workflow_dispatch`）で行います。入力は `level`（`auto`、`patch`、`minor`、`major`）、`force_bump`、`version` です。`level=auto` の場合だけ Conventional Commits から段階を算出します。`level` を明示した場合はその値を使います。`version` を明示した場合は段階を計算せず、指定した版を使います。
- タグ `vX.Y.Z` を push すると `Release` ワークフローが Sonatype へ公開します。`main` の CI が成功した後、プルリクエストの実行ではなく、公開対象と成功した CI のコミットが一致し、公開直前もそのコミットが `main` の先頭である場合だけ、`Snapshot` ワークフローがスナップショットを公開します。
- 依存の更新は Renovate が `chore(deps):` の接頭辞付きで出します。
