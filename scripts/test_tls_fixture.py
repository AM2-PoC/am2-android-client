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


if __name__ == "__main__":
    unittest.main()