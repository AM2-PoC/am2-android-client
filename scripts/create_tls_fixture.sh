#!/bin/sh
set -eu

OUTPUT_DIR="${1:?output directory is required}"
mkdir -p "$OUTPUT_DIR"

openssl req -x509 -newkey rsa:2048 -nodes -days 1 -sha256 \
    -subj "/CN=AM2 CI Test CA" \
    -keyout "$OUTPUT_DIR/ca.key" \
    -out "$OUTPUT_DIR/ca.crt" >/dev/null 2>&1

openssl req -newkey rsa:2048 -nodes -sha256 \
    -subj "/CN=10.0.2.2" \
    -keyout "$OUTPUT_DIR/server.key" \
    -out "$OUTPUT_DIR/server.csr" >/dev/null 2>&1

printf '%s\n' \
    'subjectAltName=IP:10.0.2.2,IP:127.0.0.1' \
    'keyUsage=digitalSignature,keyEncipherment' \
    'extendedKeyUsage=serverAuth' > "$OUTPUT_DIR/server.ext"

openssl x509 -req -days 1 -sha256 \
    -in "$OUTPUT_DIR/server.csr" \
    -CA "$OUTPUT_DIR/ca.crt" \
    -CAkey "$OUTPUT_DIR/ca.key" \
    -CAcreateserial \
    -extfile "$OUTPUT_DIR/server.ext" \
    -out "$OUTPUT_DIR/server.crt" >/dev/null 2>&1