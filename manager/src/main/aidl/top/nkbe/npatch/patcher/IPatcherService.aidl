package top.nkbe.npatch.patcher;

import top.nkbe.npatch.patcher.IPatcherCallback;

/** Runs the patch in the :patcher process so its memory is not shared with the manager UI. */
interface IPatcherService {
    /**
     * Merges inputApkPaths when it is a split set, then patches the result.
     *
     * @param configArgs patcher CLI arguments without the trailing input paths
     * @param inputApkPaths base APK first, then split APKs
     * @param newPackageName names the merged APK
     */
    oneway void patch(
        in String[] configArgs,
        in String[] inputApkPaths,
        String newPackageName,
        boolean verbose,
        IPatcherCallback callback);

    void abort();
}
