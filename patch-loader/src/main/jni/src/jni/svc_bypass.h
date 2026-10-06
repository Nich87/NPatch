#pragma once
#include <jni.h>

namespace lspd {

    // Arms EXTREME's last line of defense against an app that reads its own installed APK via a
    // hand-written inline `svc #0` instead of calling into libc's openat -- a path that every
    // other sigbypass layer (Java IO hooks, libc inline hooks, xhook GOT rewriting) is blind to,
    // since none of them sit between the instruction stream and the kernel. See svc_bypass.cpp
    // for the full design note.
    void RegisterSvcBypass(JNIEnv* env);

} // namespace lspd
