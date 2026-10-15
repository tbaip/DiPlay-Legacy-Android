#!/usr/bin/env python3
"""Apply the DiPlay audio-mode debounce fix to a source checkout.

Why this fix
------------
A call setup makes iOS renegotiate the AirPlay audio streams several times in the
first seconds. Each renegotiation stops the previous telephony uplink and starts a
new one. The old code restored ``MODE_NORMAL`` the moment a stream stopped, so the
replacement ``AudioRecord`` was created outside the HAL voice path. On legacy head
units (Allwinner T3 / Android 6) that path returns nothing usable, and the far end
hears silence even though the uplink reports healthy counters.

The fix defers the restore by two seconds. A new uplink cancels the pending
restore, so ``MODE_IN_COMMUNICATION`` survives the whole setup churn. Nothing else
about the audio path changes.

Measured on the reporter's head unit: healthy calls held the communication mode
for 23 s - 7.5 min, the broken call held it for 35 / 63 / 78 / 328 ms. Each
premature restore was followed by an ``airplay SETUP`` 34-135 ms later, i.e. the
whole stream was torn down and rebuilt.

Usage
-----
Run from the repository root (the directory holding ``settings.gradle.kts``)::

    python3 patches/apply_audio_mode_debounce.py

The script edits exact source text. If upstream moves or renames that code it
exits non-zero instead of producing a silently wrong APK. Re-running it on an
already patched tree reports the edits as present and changes nothing.
"""

from __future__ import annotations

import sys
from pathlib import Path

TARGET_RELATIVE = "shared/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt"

# Marker used for the idempotency check: the constant only exists after patching.
MARKER = "MODE_RESTORE_DEBOUNCE_MS"

EDITS: list[tuple[str, str, str]] = [
    (
        "1/5  add the deferred-restore handler fields",
        """    private var savedAudioMode = AudioManager.MODE_NORMAL
    private val pendingVideoCodec = ConcurrentHashMap<Int, VideoCodec>()
""",
        """    private var savedAudioMode = AudioManager.MODE_NORMAL
    /**
     * Holds MODE_IN_COMMUNICATION across the sub-second stop/start churn of a call setup.
     * A call renegotiates its streams several times; restoring MODE_NORMAL inside one of those
     * gaps leaves the replacement AudioRecord outside the HAL voice path, so legacy head units
     * capture nothing the phone can hear. Defer the restore; a new uplink cancels it.
     */
    private val audioModeHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val restoreModeRunnable = Runnable { applyRestoreAudioMode() }
    private val pendingVideoCodec = ConcurrentHashMap<Int, VideoCodec>()
""",
    ),
    (
        "2/5  cancel a pending restore when an uplink starts",
        """        synchronized(audioModeLock) {
            if (communicationModeStream != null) return
            // Select the HAL communication path before AudioRecord is created.
""",
        """        synchronized(audioModeLock) {
            // A queued restore must not fire while a replacement uplink is starting.
            audioModeHandler.removeCallbacks(restoreModeRunnable)
            if (communicationModeStream != null) return
            // Select the HAL communication path before AudioRecord is created.
""",
    ),
    (
        "3/5  turn restoreAudioMode into a debounced scheduler",
        """    private fun restoreAudioMode(id: AudioStreamId?) {
        val manager = audioManager ?: return
        synchronized(audioModeLock) {
            val active = communicationModeStream ?: return
            if (id != null && id != active) return
            communicationModeStream = null
""",
        """    private fun restoreAudioMode(id: AudioStreamId?) {
        val manager = audioManager ?: return
        synchronized(audioModeLock) {
            val active = communicationModeStream ?: return
            if (id != null && id != active) return
            audioModeHandler.removeCallbacks(restoreModeRunnable)
            audioModeHandler.postDelayed(restoreModeRunnable, MODE_RESTORE_DEBOUNCE_MS)
        }
    }

    private fun applyRestoreAudioMode() {
        val manager = audioManager ?: return
        synchronized(audioModeLock) {
            val active = communicationModeStream ?: return
            communicationModeStream = null
""",
    ),
    (
        "4/5  restore immediately when the sink closes",
        """        } finally {
            microphoneUplinks.clear()
            restoreAudioMode(null)
        }
""",
        """        } finally {
            microphoneUplinks.clear()
            // The sink is closing: drop any queued debounce and restore right away.
            audioModeHandler.removeCallbacks(restoreModeRunnable)
            applyRestoreAudioMode()
        }
""",
    ),
    (
        "5/5  declare the debounce window",
        """    private companion object {
        const val TELEPHONY_AUDIO_TYPE = "telephony"
    }
"""
        ,
        """    private companion object {
        const val TELEPHONY_AUDIO_TYPE = "telephony"
        /** How long MODE_IN_COMMUNICATION is held after a telephony uplink stops. */
        private const val MODE_RESTORE_DEBOUNCE_MS = 2_000L
    }
""",
    ),
]


def find_repo_root() -> Path:
    """Return the source root: the cwd when it looks right, else the script's grandparent."""
    cwd = Path.cwd()
    if (cwd / "settings.gradle.kts").is_file() and (cwd / TARGET_RELATIVE).is_file():
        return cwd
    candidate = Path(__file__).resolve().parents[1]
    if (candidate / "settings.gradle.kts").is_file() and (candidate / TARGET_RELATIVE).is_file():
        return candidate
    raise SystemExit(
        "error: run this from the repository root (settings.gradle.kts + "
        f"{TARGET_RELATIVE} not found)\n"
        f"       cwd = {cwd}"
    )


def main() -> int:
    root = find_repo_root()
    target = root / TARGET_RELATIVE
    source = target.read_text(encoding="utf-8")

    print(f"target : {target}")
    print(f"  bytes: {len(source.encode('utf-8'))}")
    print()

    if MARKER in source:
        print("already patched: MODE_RESTORE_DEBOUNCE_MS is present, nothing to do.")
        return 0

    applied = 0
    for label, old, new in EDITS:
        occurrences = source.count(old)
        if occurrences != 1:
            print(f"[FAIL] {label}")
            print(f"       expected exactly 1 match, found {occurrences}")
            print("       upstream changed this code; re-derive the edit before building.")
            return 2
        source = source.replace(old, new, 1)
        applied += 1
        print(f"[ ok ] {label}")

    target.write_text(source, encoding="utf-8")

    print()
    print(f"applied {applied}/{len(EDITS)} edits to {TARGET_RELATIVE}")
    print(f"  new size: {len(source.encode('utf-8'))} bytes")

    # Post-conditions: the debounce must be wired in, and the direct call must be gone.
    checks = [
        ("restoreModeRunnable is scheduled", "postDelayed(restoreModeRunnable" in source),
        ("pending restore can be cancelled", "removeCallbacks(restoreModeRunnable)" in source),
        ("applyRestoreAudioMode exists", "private fun applyRestoreAudioMode()" in source),
        ("debounce window declared", "MODE_RESTORE_DEBOUNCE_MS = 2_000L" in source),
        ("no direct restore in close()", "restoreAudioMode(null)" not in source),
    ]
    print()
    for name, ok in checks:
        print(f"  {'PASS' if ok else 'FAIL'}  {name}")
    if not all(ok for _, ok in checks):
        return 3

    print()
    print("ok: audio-mode debounce is in place.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
