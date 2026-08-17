#!/usr/bin/env python3
"""Was the frame late leaving the device, or late arriving?

The relay measures inter-arrival spacing and calls the deviation jitter. It
cannot tell those two apart, and they have opposite fixes: uneven production on
the handset is an Android problem, while even production delivered unevenly is
the transport problem that would justify moving off TCP.

Worse, the relay's two numbers do not independently confirm each other. A stall
is a spacing past a threshold and jitter is the smoothed size of that same
spacing, so a late frame raises both by construction. Correlating them measures
nothing.

The sending device already knows the answer and never reports it. `frame_sent`
carries `frame_seq` and a monotonic timestamp, sampled every twenty-fifth
frame. Between two samples the expected elapsed time is the frame count times
the frame interval, so the difference from the observed elapsed time is the
send-side pacing error -- how far behind its own schedule the device fell,
measured on one clock, with no network in it.

Compare that against the relay's arrival jitter for the same session:

    pacing error near zero, relay sees stalls  -> the network
    pacing error matches the stalls            -> the device
"""
import unittest
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
from ptt_latency_report import send_pacing_ms  # noqa: E402

MS = 1_000_000


def sent(trace, seq, at_ms):
    return {
        "event": "frame_sent",
        "trace_id": trace,
        "frame_seq": str(seq),
        "mono_ns": at_ms * MS,
    }


class SendPacingTest(unittest.TestCase):
    def test_a_device_that_keeps_schedule_reports_no_error(self):
        # Twenty-five frames at 20 ms apart is exactly 500 ms of audio.
        events = [sent(1, 25, 1000), sent(1, 50, 1500), sent(1, 75, 2000)]
        self.assertEqual([0.0, 0.0], send_pacing_ms(events))

    def test_a_device_that_falls_behind_reports_the_shortfall(self):
        # 600 ms of wall clock for 500 ms of audio: 100 ms was lost somewhere
        # between the microphone and the socket, on this device.
        events = [sent(1, 25, 1000), sent(1, 50, 1600)]
        self.assertEqual([100.0], send_pacing_ms(events))

    def test_samples_are_ordered_by_sequence_not_by_log_order(self):
        # logcat interleaves; the sequence number is the authority.
        events = [sent(1, 50, 1500), sent(1, 25, 1000)]
        self.assertEqual([0.0], send_pacing_ms(events))

    def test_transmissions_are_not_compared_against_each_other(self):
        # Two presses are two schedules. Measuring across the gap between them
        # would report the operator's thinking time as a device stall.
        events = [sent(1, 25, 1000), sent(2, 25, 90000), sent(2, 50, 90500)]
        self.assertEqual([0.0], send_pacing_ms(events))

    def test_frames_the_client_never_sent_are_not_counted(self):
        # frame_dropped carries the same fields. A frame that never reached the
        # socket says nothing about pacing to the socket.
        events = [
            sent(1, 25, 1000),
            {"event": "frame_dropped", "trace_id": 1, "frame_seq": "40", "mono_ns": 1200 * MS},
            sent(1, 50, 1500),
        ]
        self.assertEqual([0.0], send_pacing_ms(events))

    def test_a_single_sample_yields_nothing(self):
        # One point is not a spacing. Reporting 0.0 here would read as "on
        # schedule" for a transmission never measured at all.
        self.assertEqual([], send_pacing_ms([sent(1, 25, 1000)]))

    def test_a_run_ahead_of_schedule_is_reported_as_negative(self):
        # Frames arriving at the socket faster than real time means they were
        # buffered and released in a burst -- the opposite failure, and it
        # must not be hidden by an absolute value.
        events = [sent(1, 25, 1000), sent(1, 50, 1400)]
        self.assertEqual([-100.0], send_pacing_ms(events))


if __name__ == "__main__":
    unittest.main()
