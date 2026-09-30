#!/usr/bin/env bash
set -euo pipefail

readonly LIBXPOSED_VERSION="102.0.0"
readonly MAVEN_BASE="https://repo.maven.apache.org/maven2/io/github/libxposed"

declare -Ar SOURCE_SHA256=(
    [api]="c4a5761c2409f411ca0f67983a687fbd9dd9a76250d7a68ab7cb2950c820444e"
    [service]="74cd4c36acc4f0251a80a2fc19eddca7f0e330d99a5d4b1c6a07b6136f3459c6"
)

git submodule update --init core
git -C core submodule update --init --recursive \
    external/apache/commons-lang \
    external/axml/manifest-editor \
    external/dobby \
    external/fmt \
    external/lsplant \
    external/lsplt \
    external/xz-embedded

download_sources() {
    local artifact="$1"
    local target="$2"
    local archive
    local actual

    archive="$(mktemp)"
    curl --fail --silent --show-error --location --retry 3 --retry-all-errors \
        --proto '=https' --tlsv1.2 \
        "${MAVEN_BASE}/${artifact}/${LIBXPOSED_VERSION}/${artifact}-${LIBXPOSED_VERSION}-sources.jar" \
        --output "$archive"

    actual="$(sha256sum "$archive" | cut -d' ' -f1)"
    if [[ "$actual" != "${SOURCE_SHA256[$artifact]}" ]]; then
        echo "Unexpected SHA-256 for libxposed ${artifact} sources" >&2
        echo "expected=${SOURCE_SHA256[$artifact]} actual=$actual" >&2
        rm -f "$archive"
        return 1
    fi

    mkdir -p "$target"
    unzip -q -o "$archive" 'io/*' -d "$target"
    rm -f "$archive"
}

download_sources api "core/xposed/libxposed/api/src/main/java"
download_sources service "core/services/libxposed/service/src/main/java"
