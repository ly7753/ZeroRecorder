package com.zero.recorder.media

import android.os.SystemClock

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import com.zero.recorder.RecorderConfig
import com.zero.recorder.RecorderLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer

class SegmentedMp4Muxer(outputDir: String, baseName: String, private val expectedTrackCount: Int) {

    companion object {
        private const val TAG = "SegmentedMp4Muxer"
        private val SEGMENT_DURATION_MS = RecorderConfig.SEGMENT_DURATION_MS
    }

    private val outputDir: String = outputDir
    private val finalOutputPath: String = "$outputDir/$baseName.mp4"
    private val segmentFiles = mutableListOf<String>()

    @Volatile private var currentMuxer: AsyncMp4MuxerKt? = null
    private var segmentStartTimeMs: Long = 0
    private var segmentIndex = 0

    private var cachedVideoFormat: MediaFormat? = null
    private var cachedAudioFormat: MediaFormat? = null
    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private var originalVideoTrackIndex = -1
    private var originalAudioTrackIndex = -1
    @Volatile private var isReleased = false

    init {
        File(outputDir).mkdirs()
        startNewSegment()
    }

    @Synchronized
    private fun startNewSegment() {
        val segmentPath = "$outputDir/.tmp_part_$segmentIndex.mp4"
        val newMuxer = AsyncMp4MuxerKt(segmentPath, expectedTrackCount)
        segmentFiles.add(segmentPath)
        segmentStartTimeMs = SystemClock.elapsedRealtime()
        segmentIndex++

        cachedVideoFormat?.let { videoTrackIndex = newMuxer.addTrack(it) }
        cachedAudioFormat?.let { audioTrackIndex = newMuxer.addTrack(it) }
        newMuxer.start()
        currentMuxer = newMuxer
    }

    @Synchronized
    fun addTrack(format: MediaFormat): Int {
        val isVideo = format.getString(MediaFormat.KEY_MIME)?.contains("video") == true
        return if (isVideo) {
            cachedVideoFormat = format
            videoTrackIndex = currentMuxer!!.addTrack(format)
            if (originalVideoTrackIndex == -1) originalVideoTrackIndex = videoTrackIndex
            videoTrackIndex
        } else {
            cachedAudioFormat = format
            audioTrackIndex = currentMuxer!!.addTrack(format)
            if (originalAudioTrackIndex == -1) originalAudioTrackIndex = audioTrackIndex
            audioTrackIndex
        }
    }

    fun start() {
        // [优化] 废弃原有的 ArrayBlockingQueue 和轮询 Thread
        // 彻底交由 AsyncMp4MuxerKt 的协程 Channel 接管，实现零 CPU 空转
    }

    fun writeSampleData(trackIndex: Int, codecBuffer: ByteBuffer?, info: MediaCodec.BufferInfo?) {
        if (codecBuffer == null || info == null || info.size <= 0 || trackIndex < 0 || isReleased) return

        val now = SystemClock.elapsedRealtime()
        if (now - segmentStartTimeMs >= SEGMENT_DURATION_MS) {
            val isVideoTrack = trackIndex == originalVideoTrackIndex
            val isKeyFrame = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
            if ((isVideoTrack && isKeyFrame) || (now - segmentStartTimeMs >= SEGMENT_DURATION_MS + 5000L)) {
                synchronized(this) {
                    val oldMuxer = currentMuxer
                    startNewSegment()
                    // [优化] 异步释放旧分片，确保 MediaCodec 回调线程绝对不被文件 I/O 阻塞
                    CoroutineScope(Dispatchers.IO).launch {
                        try { oldMuxer?.stopAndRelease() } catch (e: Exception) { RecorderLog.w(TAG, "Old segment close error", e) }
                    }
                }
            }
        }

        // [优化] 零拷贝中转：直接将原始 ByteBuffer 喂给异步 Muxer，省去一次内存申请和拷贝
        synchronized(this) {
            val realTrackIndex = if (trackIndex == originalVideoTrackIndex) videoTrackIndex else audioTrackIndex
            currentMuxer?.writeSampleData(realTrackIndex, codecBuffer, info)
        }
    }

    fun stopAndRelease() {
        if (isReleased) return
        isReleased = true
        try { currentMuxer?.stopAndRelease() } catch (e: Exception) {
            com.zero.recorder.RecorderLog.e("ZR.Error", "Exception caught: ${e.message}", e)
        }
        try { mergeSegmentsToFinalFile() } catch (e: Exception) { RecorderLog.w("ZR.Muxer", "Merge failed, segments kept as files") }
    }

    private fun mergeSegmentsToFinalFile() {
        if (segmentFiles.isEmpty()) return

        if (segmentFiles.size == 1) {
            val single = File(segmentFiles[0])
            val target = File(finalOutputPath)
            if (single.exists() && single.length() > 0) {
                target.delete()
                single.renameTo(target)
                try {
                    RandomAccessFile(target, "rw").use { it.fd.sync() }
                } catch (e: Exception) {
                    // 忽略 posix_fadvise 缺失警告，保持日志绝对干净
                }
            }
            return
        }

        val finalMuxer = MediaMuxer(finalOutputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var finalVideoTrack = -1
        var finalAudioTrack = -1
        var buffer = ByteBuffer.allocateDirect(4 * 1024 * 1024) // [I/O 提速] 使用 DirectBuffer 避免 JNI 层面的 Java Heap 到 Native 内存的拷贝开销
        val info = MediaCodec.BufferInfo()

        // [核心修复] 使用全局单调递增时间戳，摒弃容易导致画面冻结的 Offset 算法
        var globalVideoLastPts = 0L
        var globalAudioLastPts = 0L

        try {
            for (segmentPath in segmentFiles) {
                val segFile = File(segmentPath)
                if (!segFile.exists() || segFile.length() <= 0) continue

                val extractor = MediaExtractor()
                val segmentFis = FileInputStream(segmentPath)
                val fd: FileDescriptor = segmentFis.fd

                try {
                    if (Build.VERSION.SDK_INT >= 21) {
                        val osClass = Class.forName("android.system.Os")
                        val fadvise = osClass.getMethod("posix_fadvise", FileDescriptor::class.java, Long::class.java, Long::class.java, Int::class.java)
                        fadvise.invoke(null, fd, 0L, 0L, 2)
                        fadvise.invoke(null, fd, 0L, 0L, 4)
                    }
                } catch (e: Exception) {
                    // 忽略 posix_fadvise 缺失警告，保持日志绝对干净
                }

                extractor.setDataSource(fd)
                val trackCount = extractor.trackCount
                var segVideoTrack = -1
                var segAudioTrack = -1

                if (finalVideoTrack == -1 && finalAudioTrack == -1) {
                    for (i in 0 until trackCount) {
                        val format = extractor.getTrackFormat(i)
                        val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                        when {
                            mime.startsWith("video/") -> {
                                segVideoTrack = i
                                finalVideoTrack = finalMuxer.addTrack(format)
                            }
                            mime.startsWith("audio/") -> {
                                segAudioTrack = i
                                finalAudioTrack = finalMuxer.addTrack(format)
                            }
                        }
                    }
                    finalMuxer.start()
                } else {
                    for (i in 0 until trackCount) {
                        val format = extractor.getTrackFormat(i)
                        val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                        when {
                            mime.startsWith("video/") -> segVideoTrack = i
                            mime.startsWith("audio/") -> segAudioTrack = i
                        }
                    }
                }

                if (segVideoTrack >= 0 && finalVideoTrack >= 0) extractor.selectTrack(segVideoTrack)
                if (segAudioTrack >= 0 && finalAudioTrack >= 0) extractor.selectTrack(segAudioTrack)

                var segVideoOffset = 0L
                var segAudioOffset = 0L
                var isFirstVideo = true
                var isFirstAudio = true

                while (true) {
                    val trackIndex = extractor.sampleTrackIndex
                    if (trackIndex < 0) break

                    val sampleSize = extractor.sampleSize
                    if (sampleSize > buffer.capacity()) {
                        // [性能优化] 如果发生极其罕见的超大帧导致超出初始缓冲，进行一次性对齐扩展
                        val maxInput = Math.max(sampleSize.toInt(), 8 * 1024 * 1024) 
                        com.zero.recorder.RecorderLog.i(TAG, "Expanding merge buffer to ${maxInput/1024} KB")
                        buffer = ByteBuffer.allocateDirect(maxInput)
                    }

                    val size = extractor.readSampleData(buffer, 0)
                    val pts = extractor.sampleTime
                    val flags = extractor.sampleFlags

                    when (trackIndex) {
                        segVideoTrack -> {
                            if (isFirstVideo) {
                                segVideoOffset = globalVideoLastPts - pts
                                if (globalVideoLastPts > 0) segVideoOffset += 16_000L // 补齐 1 帧间隙
                                isFirstVideo = false
                            }
                            val writePts = pts + segVideoOffset
                            if (writePts > globalVideoLastPts) globalVideoLastPts = writePts
                            info.set(0, size, writePts, flags)
                            finalMuxer.writeSampleData(finalVideoTrack, buffer, info)
                        }
                        segAudioTrack -> {
                            if (isFirstAudio) {
                                segAudioOffset = globalAudioLastPts - pts
                                if (globalAudioLastPts > 0) segAudioOffset += 23_000L // 补齐音频帧间隙
                                isFirstAudio = false
                            }
                            val writePts = pts + segAudioOffset
                            if (writePts > globalAudioLastPts) globalAudioLastPts = writePts
                            info.set(0, size, writePts, flags)
                            finalMuxer.writeSampleData(finalAudioTrack, buffer, info)
                        }
                    }
                    extractor.advance()
                }

                extractor.release()
                segmentFis.close()
            }
        } finally {
            try { finalMuxer.stop() } catch (e: Exception) {
            com.zero.recorder.RecorderLog.e("ZR.Error", "Exception caught: ${e.message}", e)
        }
            try { finalMuxer.release() } catch (e: Exception) {
            com.zero.recorder.RecorderLog.e("ZR.Error", "Exception caught: ${e.message}", e)
        }
        }

        try {
            RandomAccessFile(finalOutputPath, "rw").use { it.fd.sync() }
        } catch (e: Exception) {
            com.zero.recorder.RecorderLog.e("ZR.Error", "Exception caught: ${e.message}", e)
        }

        // [数据安全] 校验最终文件是否成功生成且大小合法，否则保留切片供后续排错或抢救
        val finalMergedFile = File(finalOutputPath)
        if (!finalMergedFile.exists() || finalMergedFile.length() < 1024) {
            com.zero.recorder.RecorderLog.e(TAG, "Merge failed or file too small. Segments are KEPT in directory.")
            return
        }
        
        for (segmentPath in segmentFiles) {
            try { File(segmentPath).delete() } catch (e: Exception) {
            com.zero.recorder.RecorderLog.e("ZR.Error", "Exception caught: ${e.message}", e)
        }
        }
    }

    fun getFinalOutputPath(): String = finalOutputPath
}
