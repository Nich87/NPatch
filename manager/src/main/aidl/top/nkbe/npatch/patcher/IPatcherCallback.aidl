package top.nkbe.npatch.patcher;

/** Progress and result callbacks sent from the patcher process back to the manager. */
oneway interface IPatcherCallback {
    /** Batched log lines. Levels match android.util.Log constants. */
    void onLog(in int[] levels, in String[] messages);

    /** Absolute paths of the APKs the patcher produced. */
    void onSuccess(in String[] outputApkPaths);

    void onError(String message);
}
