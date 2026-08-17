#!/usr/bin/env python3
"""Turn a PTT trace capture into latency numbers.

The app emits a timed event at each stage of a transmission. This joins them by
trace id and reports how long each stage took, so a claim about latency can cite
a number instead of an impression.

Capture on the device under test:

    adb logcat -c
    adb logcat -s PttTrace:D > transmit.log      # on the talking device
    adb logcat -s PttTrace:D > receive.log       # on the listening device

then:

    python3 scripts/ptt_latency_report.py transmit.log
    python3 scripts/ptt_latency_report.py receive.log

IMPORTANT: every segment reported here is measured within ONE device. The clock
is System.nanoTime, whose origin is arbitrary and unrelated between devices, so
subtracting a receiver timestamp from a sender timestamp would produce a
confident number that means nothing. Mouth-to-ear is the sum of the transmit
segments, the relay's own forwarding time, and one network crossing — report it
that way, as a sum of measured parts, not as a single subtraction.
"""
import argparse
import re
import statistics
import sys
from collections import defaultdict
from pathlib import Path

FIELD = re.compile(r"(\w+)=(-?\d+|\w+)")

# One Opus frame of audio. AudioRecorder captures 320 samples at 16 kHz, so the
# device owes the socket one frame every 20 ms; the relay uses the same figure
# to judge arrivals. Send pacing is measured against it.
FRAME_INTERVAL_MS = 20.0

# Ordered stages within one transmission on the sending device. Each pair is
# reported as the time from the first event to the second.
TRANSMIT_SEGMENTS = [
    ("button_down", "start_sent", "press to request"),
    ("start_sent", "start_authorized", "relay authorization"),
    ("start_authorized", "capture_started", "authorization to microphone"),
    ("button_down", "capture_started", "press to microphone"),
    ("capture_started", "frame_encoded", "microphone to first encoded frame"),
    ("frame_encoded", "frame_sent", "encode to socket"),
    ("button_down", "frame_sent", "press to first frame on the wire"),
]

# Ordered stages within one transmission on the receiving device.
RECEIVE_SEGMENTS = [
    ("frame_received", "frame_decoded", "arrival to decoded"),
    ("frame_decoded", "playback_written", "decoded to playback"),
    ("frame_received", "playback_written", "arrival to playback"),
]


def parse(path):
    """Every trace line, as a dict, in the order they were logged."""
    events = []
    for line in Path(path).read_text(errors="replace").splitlines():
        if "event=" not in line:
            continue
        fields = dict(FIELD.findall(line))
        if "event" not in fields or "trace_id" not in fields:
            continue
        try:
            fields["trace_id"] = int(fields["trace_id"])
            fields["mono_ns"] = int(fields["mono_ns"])
        except (KeyError, ValueError):
            continue
        events.append(fields)
    return events


def first_by_trace(events):
    """The earliest timestamp of each event name, per trace id.

    First occurrence rather than last: a segment is about when a stage was
    reached, and later frames of the same transmission would otherwise stretch
    every measurement to the length of the whole press.
    """
    seen = defaultdict(dict)
    for event in events:
        stage = seen[event["trace_id"]]
        name = event["event"]
        if name not in stage or event["mono_ns"] < stage[name]:
            stage[name] = event["mono_ns"]
    return seen


def durations_ms(stages, start, end):
    out = []
    for trace in stages.values():
        if start in trace and end in trace:
            delta = trace[end] - trace[start]
            if delta >= 0:
                out.append(delta / 1_000_000)
    return out


def send_pacing_ms(events, frame_interval_ms=FRAME_INTERVAL_MS):
    """How far behind its own schedule the sending device fell, per sample gap.

    Audio is produced at a fixed rate, so between two `frame_sent` samples the
    elapsed time *should* be the number of frames between them times the frame
    interval. Anything more was spent somewhere between the microphone and the
    socket, on this device, measured on one clock with no network in it.

    That is the number the relay cannot obtain. It sees only arrivals, so it
    cannot separate a handset that produced frames unevenly from a network that
    delivered even ones unevenly -- and those have opposite fixes.

    Positive means the device fell behind. Negative means frames reached the
    socket faster than real time, which is a burst after a hold rather than
    good news, so the sign is kept.

    Grouped per transmission: two presses are two schedules, and measuring
    across the gap between them would report the operator's thinking time as a
    device stall.
    """
    per_trace = defaultdict(list)
    for event in events:
        if event.get("event") != "frame_sent" or "frame_seq" not in event:
            continue
        try:
            per_trace[event["trace_id"]].append(
                (int(event["frame_seq"]), event["mono_ns"]))
        except (TypeError, ValueError):
            continue

    errors = []
    for samples in per_trace.values():
        # By sequence, not by log order: logcat interleaves.
        samples.sort()
        for (seq_a, ns_a), (seq_b, ns_b) in zip(samples, samples[1:]):
            expected_ms = (seq_b - seq_a) * frame_interval_ms
            actual_ms = (ns_b - ns_a) / 1_000_000
            errors.append(round(actual_ms - expected_ms, 3))
    return errors


def report_send_pacing(events):
    """Whether the frames left late, which is the half the relay cannot see."""
    errors = send_pacing_ms(events)
    if not errors:
        return
    behind = [e for e in errors if e > 0]
    print("\nsend pacing (this device, no network)")
    print("-" * 73)
    print(f"{'schedule error between samples':<38}"
          f"{len(errors):>5}{percentile(errors, 0.5):>10.1f}"
          f"{percentile(errors, 0.95):>10.1f}{max(errors):>10.1f}")
    print(f"\n  gaps where the device fell behind: {len(behind)} of {len(errors)}")
    print("\n  Compare against the relay's uplink_jitter_ms and stalls for the")
    print("  same session. Near zero here while the relay reports stalls means")
    print("  the frames left on time and the network delayed them. Matching")
    print("  numbers mean the device produced them late, and the transport is")
    print("  not the thing to change.")


def percentile(values, fraction):
    ordered = sorted(values)
    index = min(len(ordered) - 1, int(round(fraction * (len(ordered) - 1))))
    return ordered[index]


def report_segments(stages, segments, title):
    rows = []
    for start, end, label in segments:
        values = durations_ms(stages, start, end)
        if values:
            rows.append((label, len(values), percentile(values, 0.5),
                         percentile(values, 0.95), max(values)))
    if not rows:
        return False
    print(f"\n{title}")
    print(f"{'segment':<38}{'n':>5}{'p50 ms':>10}{'p95 ms':>10}{'max ms':>10}")
    print("-" * 73)
    for label, count, p50, p95, worst in rows:
        print(f"{label:<38}{count:>5}{p50:>10.1f}{p95:>10.1f}{worst:>10.1f}")
    return True


def report_backlog(events):
    """Socket backlog at the moment each frame was handed over."""
    queued = [int(e["queue_bytes"]) for e in events if "queue_bytes" in e]
    dropped = [e for e in events if e["event"] == "frame_dropped"]
    if not queued and not dropped:
        return
    print("\nuplink")
    print("-" * 73)
    if queued:
        print(f"{'socket backlog at send (bytes)':<38}"
              f"{len(queued):>5}{percentile(queued, 0.5):>10.0f}"
              f"{percentile(queued, 0.95):>10.0f}{max(queued):>10.0f}")
        if max(queued) > 0:
            print("\n  A backlog above roughly one frame means audio is waiting behind")
            print("  something else on the same socket.")
    print(f"\n  frames dropped: {len(dropped)}")


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("logs", nargs="+", help="logcat captures, one device each")
    args = parser.parse_args()

    for path in args.logs:
        events = parse(path)
        if not events:
            print(f"{path}: no PttTrace lines found", file=sys.stderr)
            continue

        stages = first_by_trace(events)
        print(f"\n=== {path} — {len(stages)} transmissions, per-device segments ===")
        sent = report_segments(stages, TRANSMIT_SEGMENTS, "transmit")
        received = report_segments(stages, RECEIVE_SEGMENTS, "receive")
        if not sent and not received:
            print("no complete segment found; was the capture taken during a transmission?")
        report_backlog(events)
        report_send_pacing(events)

    print("\nSegments are within one device. Do not subtract a timestamp on one")
    print("device from a timestamp on another: the clocks share no origin.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
