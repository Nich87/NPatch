package top.nkbe.npatch.util

internal object ProcessStorageIo {
    init {
        System.loadLibrary("npatch_metrics")
    }

    external fun readBytes(): Long
}
