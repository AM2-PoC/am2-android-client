#!/usr/bin/env python3
"""VOX owns the microphone, and that changes what every other guard means.

In push-to-talk the microphone opens when the operator presses, and the delay
work of the last rounds all hangs off that one fact: capture is armed only after
the relay has authorized, so nothing is ever captured that cannot be sent.

VOX inverts it. The microphone is opened once, when the mode is switched on, and
stays open for as long as the radio is on -- that is how it hears the voice that
starts a transmission. Every gate written into the "open the microphone" path is
therefore skipped in VOX, silently, because `startRecording` returns at its
first line when a recording is already running.

That one difference is what these contracts pin. Assertions are booleans so a
failure prints the reason rather than the file.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app/src/main/java/com/am2/am2"
RECORDER = JAVA / "AudioRecorder.kt"
SERVICE = JAVA / "PTTService.kt"
SOCKET = JAVA / "WebSocketManager.kt"
SOUNDS = JAVA / "SoundManager.kt"
DEVICES = JAVA / "AudioDeviceManager.kt"


def section(text: str, start: str, end: str) -> str:
    begin = text.index(start)
    return text[begin:text.index(end, begin)]


class VoxTestCase(unittest.TestCase):
    """Boolean assertions, so a failing contract prints its reason only."""

    def has(self, text: str, pattern: str) -> bool:
        return re.search(pattern, text, re.S) is not None

    def assertHas(self, text: str, pattern: str, why: str):
        self.assertTrue(self.has(text, pattern), why)

    def assertLacks(self, text: str, pattern: str, why: str):
        self.assertFalse(self.has(text, pattern), why)


class VoxSurvivesLosingTheMicrophoneTest(VoxTestCase):
    """The recording thread is the whole of VOX. Nothing restarts it.

    It exits on a negative read, on an exception, and when no audio source can
    be opened -- an incoming phone call produces all three. `finally` clears
    isRecording and releases the recorder, and then nothing happens:
    checkVoxState runs on service start, on a settings toggle and on a socket
    reconnect, none of which a lost microphone causes.

    So VOX stops listening while the notification still says PTT Aktif and the
    socket stays green. It is this codebase's recurring failure shape -- broken
    in a way that looks exactly like working -- and on a handset left on a desk
    all day it is the one that costs a missed call.
    """

    def setUp(self):
        self.recorder = RECORDER.read_text()
        self.service = SERVICE.read_text()

    def test_an_unrequested_exit_asks_for_the_recording_back(self):
        self.assertHas(
            self.recorder, r"ACTION_VOX_RESTART",
            "the recording thread can die without anything asking for it back",
        )

    def test_only_an_unrequested_exit_re_arms(self):
        # isRecording is still true when the loop broke out on its own, and
        # false when stopRecording asked. That is the discriminator; without it
        # a re-arm would fight the operator switching VOX off.
        start = section(self.recorder, "fun startRecording(", "private fun handleVoxLogic")
        self.assertHas(
            start, r"finally\s*\{.{0,400}?ACTION_VOX_RESTART|unrequested|isRecording\s*//",
            "the exit path does not distinguish a stop that was asked for from "
            "a microphone that was taken away",
        )

    def test_the_retry_backs_off_and_has_a_ceiling(self):
        # A microphone held by a phone call refuses every source immediately, so
        # an undelayed retry is a tight loop of service intents.
        self.assertHas(
            self.recorder, r"VOX_RESTART_BASE_MS",
            "the restart has no backoff: a permanently unavailable microphone "
            "becomes a retry storm",
        )
        self.assertHas(
            self.recorder, r"VOX_RESTART_MAX_MS",
            "the backoff has no ceiling",
        )

    def test_the_backoff_resets_once_capture_works_again(self):
        self.assertHas(
            self.recorder, r"voxRestartAttempts\s*=\s*0",
            "the backoff never resets, so recovery gets permanently slower",
        )

    def test_the_wait_is_served_by_the_service_not_by_the_dying_thread(self):
        # startRecording joins the previous thread. A thread that sleeps before
        # exiting makes that join time out and refuses the very restart it
        # asked for.
        self.assertHas(
            self.service, r"ACTION_VOX_RESTART\s*->.{0,300}?postDelayed",
            "the service does not defer the restart, so it lands before the "
            "microphone has had any time to come back",
        )
        self.assertLacks(
            section(self.recorder, "fun startRecording(", "private fun handleVoxLogic"),
            r"Thread\.sleep",
            "the recording thread sleeps before exiting, which makes the join "
            "in the next startRecording time out",
        )


class VoxWaitsForTheRelayTest(VoxTestCase):
    """The authorization gate is skipped in VOX, and the relay drops the tail.

    A press arms capture only once `transmitAuthorized` is set. VOX cannot use
    that gate: the microphone is already open, so `executeStartRecording` hits
    `if (isRecording) return` and frames start flowing the moment
    `internalIsTalking` flips -- which is before `ptt_audio_start` has even been
    written to the socket.

    The relay meanwhile sets `ws.is_rx_only = true` for the duration of its
    database lookup and drops every type 1 frame that arrives while it is set:

        if (binaryType === 1 && ws.is_rx_only) return;

    So the opening of a VOX transmission is not clipped, it is holed: frames
    pass, then a lookup's worth are dropped, then frames pass again. VOX trips
    on the onset of speech, so what falls in the hole is the first word.

    Delaying capture would only move the clip. The frames exist and are good --
    they simply cannot be sent yet -- so they are held and flushed on
    authorization, which is what a VOX pre-roll buffer is for.
    """

    def setUp(self):
        self.recorder = RECORDER.read_text()
        self.socket = SOCKET.read_text()

    def test_the_client_can_ask_whether_the_relay_has_authorized(self):
        self.assertHas(
            self.socket, r"fun isTransmitAuthorized\(\)",
            "nothing outside WebSocketManager can tell whether the relay has "
            "authorized, so the recorder cannot hold frames it would lose",
        )

    def test_frames_encoded_before_authorization_are_held_rather_than_sent(self):
        loop = section(self.recorder, "if (success && isRecording)", "private fun handleVoxLogic")
        self.assertHas(
            loop, r"isTransmitAuthorized\(\)",
            "the send path ignores authorization, so the frames carrying the "
            "first word are handed to a relay that is dropping them",
        )
        self.assertHas(
            self.recorder, r"VOX_PREROLL_FRAMES",
            "there is no bound on what is held before authorization",
        )

    def test_the_held_frames_are_flushed_when_authorization_arrives(self):
        # Holding without flushing is just a slower way of losing them.
        self.assertHas(
            self.recorder, r"fun flushPreRoll|flushPreRoll\(\)",
            "held frames are never flushed, so the first word is dropped by "
            "the client instead of by the relay",
        )

    def test_the_hold_is_bounded_so_a_refused_transmission_cannot_grow_it(self):
        # A transmission the relay never authorizes must not accumulate.
        self.assertHas(
            self.recorder, r"(removeFirst|poll)\(\)",
            "the pre-roll never sheds, so a transmission that is never "
            "authorized grows it without limit",
        )


class CaptureAsksForTheProcessingItNeedsTest(VoxTestCase):
    """Asking for VOICE_COMMUNICATION is a request, not a guarantee.

    The source is chosen for echo cancellation, and on many handsets the
    platform obliges with gain control and noise suppression too. On many
    others it does not, and nothing here ever asked: no AutomaticGainControl,
    no NoiseSuppressor, no AcousticEchoCanceler is attached to the session
    anywhere in this module.

    What the operator reports is exactly the shape of that: audio arriving
    quiet on *some* devices, and VOX deaf on *some* devices. Both follow from a
    capture level nobody set.

    The app's own gain stage cannot fix it. AudioFilter multiplies by 1.0, 1.0
    and 0.8 -- unity, with the treble pulled down -- and its comment records
    why: a fixed boost was there and was taken out because it clipped. A fixed
    boost is exactly what cannot serve a loud handset and a quiet one at once.
    Gain that follows the signal can, and that is the effect that was never
    attached.

    Availability is per device, so what matters as much as asking is recording
    which ones answered.
    """

    def setUp(self):
        self.recorder = RECORDER.read_text()

    def test_gain_control_is_asked_for(self):
        # create(), not the import: naming the class proves nothing, and an
        # earlier version of this assertion passed against a build where the
        # effect was never attached to any session.
        self.assertHas(
            self.recorder, r"AutomaticGainControl\.create\(",
            "capture takes whatever level the device happens to give, so a "
            "handset with no platform gain control transmits quietly and "
            "hears nothing on VOX",
        )

    def test_availability_is_checked_rather_than_assumed(self):
        # create() on a device without the effect returns null or throws, and
        # either one taken as success is a silent no-op.
        for effect in ("AutomaticGainControl", "NoiseSuppressor", "AcousticEchoCanceler"):
            self.assertHas(
                self.recorder, effect + r"\.isAvailable\(\)",
                f"{effect} is created without asking whether the device has it",
            )

    def test_the_effects_reach_the_session_that_is_recording(self):
        # Everything above can be true of code nothing calls. The session id
        # comes from the AudioRecord that just started, and this is the only
        # line that ties the two together.
        self.assertHas(
            self.recorder, r"attachCaptureEffects\(recorder\.audioSessionId\)",
            "the effects are configured for no session, so capture is "
            "processed exactly as it was before",
        )

    def test_which_effects_were_obtained_is_recorded(self):
        # The whole complaint is "sebagian device". Which half a handset is in
        # cannot be answered by reading this file.
        self.assertHas(
            self.recorder, r"(SafeLog|PttTrace)[\s\S]{0,400}?(agc|effects|gain_control)",
            "nothing says which effects a device actually granted, so the "
            "device-dependent half of the report stays unanswerable",
        )

    def test_the_effects_are_released_with_the_recorder(self):
        # They hold a native session. Leaking one per restart is a leak per
        # VOX dropout, and VOX drops out on every phone call.
        self.assertHas(
            self.recorder, r"(agc|noiseSuppressor|echoCanceler)[\s\S]{0,200}?release\(\)",
            "the effects outlive the AudioRecord they were attached to",
        )


class VoxHearsTheBuiltInMicrophoneTest(VoxTestCase):
    """Noise suppression is tuned for a telephone call, not for a radio.

    Capture asks for VOICE_COMMUNICATION on every route, because only that
    source asks the platform for echo cancellation and without it the open VOX
    microphone closes a loop through the room. That was the right trade and it
    is not being undone.

    What came with it was the platform's noise suppressor, tuned to keep a
    near-field voice and discard everything else. On a headset the microphone
    is at the mouth and it has an easy job. On the built-in microphone -- a
    handset on a desk, or held at arm's length -- it treats the operator's own
    speech as ambient and crushes it, and VOX compares what is left against a
    threshold. Reported from the field exactly that way: headset fine, built-in
    microphone deaf, and still deaf with the sensitivity bar at its limit.

    Then it was made worse here. NoiseSuppressor was force-enabled while
    attaching gain control, which turned a platform default into a guarantee.

    Echo cancellation stays: it is the reason the source was made unconditional.
    Gain control stays: it is what raises a quiet capture. Suppression goes,
    explicitly rather than by omission, because what the platform does when
    nobody says is exactly what varies between the two microphones.
    """

    def setUp(self):
        self.recorder = RECORDER.read_text()

    def test_suppression_is_turned_off_rather_than_left_to_the_platform(self):
        block = section(self.recorder, "fun attachCaptureEffects", "private fun releaseCaptureEffects")
        self.assertRegex(
            block, r"NoiseSuppressor[\s\S]{0,200}?enabled\s*=\s*false",
            "noise suppression is enabled or left to the platform, and on the "
            "built-in microphone it removes the speech VOX is listening for",
        )

    def test_echo_cancellation_and_gain_control_stay(self):
        block = section(self.recorder, "fun attachCaptureEffects", "private fun releaseCaptureEffects")
        for effect in ("AcousticEchoCanceler", "AutomaticGainControl"):
            self.assertRegex(
                block, effect + r"[\s\S]{0,200}?enabled\s*=\s*true",
                f"{effect} was turned off with the suppressor; the first is why "
                "the source is unconditional and the second is what raises a "
                "quiet capture",
            )

    def test_the_amplitude_vox_compares_is_observable(self):
        # Three rounds of this were argued from source because nothing ever
        # recorded the one number that decides: what VOX measured, against what
        # it was measuring for.
        self.assertRegex(
            self.recorder, r"(SafeLog|PttTrace)[\s\S]{0,300}?vox[_ ]?level",
            "nothing reports the amplitude VOX saw, so whether a threshold is "
            "wrong or a microphone is silent cannot be told apart",
        )
        # And that it is called on the amplitude the loop just measured. The
        # call, with its argument -- the declaration uses a different parameter
        # name, so matching the bare name matches the definition and passes
        # against a build that never invokes it. Two versions of this assertion
        # did exactly that.
        self.assertIn(
            "reportVoxLevel(maxAmplitude)", self.recorder,
            "the level is never reported from the frame loop that measures it",
        )


class VoxKeepsTheWordThatTriggeredItTest(VoxTestCase):
    """The word that opens a transmission is the one VOX throws away.

    Frames reach the encoder only while `isTalkingNow()`:

        val talking = WebSocketManager.isTalkingNow()
        if (talking) { audioFilter.apply(...); opusCodec.encode(...) }

    In push-to-talk that is right -- the operator pressed, so nothing before
    the press was meant to be sent. In VOX it is the whole complaint. VOX is
    triggered *by* the onset of speech, so by the time `talking` is true the
    syllable that crossed the threshold has already been read, measured, and
    dropped on the floor.

    The existing pre-roll does not cover it. That one holds frames between
    "talking started" and "the relay authorized", which is a later window
    entirely; everything before the trigger is never captured at all.

    So the frames have to be kept before there is any reason to keep them, and
    handed to the encoder in order when the reason arrives. Memory is the whole
    cost: fifteen frames of 16-bit mono at 16 kHz is under ten kilobytes.
    """

    def setUp(self):
        self.recorder = RECORDER.read_text()

    def test_frames_are_kept_before_vox_has_any_reason_to_keep_them(self):
        self.assertHas(
            self.recorder, r"VOX_PRETRIGGER_FRAMES",
            "nothing is retained before the trigger, so the syllable that "
            "crossed the threshold is read and discarded",
        )

    def test_the_ring_holds_copies_rather_than_the_buffer_it_reads_into(self):
        # pcmBuffer is reused every iteration. Keeping references to it would
        # leave a ring of fifteen pointers to the same, latest, frame.
        ring = section(self.recorder, "val talking = WebSocketManager.isTalkingNow()",
                       "private fun handleVoxLogic")
        self.assertHas(
            ring, r"copyOf\(",
            "the ring stores the live buffer rather than a copy of it, so "
            "every held frame is whichever frame was read last",
        )

    def test_the_ring_is_bounded(self):
        self.assertHas(
            self.recorder, r"preTrigger[\s\S]{0,200}?removeFirst\(\)",
            "a radio left on in a quiet room fills the ring for as long as it "
            "is on",
        )

    def test_what_was_kept_is_handed_over_when_the_transmission_opens(self):
        self.assertHas(
            self.recorder, r"fun flushPreTrigger|flushPreTrigger\(\)",
            "the ring is filled and never emptied, which is a slower way of "
            "losing the same word",
        )

    def test_the_ring_is_dropped_when_it_can_no_longer_be_sent(self):
        # A transmission that ends, or a recorder that stops, must not leave
        # last week's syllable to be prepended to next week's transmission.
        self.assertHas(
            self.recorder, r"preTrigger\.clear\(\)",
            "the ring outlives the transmission it was collected for",
        )


class VoxIsNotTriggeredByItsOwnLoudspeakerTest(VoxTestCase):
    """The microphone is open while the radio is talking to the operator.

    Capture picks its source from the *output* route:

        val useVoiceComm = hasBluetooth || hasWired || hasUsb

    which selects VOICE_COMMUNICATION when a headset is attached -- no acoustic
    path back to the microphone -- and raw MIC on the built-in loudspeaker,
    which is the one route that has one. It is backwards, and only VOICE_
    COMMUNICATION asks the platform for echo cancellation.

    In push-to-talk that costs quality. In VOX it closes a loop: the tones go
    out of the same loudspeaker at USAGE_MEDIA, the open microphone hears them
    above the 2200 threshold, and VOX transmits. playRxStop() fires exactly as
    the remote speaker leaves activeSpeakersList -- as the guard lifts -- and
    playStopTx() fires just after VOX has closed the operator's own
    transmission, when the list is empty by definition.
    """

    def setUp(self):
        self.recorder = RECORDER.read_text()
        self.devices = DEVICES.read_text()
        self.sounds = SOUNDS.read_text()

    def test_capture_does_not_pick_its_source_from_the_output_route(self):
        self.assertLacks(
            self.devices, r"AudioRecorder\.updateAudioSource",
            "capture still takes its source from the output route, so the "
            "loudspeaker -- the only route with an echo path -- is the one "
            "route that gets no echo canceller",
        )

    def test_capture_prefers_the_source_that_carries_echo_cancellation(self):
        self.assertHas(
            self.recorder,
            r"sources\s*=\s*arrayOf\(\s*MediaRecorder\.AudioSource\.VOICE_COMMUNICATION",
            "VOICE_COMMUNICATION is not the first source tried, so the "
            "platform echo canceller is not asked for",
        )

    def test_vox_ignores_the_microphone_while_the_radio_is_making_sound(self):
        vox = section(self.recorder, "private fun handleVoxLogic", "private fun triggerServiceAction")
        self.assertHas(
            vox, r"isActuallyPlaying\(\)",
            "VOX does not know when incoming audio is being played out of the "
            "loudspeaker it is listening to",
        )
        self.assertHas(
            vox, r"isWithinToneHoldoff\(\)",
            "VOX does not know when the radio's own tones are playing, so "
            "playRxStop and playStopTx retrigger it",
        )

    def test_the_playback_signal_vox_now_reads_is_published(self):
        # isActuallyPlaying() reads audioTrack and totalFramesWritten, both
        # written on the playback thread. Asking it from the recording thread
        # adds a reader, so the fields have to be published -- a plain Long is
        # not even guaranteed to be read whole on 32-bit.
        player = (JAVA / "AudioPlayer.kt").read_text()
        for field in ("audioTrack", "totalFramesWritten"):
            self.assertHas(
                player, r"@Volatile\s+(private )?var " + field,
                f"{field} is read by isActuallyPlaying() from another thread "
                "without being published",
            )

    def test_the_hold_off_lasts_as_long_as_the_tone_does(self):
        # MediaPlayer knows the clip length. A guessed constant is what this
        # codebase has had to remove twice.
        self.assertHas(
            self.sounds, r"fun isWithinToneHoldoff\(\)",
            "SoundManager cannot say whether a tone is sounding",
        )
        self.assertHas(
            self.sounds, r"\.duration",
            "the hold-off is a guess rather than the clip's own length",
        )


class VoxDoesNotStormTheServiceTest(VoxTestCase):
    """One trigger per onset, not one per 20 ms frame.

    VOX_TRIGGER_REQUIRED is 1 and voxTriggerCount is never reset after it
    fires, so every frame above the threshold sends another
    startForegroundService while `isTalking` is still false. For a user who can
    transmit that window is short. For an RX-only user performStartTalking
    always returns, `isTalking` never becomes true, and the storm runs for as
    long as they keep talking -- 50 intents a second, each rebuilding the
    foreground notification.
    """

    def setUp(self):
        self.recorder = RECORDER.read_text()

    def test_a_trigger_clears_the_count_that_produced_it(self):
        vox = section(self.recorder, "private fun handleVoxLogic", "private fun triggerServiceAction")
        # The whole branch that fires, and nothing after it. The reset in the
        # *below-threshold* branch is a different reset and must not be allowed
        # to satisfy this -- it is the one the original already had.
        fired = section(vox, "voxTriggerCount >= VOX_TRIGGER_REQUIRED", "} else voxTriggerCount")
        self.assertHas(fired, r"ACTION_START_PTT", "sliced the wrong branch")
        self.assertHas(
            fired, r"voxTriggerCount\s*=\s*0",
            "the trigger count is not cleared when it fires, so every "
            "subsequent frame above the threshold fires again",
        )

    def test_triggers_are_rate_limited_so_a_refused_press_cannot_repeat_at_50hz(self):
        self.assertHas(
            self.recorder, r"VOX_TRIGGER_INTERVAL_MS",
            "nothing bounds how often VOX may ask the service to transmit, so "
            "an RX-only operator produces 50 service intents a second",
        )


class VoxTellsTheOperatorWhenItRefusesTest(VoxTestCase):
    """A refusal that makes no sound is indistinguishable from a dead button.

    performStartTalking returns bare when VOX owns the transmit decision. The
    on-screen button is dimmed and toasts, but a Bluetooth or wired PTT button
    goes through the same path and gets nothing at all -- no tone, no message.
    The refusal tone added for exactly this reason is right there.
    """

    def setUp(self):
        self.service = SERVICE.read_text()

    def test_a_press_refused_because_vox_owns_the_button_is_audible(self):
        body = section(self.service, "private fun performStartTalking", "private fun performStopTalking")
        vox_refusal = body[:body.index("val isRx")]
        self.assertHas(
            vox_refusal, r"playRefused\(\)",
            "a hardware PTT press in VOX mode is refused in silence",
        )


class VoxSensitivityIsConfigurableTest(VoxTestCase):
    """2200 is right in one room and wrong in the next.

    voxThreshold is a `var` with no setter and no caller. Sensitivity is the
    one control every VOX radio exposes, and without it the mode is either deaf
    or permanently triggered depending on where the operator is standing.
    """

    def setUp(self):
        self.recorder = RECORDER.read_text()
        self.service = SERVICE.read_text()

    def test_the_threshold_can_be_set(self):
        self.assertHas(
            self.recorder, r"fun (setVoxThreshold|updateVoxThreshold)",
            "the VOX threshold is fixed at compile time",
        )

    def test_the_service_applies_the_stored_sensitivity(self):
        body = section(self.service, "private fun checkVoxState", "private fun startBackgroundLocationReporting")
        self.assertHas(
            body, r"vox_threshold",
            "the stored sensitivity is never applied to the recorder",
        )

    def test_the_operator_can_actually_set_it(self):
        # A preference nothing writes is a setting that does not exist. That is
        # how the update channel shipped inert, and it is worth one assertion.
        settings = (JAVA / "SettingActivity.kt").read_text()
        layout = (ROOT / "app/src/main/res/layout/activity_setting.xml").read_text()
        self.assertHas(
            layout, r"sbVoxSensitivity",
            "there is no control for VOX sensitivity, so the preference "
            "behind it can never be anything but its default",
        )
        self.assertHas(
            settings, r"vox_threshold",
            "the settings screen never writes the sensitivity",
        )

    def test_changing_it_reaches_the_running_recorder(self):
        # Same as the VOX checkbox: without this the new value waits for the
        # next service start, which on a radio left switched on is never.
        settings = (JAVA / "SettingActivity.kt").read_text()
        listener = section(
            settings, "sbVoxSensitivity.setOnSeekBarChangeListener", "binding.cbShowVirtualPtt",
        )
        self.assertHas(
            listener, r"notifyVoxChanged\(\)",
            "a sensitivity change does not reach the running service",
        )
        notify = section(settings, "private fun notifyVoxChanged", "\n    }")
        self.assertHas(
            notify, r"ACTION_UPDATE_VOX",
            "notifyVoxChanged does not actually tell the service anything",
        )


class VoxCaptureTakesNoDeadArgumentTest(VoxTestCase):
    """startRecording(channelSlug) never reads channelSlug.

    Callers pass "private_$target" as though it decided where the audio goes.
    It decides nothing: the frame carries a user id and the relay resolves the
    room. A parameter that looks like routing and is not is the kind of thing
    the next reader trusts.
    """

    def setUp(self):
        self.recorder = RECORDER.read_text()
        self.socket = SOCKET.read_text()
        self.service = SERVICE.read_text()

    def test_the_recorder_does_not_take_a_channel_it_ignores(self):
        self.assertHas(
            self.recorder, r"fun startRecording\(\s*\)",
            "startRecording still takes a channel argument it never reads",
        )

    def test_no_caller_still_passes_one(self):
        for name, text in (("WebSocketManager", self.socket), ("PTTService", self.service)):
            self.assertLacks(
                text, r"AudioRecorder\.startRecording\([^)]+\)",
                f"{name} still passes a routing argument that does nothing",
            )


if __name__ == "__main__":
    unittest.main()
