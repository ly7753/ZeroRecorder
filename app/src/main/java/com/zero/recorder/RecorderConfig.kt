package com.zero.recorder

object RecorderConfig {
    const val OUTPUT_DIR = "/sdcard/Movies/ZeroRecorder"
    const val PID_FILE_PATH = "/data/local/tmp/zero_recorder.pid"
    const val STOP_FLAG_PATH = "/data/local/tmp/zero_recorder.stop"
    const val DONE_FLAG_PATH = "/data/local/tmp/zero_recorder.done"
    const val REMOTE_APK_PATH = "/data/local/tmp/zero_recorder.apk"

    const val DEFAULT_VIDEO_BITRATE_BPS = 8_000_000
    const val DOWNGRADE_VIDEO_BITRATE_BPS = 4_000_000
    const val DEFAULT_FALLBACK_FPS = 60
    const val DEFAULT_I_FRAME_INTERVAL = 1

    const val DEFAULT_AUDIO_BITRATE_BPS = 192_000
    const val DEFAULT_AUDIO_SAMPLE_RATE = 48000
    const val DEFAULT_AUDIO_MAX_INPUT_SIZE = 16_384

    const val FIRST_FRAME_TIMEOUT_MS = 1_500L
    const val MAX_STARTUP_REBINDS = 2
    const val RUNTIME_FRAME_TIMEOUT_MS = 2_000L
    const val MAX_RUNTIME_REBINDS = 3

    const val SEGMENT_DURATION_MS = 5_000L
    const val FLOAT_ITEM_SIZE_DP = 45f
}
