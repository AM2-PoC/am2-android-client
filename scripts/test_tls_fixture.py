#!/usr/bin/env python3
import base64
import importlib.util
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("tls_fixture_server.py")
SPEC = importlib.util.spec_from_file_location("tls_fixture_server", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
SERVER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SERVER)


class TlsFixtureResponseTest(unittest.TestCase):
    def test_generated_ca_is_explicitly_a_ca(self):
        text = Path(__file__).with_name("create_tls_fixture.sh").read_text()
        self.assertIn("basicConstraints=critical,CA:TRUE,pathlen:0", text)
        self.assertIn("keyUsage=critical,keyCertSign,cRLSign", text)

    def test_https_health_response_is_bounded_and_successful(self):
        response = SERVER.response_for(
            b"GET /health HTTP/1.1\r\nHost: 10.0.2.2\r\nConnection: close\r\n\r\n"
        )
        self.assertTrue(response.startswith(b"HTTP/1.1 200 OK\r\n"))
        self.assertIn(b"Content-Length: 10\r\n", response)
        self.assertTrue(response.endswith(b"am2-ci-ok\n"))

    def test_websocket_upgrade_uses_rfc6455_accept_value(self):
        key = "dGhlIHNhbXBsZSBub25jZQ=="
        response = SERVER.response_for(
            (
                "GET / HTTP/1.1\r\n"
                "Host: 10.0.2.2\r\n"
                "Upgrade: websocket\r\n"
                "Connection: Upgrade\r\n"
                f"Sec-WebSocket-Key: {key}\r\n"
                "Sec-WebSocket-Version: 13\r\n\r\n"
            ).encode()
        )
        self.assertTrue(response.startswith(b"HTTP/1.1 101 Switching Protocols\r\n"))
        expected = base64.b64encode(
            __import__("hashlib").sha1(
                (key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()
            ).digest()
        )
        self.assertIn(b"Sec-WebSocket-Accept: " + expected + b"\r\n", response)

    def test_websocket_upgrade_without_key_is_rejected(self):
        response = SERVER.response_for(
            b"GET / HTTP/1.1\r\nHost: 10.0.2.2\r\nUpgrade: websocket\r\n\r\n"
        )
        self.assertTrue(response.startswith(b"HTTP/1.1 400 Bad Request\r\n"))


class TlsFixtureAcceptLoopTest(unittest.TestCase):
    """The fixture must survive the handshakes the tests deliberately abort.

    Two instrumented tests exist to prove a bad certificate is rejected, and
    rejection means the client tears the connection down mid-handshake. The
    accept loop caught only `ssl.SSLError`, so a peer reset -- `ConnectionResetError`
    -- propagated out of `main()` and killed the fixture.

    JUnit4 does not run methods in source order. Whenever a negative test ran
    first, every remaining test in the class then failed against a dead server,
    and the legacy lanes reported a confusing multi-test failure whose cause was
    nowhere in the output.
    """

    def setUp(self):
        self.source = MODULE_PATH.read_text(encoding="utf-8")

    def test_an_aborted_handshake_does_not_kill_the_fixture(self):
        self.assertIn(
            "OSError",
            self.source,
            "only ssl.SSLError is caught; a peer reset ends the accept loop",
        )

    def test_a_stalled_peer_cannot_wedge_the_accept_loop(self):
        # wrap_socket runs inline in a single-threaded loop, so a client that
        # connects and then says nothing blocks every later connection forever.
        self.assertIn(
            "settimeout",
            self.source,
            "the handshake has no timeout; one silent peer stops the fixture",
        )


if __name__ == "__main__":
    unittest.main()

