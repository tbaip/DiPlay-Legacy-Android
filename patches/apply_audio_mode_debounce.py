#!/usr/bin/env python3
"""Apply the DiPlay call-audio fixes to a source checkout.

Three independent changes
-------------------------
1. Audio-mode debounce (AndroidMediaSink.kt)
   A call renegotiates its AirPlay streams several times in the first seconds. The old
   code restored MODE_NORMAL the moment a stream stopped, so the replacement AudioRecord
   was created outside the HAL voice path and captured nothing the phone could hear.
   The fix defers the restore by two seconds; a new uplink cancels it.

2. Force the MIC source for telephony (MicrophoneUplink.kt)
   Upstream uses AudioSource.VOICE_COMMUNICATION unless it proves "silent broken" by
   returning all-zero frames. That check cannot fire on this head unit, and the packet
   counters look healthy either way, so the captured content was never actually
   verified. This patch uses AudioSource.MIC directly, bypassing the platform voice
   processing chain. Revert it if the RMS diagnostics show real audio on VOICE_COMMUNICATION.

3. Per-frame RMS diagnostics (MicrophoneCaptureStats.kt + MicrophoneUplink.kt)
   The packet counters (udpSent, captureBytes) only prove packets left the device, not
   that they carried sound -- a silent or distorted capture looks equally healthy. This
   adds peakRms / minRms to the periodic "Microphone: stats" line, so the next diagnostic
   report answers the question directly:

       peakRms clearly above 0 (hundreds+)  -> real audio is being captured
       peakRms stuck at 0..5                -> the path returns silence

   RMS is computed per frame from 16-bit samples and is purely observational.

Usage
-----
Run from the repository root (the directory holding settings.gradle.kts)::

    python3 patches/apply_audio_mode_debounce.py

Every edit matches exact source text, so a moved or renamed block makes the script exit
non-zero instead of producing a silently wrong APK. Re-running on a patched tree reports
everything as already applied and changes nothing.
"""

from __future__ import annotations

import sys
from pathlib import Path

SINK = "shared/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt"
UPLINK = "shared/src/main/java/com/shilapi/xcertplay/media/MicrophoneUplink.kt"
STATS = "shared/src/main/java/com/shilapi/xcertplay/media/MicrophoneCaptureStats.kt"

# (path, idempotency marker, [(label, old, new), ...])
PATCHES: list[tuple[str, str, list[tuple[str, str, str]]]] = [
    (SINK, "MODE_RESTORE_DEBOUNCE_MS", [
        (
            "1/11  AndroidMediaSink: 加入延迟恢复的处理器字段",
            """    private var savedAudioMode = AudioManager.MODE_NORMAL
    private val pendingVideoCodec = ConcurrentHashMap<Int, VideoCodec>()
""",
            """    private var savedAudioMode = AudioManager.MODE_NORMAL
    /**
     * 在通话建立的亚秒级停止/启动抖动中保住 MODE_IN_COMMUNICATION。
     * 一次通话会反复重建音频流；在间隙里恢复 MODE_NORMAL 会让替代上行的 AudioRecord
     * 落到 HAL 语音通路之外，旧车机于是采不到任何对方能听到的东西。
     * 延后恢复；新上行启动时会取消它。
     */
    private val audioModeHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val restoreModeRunnable = Runnable { applyRestoreAudioMode() }
    private val pendingVideoCodec = ConcurrentHashMap<Int, VideoCodec>()
""",
        ),
        (
            "2/11  AndroidMediaSink: 新上行启动时取消待恢复",
            """        synchronized(audioModeLock) {
            if (communicationModeStream != null) return
            // Select the HAL communication path before AudioRecord is created.
""",
            """        synchronized(audioModeLock) {
            // 替代上行正在启动，排队中的恢复不能现在触发。
            audioModeHandler.removeCallbacks(restoreModeRunnable)
            if (communicationModeStream != null) return
            // Select the HAL communication path before AudioRecord is created.
""",
        ),
        (
            "3/11  AndroidMediaSink: restoreAudioMode 改为延迟调度",
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
            "4/11  AndroidMediaSink: 接收端关闭时立即恢复",
            """        } finally {
            microphoneUplinks.clear()
            restoreAudioMode(null)
        }
""",
            """        } finally {
            microphoneUplinks.clear()
            // 接收端要关了：撤掉排队的防抖，立刻恢复。
            audioModeHandler.removeCallbacks(restoreModeRunnable)
            applyRestoreAudioMode()
        }
""",
        ),
        (
            "5/11  AndroidMediaSink: 声明防抖窗口常量",
            """    internal companion object {
        const val TELEPHONY_AUDIO_TYPE = "telephony"
    }
""",
            """    internal companion object {
        const val TELEPHONY_AUDIO_TYPE = "telephony"
        /** 上行停止后 MODE_IN_COMMUNICATION 还保持多久。 */
        private const val MODE_RESTORE_DEBOUNCE_MS = 2_000L
    }
""",
        ),
    ]),
    (UPLINK, "frameRms", [
        (
            "11/12  MicrophoneUplink: 加入发送目标诊断字段",
            """        @Volatile var voiceCommunicationSilentBroken = false
        /** Minimum silent frames before declaring VOICE_COMMUNICATION broken (~4s at 20ms). */
        const val BROKEN_SILENCE_FRAMES = 200L
""",
            """        @Volatile var voiceCommunicationSilentBroken = false
        /** Minimum silent frames before declaring VOICE_COMMUNICATION broken (~4s at 20ms). */
        const val BROKEN_SILENCE_FRAMES = 200L
        /** 诊断：上行发送目标只记录一次。 */
        @Volatile var uplinkTargetLogged = false
""",
        ),
        (
            "12/12  MicrophoneUplink: 记录目标地址 / 端口 / 密钥指纹（诊断）",
            """            socket.send(DatagramPacket(packet, packet.size, config.host, config.port))
            stats.sent()
""",
            """            socket.send(DatagramPacket(packet, packet.size, config.host, config.port))
            if (!uplinkTargetLogged) {
                uplinkTargetLogged = true
                val fingerprint = config.key.take(8).joinToString("") { "%02x".format(it) }
                Log.i(TAG, "microphone uplink target=${config.host}:${config.port}" +
                    " payloadType=${config.payloadType} samples=${config.rtpSamplesPerPacket}" +
                    " keyFp=$fingerprint")
            }
            stats.sent()
""",
        ),
        (
            "6/12  MicrophoneUplink: 计算每帧 RMS 并上报",
            """    private fun sendFrame(socket: DatagramSocket, counters: MicrophoneCounters, frame: ByteArray) {
        stats.frameSilent(frame.all { it == 0.toByte() })
""",
            """    /** 一帧 16-bit PCM 的 RMS 电平，用于诊断「采到的是声音还是静音」。 */
    private fun frameRms(frame: ByteArray): Int {
        var sum = 0L
        var samples = 0
        var i = 0
        while (i + 1 < frame.size) {
            val sample = ((frame[i + 1].toInt() shl 8) or (frame[i].toInt() and 0xFF)).toShort().toInt()
            sum += sample.toLong() * sample
            samples++
            i += 2
        }
        if (samples == 0) return 0
        return kotlin.math.sqrt(sum.toDouble() / samples).toInt()
    }

    private fun sendFrame(socket: DatagramSocket, counters: MicrophoneCounters, frame: ByteArray) {
        stats.frameSilent(frame.all { it == 0.toByte() })
        stats.frameLevel(frameRms(frame))
""",
        ),
    ]),
    (STATS, "frameLevel", [
        (
            "7/12  MicrophoneCaptureStats: 加入电平字段",
            """    private var silentStreak = 0
    private var silentFrames = 0L
""",
            """    private var silentStreak = 0
    private var silentFrames = 0L
    /** 本报告窗口内单帧 PCM 的 RMS 峰值与最小值（16-bit 单位）。纯诊断。 */
    private var peakRms = 0
    private var minRms = Int.MAX_VALUE
""",
        ),
        (
            "8/12  MicrophoneCaptureStats: 加入 frameLevel 方法",
            """    fun frameSilent(silent: Boolean) {
""",
            """    /** 记录一帧 PCM 电平（16-bit RMS）。不参与任何判定，只进诊断输出。 */
    fun frameLevel(rms: Int) {
        if (rms > peakRms) peakRms = rms
        if (rms < minRms) minRms = rms
    }

    fun frameSilent(silent: Boolean) {
""",
        ),
        (
            "9/12 MicrophoneCaptureStats: stats 行输出电平",
            """            "firstAudioMs=$firstAudioMs ended=$ended")
""",
            """            "firstAudioMs=$firstAudioMs peakRms=$peakRms " +
            "minRms=${if (minRms == Int.MAX_VALUE) -1 else minRms} ended=$ended")
""",
        ),
        (
            "10/12 MicrophoneCaptureStats: 窗口重置时清零电平",
            """        sendGapMaxNs = 0
        silentFrames = 0
    }
""",
            """        sendGapMaxNs = 0
        silentFrames = 0
        peakRms = 0
        minRms = Int.MAX_VALUE
    }
""",
        ),
    ]),
]

POST_CHECKS = [
    ("audio-mode 防抖已接入", SINK, "postDelayed(restoreModeRunnable"),
    ("防抖窗口已声明", SINK, "MODE_RESTORE_DEBOUNCE_MS = 2_000L"),
    ("close() 走立即恢复", SINK, "applyRestoreAudioMode()"),
    ("音源保持上游原样（VOICE_COMMUNICATION）", UPLINK, "MediaRecorder.AudioSource.VOICE_COMMUNICATION"),
    ("每帧 RMS 已计算", UPLINK, "stats.frameLevel(frameRms(frame))"),
    ("电平字段已声明", STATS, "private var peakRms = 0"),
    ("stats 输出含 peakRms", STATS, "peakRms=$peakRms"),
]


def find_repo_root() -> Path:
    cwd = Path.cwd()
    if (cwd / "settings.gradle.kts").is_file() and (cwd / SINK).is_file():
        return cwd
    cand = Path(__file__).resolve().parents[1]
    if (cand / "settings.gradle.kts").is_file() and (cand / SINK).is_file():
        return cand
    raise SystemExit(
        "error: run this from the repository root "
        f"(settings.gradle.kts + {SINK} not found)\n       cwd = {cwd}"
    )


def main() -> int:
    root = find_repo_root()
    print(f"仓库根目录：{root}")
    print()

    applied_any = False
    total_edits = sum(len(e) for _, _, e in PATCHES)
    done = 0

    for rel, marker, edits in PATCHES:
        target = root / rel
        if not target.is_file():
            print(f"[FAIL] 找不到文件：{rel}")
            return 2
        source = target.read_text(encoding="utf-8")
        print(f"===== {rel.split('/')[-1]}  ({len(source)} 字节) =====")

        if marker in source:
            done += len(edits)
            print(f"  已打过补丁（发现标记 {marker}），跳过")
            print()
            continue

        for label, old, new in edits:
            n = source.count(old)
            if n != 1:
                print(f"  [FAIL] {label}")
                print(f"         期望恰好 1 处匹配，实际 {n} 处")
                print("         上游改动了这段代码，请重新生成补丁后再构建。")
                return 3
            source = source.replace(old, new, 1)
            done += 1
            applied_any = True
            print(f"  [ ok ] {label}")

        target.write_text(source, encoding="utf-8")
        print(f"  已写入，新大小 {len(source.encode('utf-8'))} 字节")
        print()

    print(f"编辑进度：{done}/{total_edits}")
    if not applied_any:
        print()
        print("三处补丁都已在位，无需改动。")
    print()

    print("=" * 60)
    print("后置检查")
    print("=" * 60)
    ok = True
    for name, rel, needle in POST_CHECKS:
        found = needle in (root / rel).read_text(encoding="utf-8")
        print(f"  {'PASS' if found else 'FAIL'}  {name}")
        ok = ok and found
    print()
    if not ok:
        return 4
    print("ok: 模式防抖（不改音源）+ 上行发送诊断 + RMS 电平诊断，全部就位。")
    print()
    print("下次诊断报告里重点看这两行：")
    print("  Microphone: stats ... peakRms=<n> minRms=<n>")
    print("     peakRms 明显大于 0（数百以上）→ 采到的是真实声音，问题在传输/加密/对端")
    print("     peakRms 只有 0~5              → 采到的是静音")
    print("  microphone uplink target=<ip>:<port> payloadType=<n> samples=<n> keyFp=<hex>")
    print("     → 这是上行包的真正去向与密钥指纹，可与蜂窝电话时的值做对照")
    return 0


if __name__ == "__main__":
    sys.exit(main())
