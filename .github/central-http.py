#! /usr/bin/env python3
"""central-portal.py の HTTP の照会。締め切り（単調時計）と、本文を締め切りで切る読み方（#925）。

event-store-adapter-rs#375 の read_body・Watchdog を写し、照会の失敗（接続、時間切れ、本文の途中の切断、締め切り）を
どれも (None, b'') にする。照会の結果の読み方は central-rules.py、コマンドは central-portal.py。
"""
import http.client as http_client  # 関数 http と名前がぶつからないように
import socket
import threading
import time
import urllib.error
import urllib.request

BODY_CHUNK = 64 * 1024


class Deadline:
    """締め切り（単調時計）。照会の時間も待ちに数える（#925。scala#934 の CodeRabbit の指摘）。"""

    def __init__(self, seconds, clock=time.monotonic):
        self.clock = clock
        self.end = clock() + seconds

    def remaining(self):
        return max(0.0, self.end - self.clock())


def response_socket(response):
    """応答の socket。CPython の http.client では、HTTPResponse.fp は socket.makefile('rb') の BufferedReader で、
    その raw（SocketIO）が socket を持つ。HTTPError は fp に HTTPResponse を持つ。取れなければ None。"""
    fp = getattr(response, 'fp', None)
    raw = getattr(fp, 'raw', None)
    sock = getattr(raw, '_sock', None)
    return sock if isinstance(sock, socket.socket) else None


def read_body(response, deadline, timeout):
    """本文を読み、締め切りで打ち切る（過ぎたら TimeoutError）。rs#375 の read_body の写し。

    urlopen の timeout は通信の1回ごとにかかり、read() は本文がそろうまで何度も受信するので、本文が少しずつ届くと
    締め切りを超えて待つ（#925）。そこで、
    - 受信の前ごとに socket の timeout を締め切りの残りにし、read1（受信は1回）で読み、読むたびに締め切りを確かめる。
    - chunked の長さの行のように1回の read1 の中で何度も受信する場合に備え、締め切りに socket を閉じる（shutdown）。
      閉じたあとの受信は失敗し、照会の失敗になる。締め切りのあとに読み終えた本文も使わない。
    - Content-Length に足りない本文は、途中で切れた応答（IncompleteRead）。"""
    sock = response_socket(response)
    read = getattr(response, 'read1', None) or response.read
    watchdog = Watchdog(sock, deadline.remaining()) if sock is not None else None
    try:
        chunks = []
        while True:
            left = deadline.remaining()
            if left <= 0:
                raise TimeoutError('the deadline passed while reading the body')
            if sock is not None and sock.fileno() >= 0:   # 本文を読み終えると http.client が socket を閉じる
                sock.settimeout(min(timeout, left))
            chunk = read(BODY_CHUNK)
            if not chunk:
                break
            chunks.append(chunk)
        if deadline.remaining() <= 0:
            raise TimeoutError('the deadline passed while reading the body')
        body = b''.join(chunks)
        # read1 は、Content-Length に足りないまま接続が閉じても IncompleteRead を投げずに b'' を返す（read は投げる）。
        # 残りの長さが 0 でなければ、途中で切れた応答として照会の失敗にする（java#871 の Devin の指摘）。
        # chunked は終わりの chunk が来なければ http.client が IncompleteRead を投げる
        left_over = getattr(response, 'length', None)
        if isinstance(left_over, int) and left_over > 0:
            raise http_client.IncompleteRead(body, left_over)
        return body
    finally:
        if watchdog is not None:
            watchdog.cancel()
            watchdog.raise_failure()


class Watchdog:
    """締め切りに socket を閉じ、受信を止める timer。閉じられなかったときは、その照会を時間切れとして失敗にする
    （raise_failure）。読み終えて閉じた socket には何もしない。"""

    def __init__(self, sock, seconds):
        self.sock = sock
        self.failure = None
        self.timer = threading.Timer(max(seconds, 0), self.fire)
        self.timer.daemon = True
        self.timer.start()

    def fire(self):
        if self.sock.fileno() < 0:
            return
        try:
            self.sock.shutdown(socket.SHUT_RDWR)
        except OSError as error:
            self.failure = error

    def cancel(self):
        self.timer.cancel()

    def raise_failure(self):
        if self.failure is not None:
            raise TimeoutError(f'could not shut the socket down at the deadline: {self.failure}') from self.failure


def http(method, url, headers=None, body=None, timeout=60, deadline=None):
    """(状態の番号, 本文) を返す。接続の失敗、時間切れ、本文の途中の切断（IncompleteRead）、締め切りは (None, b'')。

    接続と見出しの受信は、1回の通信ごとに timeout（コマンドの締め切りがあれば、その残りまで）。本文は read_body が
    締め切りで切る：コマンドの締め切り deadline があればそれ、無ければ（upload、published）見出しを受け取ってから
    timeout 秒（upload の送信の時間は数えない）。締め切りのあとに読み終えた応答は使わない。"""
    if deadline is not None:
        left = deadline.remaining()
        if left <= 0:
            return None, b''
        timeout = min(timeout, left)
    request = urllib.request.Request(url, data=body, method=method, headers=headers or {})
    try:
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                return response.status, read_body(response, deadline or Deadline(timeout), timeout)
        except urllib.error.HTTPError as error:
            with error:
                fp = getattr(error, 'fp', None) or error
                return error.code, read_body(fp, deadline or Deadline(timeout), timeout)
    except (urllib.error.URLError, http_client.HTTPException, TimeoutError, OSError, ValueError):
        return None, b''
