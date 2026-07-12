package com.zero.recorder.system

import android.util.Log

object NativeCore {
    private var isLoaded = false

    init {
        try {
            System.loadLibrary("zerocore")
            isLoaded = true
        } catch (t: Throwable) {
            Log.e("ZR.Native", "Failed to load C++ core: ${t.message}")
        }
    }

    // 激活内核级实时 FIFO 调度 (必须在核心音视频线程中调用)
    external fun enableRealTimeScheduling(): Boolean

    // 物理锁定所有内存页
    external fun lockMemoryIntoRAM(): Boolean
    external fun bindToPerformanceCores(): Boolean
    external fun protectFromOOM()

    fun isAvailable(): Boolean = isLoaded
}
