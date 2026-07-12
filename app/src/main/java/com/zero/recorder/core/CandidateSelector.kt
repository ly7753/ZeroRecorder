package com.zero.recorder.core

import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Pair
import com.zero.recorder.capture.DisplayCaptureController

object CandidateSelector {
    data class VideoCandidate(
        val mimeType: String,
        val width: Int,
        val height: Int,
        val fps: Int,
        val codecLabel: String
    )

    fun buildVideoCandidates(displayController: DisplayCaptureController, fps: Int): List<VideoCandidate> {
        val mimeTypes = arrayOf(MediaFormat.MIMETYPE_VIDEO_HEVC, MediaFormat.MIMETYPE_VIDEO_AVC)
        val resolutionCaps = arrayOf(
            arrayOf(0, 0), arrayOf(1440, 0), arrayOf(1080, 0), arrayOf(720, 0), arrayOf(1920, 1)
        )
        val fpsCaps = if (fps > 30) arrayOf(fps, 30) else arrayOf(fps)

        val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
        val seen = mutableSetOf<String>()
        val candidates = mutableListOf<VideoCandidate>()

        for (cap in resolutionCaps) {
            val size = if (cap[0] == 0) {
                Pair(displayController.screenWidth and -16, displayController.screenHeight and -16)
            } else {
                displayController.computeTargetSize(cap[0], cap[1] == 1)
            }

            for (targetFps in fpsCaps) {
                for (mimeType in mimeTypes) {
                    val codecLabel = if (mimeType.contains("hevc")) "H.265" else "H.264"
                    val key = "$mimeType:${size.first}x${size.second}@$targetFps"
                    if (seen.add(key)) {
                        val testFmt = MediaFormat.createVideoFormat(mimeType, size.first, size.second)
                        if (codecList.findEncoderForFormat(testFmt) == null) continue
                        candidates.add(VideoCandidate(mimeType, size.first, size.second, targetFps, codecLabel))
                    }
                }
            }
        }
        return candidates
    }
}
