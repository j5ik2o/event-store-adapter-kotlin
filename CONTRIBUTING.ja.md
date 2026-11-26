> この文書は [CONTRIBUTING.md](CONTRIBUTING.md) の日本語版です。英語版が正本で、食い違うときは英語版に従います。

# event-store-adapter-kotlin への貢献

## 概要

この案内は、このリポジトリを変更する開発者向けです。ライブラリの使い方は [README.ja.md](README.ja.md) を参照してください。

このライブラリは、Java 配布の上にある薄い Kotlin の包みです。ビルドは `core/build.gradle.kts` の `api("io.github.j5ik2o:event-store-adapter-java-core:5.2.0")` でそれに依存します。Maven Central で公開済みの Java `5.2.0` が SQLite・Spanner・Bigtable の保存先を提供します。

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
python3 -m pip install -r .github/tests/requirements.txt
python3 -m unittest discover -s .github/tests -v
```

`release-bundle` ジョブは、Release ワークフローと同じ `.github/central-bundle.sh` と使い捨ての署名の鍵で Maven Central の bundle を作り、180 ファイルと 30 の署名を確かめます。`release-model` ジョブは、公開の経路の Quint のモデルを検査します（`bash .github/quint/run-quint.sh ci`。[.github/quint/README.md](.github/quint/README.md)）。どちらも秘密の値を使わず、何も公開しません。

同じジョブは、`conformance/manifest.json`、`user-docs/template.json`、`tools/user-docs/check.py`、`api-surface/surface.json` の SHA-256 も `sha256sum -c -` で照合し、ワークフローのファイルを `actionlint`（`go install` で入れる `actionlint@v1.7.12`）で検査します。期待するハッシュの値は `.github/workflows/ci.yml` にあります。

`build.gradle.kts` では `JavaCompile` のタスクが `spotlessApply` に依存しているため、コンパイルでソースの整形が書き換わることがあります。

## 設計の経緯

このリポジトリには独自の設計文書がありません。Kotlin の設計判断は、ハブの実装計画に記録されています。

- [実装計画](https://github.com/j5ik2o/event-store-adapter/blob/main/docs/plan/implementation-plan.md)
- [4.2 言語ごとの主な作業](https://github.com/j5ik2o/event-store-adapter/blob/main/docs/plan/implementation-plan.md#42-言語ごとの主な作業): Kotlin は `api(...)` で Java の新しい API に依存し、`kotlinx.coroutines` の `await` を使います。
- [決めたこと（2026-10-13）](https://github.com/j5ik2o/event-store-adapter/blob/main/docs/plan/implementation-plan.md#決めたこと2026-10-13): Kotlin と Scala は Java の封筒の型をそのまま公開し、包む層は言語で違う部分（Kotlin の null 許容型とコルーチン）だけを合わせます。Kotlin には設計文書がないので、この記録をもって設計段階のレビューとします。

## 変更とリリース

- 変更は pull request で `main` に入れます。`CI Success` ジョブが、lint・test・release-checks・release-bundle・release-model・aislop の結果をまとめて判定します。
- 版は `version` ファイルに持ち、ビルドが読みます。手で版を上げないでください。
- Bump-Version ワークフローは手動で起動します。入力は `level`（`auto`、`patch`、`minor`、`major`）・`force_bump`・`version`・`rehearsal` です。`level=auto` の場合だけ、Conventional Commits から段階を決めます（`.github/semver-level.py`）。`!`、`BREAKING CHANGE:`、`BREAKING-CHANGE:` は major、`feat` と `revert` は minor、ほかの型は patch です。`level` を明示した場合はその値を使います。`version` を明示した場合は段階を使わず、指定した版を次の版にします。
- Bump-Version は `main` へ push しません。新しい版を `release/vX.Y.Z` の枝の `version` にコミットし、`main` への pull request を開きます。GitHub Release は作らず、`version` を SNAPSHOT 版へ戻すこともしません。
- `version` を変えたコミットが `main` に入ると、Bump-Version のタグのジョブが、関門（`.github/release-gate.py`）でそのコミットを確かめます。コミットは、このリポジトリの `release/vX.Y.Z` からの pull request を squash でマージしたもので、`version` の版だけを変えていなければなりません。版は、前の版とすべての `vX.Y.Z` のタグより大きくなければなりません。親のコミットに `version` が無いときは、リリースしません。
- `vX.Y.Z` のタグを push すると Release ワークフローが起動します。Release ワークフローは、タグのコミットをもう一度関門で確かめてから、その版を Maven Central へ公開します（[リリースの出し方](#リリースの出し方)）。Snapshot（試験版）は公開しません。正式版を使ってください。
- 依存の更新は Renovate が `chore(deps):` の接頭辞付きで出します。

保存先ごとの実装は `core`・`dynamodb`・`spanner`・`bigtable`・`sqlite` に分かれます。root は全モジュールの依存をまとめ、既存の統合テストを実行します。core の保存先依存は compileOnly です。`./gradlew :core:test :dynamodb:test verifyModulePoms` で限定クラスパスと生成 POM を検証します。

### リリースの出し方

1. Bump-Version を流します（`rehearsal: false`）。版を決めるときは `version` に書きます。空なら、版を上げる段階で決まります。
2. pull request `version up to vX.Y.Z` を確かめます。変えているのが `version` だけで、Check unresolved comments が成功していなければなりません。リリースを出してよいときに、squash でマージします。やめるときは、閉じて枝を消します。
3. タグのジョブ（`Tag the merged version`）が、マージのコミットを関門で確かめ、`vX.Y.Z` を push します。
4. Release ワークフローが、ジョブ `Publish to Maven Central` を次の順に流します。
   1. タグのコミットを関門で確かめます。
   2. Gradle がタグの版をビルドすることを確かめます。
   3. Maven Central と前の Release の run を確かめます（`.github/central-portal.py precheck`）。処理中の deployment があれば待ちます（最大 60 分）。版が公開済みならアップロードせずに先へ進み、行方の分からない試みがあれば止まります。
   4. bundle（180 ファイル。6つの成果物それぞれの jar・sources・javadoc・POM・Gradle の module のファイルと、その署名と4種の checksum）を作り、Central Portal へ上げます（`publishingType=AUTOMATIC`）。
   5. deployment の ID を artifact `central-deployment-<試みの番号>-<ID>` に残します（90 日）。
   6. deployment が `PUBLISHED` になるまで待ちます（最大 60 分）。
5. `PUBLISHED` になったら、`Create the GitHub release` が GitHub のリリースを作り、`Check Maven Central` が、repo1 が6つの POM を返すまで待ちます（最大 60 分）。

予行（`rehearsal: true`）は、`version up to vX.Y.Z (rehearsal, do not merge)` という題の draft の pull request を開きます。マージせずに閉じて、枝を消してください。

署名の秘密の値は bundle の手順だけに、Sonatype の秘密の値は事前の確認・アップロード・待ちの手順だけに渡します。`release.yml` は personal access token を使いません。

確かめる仕組みを保つための決まり：

- `vX.Y.Z` のタグを手で作らないでください。GitHub のリリースの画面で作るタグも同じです。タグは、そのタグが指すコミットの Release ワークフローを動かします。Release ワークフローに関門が入る前のコミットには、タグを作らないでください。そのコミットの Release ワークフローは、関門も事前の確認も無しに公開します。
- Release の run と、その `central-` の artifact を消さないでください。artifact の保存期間は 90 日のままにします。事前の確認は、これらを読んで前の試みの deployment を見つけます。
- `release.yml` のジョブと手順の名前を変えないでください。事前の確認は、`.github/central-rules.py` の名前（`RELEASE_JOB`、`UPLOAD_STEP`、`PRE_UPLOAD_STEPS`）で、前の run のアップロードを見つけます。どうしても変えるときは、古い名前も集合に残します。
- `release.yml` を変える pull request では、`.github/tests/fixtures/release-workflow.yml`（レビュー済みの写し）も同じように変えます。`.github/tests/test_release_workflow_shape.py` が `PUSH_RELEASE_TAG: 'true'` を許すのは、`release.yml` がこの写しと行ごとに一字一句同じときだけです。違ってよいのは `uses:` の行の版だけです。
- 失敗した run・取り消された run を再実行できるのは、始まってから 30 日までです。

リリースを止めるときは、`bump-version.yml` の `PUSH_RELEASE_TAG` を `'false'` にする pull request をマージします。`'false'` の間、タグのジョブは、打つはずのタグを job summary に書いて終わります。その間にマージした版上げの pull request の版は、タグが付かないまま `main` に残ります。pull request で `version` を前の SNAPSHOT 版に戻し、出したい版を `version` に指定して Bump-Version をやり直してください。

再実行は、`main` ではなく、その run のコミットのワークフローのファイルで動きます。リリースを止めている間は、止める前に動いたタグのジョブ（`Tag the merged version`）を再実行しないでください。その `bump-version.yml` は `PUSH_RELEASE_TAG: 'true'` のままなので、タグを push します。止める前に push したタグの Release の run も同じです。再実行するのは、リリースを再開してからにします。

### 5.2.0 が Maven Central に出るまで

タグ `v5.2.0` と GitHub のリリースはありますが、5.2.0 は Maven Central にありません。2026-11-11 の公開が拒否されたためです（[#908](https://github.com/j5ik2o/event-store-adapter-kotlin/issues/908)）。5.2.0 は、その Release の run（[42966589471](https://github.com/j5ik2o/event-store-adapter-kotlin/actions/runs/42966589471)）の失敗したジョブを再実行して出します。再実行は、タグのコミット `7a49995` の Release ワークフローで動き、関門を持ちません。事前の確認は、この run を数えません（`.github/central-rules.py` の `FIRST_COUNTED_RUN_ID`）。

- repo1 が 5.2.0 の6つの POM を返すまで、関門は、タグのジョブでも Release ワークフローでも、それより新しい版をすべて拒否します（`.github/release-gate.py` の `REQUIRED_PUBLISHED = '5.2.0'`）。Portal で `PUBLISHED` でも、repo1 にまだ無ければ拒否します。
- 5.2.0 が repo1 に出る前に、版上げの pull request をマージしないでください。マージした場合、タグのジョブが関門で失敗し、タグも公開もありません。マージした版は 5.2.0 の再実行を妨げません。再実行は `v5.2.0` のワークフローで動き、マージした版は関門が止めておくからです。repo1 が 5.2.0 の6つの POM を返すようになったら、失敗したタグのジョブを再実行します。
- repo1 は 404 をしばらく覚えています。5.2.0 を待つジョブを再実行する前に、6つの POM を確かめてください（`.github/central-bundle.py` の成果物ごとに `https://repo1.maven.org/maven2/io/github/j5ik2o/<成果物>/5.2.0/<成果物>-5.2.0.pom`）。
- 失敗したタグのジョブを再実行できるのは、始まってから 30 日までです。過ぎたら、マージした版は飛ばします。次の版で Bump-Version を流してください（たとえば、`5.2.1` がタグなしで `main` にあるなら `version=5.2.2`）。
- この公開の経路より前のコミットにタグを作らないでください。そのコミットの Release ワークフローはタグのコミットの `version` の版を公開し、それらのコミットの版は 5.2.0 以下か SNAPSHOT なので、5.2.0 より新しい版は出ません。それでも、関門と事前の確認の外で公開することになり、あとの事前の確認はその run で止まります（ジョブの名前が `Release` のため）。
- repo1 が 5.2.0 を返すようになったら、`REQUIRED_PUBLISHED = None` にする pull request を出します。それまでは、関門が動くたびに repo1 の 5.2.0 の6つの POM を読みます。

### リリースが途中で止まったとき

たいていは、その run の失敗したジョブを再実行すれば回復します（Re-run failed jobs）。再実行のたびに、アップロードの前に Maven Central と前の試みを確かめ直すので、同じ版を二度上げることはありません。

止まり方によっては、repository variable `CENTRAL_DEPLOYMENTS`（Settings → Secrets and variables → Actions → Variables）に行を足します。1行に1つ書きます。

- `<run_id>/<試みの番号>=<deployment の ID>`：run `<run_id>` の試み `<試みの番号>` が上げた deployment。
- `<run_id>/<試みの番号>=none`：その試みは何も上げていない。または、その deployment を Portal で drop した。
- `tag/vX.Y.Z=checked`：その版の deployment が処理中でないことを、Portal で確かめた。

ID が無くて事前の確認が止まったときは、そのエラーの文が、足す行を `<run_id>/1` から `<run_id>/<run_attempt>` まで、行の無い試みすべてについてまとめて出します。試みの deployment は、Portal で `event-store-adapter-kotlin-<版>-run<run_id>-<試みの番号>` という名前になっています。

| 起きたこと | ワークフローの動き | 回復 |
|---|---|---|
| 5.2.0 がまだ repo1 に無い（`must wait until 5.2.0 is published on Maven Central`） | タグのジョブか Release ワークフローの関門が失敗する。タグもビルドもアップロードもしない | 先に 5.2.0 を出す（[5.2.0 が Maven Central に出るまで](#520-が-maven-central-に出るまで)）。repo1 が6つの POM を返すようになったら、30 日のうちに失敗したジョブを再実行する |
| repo1 で 5.2.0 を読めなかった（`could not read whether 5.2.0 is on Maven Central`） | 関門が失敗する | 失敗したジョブを再実行する |
| タグが版上げの pull request の squash に付いていない、版と一緒にコードを変えた、版を下げた、タグと版が合わない | 関門の手順が失敗する。ビルドもアップロードもしない | タグを消す。`version` を pull request で直す |
| Gradle がタグと違う版をビルドする | `Check the version Gradle builds` が失敗する | `version` を pull request で直し、次の版で出す |
| GitHub・Portal・repo1 への照会が失敗した（5xx、時間切れ） | 事前の確認が止まる | 失敗したジョブを再実行する |
| ほかの run の deployment が処理中 | 事前の確認が最大 60 分待ち、時間切れで失敗する | 失敗したジョブを再実行する |
| アップロードしたかもしれないのに、deployment の ID が無い試みがある（手順の一覧が切れた試み、ジョブの無い run、release ジョブの無い試みを含む） | 事前の確認が止まり、足す行を出す | 試みの job summary やログ、または Portal で名前から ID を探す。出された試みすべてに `<run_id>/<試みの番号>=<ID>` か `=none` を足して再実行する |
| アップロードが 201 と deployment の ID を返さない | アップロードの手順が失敗する。次の試みは上の行のとおり止まる | 上の行と同じ |
| bundle のファイルが足りない・多い | bundle の手順が失敗する。アップロードしない | pull request で直し、次の版で出す。再実行はタグのコミットのスクリプトで動くので、直しは効かない |
| 署名の鍵が無い・違う | bundle の手順の署名で失敗する。アップロードしない | 秘密の値を直して再実行する |
| deployment が `FAILED` | 待ちの手順が Portal の errors を出して失敗する。GitHub のリリースは作らない | 失敗したジョブを再実行する（事前の確認が `FAILED` を見て、上げ直す）。中身の誤りなら次の版で直す |
| `FAILED` の deployment を Portal で drop した | 事前の確認が止まる（ID が見つからない） | drop したあとに `<run_id>/<試みの番号>=none` を足して再実行する |
| 60 分たっても deployment が処理中 | 待ちの手順が失敗する | 失敗したジョブを再実行する。事前の確認は同じ deployment を待ち、上げ直さない |
| 60 分たっても repo1 が版を返さない | `Check Maven Central` が失敗する | `Check Maven Central` だけを再実行する |
| GitHub のリリースを作れなかった | `Create the GitHub release` が失敗する | `Create the GitHub release` だけを再実行する |
| より新しいタグがある（版が追い越された） | 関門が止まる（`must be greater than the latest tag`）。その版は公開しない | そのまま（飛んだ版）。その版が既に `PUBLISHED` だったとき（待ちが時間切れになり、再実行の前に新しいタグができた）は、[新しいタグの前に公開された版](#新しいタグの前に公開された版)の手順で回復する |
| 待っていた公開のジョブが、`release-publish` の group にあとから着いたジョブ（ほかのタグの push、ほかの版の再実行）に置き換えられた | その run は始まる前に取り消される。その run は何も公開しない | その版が最新の `vX.Y.Z` のタグなら、取り消された run のジョブをすべて再実行する（取り消されたジョブは、アップロードに数えない）。そうでなければ、再実行しても新しいタグがあるので関門で止まる。前の試みで版が既に公開されていたとき（たとえば GitHub のリリースの作成だけが失敗していた）は、[新しいタグの前に公開された版](#新しいタグの前に公開された版)の手順で回復する。公開されていなければそのまま（飛んだ版） |
| タグのジョブ（`Tag the merged version`）が、`release-tag` の group にあとから着いたジョブに置き換えられた | その run は取り消される。その版にタグが付かない | その版が最新の `vX.Y.Z` のタグより大きければ、取り消された run を再実行する。あとにほかの pull request がマージされていても同じ（関門は `main` の先頭であることを求めない）。そうでなければそのまま（関門が拒否する） |
| `CENTRAL_DEPLOYMENTS` の行の形が違う、artifact と違う ID を書いた、Portal が知っている deployment に `none` を書いた | 事前の確認が止まる | 行を直すか消して、再実行する |
| Release の run や `central-` の artifact を消した。タグの push で Release の run が起きなかった（一度に4つ以上のタグの push、障害） | 事前の確認が止まる（Release の run の無いタグがある） | その版の deployment が処理中でないことを Portal で確かめ、`tag/vX.Y.Z=checked` を足して再実行する。タグは動かさない |
| `release.yml` のジョブや手順の名前を変えた | 前の run で、事前の確認が止まる | 変えない。どうしても変えるときは、古い名前も `.github/central-rules.py` に残す |
| artifact の保存期間を 90 日より短くした | 事前の確認が止まる | 90 日に戻す |
| 30 日を過ぎて、run を再実行できない | | その版は飛んだ版として扱い、次の版を出す。その前に、行方の分からない試みすべてに行を足す |
| 80 日より前の run | 事前の確認は数えない | |

#### 新しいタグの前に公開された版

版は Maven Central に出ているのに、その run が終わる前に新しい `vX.Y.Z` のタグが push された場合、古い版の再実行は、どれも関門で止まります。起きるのは、deployment の待ちが時間切れになり、そのあと Portal が公開したとき、または前の試みが公開したあとで後の手順（たとえば GitHub のリリースの作成）が失敗し、再実行が `release-publish` の group で置き換えられたときです。このとき、GitHub のリリースと repo1 の確認が欠けます。手で回復します。

1. repo1 が版を返すことを確かめます。`.github/central-bundle.py` の6つの成果物（`ARTIFACTS`）それぞれについて、`https://repo1.maven.org/maven2/io/github/j5ik2o/<成果物>/<版>/<成果物>-<版>.pom` を見ます。
2. 前のリリースのタグ `<previous>` からリリースノートを書きます。

   ```sh
   python3 .github/create-release-note-header.py https://github.com j5ik2o/event-store-adapter-kotlin <previous> vX.Y.Z > release-body.md
   git log <previous>..vX.Y.Z --no-merges --format='%H%x1f%s%x1f%b%x1e' \
     | python3 .github/create-release-note-body.py https://github.com j5ik2o/event-store-adapter-kotlin >> release-body.md
   ```

3. リリースを作ります：`gh release create vX.Y.Z --verify-tag --title "Release vX.Y.Z" -F release-body.md`。

この場合のために関門を緩めることはしません。関門は、事前の確認より前に、秘密の値を持たずに、版がほかのどのタグより大きいことを確かめ続けます。
