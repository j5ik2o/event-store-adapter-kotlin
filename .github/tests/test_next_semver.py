"""next-semver.py と、版上げの「次の版」の手順（#925）。

next-semver.py は先頭の v だけを外し、残り全体を SemVer として読む（`.*v?(\\d+…)` は v10.2.0 を 0.2.0 と読んでいた）。
版上げの手順は、決めた次の版が `version` の今の版より大きいことを release-gate.py check-next で確かめる。
"""
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

import semver
from hypothesis import given, settings, strategies as st

from release_test_support import BUMP, GITHUB, outputs, run_body

SCRIPT = GITHUB / 'next-semver.py'
LEVELS = ('patch', 'minor', 'major', 'snapshot')
# 子の python3 にも semver を見せる（CI は requirements.txt を入れた python3）
PATH = f'{Path(sys.executable).parent}{os.pathsep}{os.environ["PATH"]}'


def next_semver(text, level):
    return subprocess.run([sys.executable, str(SCRIPT), level], input=text, capture_output=True, text=True)


def expected(version, level):
    current = semver.Version.parse(version)
    if level == 'snapshot':
        return f'{current.bump_patch()}-SNAPSHOT'
    return str({'patch': current.bump_patch, 'minor': current.bump_minor, 'major': current.bump_major}[level]())


class NextSemverTest(unittest.TestCase):
    def test_a_two_digit_major_is_read_whole(self):
        self.assertEqual(next_semver('v10.2.0\n', 'patch').stdout, '10.2.1\n')
        self.assertEqual(next_semver('v10.2.0\n', 'major').stdout, '11.0.0\n')
        self.assertEqual(next_semver('v123.45.678\n', 'minor').stdout, '123.46.0\n')

    def test_no_syntax_warning(self):
        result = next_semver('v5.2.0\n', 'patch')
        self.assertEqual((result.returncode, result.stdout, result.stderr), (0, '5.2.1\n', ''))

    @settings(max_examples=60, deadline=None)
    @given(st.integers(0, 10 ** 6), st.integers(0, 10 ** 6), st.integers(0, 10 ** 6), st.booleans(),
           st.sampled_from(LEVELS), st.sampled_from(['', ' ', '\t']), st.sampled_from(['\n', '\r\n', '', ' \n']))
    def test_p_every_version_is_bumped_like_semver(self, major, minor, patch, prefixed, level, before, after):
        version = f'{major}.{minor}.{patch}'
        result = next_semver(before + ('v' if prefixed else '') + version + after, level)
        self.assertEqual((result.returncode, result.stdout), (0, expected(version, level) + '\n'), result.stderr)

    @settings(max_examples=40, deadline=None)
    @given(st.sampled_from(['v', 'vx.y.z', 'foo-v1.2.3', 'release', 'v1.2', 'v01.2.3', 'V1.2.3', 'v1.2.3.4', 'vv1.2.3',
                            '1.2.3v']), st.sampled_from(LEVELS))
    def test_p_a_line_that_is_not_a_version_fails(self, text, level):
        # 入力は release-gate.py latest-tag の出力（vX.Y.Z か空）。ほかの行は読み飛ばさずに失敗する
        result = next_semver(text + '\n', level)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stdout, '')

    def test_blank_lines_print_nothing(self):
        for text in ('', '\n', '  \n', '\t\n\n'):
            with self.subTest(text=text):
                self.assertEqual((next_semver(text, 'patch').returncode, next_semver(text, 'patch').stdout), (0, ''))

    def test_a_prerelease_is_bumped_like_semver(self):
        self.assertEqual(next_semver('v1.2.3-rc.1\n', 'patch').stdout, expected('1.2.3-rc.1', 'patch') + '\n')


class NextVersionStepTest(unittest.TestCase):
    """bump-version.yml の Get the next version：下げる・同じ版の PR を作らない（`version` と比べる）。"""

    def run_step(self, version_text, prev_tag='v5.2.0', level='patch', requested=''):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'version').write_text(version_text)
            output = root / 'output'
            output.write_text('')
            result = subprocess.run(['bash', '-e', '-c', run_body(BUMP, 'versions')], cwd=root, capture_output=True,
                                    text=True, env={**os.environ, 'PATH': PATH, 'GITHUB_WORKSPACE': str(GITHUB.parent),
                                                    'GITHUB_OUTPUT': str(output), 'PREV_TAG': prev_tag,
                                                    'SEMVER_LEVEL': level, 'REQUESTED_VERSION': requested})
            return result, outputs(output)

    def test_the_next_version_is_above_the_version_file(self):
        result, out = self.run_step('5.2.1-SNAPSHOT\n')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(out['next_version'], '5.2.1')
        result, out = self.run_step('5.2.1-SNAPSHOT\n', level='minor')
        self.assertEqual(out['next_version'], '5.3.0')

    def test_a_merged_but_untagged_version_is_not_bumped_again(self):
        # 5.2.1 の版上げがマージ済みでタグが無い（関門で止まった）：auto の patch は 5.2.1 をもう一度出そうとして止まる
        result, _ = self.run_step('5.2.1\n')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('must be greater than the version 5.2.1 in version', result.stderr)
        # CONTRIBUTING の回復（version=5.2.2）は通る
        result, out = self.run_step('5.2.1\n', requested='5.2.2')
        self.assertEqual((result.returncode, out.get('next_version')), (0, '5.2.2'), result.stderr)

    def test_a_requested_version_below_the_version_file_is_refused(self):
        result, _ = self.run_step('5.3.0-SNAPSHOT\n', requested='5.2.9')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('must be greater than the version 5.3.0-SNAPSHOT', result.stderr)


if __name__ == '__main__':
    unittest.main()
