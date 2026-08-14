#!/usr/bin/env python3
import argparse
import base64
import hashlib
import socket
import ssl
import threading
import time


def response_for(request: bytes) -> bytes:
    headers = request.decode("iso-8859-1").split("\r\n")
    values = {}
    for line in headers[1:]:
        if ":" in line:
            name, value = line.split(":", 1)
            values[name.lower()] = value.strip()
    if values.get("upgrade", "").lower() == "websocket":
        key = values.get("sec-websocket-key")
        if not key:
            return b"HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n"
        accept = base64.b64encode(
            hashlib.sha1((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()).digest()
        ).decode()
        return (
            "HTTP/1.1 101 Switching Protocols\r\n"
            "Upgrade: websocket\r\n"
            "Connection: Upgrade\r\n"
            f"Sec-WebSocket-Accept: {accept}\r\n\r\n"
        ).encode()
    body = b"am2-ci-ok\n"
    return (
        b"HTTP/1.1 200 OK\r\n"
        b"Content-Type: text/plain\r\n"
        + f"Content-Length: {len(body)}\r\nConnection: close\r\n\r\n".encode()
        + body
    )


def handle(connection: ssl.SSLSocket) -> None:
    with connection:
        request = b""
        while b"\r\n\r\n" not in request and len(request) < 16384:
            chunk = connection.recv(4096)
            if not chunk:
                return
            request += chunk
        is_websocket = b"upgrade: websocket" in request.lower()
        connection.sendall(response_for(request))
        if is_websocket:
            # Keep the upgraded connection alive long enough for OkHttp's
            # onOpen callback to run and send its close frame.
            time.sleep(5)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--cert", required=True)
    parser.add_argument("--key", required=True)
    parser.add_argument("--port", required=True, type=int)
    args = parser.parse_args()

    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.maximum_version = ssl.TLSVersion.TLSv1_2
    # Android 4.1's OpenSSL stack does not advertise ECDHE suites that modern
    # OpenSSL enables by default. Keep this fixture at TLS 1.2 while offering
    # an RSA key-exchange suite so it exercises the real Jelly Bean path.
    context.set_ciphers("AES128-SHA:@SECLEVEL=1")
    context.load_cert_chain(args.cert, args.key)

    with socket.create_server(("127.0.0.1", args.port), reuse_port=False) as listener:
        while True:
            raw, _ = listener.accept()
            try:
                connection = context.wrap_socket(raw, server_side=True)
            except ssl.SSLError:
                raw.close()
                continue
            threading.Thread(target=handle, args=(connection,), daemon=True).start()


if __name__ == "__main__":
    main()