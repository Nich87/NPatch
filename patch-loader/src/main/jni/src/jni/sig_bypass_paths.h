#pragma once

// Shared by bypass_sig.cpp / seccompv.cpp / svc_bypass.cpp: each hooks sigbypass at a different
// layer (inline libc hook, seccomp trap, raw svc instrumentation), but all three need the exact
// same "is this path the one we're redirecting" check, including the once-unlinked-file case --
// the kernel appends " (deleted)" to a path resolved after the file was unlinked (e.g. an app that
// reopens its own apk via /proc/self/fd/N after the installer replaces it). Before this header
// each of the three files carried its own copy of this comparison; consolidating it here means a
// future fix only has to land once.

#include <cstring>
#include <string_view>

namespace lspd {

    inline bool ShouldRedirectApkContents(const char* caller_path, bool module_caller,
                                         bool target_apk) {
        if (module_caller) return false;
        if (!target_apk || caller_path == nullptr) return true;
        std::string_view path(caller_path);
        if (!path.starts_with("/system/") && !path.starts_with("/system_ext/")
                && !path.starts_with("/apex/")) return true;
        if (path.ends_with(" (deleted)")) path.remove_suffix(10);
        const auto library = path.substr(path.find_last_of('/') + 1);
        // Resource ZIP offsets belong to the patched APK, including when AssetManager
        // reopens a stored asset for openFd. Generic Java/libc readers still redirect.
        return library != "libandroidfw.so" && library != "libandroid.so"
               && library != "libandroid_runtime.so" && library != "libziparchive.so";
    }

    inline bool PathMatchesTarget(const char* candidate, const char* target_path, size_t target_len) {
        if (candidate == nullptr || target_path == nullptr || target_len == 0) {
            return false;
        }
        if (std::strncmp(candidate, target_path, target_len) != 0) {
            return false;
        }
        return candidate[target_len] == '\0' || std::strcmp(candidate + target_len, " (deleted)") == 0;
    }

    inline bool PathMatchesTarget(const char* candidate, const char* target_path) {
        if (target_path == nullptr) return false;
        return PathMatchesTarget(candidate, target_path, std::strlen(target_path));
    }

    inline void CopyPathBuffer(char* dest, size_t dest_size, const char* src) {
        if (dest == nullptr || dest_size == 0) return;
        if (src == nullptr) {
            dest[0] = '\0';
            return;
        }
        std::strncpy(dest, src, dest_size - 1);
        dest[dest_size - 1] = '\0';
    }

} // namespace lspd
