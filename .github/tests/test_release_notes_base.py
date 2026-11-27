"""release.yml の github-release ジョブ（#925。scala#934 と rs#375 の形）。

- Create the GitHub release：リリースの一覧を1回で読み（読めなければ止める）、ノートの基点は、前の正式版のタグのうち
  GitHub のリリースがある（公開を確かめた）もの。公開されずに飛んだタグの変更も、このリリースのノートに入れる。
  Latest はここでは付けない（--latest=false）。
- Mark the greatest release as latest：一覧（下書きを除く）と今のタグのうち、最大の正式版を Latest にする。
"""
import json
import os
import re
import subprocess
import tempfile
import unittest
from pathlib import Path

from hypothesis import given, settings, strategies as st

from release_test_support import GITHUB, RELEASE, steps
from test_release_rehearsal import FAKE_GH


def step_body(name):
    text = next(item for item in steps(RELEASE, 'github-release') if f'name: {name}\n' in item)
    body = re.search(r'        run: \|\n((?:          [^\n]*\n|\n)+)', text + '\n').group(1)
    return '\n'.join(line[10:] for line in body.splitlines())


class GitHubReleaseTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.work = self.root / 'work'
        self.work.mkdir()
        self.bin = self.root / 'bin'
        self.bin.mkdir()
        (self.bin / 'gh').write_text(FAKE_GH)
        (self.bin / 'gh').chmod(0o755)
        self.releases = self.root / 'releases'
        self.log = self.root / 'gh-log'
        self.git('init', '-q')
        self.git('config', 'user.name', 'Test')
        self.git('config', 'user.email', 'test@example.invalid')
        for subject, tag in (('feat: first', 'v5.2.0'), ('fix: skipped change', 'v5.3.0'), ('feat: latest change', 'v5.4.0')):
            self.git('-c', 'commit.gpgsign=false', 'commit', '--allow-empty', '-qm', subject)
            self.git('tag', tag)

    def git(self, *args):
        return subprocess.run(['git', *args], cwd=self.work, check=True, capture_output=True, text=True).stdout

    def run_step(self, name, releases, tag='v5.4.0', **env):
        self.releases.write_text(''.join(f'{release}\n' for release in releases))
        self.log.write_text('')
        return subprocess.run(['bash', '-e', '-c', step_body(name)], cwd=self.work, capture_output=True, text=True,
                              env={**os.environ, 'PATH': f'{self.bin}{os.pathsep}{os.environ["PATH"]}',
                                   'FAKE_RELEASES': str(self.releases), 'FAKE_LOG': str(self.log),
                                   'GH_TOKEN': 'unused', 'RELEASE_TAG': tag, 'SERVER_URL': 'https://github.com',
                                   'GITHUB_REPOSITORY': 'o/r', 'GITHUB_WORKSPACE': str(GITHUB.parent),
                                   'RUNNER_TEMP': str(self.root), **env})

    def calls(self, *prefix):
        return [args for args in map(json.loads, self.log.read_text().splitlines()) if args[:len(prefix)] == list(prefix)]

    def release(self, releases):
        result = self.run_step('Create the GitHub release', releases)
        self.assertEqual(result.returncode, 0, result.stderr)
        return result.stdout, (self.root / 'release-notes.md').read_text()

    # ------------------------------------------------------------ ノートの基点と一覧の照会
    def test_a_failed_release_listing_stops_before_creating_the_release(self):
        # 照会の失敗を「リリースなし」と見て、作り直したり未公開のタグを基点にしたりしない
        result = self.run_step('Create the GitHub release', ['v5.2.0'], FAKE_RELEASES_FAIL='1')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('HTTP 502', result.stderr)
        self.assertEqual(self.calls('release'), [])

    def test_an_existing_release_is_not_created_again(self):
        result = self.run_step('Create the GitHub release', ['v5.2.0', 'v5.4.0'])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('the GitHub release v5.4.0 already exists', result.stdout)
        self.assertEqual(self.calls('release'), [])

    def test_a_skipped_tag_is_not_the_base(self):
        # v5.3.0 はタグだけで公開されていない（リリースが無い）→ 基点は v5.2.0。v5.3.0 の変更もノートに入る
        out, notes = self.release(['v5.2.0'])
        self.assertIn('release notes from v5.2.0', out)
        self.assertIn('v5.2.0...v5.4.0', notes)
        self.assertIn('skipped change', notes)
        self.assertIn('latest change', notes)

    def test_the_nearest_published_tag_is_the_base(self):
        out, notes = self.release(['v5.2.0', 'v5.3.0'])
        self.assertIn('release notes from v5.3.0', out)
        self.assertNotIn('skipped change', notes)
        self.assertIn('latest change', notes)

    def test_without_any_release_the_previous_tag_is_used(self):
        out, _ = self.release([])
        self.assertIn('release notes from v5.3.0', out)

    def test_prerelease_and_other_tags_are_not_the_base(self):
        self.git('tag', 'v5.3.1-rc.1', 'v5.3.0')
        self.git('tag', 'v5.3.9x', 'v5.3.0')
        out, _ = self.release(['v5.2.0', 'v5.3.1-rc.1', 'v5.3.9x'])
        self.assertIn('release notes from v5.2.0', out)

    def test_drafts_and_prereleases_are_not_published_releases(self):
        # 下書きとプレリリースは、公開を確かめたリリースに数えない（java#871 の Devin の指摘）
        result = self.run_step('Create the GitHub release', ['v5.2.0', 'v5.3.0'], FAKE_DRAFTS='v5.3.0')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('release notes from v5.2.0', result.stdout)
        result = self.run_step('Create the GitHub release', ['v5.2.0', 'v5.3.0'], FAKE_PRERELEASES='v5.3.0')
        self.assertIn('release notes from v5.2.0', result.stdout)
        # 今のタグのリリースが下書きかプレリリースとしてだけある：作り足さず、省きもせずに止まる
        for hidden in ('FAKE_DRAFTS', 'FAKE_PRERELEASES'):
            with self.subTest(hidden):
                result = self.run_step('Create the GitHub release', ['v5.2.0', 'v5.4.0'], **{hidden: 'v5.4.0'})
                self.assertNotEqual(result.returncode, 0)
                self.assertIn('is a draft or a pre-release', result.stdout + result.stderr)
                self.assertEqual(self.calls('release'), [])

    def test_the_release_is_created_without_taking_latest(self):
        self.release(['v5.2.0'])
        created = self.calls('release', 'create')
        self.assertEqual(len(created), 1)
        self.assertEqual(created[0][2], 'v5.4.0')
        self.assertIn('--latest=false', created[0])
        self.assertIn('--verify-tag', created[0])

    # ------------------------------------------------------------ Latest
    def mark(self, releases, tag, **env):
        result = self.run_step('Mark the greatest release as latest', releases, tag=tag, **env)
        edits = self.calls('release', 'edit')
        return result, edits

    def test_the_greatest_release_is_marked_latest(self):
        # 古い版（v5.3.0）の実行が新しい版のあとに動いても、Latest は最大の版に戻る
        result, edits = self.mark(['v1.0.511', 'v5.2.0', 'v5.4.0', 'v6.0.0-rc.1', 'v5.3.0'], 'v5.3.0')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(edits, [['release', 'edit', 'v5.4.0', '--repo', 'o/r', '--latest']])

    def test_the_current_tag_counts_while_the_list_lags(self):
        # 作った直後の一覧に今のタグがまだ出ないことがある（java#870 の M3）
        _, edits = self.mark(['v5.2.0'], 'v5.4.0')
        self.assertEqual(edits[0][2], 'v5.4.0')

    def test_a_draft_or_a_prerelease_is_not_marked_latest(self):
        # 下書きとプレリリースは候補から外す。今のタグも、下書きかプレリリースとして一覧にあれば外す（java#871 の Devin の指摘）
        _, edits = self.mark(['v5.2.0', 'v5.4.0', 'v5.5.0'], 'v5.4.0', FAKE_DRAFTS='v5.5.0')
        self.assertEqual(edits[0][2], 'v5.4.0')
        _, edits = self.mark(['v5.2.0', 'v5.4.0', 'v5.5.0'], 'v5.4.0', FAKE_PRERELEASES='v5.5.0')
        self.assertEqual(edits[0][2], 'v5.4.0')
        _, edits = self.mark(['v5.2.0', 'v5.5.0'], 'v5.5.0', FAKE_PRERELEASES='v5.5.0')
        self.assertEqual(edits[0][2], 'v5.2.0')
        result, edits = self.mark(['v5.5.0'], 'v5.5.0', FAKE_DRAFTS='v5.5.0')
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(edits, [])

    def test_a_failed_listing_marks_nothing(self):
        result, edits = self.mark(['v5.2.0'], 'v5.4.0', FAKE_RELEASES_FAIL='1')
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(edits, [])

    @settings(max_examples=40, deadline=None)
    @given(st.lists(st.sampled_from(['v5.2.0', 'v5.3.0', 'v5.4.0', 'v5.10.0', 'v10.0.0', 'v1.0.511', 'v6.0.0-rc.1',
                                     'v5.3.9x', 'release']), max_size=6, unique=True),
           st.sets(st.sampled_from(['v5.2.0', 'v5.4.0', 'v5.10.0', 'v10.0.0'])),
           st.sets(st.sampled_from(['v5.3.0', 'v5.10.0', 'v10.0.0'])),
           st.sampled_from(['v5.2.0', 'v5.3.0', 'v5.4.0', 'v5.10.0']))
    def test_p_latest_is_the_greatest_of_the_list_and_the_current_tag(self, releases, drafts, prereleases, tag):
        """Latest は「下書きでもプレリリースでもない一覧 ∪ {今のタグ（下書きかプレリリースとして一覧にあれば外す）}」の
        最大の vX.Y.Z（順序と回数に依らない）。候補が無ければ失敗して何もしない。"""
        hidden = (drafts | prereleases) & set(releases)
        result, edits = self.mark(releases, tag, FAKE_DRAFTS=' '.join(drafts), FAKE_PRERELEASES=' '.join(prereleases))
        candidates = (set(releases) - hidden) | ({tag} if tag not in hidden else set())
        stable = [name for name in candidates if re.fullmatch(r'v[0-9]+\.[0-9]+\.[0-9]+', name)]
        if not stable:
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(edits, [])
            return
        expected = max(stable, key=lambda name: tuple(int(part) for part in name[1:].split('.')))
        self.assertEqual([edit[2] for edit in edits], [expected])


if __name__ == '__main__':
    unittest.main()
