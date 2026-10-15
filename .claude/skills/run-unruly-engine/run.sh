#!/usr/bin/env bash
# Builds the unruly-engine jars and runs a Java source file against them, on a chosen JDK, with chosen JVM flags and
# a chosen SLF4J binding. Git Bash on Windows, or any bash. Exits with java's exit code.
#
#   .claude/skills/run-unruly-engine/run.sh [options] [Snippet.java [args...]]
#
#   --jdk <dir>       JAVA_HOME of the JDK to run on (default: $JAVA_HOME). Needs JDK 21+: the jars are Java 21.
#   --slf4j simple|none  slf4j-simple on the class path (default), or no binding at all (SLF4J's NOP logger).
#   -J<flag>          A JVM flag, repeatable: -J-Xss512k -J-Xcomp -J-Dorg.slf4j.simpleLogger.defaultLogLevel=debug
#   --skip-build      Don't run Gradle; use the jars from the last build.
#   Snippet.java      A single-file Java program (default: Smoke.java next to this script). Its args follow it.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../../.." && pwd)"
jdk="${JAVA_HOME:-}"
slf4j=simple
build=1
jvm=()
while [ $# -gt 0 ]; do
    case "$1" in
        --jdk) jdk="$2"; shift 2 ;;
        --slf4j) slf4j="$2"; shift 2 ;;
        --skip-build) build=0; shift ;;
        -J*) jvm+=("${1#-J}"); shift ;;
        -h|--help) sed -n '2,11p' "${BASH_SOURCE[0]}"; exit 0 ;;
        --) shift; break ;;
        -*) echo "run.sh: unknown option $1" >&2; exit 2 ;;
        *) break ;;
    esac
done
snippet="${1:-$here/Smoke.java}"
[ $# -gt 0 ] && shift
case "$slf4j" in simple|none) ;; *) echo "run.sh: --slf4j takes simple or none, not $slf4j" >&2; exit 2 ;; esac
[ -n "$jdk" ] || { echo "run.sh: set JAVA_HOME or pass --jdk <dir>" >&2; exit 2; }
java="$jdk/bin/java"
[ -x "$java" ] || [ -x "$java.exe" ] || { echo "run.sh: no java at $java" >&2; exit 2; }

# The jars: the two artifacts and their runtime dependencies, which :native-smoke:installDist copies into one
# directory, so no build file has to change and no jar is looked up in Gradle's cache.
lib="$root/native-smoke/build/install/native-smoke/lib"
if [ "$build" = 1 ]; then
    (cd "$root" && ./gradlew -q :native-smoke:installDist)
fi
[ -d "$lib" ] || { echo "run.sh: $lib is missing; run without --skip-build" >&2; exit 2; }

# Windows paths for a Windows java: Git Bash would otherwise hand it /c/... paths, which it can't open.
to_java_path() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }
case "${OSTYPE:-}" in msys*|cygwin*) sep=';' ;; *) sep=':' ;; esac
if [ "$slf4j" = simple ]; then
    cp="$(to_java_path "$lib")/*"
else
    cp=""
    for jar in "$lib"/*.jar; do
        case "$jar" in */slf4j-simple-*) continue ;; esac
        cp="${cp:+$cp$sep}$(to_java_path "$jar")"
    done
fi

echo "run.sh: $(to_java_path "$java") ${jvm[*]:-} -cp <$slf4j> $(to_java_path "$snippet") $*" >&2
exec "$java" ${jvm[@]+"${jvm[@]}"} -cp "$cp" "$(to_java_path "$snippet")" "$@"
