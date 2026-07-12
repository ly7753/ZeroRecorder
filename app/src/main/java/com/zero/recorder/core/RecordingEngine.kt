package com.zero.recorder.core

import android.content.Context
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.view.Surface
import com.zero.recorder.RecorderLog
import com.zero.recorder.audio.AudioCaptureFactory
import com.zero.recorder.capture.DisplayCaptureController
import com.zero.recorder.gl.GlFrameRenderer
import com.zero.recorder.media.SegmentedMp4Muxer
import com.zero.recorder.media.VideoEncoderFactory
import com.zero.recorder.system.NativeCore
import com.zero.recorder.system.RecorderResourceManager
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class RecordingEngine(
    private val shellContext: Context,
    private val displayController: DisplayCaptureController,
    private val recordingActive: AtomicBoolean
) {
    private val TAG = "ZR.Engine"
    private val ptsNormalizer = PtsNormalizer()

    fun start(initialRotation: Int) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
        val outputPath = OutputManager.buildOutputPath()
        val candidates = CandidateSelector.buildVideoCandidates(displayController, displayController.detectRefreshRate())
        if (candidates.isEmpty()) return

        for (candidate in candidates) {
            ptsNormalizer.reset()
            val success = attemptRecording(initialRotation, outputPath, candidate)
            if (success) {
                RecorderLog.i(TAG, "Saved to $outputPath")
                return
            }
            OutputManager.cleanupFailedOutput(outputPath)
            if (!recordingActive.get()) break
        }
    }

    private fun attemptRecording(initialRotation: Int, outputPath: String, candidate: CandidateSelector.VideoCandidate): Boolean {
        RecorderResourceManager().use { resourceManager ->
            var videoCodec: MediaCodec?
            var audioCodec: MediaCodec? = null
            var audioSession: AudioCaptureFactory.AudioCaptureSession? = null
            var muxer: SegmentedMp4Muxer?
            var renderer: GlFrameRenderer?
            var inputSurface: Surface?
            
            val videoEosLatch = CountDownLatch(1)
            val audioEosLatch = CountDownLatch(1)
            val bitrateController = BitrateController(8_000_000)

            try {
                videoCodec = MediaCodec.createEncoderByType(candidate.mimeType)
                resourceManager.addVideoCodec(videoCodec)

                try {
                    if (android.os.Build.VERSION.SDK_INT == 30) {
                        try {
                            Runtime.getRuntime().exec("am start -n com.android.shell/.HeapDumpActivity")
                            Thread.sleep(250) // 【关键修复】创建 AudioRecord 前，提前抢占前台焦点
                        } catch (e: Exception) {}
                    }
                    audioSession = AudioCaptureFactory.createBestEffortSession()
                    audioCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                    resourceManager.addAudioSession(audioSession)
                    resourceManager.addAudioCodec(audioCodec)
                } catch (e: Exception) {
                    RecorderLog.w(TAG, "Audio capture failed, fallback to video only")
                }

                val expectedTracks = if (audioCodec != null) 2 else 1
                val outputFile = File(outputPath)
                val outputDir = outputFile.parent!!
                val baseName = outputFile.nameWithoutExtension
                muxer = SegmentedMp4Muxer(outputDir, baseName, expectedTracks)
                resourceManager.addMuxer(muxer)

                val videoCbThread = HandlerThread("VideoCodecCb", Process.THREAD_PRIORITY_URGENT_DISPLAY).also { it.start() }
                Handler(videoCbThread.looper).post {
                    if (NativeCore.isAvailable()) {
                        NativeCore.enableRealTimeScheduling()
                        NativeCore.bindToPerformanceCores()
                    }
                }
                resourceManager.addThread(videoCbThread)
                
                val videoTrackIndex = arrayOf(-1)
                val finalMuxer = muxer
                videoCodec.setCallback(object : MediaCodec.Callback() {
                    override fun onInputBufferAvailable(mc: MediaCodec, index: Int) {}
                    override fun onOutputBufferAvailable(mc: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                        bitrateController.checkAndThrottleBitrate(mc)
                        val isVideoEos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        if (info.size > 0 && videoTrackIndex[0] >= 0 && !isVideoEos) {
                            ptsNormalizer.normalizeTimeBase(info)
                            finalMuxer.writeSampleData(videoTrackIndex[0], mc.getOutputBuffer(index), info)
                        }
                        mc.releaseOutputBuffer(index, false)
                        if (isVideoEos) videoEosLatch.countDown()
                    }
                    override fun onOutputFormatChanged(mc: MediaCodec, format: MediaFormat) {
                        videoTrackIndex[0] = finalMuxer.addTrack(format)
                    }
                    override fun onError(mc: MediaCodec, e: MediaCodec.CodecException) {
                        RecorderLog.e(TAG, "Video Codec Fatal Error: ${e.message}")
                        if (!e.isRecoverable && !e.isTransient) recordingActive.set(false)
                    }
                }, Handler(videoCbThread.looper))

                val videoFormat = VideoEncoderFactory.createVideoFormat(
                    candidate.mimeType, candidate.width, candidate.height,
                    candidate.fps, Math.max((candidate.width * candidate.height * candidate.fps * 0.15f).toInt(), 4_000_000)
                )
                try {
                    val vc = videoCodec.codecInfo.getCapabilitiesForType(candidate.mimeType).videoCapabilities
                    val targetBitrate = videoFormat.getInteger(MediaFormat.KEY_BIT_RATE)
                    val safeBitrate = vc?.bitrateRange?.clamp(targetBitrate) ?: targetBitrate
                    videoFormat.setInteger(MediaFormat.KEY_BIT_RATE, safeBitrate)
                } catch (e: Exception) {
                    com.zero.recorder.RecorderLog.w("ZR.Engine", "Bitrate clamp skipped: ${e.message}")
                }

                videoCodec.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                inputSurface = videoCodec.createInputSurface()

                if (audioCodec != null) {
                    val audioCbThread = HandlerThread("AudioCodecCb", Process.THREAD_PRIORITY_URGENT_AUDIO).also { it.start() }
                    Handler(audioCbThread.looper).post {
                        if (NativeCore.isAvailable()) {
                            NativeCore.enableRealTimeScheduling()
                            NativeCore.bindToPerformanceCores()
                        }
                    }
                    resourceManager.addThread(audioCbThread)
                    
                    val audioTrackIndex = arrayOf(-1)
                    val audioSampleCount = arrayOf(0)
                    val dummySilence = ByteArray(2048)
                    val finalAudioSession = audioSession!!
                    
                    audioCodec.setCallback(object : MediaCodec.Callback() {
                        override fun onInputBufferAvailable(mc: MediaCodec, index: Int) {
                            if (!recordingActive.get()) {
                                mc.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                try { finalAudioSession.record.stop() } catch (e: Exception) {}
                                return
                            }
                            val inBuf = mc.getInputBuffer(index)!!
                            // [核心修正] 恢复阻塞读取，利用麦克风硬件时钟作为天然同步源
                            val read = finalAudioSession.record.read(inBuf, Math.min(inBuf.capacity(), 4096))
                            if (read > 0) {
                                var pts = (audioSampleCount[0] * 1000000L) / finalAudioSession.sampleRate
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                                    val ts = android.media.AudioTimestamp()
                                    if (finalAudioSession.record.getTimestamp(ts, android.media.AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                                        val hwPts = (ts.nanoTime - ptsNormalizer.globalStartNs) / 1000L
                                        if (hwPts > 0 && Math.abs(hwPts - pts) < 50000) pts = hwPts
                                    }
                                }
                                audioSampleCount[0] += read / (finalAudioSession.channelCount * 2)

                                val currentSysNs = System.nanoTime() - ptsNormalizer.globalStartNs
                                val newOffset = currentSysNs - (pts * 1000L)
                                if (ptsNormalizer.audioTimeOffsetNs == 0L || Math.abs(ptsNormalizer.audioTimeOffsetNs - newOffset) > 500_000_000L) {
                                    ptsNormalizer.audioTimeOffsetNs = newOffset
                                } else {
                                    ptsNormalizer.audioTimeOffsetNs = (ptsNormalizer.audioTimeOffsetNs * 19 + newOffset) / 20
                                }
                                mc.queueInputBuffer(index, 0, read, pts, 0)
                            } else {
                                val fallbackPts = (audioSampleCount[0] * 1000000L) / finalAudioSession.sampleRate
                                val dummySize = 2048
                                inBuf.clear()
                                inBuf.put(dummySilence)
                                audioSampleCount[0] += dummySize / (finalAudioSession.channelCount * 2)
                                mc.queueInputBuffer(index, 0, dummySize, fallbackPts, 0)
                                if (read <= 0) {
                                    try { Thread.sleep(5) /* FIXME: 阻塞式休眠，建议重构为协程 delay() 或使用 ReentrantLock.Condition 同步机制 */ } catch (e: Exception) {
            com.zero.recorder.RecorderLog.e("ZR.Error", "Exception caught: ${e.message}", e)
        }
                                }
                            }
                        }

                        override fun onOutputBufferAvailable(mc: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                            val isAudioEos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            if (info.size > 0 && audioTrackIndex[0] >= 0 && !isAudioEos) {
                                ptsNormalizer.normalizeTimeBase(info)
                                finalMuxer.writeSampleData(audioTrackIndex[0], mc.getOutputBuffer(index), info)
                            }
                            mc.releaseOutputBuffer(index, false)
                            if (isAudioEos) audioEosLatch.countDown()
                        }

                        override fun onOutputFormatChanged(mc: MediaCodec, format: MediaFormat) {
                            audioTrackIndex[0] = finalMuxer.addTrack(format)
                        }

                        override fun onError(mc: MediaCodec, e: MediaCodec.CodecException) {
                            RecorderLog.e(TAG, "Audio Codec Fatal Error: ${e.message}")
                            if (!e.isRecoverable && !e.isTransient) recordingActive.set(false)
                        }
                    }, Handler(audioCbThread.looper))

                    val audioFormat = MediaFormat.createAudioFormat(
                        MediaFormat.MIMETYPE_AUDIO_AAC,
                        audioSession.sampleRate,
                        audioSession.channelCount
                    ).apply {
                        setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
                        setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
                    }
                    audioCodec.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                }

                muxer.start()
                renderer = GlFrameRenderer(candidate.width, candidate.height)
                resourceManager.addRenderer(renderer)
                renderer.initialize(inputSurface)
                resourceManager.addSurface(inputSurface)
                renderer.updateSourceSize(displayController.screenWidth, displayController.screenHeight)
                displayController.bindCaptureSurface(shellContext, renderer.inputSurface, candidate.width, candidate.height)
                resourceManager.addCaptureSurface(displayController)

                videoCodec.start()
                if (audioCodec != null) {
                    audioCodec.start()
                    audioSession!!.record.startRecording()
                    
                    if (android.os.Build.VERSION.SDK_INT == 30) {
                        try { 
                            // 录音启动完毕，模拟按下返回键，退掉透明 Activity，让用户无缝留在当前应用中
                            Runtime.getRuntime().exec("input keyevent 4")
                        } catch (e: Exception) {}
                    }
                }

                val framePumper = FramePumper(displayController, shellContext, renderer, ptsNormalizer, bitrateController)
                framePumper.pump(recordingActive, candidate.fps, initialRotation, candidate.width, candidate.height)

                videoCodec.signalEndOfInputStream()
                if (audioCodec != null && audioSession != null) {
                    try { audioSession.record.stop() } catch (e: Exception) {
            com.zero.recorder.RecorderLog.e("ZR.Error", "Exception caught: ${e.message}", e)
        }
                }
                try { videoEosLatch.await(10, TimeUnit.SECONDS) } catch (e: InterruptedException) {}
                if (audioCodec != null) {
                    try { audioEosLatch.await(5, TimeUnit.SECONDS) } catch (e: InterruptedException) {}
                }
                
                return true
            } catch (e: Exception) {
                RecorderLog.e(TAG, "Engine failed: ${e.javaClass.simpleName} - ${e.message}")
                recordingActive.set(false)
                return false
            }
        }
    }
}
