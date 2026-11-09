#!/usr/bin/env bash
set -euo pipefail

source_root="${1:-$PWD}"
application_id="${PARALLEL_APPLICATION_ID:-app.gamenative.parallel}"
application_name="${PARALLEL_APP_NAME:-GameNative Parallel}"

fail() {
    echo "Parallel patch error: $*" >&2
    exit 1
}

replace_exact() {
    local file="$1"
    local from="$2"
    local to="$3"
    local expected_count="$4"
    local optional="${5:-false}"

    if [[ ! -f "$file" ]]; then
        [[ "$optional" == "true" ]] && return
        fail "missing required file: $file"
    fi

    python3 - "$file" "$from" "$to" "$expected_count" "$optional" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
old, new = sys.argv[2], sys.argv[3]
expected = int(sys.argv[4])
optional = sys.argv[5] == "true"
contents = path.read_text(encoding="utf-8")
count = contents.count(old)
if count == 0 and optional:
    raise SystemExit(0)
if count != expected:
    raise SystemExit(
        f"Parallel patch error: expected {expected} occurrence(s) of {old!r} "
        f"in {path}, found {count}"
    )
path.write_text(contents.replace(old, new), encoding="utf-8")
PY
}

[[ -d "$source_root/app" ]] || fail "not a GameNative source tree: $source_root"

replace_exact \
    "$source_root/gradle/wrapper/gradle-wrapper.properties" \
    'networkTimeout=10000' \
    'networkTimeout=120000' \
    1

replace_exact \
    "$source_root/app/build.gradle.kts" \
    'applicationId = "app.gamenative"' \
    "applicationId = \"$application_id\"" \
    1

mapfile -t string_files < <(
    find "$source_root/app/src/main/res" -type f -path '*/values*/strings.xml' -print | sort
)
(( ${#string_files[@]} > 0 )) || fail "no Android string resources found"

for strings_file in "${string_files[@]}"; do
    replace_exact \
        "$strings_file" \
        '<string name="app_name">GameNative</string>' \
        "<string name=\"app_name\">$application_name</string>" \
        1
    replace_exact \
        "$strings_file" \
        '<string name="login_app_name">GameNative</string>' \
        "<string name=\"login_app_name\">$application_name</string>" \
        1
done

replace_exact \
    "$source_root/app/src/main/AndroidManifest.xml" \
    'android:name="app.gamenative.LAUNCH_GAME"' \
    "android:name=\"$application_id.LAUNCH_GAME\"" \
    1
replace_exact \
    "$source_root/app/src/main/AndroidManifest.xml" \
    'android:scheme="app.gamenative"' \
    "android:scheme=\"$application_id\"" \
    1 \
    true

# The Kotlin package intentionally remains app.gamenative, but private-data paths
# must use the installed application's ID. Otherwise a Parallel build writes to
# GameNative's sandbox, which Android denies.
replace_exact \
    "$source_root/app/src/main/java/com/winlator/core/WineUtils.java" \
    'E:/data/data/app.gamenative/storage' \
    "E:/data/data/$application_id/storage" \
    1
replace_exact \
    "$source_root/app/src/main/java/com/winlator/core/WineUtils.java" \
    '/app.gamenative/storage' \
    "/$application_id/storage" \
    1
replace_exact \
    "$source_root/app/src/main/java/com/winlator/core/DXVKHelper.java" \
    '/data/data/app.gamenative/files/imagefs' \
    "/data/data/$application_id/files/imagefs" \
    1
replace_exact \
    "$source_root/app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java" \
    '/data/data/app.gamenative/files/imagefs/tmp/gamepad' \
    "/data/data/$application_id/files/imagefs/tmp/gamepad" \
    2
replace_exact \
    "$source_root/app/src/main/java/com/winlator/container/Container.java" \
    '/data/data/app.gamenative/files/imagefs/home/xuser/' \
    "/data/data/$application_id/files/imagefs/home/xuser/" \
    6
replace_exact \
    "$source_root/app/src/main/java/com/winlator/container/Container.java" \
    'E:/data/data/app.gamenative/storage' \
    "E:/data/data/$application_id/storage" \
    1

# JavaSteam can report a failed asynchronous job from its WebSocket worker after
# the requesting download coroutine has already handled the failure. Do not let
# that detached worker take down the entire Android process.
replace_exact \
    "$source_root/app/src/main/java/app/gamenative/CrashHandler.kt" \
    'import android.content.Context' \
    'import android.content.Context
import `in`.dragonbra.javasteam.steam.steamclient.AsyncJobFailedException' \
    1
replace_exact \
    "$source_root/app/src/main/java/app/gamenative/CrashHandler.kt" \
    '    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        PrefManager.recentlyCrashed = true

        saveCrashToFile(throwable)
        defaultHandler?.uncaughtException(thread, throwable)
    }' \
    '    private fun isAsyncSteamJobFailure(throwable: Throwable): Boolean {
        var current: Throwable? = throwable
        while (current != null) {
            if (current is AsyncJobFailedException) return true
            current = current.cause
        }
        return false
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        if (isAsyncSteamJobFailure(throwable)) {
            android.util.Log.w(
                "CrashHandler",
                "Ignoring JavaSteam asynchronous job failure from ${thread.name}",
                throwable,
            )
            saveCrashToFile(throwable)
            return
        }

        PrefManager.recentlyCrashed = true
        saveCrashToFile(throwable)
        defaultHandler?.uncaughtException(thread, throwable)
    }' \
    1
replace_exact \
    "$source_root/app/src/main/java/app/gamenative/mods/NexusOAuthModels.kt" \
    'const val REDIRECT_URI = "app.gamenative://oauth/callback"' \
    "const val REDIRECT_URI = \"$application_id://oauth/callback\"" \
    1 \
    true
replace_exact \
    "$source_root/app/src/main/java/app/gamenative/utils/IntentLaunchManager.kt" \
    'private const val ACTION_LAUNCH_GAME = "app.gamenative.LAUNCH_GAME"' \
    "private const val ACTION_LAUNCH_GAME = \"$application_id.LAUNCH_GAME\"" \
    1
replace_exact \
    "$source_root/app/src/main/java/app/gamenative/utils/ShortcutUtils.kt" \
    'val intent = Intent("app.gamenative.LAUNCH_GAME").apply {' \
    "val intent = Intent(\"$application_id.LAUNCH_GAME\").apply {" \
    1
replace_exact \
    "$source_root/app/src/main/java/app/gamenative/ui/components/BootingSplash.kt" \
    'text = "GameNative",' \
    "text = \"$application_name\"," \
    2
replace_exact \
    "$source_root/app/src/test/java/app/gamenative/mods/NexusOAuthControllerTest.kt" \
    'app.gamenative://oauth/callback' \
    "$application_id://oauth/callback" \
    12 \
    true
replace_exact \
    "$source_root/app/src/test/java/app/gamenative/ui/screen/auth/NexusOAuthCallbackContractTest.kt" \
    'app.gamenative://oauth/callback' \
    "$application_id://oauth/callback" \
    6 \
    true

echo "Applied Parallel identity patch to $source_root"
