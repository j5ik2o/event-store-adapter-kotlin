"""central-portal.py の照会の失敗の扱いと、締め切り（#925。scala#934 と rs#375 の形）。

- 本文の途中の切断（IncompleteRead）などは照会の失敗（`(None, b'')`）。
- precheck・wait・confirm は --deadline 秒の締め切りの中で照会し、待つ。
- 本文は締め切りで切る。締め切りの無いコマンド（upload、published）は、見出しを受け取ってから timeout 秒。
"""
import contextlib
import http.client
import io
import os
import socket
import tempfile
import threading
import time
import unittest
import urllib.error
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest import mock

from hypothesis import given, settings, strategies as st

from central_portal_support import commands, portal
from release_test_support import LOOPBACK


class Stream:
    """urlopen の応答の代わり。read(n) のたびに clock を step 進め、pieces を1つずつ返す（尽きたら b''）。
    pieces の代わりに例外を入れると、その read で投げる。"""
    closed = False

    def __init__(self, status, pieces, clock=None, step=0.0):
        self.status = status
        self.pieces = list(pieces)
        self.clock = clock
        self.step = step
        self.reads = []

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False

    def close(self):
        pass

    def read(self, amount=-1):
        self.reads.append(amount)
        if self.clock is not None:
            self.clock[0] += self.step
        if not self.pieces:
            return b''
        piece = self.pieces.pop(0)
        if isinstance(piece, BaseException):
            raise piece
        return piece


def truncated():
    return http.client.IncompleteRead(b'{"deploymentState": "PUB', 40)


def http_error(code, fp):
    return urllib.error.HTTPError('http://127.0.0.1/x', code, 'x', {}, fp)


class HttpTest(unittest.TestCase):
    def test_a_body_cut_in_the_middle_is_a_failed_query(self):
        with mock.patch.object(commands.urllib.request, 'urlopen', return_value=Stream(200, [truncated()])):
            self.assertEqual(commands.http('POST', 'http://127.0.0.1/x'), (None, b''))
        with mock.patch.object(commands.urllib.request, 'urlopen', side_effect=http_error(503, Stream(503, [truncated()]))):
            self.assertEqual(commands.http('GET', 'http://127.0.0.1/x'), (None, b''))

    def test_every_broken_connection_is_a_failed_query(self):
        # 照会の失敗は、どれも「無い」と読まない（P4）
        failures = (truncated(), http.client.RemoteDisconnected('closed'), http.client.BadStatusLine('x'),
                    ConnectionResetError(), socket.timeout(), TimeoutError(), OSError(), ValueError('bad chunk'))
        for failure in failures:
            with self.subTest(type(failure).__name__):
                with mock.patch.object(commands.urllib.request, 'urlopen', return_value=Stream(200, [b'{', failure])):
                    self.assertEqual(commands.http('GET', 'http://127.0.0.1/x'), (None, b''))
                with mock.patch.object(commands.urllib.request, 'urlopen', side_effect=failure):
                    self.assertEqual(commands.http('GET', 'http://127.0.0.1/x'), (None, b''))

    def test_complete_answers_are_returned_as_they_are(self):
        with mock.patch.object(commands.urllib.request, 'urlopen', return_value=Stream(200, [b'o', b'k'])):
            self.assertEqual(commands.http('GET', 'http://127.0.0.1/x'), (200, b'ok'))
        with mock.patch.object(commands.urllib.request, 'urlopen', side_effect=http_error(404, io.BytesIO(b'missing'))):
            self.assertEqual(commands.http('GET', 'http://127.0.0.1/x'), (404, b'missing'))

    def test_a_truncated_status_answer_is_queried_again(self):
        # wait は切断を照会の失敗として数え、次の照会で PUBLISHED を読む
        answers = [Stream(200, [truncated()]), Stream(200, [b'{"deploymentState": "PUBLISHED"}'])]
        with mock.patch.object(commands.urllib.request, 'urlopen', side_effect=answers), \
                mock.patch.object(commands, 'central_auth', return_value={}), contextlib.redirect_stdout(io.StringIO()):
            args = type('Args', (), {'id': 'x', 'attempts': 3, 'interval': 0, 'deadline': 3600})()
            self.assertEqual(commands.wait(args, sleep=lambda _: None), 0)


class SlowBodyTest(unittest.TestCase):
    """urlopen の timeout は通信の1回ごと。本文を少しずつ読み、締め切りで打ち切る（rs#375 の形）。"""

    def query(self, stream, clock, seconds, error=False):
        commands.DEADLINE = commands.Deadline(seconds, lambda: clock[0])
        try:
            if error:
                with mock.patch.object(commands.urllib.request, 'urlopen', side_effect=http_error(404, stream)):
                    return commands.http('GET', 'http://127.0.0.1/x')
            with mock.patch.object(commands.urllib.request, 'urlopen', return_value=stream):
                return commands.http('GET', 'http://127.0.0.1/x')
        finally:
            commands.DEADLINE = None

    def test_a_body_that_finishes_after_the_deadline_is_a_failed_query(self):
        clock = [0.0]
        self.assertEqual(self.query(Stream(200, [b'{"a": ', b'1}'], clock, 6.0), clock, 10), (None, b''))
        clock = [0.0]
        stream = Stream(200, [b'{"a": ', b'1}'], clock, 1.0)
        self.assertEqual(self.query(stream, clock, 100), (200, b'{"a": 1}'))
        self.assertTrue(all(amount == commands.BODY_CHUNK for amount in stream.reads))

    def test_a_body_that_never_ends_is_cut_at_the_deadline(self):
        for error in (False, True):
            with self.subTest(error=error):
                clock = [0.0]
                stream = Stream(404 if error else 200, [b'x'] * 1000, clock, 1.0)
                self.assertEqual(self.query(stream, clock, 10, error), (None, b''))
                self.assertEqual(len(stream.reads), 10)

    @settings(max_examples=80, deadline=None)
    @given(st.lists(st.floats(0, 5), min_size=1, max_size=20), st.floats(0.5, 30))
    def test_p_the_body_is_whole_before_the_deadline_or_nothing(self, delays, seconds):
        """本文の任意の分け方と片ごとの遅れで、締め切りの前に読み終えれば本文全体、そうでなければ照会の失敗。"""
        clock = [0.0]
        pieces = [bytes([65 + index % 26]) for index in range(len(delays))]

        class Delayed(Stream):
            def read(self, amount=-1):
                if self.pieces:
                    clock[0] += delays[len(delays) - len(self.pieces)]
                return super().read(amount)

        result = self.query(Delayed(200, pieces), clock, seconds)
        # 片を読むたびに締め切りを見る。最後の空の read（遅れなし）のあとにも見る
        finished_in_time = all(sum(delays[:index + 1]) < seconds for index in range(len(delays)))
        self.assertEqual(result, (200, b''.join(pieces)) if finished_in_time else (None, b''))


class TrickleServer:
    """少しずつ送り続ける本物の HTTP サーバー（127.0.0.1 のスレッド）。mode は
    'length'（200、Content-Length つきの本文を 1 バイトずつ）、'error'（404 で同じ）、
    'chunked'（200、chunked の長さの行を 1 文字ずつ。1回の read1 の中で何度も受信させる）。"""

    def __init__(self, mode, every=0.05, total=8.0):
        stop = self.stop = threading.Event()

        class Handler(BaseHTTPRequestHandler):
            protocol_version = 'HTTP/1.1'

            def log_message(self, *args):
                pass

            def answer(self):
                count = int(total / every)
                self.send_response(404 if mode == 'error' else 200)
                if mode == 'chunked':
                    self.send_header('Transfer-Encoding', 'chunked')
                else:
                    self.send_header('Content-Length', str(count))
                self.end_headers()
                self.wfile.flush()
                try:
                    if mode == 'chunked':
                        self.wfile.write(b'1;')
                    for _ in range(count):
                        if stop.wait(every):
                            return
                        self.wfile.write(b'x')
                        self.wfile.flush()
                except OSError:
                    pass

            do_GET = do_POST = answer

        self.server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.server.daemon_threads = True
        self.url = f'http://127.0.0.1:{self.server.server_address[1]}/x'
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def close(self):
        self.stop.set()
        self.server.shutdown()
        self.server.server_close()


class TrickleTest(unittest.TestCase):
    """read は1回の呼び出しの中で何度も受信するので、mock では待ちを再現できない。本物のサーバーが少しずつ送り続けても、
    http は締め切りで打ち切る。締め切りの無いコマンド（upload、published）も、見出しから timeout 秒で打ち切る。"""

    def setUp(self):
        patcher = mock.patch.dict(os.environ, {'no_proxy': LOOPBACK, 'NO_PROXY': LOOPBACK})
        patcher.start()
        self.addCleanup(patcher.stop)

    def server(self, mode, **kwargs):
        server = TrickleServer(mode, **kwargs)
        self.addCleanup(server.close)
        return server

    def test_a_trickling_body_is_cut_at_the_deadline_of_the_command(self):
        for mode in ('length', 'error', 'chunked'):
            with self.subTest(mode):
                server = self.server(mode)
                commands.DEADLINE = commands.Deadline(1.0)
                started = time.monotonic()
                try:
                    result = commands.http('GET', server.url)
                finally:
                    commands.DEADLINE = None
                self.assertEqual(result, (None, b''))
                self.assertLess(time.monotonic() - started, 2.5, mode)

    def test_a_trickling_body_is_cut_after_the_timeout_without_a_deadline(self):
        for mode in ('length', 'error', 'chunked'):
            with self.subTest(mode):
                server = self.server(mode)
                started = time.monotonic()
                self.assertEqual(commands.http('POST', server.url, body=b'bundle', timeout=1.0), (None, b''))
                self.assertLess(time.monotonic() - started, 2.5, mode)

    def test_a_body_that_arrives_in_time_is_read(self):
        server = self.server('length', every=0.01, total=0.2)
        self.assertEqual(commands.http('GET', server.url, timeout=10), (200, b'x' * 20))

    def test_an_upload_answer_after_its_timeout_leaves_no_deployment(self):
        # 締め切りのあとに読み終えた upload の応答は捨てる。ID を書かずに失敗し、次の試みは「ID の無い試み」で止まる
        server = self.server('length', every=0.05, total=3.0)
        with mock.patch.object(commands, 'central_url', return_value=server.url), \
                mock.patch.object(commands, 'central_auth', return_value={}), \
                mock.patch.dict(os.environ, {'GITHUB_RUN_ID': '1', 'GITHUB_RUN_ATTEMPT': '1'}), \
                mock.patch.object(commands, 'write_output') as output, \
                mock.patch.object(commands.Path, 'write_text') as record:
            bundle = self.server_file()
            original = commands.http
            with mock.patch.object(commands, 'http', lambda *a, **k: original(*a, **{**k, 'timeout': 1.0})):
                with self.assertRaises(portal.Stop) as raised:
                    commands.upload(type('Args', (), {'version': '5.3.0', 'bundle': bundle, 'record': 'unused'})())
        self.assertIn('may or may not have created', str(raised.exception))
        output.assert_not_called()
        record.assert_not_called()

    def server_file(self):
        handle, path = tempfile.mkstemp()
        os.write(handle, b'zip')
        os.close(handle)
        self.addCleanup(os.remove, path)
        return path


class WatchdogTest(unittest.TestCase):
    def test_a_socket_that_cannot_be_shut_down_fails_the_query(self):
        """締め切りに socket を閉じられなかったら、握りつぶさずにその照会を時間切れにする。"""
        class Broken:
            def fileno(self):
                return 3

            def shutdown(self, how):
                raise OSError(107, 'Transport endpoint is not connected')
        watchdog = commands.Watchdog(Broken(), 60)
        watchdog.cancel()
        watchdog.fire()
        with self.assertRaises(TimeoutError):
            watchdog.raise_failure()

    def test_a_closed_socket_is_left_alone(self):
        class Closed:
            def fileno(self):
                return -1

            def shutdown(self, how):
                raise AssertionError('a closed socket must not be shut down')
        watchdog = commands.Watchdog(Closed(), 60)
        watchdog.cancel()
        watchdog.fire()
        watchdog.raise_failure()


class DeadlineTest(unittest.TestCase):
    """照会に時間がかかっても、コマンドは --deadline 秒を超えて照会も待ちもしない（P3）。"""

    def run_command(self, command, args, seconds_per_query, answer):
        now = [0.0]
        timeouts = []

        def urlopen(request, timeout):
            timeouts.append(timeout)
            now[0] += min(seconds_per_query, timeout)
            if seconds_per_query > timeout:
                raise TimeoutError()
            return answer(request)

        def sleep(seconds):
            self.assertGreaterEqual(seconds, 0)
            now[0] += seconds

        with mock.patch.object(commands.urllib.request, 'urlopen', side_effect=urlopen), \
                mock.patch.object(commands, 'central_auth', return_value={}), contextlib.redirect_stdout(io.StringIO()):
            try:
                result = command(args, sleep=sleep, monotonic=lambda: now[0])
            except portal.Stop:
                result = 'stopped'
        return result, now[0], timeouts

    @settings(max_examples=60, deadline=None)
    @given(st.floats(0, 20), st.floats(0, 120), st.floats(1, 600))
    def test_confirm_stays_within_the_deadline(self, per_query, interval, limit):
        args = type('Args', (), {'version': '5.3.0', 'attempts': 1000, 'interval': interval, 'deadline': limit})()
        result, elapsed, timeouts = self.run_command(commands.confirm, args, per_query, lambda request: Stream(404, []))
        self.assertEqual(result, 'stopped')
        self.assertLessEqual(elapsed, limit + 1e-6)
        self.assertTrue(all(0 < timeout <= 60 for timeout in timeouts))

    @settings(max_examples=60, deadline=None)
    @given(st.floats(0, 20), st.floats(0, 120), st.floats(1, 600))
    def test_wait_stays_within_the_deadline(self, per_query, interval, limit):
        args = type('Args', (), {'id': 'x', 'attempts': 1000, 'interval': interval, 'deadline': limit})()
        pending = lambda request: Stream(200, [b'{"deploymentState": "PUBLISHING"}'])
        result, elapsed, _ = self.run_command(commands.wait, args, per_query, pending)
        self.assertEqual(result, 'stopped')
        self.assertLessEqual(elapsed, limit + 1e-6)

    @settings(max_examples=60, deadline=None)
    @given(st.integers(1, 30), st.floats(0, 60), st.floats(0, 10))
    def test_a_long_deadline_keeps_the_attempt_limit(self, attempts, interval, per_query):
        # 締め切りが十分長ければ、終わり方は今までどおり回数の上限で決まる
        args = type('Args', (), {'id': 'x', 'attempts': attempts, 'interval': interval, 'deadline': 10 ** 9})()
        pending = lambda request: Stream(200, [b'{"deploymentState": "PUBLISHING"}'])
        with mock.patch.object(commands, 'query_status', wraps=commands.query_status) as queries:
            result, _, _ = self.run_command(commands.wait, args, per_query, pending)
        self.assertEqual((result, queries.call_count), ('stopped', attempts))

    def test_an_answer_before_the_deadline_succeeds_at_once(self):
        args = type('Args', (), {'id': 'x', 'attempts': 1000, 'interval': 30, 'deadline': 3600})()
        published = lambda request: Stream(200, [b'{"deploymentState": "PUBLISHED"}'])
        result, elapsed, _ = self.run_command(commands.wait, args, 5, published)
        self.assertEqual((result, elapsed), (0, 5))

    def test_no_query_starts_after_the_deadline(self):
        commands.DEADLINE = commands.Deadline(0, lambda: 0.0)
        try:
            with mock.patch.object(commands.urllib.request, 'urlopen') as urlopen:
                self.assertEqual(commands.http('GET', 'http://127.0.0.1/x'), (None, b''))
            urlopen.assert_not_called()
        finally:
            commands.DEADLINE = None

    def test_listing_the_tags_stays_within_the_deadline(self):
        # precheck の git ls-remote も、残り時間でタイムアウトを切り、締め切りを過ぎたら動かさない
        now = [0.0]
        calls = []

        def run(command, **kwargs):
            calls.append(kwargs['timeout'])
            return type('Result', (), {'returncode': 0, 'stdout': '', 'stderr': ''})()

        with mock.patch.object(commands.subprocess, 'run', side_effect=run):
            for left, expected in ((3600, 120), (30, 30), (0.5, 0.5)):
                with self.subTest(left=left):
                    commands.DEADLINE = commands.Deadline(left, lambda: now[0])
                    try:
                        commands.remote_tags('origin')
                    finally:
                        commands.DEADLINE = None
                    self.assertEqual(calls[-1], expected)
            commands.DEADLINE = commands.Deadline(0, lambda: now[0])
            try:
                with self.assertRaises(portal.Stop):
                    commands.remote_tags('origin')
            finally:
                commands.DEADLINE = None
        self.assertEqual(len(calls), 3)
        # 締め切りの外では、今までどおり 120 秒
        with mock.patch.object(commands.subprocess, 'run', side_effect=run):
            commands.remote_tags('origin')
        self.assertEqual(calls[-1], 120)

    def test_the_default_deadline_is_sixty_minutes(self):
        for command in ('precheck', 'wait', 'confirm'):
            with self.subTest(command):
                argv = {'precheck': ['--version', '5.3.0'], 'wait': ['--id', 'x'], 'confirm': ['--version', '5.3.0']}
                with mock.patch.object(commands, command, side_effect=lambda args: args.deadline) as fake:
                    fake.__name__ = command
                    self.assertEqual(commands.main(['central-portal.py', command] + argv[command]), 3600)


if __name__ == '__main__':
    unittest.main()
