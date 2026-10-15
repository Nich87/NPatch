#include <jni.h>
#include <sys/resource.h>

JNIEXPORT jlong JNICALL
Java_top_nkbe_npatch_util_ProcessStorageIo_readBytes(JNIEnv *env, jobject instance) {
    struct rusage usage;
    if (getrusage(RUSAGE_SELF, &usage) != 0) {
        return -1;
    }
    // Linux derives these 512-byte block counts from read_bytes and write_bytes.
    return ((jlong) usage.ru_inblock + (jlong) usage.ru_oublock) * 512;
}
