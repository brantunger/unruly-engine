#!/usr/bin/env bash
# Writes the *.ser files in this folder's parent: exceptions as unruly-engine 2.35.4 serializes them, which
# SerializedExceptionsTest and ReportedFailureTest read on the current version. They need writing again only if
# SerializedSubclasses.java changes or a stream is added: 2.35.4 is released, so what it writes doesn't change.
#
# Usage, from anywhere, with JDK 21.0.7 on the PATH:
#   generate.sh CLASSPATH
# CLASSPATH holds these jars from Maven Central, joined with the platform's path separator (";" on Windows):
#   io.github.brantunger:unruly-engine:2.35.4 and unruly-engine-core:2.35.4, org.mvel:mvel2:2.5.4.Final,
#   org.slf4j:slf4j-api:2.0.20 and org.jspecify:jspecify:1.0.1.
#
# The files were written on JDK 21.0.7. Another JDK writes the same bytes except in run-failure.ser, whose stack
# trace holds frames of the JDK's own modules with their version: a few bytes differ, which no test reads.
#
# The application subclasses are compiled from the test's own SerializedSubclasses.java, with its imports changed
# back to the ones 2.x needs, so each class has the name and serialVersionUID it has in the test.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
streams="$(dirname "$here")"
subclasses="$here/../../../java/io/github/brantunger/unruly/api/SerializedSubclasses.java"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir "$work/src" "$work/classes"
moved="import io.github.brantunger.unruly.api.exception"
# awk, not sed: a new line in a sed replacement is written differently by GNU and BSD sed.
awk -v moved="$moved" '
    { gsub(/unruly\.api\.language\./, "unruly.api.exception."); print }
    /^import java\.util\.List;$/ { print moved ".RuleCompilationException;"; print moved ".RuleExecutionException;" }
' "$subclasses" > "$work/src/SerializedSubclasses.java"
javac -nowarn -d "$work/classes" -cp "$1" "$work/src/SerializedSubclasses.java" "$here/WriteStreams.java"
separator=":"
case "$1" in *\;*) separator=";" ;; esac
# From the work folder, so the class path names it relatively: Git Bash doesn't translate a path inside a ";" list.
cd "$work"
java -cp "classes$separator$1" io.github.brantunger.unruly.api.WriteStreams "$streams"
