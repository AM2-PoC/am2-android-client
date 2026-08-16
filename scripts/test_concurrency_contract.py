#!/usr/bin/env python3
"""Shared state is published, and no lock is held across slow work.

These are the remainder of the concurrency review. Each one is a silent
failure — nothing throws, nothing logs, and the symptom looks like the network:

  - AudioPlayer's monitor is entered from the socket reader thread and held
    across AudioTrack build/stop/release, 10-50 ms during which NO audio and no
    video is read from the socket at all;
  - isPlaying is unpublished, so a release racing a setup can leave two mixer
    threads draining one queue into one AudioTrack;
  - lastSeen is written by the reader thread and read by the mixer, which can
    release a handler that is actively receiving;
  - isStreaming is written on the main thread and read on the camera thread, so
    frames can be sent after release or dropped at the start;
  - the mixer has no backoff when the track is unusable, so it spins at CPU
    speed and drains the jitter buffer it just filled;
  - the volume-key PTT timer is never cancelled by a real release, so it fires
    later and stops a *subsequent* transmission;
  - updateTalkingStatusUI holds the activeSpeakers monitor across a native call
    and a LiveData dispatch that reaches AudioManager, while the reader thread
    takes the same monitor for every inbound frame at 50 Hz.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app/src/main/java/com/am2/am2"
PLAYER = JAVA / "AudioPlayer.kt"
VIDEO = JAVA / "VideoActivity.kt"
SERVICE = JAVA / "PTTService.kt"
WS = JAVA / "WebSocketManager.kt"


def section(text: str, start: str, end: str) -> str:
    begin = text.index(start)
    return text[begin:text.index(end, begin)]


class ConcurrencyContractTest(unittest.TestCase):
    def setUp(self):
        self.player = PLAYER.read_text()
        self.video = VIDEO.read_text()
        self.service = SERVICE.read_text()
        self.ws = WS.read_text()

    def test_the_socket_reader_never_builds_an_audio_track(self):
        play = section(self.player, "fun playAudio(", "private fun requestAudioTrack()")
        # playAudio runs on the OkHttp reader thread. Building an AudioTrack
        # there stalls every inbound frame, audio and video alike.
        self.assertNotIn("setupAudioTrack(", play,
                         "the reader thread still builds the track inline")
        # And the request has to hand the work somewhere else, not merely rename it.
        request = section(self.player, "private fun requestAudioTrack()", "\n    fun stop()")
        self.assertIn("setupExecutor.execute", request)
        self.assertLess(request.index("setupExecutor.execute"), request.index("setupAudioTrack("))

    def test_mixer_state_is_published(self):
        for field in ("isPlaying", "lastSeen"):
            self.assertRegex(self.player, rf"@Volatile\s+(?:private )?var {field}",
                             f"{field} crosses threads unpublished")

    def test_only_one_mixer_thread_can_exist(self):
        release = section(self.player, "fun release()", "\n}")
        self.assertIn("join(", release,
                      "release does not wait for the mixer, so a restart can start a second one")

    def test_the_mixer_backs_off_when_it_cannot_write(self):
        mixer = section(self.player, "private fun startMixerThread()", "fun playAudio(")
        frames = section(mixer, "if (activeFrames.isNotEmpty())", "} else {")
        # Without this the loop re-enters immediately, decodes every handler and
        # discards the PCM, draining the jitter buffer at CPU speed.
        self.assertIn("sleep", frames,
                      "the mixer spins when the track is unusable")

    def test_video_streaming_state_is_published(self):
        self.assertRegex(self.video, r"@Volatile\s+private var isStreaming",
                         "isStreaming is written on the main thread and read on the camera thread")

    def test_a_late_camera_frame_cannot_crash_the_camera_thread(self):
        frame = section(self.video, "override fun onPreviewFrame(", "override fun onKeyDown(")
        # onDestroy shuts the executor down, but surfaceDestroyed can run after,
        # so a frame can still arrive and be rejected.
        self.assertIn("RejectedExecutionException", frame)

    def test_a_real_release_cancels_the_volume_key_timer(self):
        stop = section(self.service, "private fun performStopTalking()", "\n    private val ")
        self.assertIn("VOL_PTT_END", stop,
                      "a genuine release leaves the timer armed to stop the next transmission")

    def test_no_lock_is_held_across_a_dispatch_that_reaches_the_audio_manager(self):
        ui = section(self.ws, "private fun updateTalkingStatusUI()", "\n    private fun ")
        guarded = re.search(r"synchronized\(activeSpeakers\) \{", ui)
        self.assertIsNotNone(guarded, "the speaker snapshot is no longer taken under the lock")
        # The LiveData write reaches PTTService and AudioManager synchronously.
        # It must happen after the lock is released, not inside it.
        body = ui[guarded.end():]
        closing = body.index("\n        }")
        self.assertNotIn("_isCommunicationActive", body[:closing],
                         "a LiveData dispatch still runs inside the lock the reader thread needs")


if __name__ == "__main__":
    unittest.main()
