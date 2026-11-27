"""リリースの経路のテストが共有する読み込みと補助関数。"""
import importlib.util
import re
from pathlib import Path


GITHUB = Path(__file__).resolve().parents[1]
WORKFLOWS = GITHUB / 'workflows'
GATE_SCRIPT = GITHUB / 'release-gate.py'
BUMP = (WORKFLOWS / 'bump-version.yml').read_text()
RELEASE = (WORKFLOWS / 'release.yml').read_text()
REPOSITORY = 'o/r'
VERSION_FILE = 'version'
# 手元の HTTP のスタブ（127.0.0.1）を叩く子プロセスの no_proxy（環境のプロキシへ送らない）
LOOPBACK = '127.0.0.1,localhost'


def load_script(filename, name):
    spec = importlib.util.spec_from_file_location(name, GITHUB / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def load_gate():
    return load_script(GATE_SCRIPT.name, 'release_gate')


def serve_required(stub, code, version=None):
    """repo1 のスタブ（/repo1）が、公開先に必要な版の6つの POM に code を返すようにする。code は数か関数。"""
    bundle = load_script('central-bundle.py', 'central_bundle_for_required')
    version = version or load_gate().REQUIRED_PUBLISHED
    for artifact in bundle.ARTIFACTS:
        path = f'/repo1/{bundle.GROUP_PATH}/{artifact}/{version}/{artifact}-{version}.pom'
        stub.routes[('HEAD', path)] = lambda request, artifact=artifact: (code(artifact) if callable(code) else code, b'')


def run_body(workflow, step_id):
    return re.search(
        r'id: ' + step_id + r'\n.*?        run: \|\n((?:          [^\n]*\n|\n)+)',
        workflow, re.DOTALL,
    ).group(1)


def run_blocks(workflow):
    blocks = re.findall(r'run: \|\n((?:          [^\n]*\n|\n)+)', workflow)
    return blocks + re.findall(r'run: ([^|\n][^\n]*)', workflow)


def job(workflow, name):
    start = workflow.index('\n  ' + name + ':\n')
    end = re.search(r'\n  [a-z-]+:\n', workflow[start + 1:])
    return workflow[start:start + 1 + end.start()] if end else workflow[start:]


def steps(workflow, job_name):
    """ジョブの手順を、書いた順に文字列で返す。"""
    body = job(workflow, job_name)
    body = body[body.index('\n    steps:\n'):]
    return ['\n      - ' + part for part in body.split('\n      - ')[1:]]


def step(workflow, step_id):
    start = workflow.rindex('\n      - ', 0, workflow.index('id: ' + step_id + '\n'))
    end = workflow.find('\n      - ', start + 1)
    end = len(workflow) if end == -1 else end
    following_job = re.search(r'\n  [a-z-]+:\n', workflow[start + 1:end])
    return workflow[start:start + 1 + following_job.start()] if following_job else workflow[start:end]


def outputs(path):
    return dict(line.split('=', 1) for line in path.read_text().splitlines() if line)


def pull(sha, ref='release/v5.3.0', repo=REPOSITORY, merged=True, base='main', number=1, head_sha=None,
         whole_diff='default'):
    """GET pulls/<番号> の応答の形に、release-gate-inputs.sh が git で足す whole_diff（全体の差分）を加えたもの。
    whole_diff の既定は、5.2.1-SNAPSHOT から ref の版（release/vX.Y.Z）へ版だけを替える差分。None なら付けない（#925）。"""
    if whole_diff == 'default':
        after = ref.removeprefix('release/v') if ref.startswith('release/v') else '5.3.0'
        whole_diff = {'files': [VERSION_FILE], 'before': '5.2.1-SNAPSHOT\n', 'after': after + '\n'}
    value = {
        'number': number, 'merged_at': '2026-11-25T00:00:00Z' if merged else None,
        'merge_commit_sha': sha, 'base': {'ref': base},
        'head': {'ref': ref, 'sha': head_sha or 'f' * 40, 'repo': {'full_name': repo}},
    }
    if whole_diff is not None:
        value['whole_diff'] = whole_diff
    return value


def pull_head(git, work, merged, number=1, commits=None):
    """merged（main に入った squash のコミット）の PR の head を作り、origin の refs/pull/<番号>/head に push する。
    git は git(cwd, *args) の形の関数。既定は、merged の親から merged と同じ木を持つ1つのコミット（squash の前の版上げの
    ブランチ）。commits を渡すと、merged の親から {ファイル: 中身か None（消す）} を順に積んだコミットを head にする。
    head の SHA を返す。"""
    parent = git(work, 'rev-parse', f'{merged}^')
    if commits is None:
        tree = git(work, 'rev-parse', f'{merged}^{{tree}}')
        head = git(work, 'commit-tree', tree, '-p', parent, '-m', 'the version branch')
    else:
        current = git(work, 'rev-parse', 'HEAD')
        git(work, 'checkout', '-q', '--detach', parent)
        for files in commits:
            for name, text in files.items():
                if text is None:
                    (work / name).unlink()
                else:
                    (work / name).write_text(text)
            git(work, 'add', '-A')
            git(work, 'commit', '-q', '--allow-empty', '-m', 'a pull request commit')
        head = git(work, 'rev-parse', 'HEAD')
        git(work, 'checkout', '-q', current)
    git(work, 'push', '-q', 'origin', f'+{head}:refs/pull/{number}/head')
    return head
