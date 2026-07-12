package com.zero.recorder.core

import android.content.Context
import android.os.Binder
import android.os.Handler
import android.os.Looper
import android.os.Process
import com.zero.recorder.RecorderLog
import com.zero.recorder.capture.DisplayCaptureController
import com.zero.recorder.system.NativeCore
import com.zero.recorder.system.ShellEnvironment
import com.zero.recorder.system.WakeLockController
import com.zero.recorder.ui.FloatingMenuController
import com.zero.recorder.ui.RawTouchDispatcher
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

object RecordingOrchestrator {
    private const val TAG = "ZR.Orchestrator"
    private val recordingActive = AtomicBoolean(false)
    private var currentRecordThread: Thread? = null

    @JvmStatic
    fun main(args: Array<String>) {
        setupProcessEnvironment()
        
        val shellContext = ShellEnvironment.getShellContext()
        val displayController = DisplayCaptureController(ShellEnvironment.getDisplayManagerGlobal())
        displayController.refreshDisplayInfo()

        val menuController = FloatingMenuController()
        var menuPos = mutableListOf(100, 100)
        menuController.init(menuPos[0], menuPos[1])
        menuController.drawMenu(false)

        val touchDispatcher = setupTouchDispatcher(menuController, menuPos, shellContext, displayController)
        touchDispatcher.start(
            displayController.screenWidth,
            displayController.screenHeight,
            { displayController.rotation }
        )

        displayController.registerDisplayListener(Handler(Looper.getMainLooper()))
        Looper.loop()
    }

    private fun setupProcessEnvironment() {
        val pidFile = File("/data/local/tmp/zero_recorder.pid")
        if (pidFile.exists()) {
            try {
                val oldPid = String(Files.readAllBytes(pidFile.toPath())).trim()
                if (File("/proc/$oldPid").exists()) {
                    RecorderLog.e(TAG, "Another instance is already running (PID $oldPid). Exiting.")
                    System.exit(1)
                }
            } catch (e: Exception) {
            com.zero.recorder.RecorderLog.e("ZR.Error", "Exception caught: ${e.message}", e)
        }
            pidFile.delete()
        }
        try {
            Files.write(pidFile.toPath(), Process.myPid().toString().toByteArray())
        } catch (e: Exception) {
            com.zero.recorder.RecorderLog.e("ZR.Error", "Exception caught: ${e.message}", e)
        }

        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            RecorderLog.e(TAG, "Process crashed", e)
            recordingActive.set(false)
            try { Thread.sleep(1000) /* FIXME: 阻塞式休眠，建议重构为协程 delay() 或使用 ReentrantLock.Condition 同步机制 */ } catch (e: InterruptedException) {}
            System.exit(1)
        }

        if (Looper.getMainLooper() == null) Looper.prepareMainLooper()
        Process.setThreadPriority(Process.THREAD_PRIORITY_VIDEO)
        Binder.clearCallingIdentity()

        if (NativeCore.isAvailable()) {
            NativeCore.protectFromOOM()
            NativeCore.bindToPerformanceCores()
            NativeCore.lockMemoryIntoRAM()
        }

        ShellEnvironment.bypassHiddenApi()
        ShellEnvironment.applyWorkarounds()
        OutputManager.cleanupResidualEnvironment()
        
        startStopMonitor()
    }

    private fun startStopMonitor() {
        Thread({
            val stopFlag = File("/data/local/tmp/zero_recorder.stop")
            val doneFlag = File("/data/local/tmp/zero_recorder.done")
            if (stopFlag.exists()) stopFlag.delete()
            if (doneFlag.exists()) doneFlag.delete()
            while (true) {
                if (stopFlag.exists()) {
                    RecorderLog.i(TAG, "Stop signal received, waiting for recording to finish...")
                    recordingActive.set(false)
                    stopFlag.delete()
                    currentRecordThread?.let {
                        try { it.join() } catch (e: Exception) {
            com.zero.recorder.RecorderLog.e("ZR.Error", "Exception caught: ${e.message}", e)
        } 
                    }
                    try { doneFlag.createNewFile() } catch (e: Exception) {
            com.zero.recorder.RecorderLog.e("ZR.Error", "Exception caught: ${e.message}", e)
        }
                    System.exit(0)
                }
                try { Thread.sleep(200) /* FIXME: 阻塞式休眠，建议重构为协程 delay() 或使用 ReentrantLock.Condition 同步机制 */ } catch (e: Exception) {
            com.zero.recorder.RecorderLog.e("ZR.Error", "Exception caught: ${e.message}", e)
        }
            }
        }, "StopMonitorThread").start()
    }

    private fun setupTouchDispatcher(
        menuController: FloatingMenuController, 
        menuPos: MutableList<Int>,
        shellContext: Context,
        displayController: DisplayCaptureController
    ): RawTouchDispatcher {
        return RawTouchDispatcher(object : RawTouchDispatcher.TouchListener {
            private var isDragging = false
            private var downX = 0f
            private var downY = 0f
            private var initialMenuX = 0
            private var initialMenuY = 0
            private var isDownInside = false

            override fun onDown(x: Float, y: Float) {
                val currentWidth = menuController.getCurrentWidth(recordingActive.get())
                if (x >= menuPos[0] && x <= menuPos[0] + currentWidth &&
                    y >= menuPos[1] && y <= menuPos[1] + menuController.getCurrentHeight()) {
                    isDownInside = true
                    isDragging = false
                    downX = x
                    downY = y
                    initialMenuX = menuPos[0]
                    initialMenuY = menuPos[1]
                } else {
                    isDownInside = false
                }
            }

            override fun onMove(x: Float, y: Float) {
                if (!isDownInside) return
                val dx = x - downX
                val dy = y - downY
                if (!isDragging && (Math.abs(dx) > 10 || Math.abs(dy) > 10)) isDragging = true
                if (isDragging) {
                    menuPos[0] = Math.max(0, Math.min(initialMenuX + dx.toInt(), displayController.screenWidth - menuController.getCurrentWidth(recordingActive.get())))
                    menuPos[1] = Math.max(0, Math.min(initialMenuY + dy.toInt(), displayController.screenHeight - menuController.getCurrentHeight()))
                    menuController.setPosition(menuPos[0], menuPos[1])
                }
            }

            override fun onUp() {
                if (!isDownInside) return
                if (!isDragging) {
                    val clickedIndex = ((downX - menuPos[0]) / FloatingMenuController.ITEM_SIZE).toInt()
                    try {
                        val v = shellContext.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
                        v?.vibrate(android.os.VibrationEffect.createOneShot(30, 100))
                    } catch (e: Exception) {
            com.zero.recorder.RecorderLog.e("ZR.Error", "Exception caught: ${e.message}", e)
        }
                    if (!recordingActive.get()) {
                        when (clickedIndex) {
                            0 -> {
                                recordingActive.set(true)
                                menuController.drawMenu(true)
                                if (currentRecordThread == null || !currentRecordThread!!.isAlive) {
                                    currentRecordThread = Thread({
                                        try {
                                            WakeLockController.acquire(shellContext)
                                            val engine = RecordingEngine(shellContext, displayController, recordingActive)
                                            engine.start(displayController.rotation)
                                        } catch (t: Throwable) {
                                            RecorderLog.e(TAG, "Engine crashed", t)
                                        } finally {
                                            recordingActive.set(false)
                                            menuController.drawMenu(false)
                                            WakeLockController.release()
                                        }
                                    }, "RecordThread").also { it.start() }
                                }
                            }
                            2 -> {
                                recordingActive.set(false)
                                menuController.release()
                                Thread {
                                    currentRecordThread?.let { try { it.join() } catch (e: Exception) {
            com.zero.recorder.RecorderLog.e("ZR.Error", "Exception caught: ${e.message}", e)
        } }
                                    WakeLockController.release()
                                    System.exit(0)
                                }.start()
                            }
                        }
                    } else if (clickedIndex == 0) {
                        recordingActive.set(false)
                        val restoredWidth = menuController.getCurrentWidth(false)
                        if (menuPos[0] + restoredWidth > displayController.screenWidth) {
                            menuPos[0] = Math.max(0, displayController.screenWidth - restoredWidth)
                            menuController.setPosition(menuPos[0], menuPos[1])
                        }
                        menuController.drawMenu(false)
                    }
                }
                isDownInside = false
                isDragging = false
            }
        })
    }
}
