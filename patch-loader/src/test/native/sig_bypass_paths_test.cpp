#include "../../main/jni/src/jni/sig_bypass_paths.h"

#include <cstdio>

int main() {
    struct Case {
        const char* caller;
        bool module;
        bool apk;
        bool redirect;
    };
    const Case cases[] = {
        {"/system/lib64/libandroidfw.so", false, true, false},
        {"/system/lib/libandroidfw.so", false, true, false},
        {"/system_ext/lib64/libandroidfw.so", false, true, false},
        {"/apex/com.android.example/lib64/libandroidfw.so", false, true, false},
        {"/system/lib64/libandroid.so", false, true, false},
        {"/system/lib64/libandroid_runtime.so", false, true, false},
        {"/system/lib64/libziparchive.so", false, true, false},
        {"/system/lib64/libandroidfw.so (deleted)", false, true, false},
        {"/system/lib64/libandroidfw.so", false, false, true},
        {"/apex/com.android.art/lib64/libopenjdk.so", false, true, true},
        {"/apex/com.android.runtime/lib64/bionic/libc.so", false, true, true},
        {"/data/app/example/lib/arm64/libandroidfw.so", false, true, true},
        {"/system_fake/lib64/libandroidfw.so", false, true, true},
        {"/system/lib64/libandroidfw.so.backup", false, true, true},
        {"libandroidfw.so", false, true, true},
        {"/data/app/example/lib/arm64/libapp.so", false, true, true},
        {"/data/user/0/example/modules/libmodule.so", true, true, false},
        {nullptr, false, true, true},
    };
    for (const auto& test : cases) {
        if (lspd::ShouldRedirectApkContents(test.caller, test.module, test.apk) != test.redirect) {
            std::fprintf(stderr, "Incorrect APK redirect: caller=%s module=%d apk=%d expected=%d\n",
                         test.caller ? test.caller : "<unknown>", test.module, test.apk, test.redirect);
            return 1;
        }
    }
    std::printf("PASS: %zu caller-policy cases (resource readers, signature readers, modules, snapshots)\n",
                sizeof(cases) / sizeof(cases[0]));
}
