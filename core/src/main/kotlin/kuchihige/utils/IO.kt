package kuchihige.utils

import android.os.Handler
import android.os.Looper

val mainHandler = Handler(Looper.getMainLooper())

fun launchIO(block: () -> Unit) {
    Thread(block).start()
}

fun runOnMain(block: () -> Unit) {
    mainHandler.post(block)
}
