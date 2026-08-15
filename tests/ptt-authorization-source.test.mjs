import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const source = readFileSync(
    new URL('../app/src/main/java/com/am2/am2/WebSocketManager.kt', import.meta.url),
    'utf8',
);

const reconnect = source.slice(
    source.indexOf('"login_success" ->'),
    source.indexOf('"login_error" ->'),
);
const authorization = source.slice(
    source.indexOf('"ptt_audio_start_authorized" ->'),
    source.indexOf('"ptt_active_status" ->'),
);

test('non-gateway initial PTT capture waits for the relay authorization acknowledgement', () => {
    const start = source.slice(source.indexOf('fun startTalking()'), source.indexOf('private fun executePttStartSignal()'));
    assert.doesNotMatch(start, /700L|400L|postDelayed\s*\{/);
    assert.match(start, /if \(isGateway\) \{\s*executeStartRecording\(\)/s);
    assert.match(authorization, /traceId > 0L && traceId == activeTransmitTraceId && internalIsTalking/);
    assert.match(authorization, /executeStartRecording\(\)/);
});

test('non-gateway reconnect re-requests authorization without opening capture early', () => {
    assert.match(reconnect, /executePttStartSignal\(\)/);
    assert.match(reconnect, /if \(prefs\?\.getBoolean\("gateway_mode", false\) == true\) \{\s*executeStartRecording\(\)/s);
    assert.doesNotMatch(reconnect, /executePttStartSignal\(\)\s*executeStartRecording\(\)/s);
});

test('stale authorization acknowledgements cannot begin capture for another press', () => {
    assert.match(authorization, /traceId == activeTransmitTraceId/);
    assert.match(authorization, /internalIsTalking/);
});
