#!/usr/bin/env bash
set -euo pipefail
ROOT="$(dirname "$(realpath "$0")")"
mkdir -p "$ROOT/build/test-classes"
JSON_JAR="$(python "$ROOT/tests/fetch_json.py")"
javac -encoding UTF-8 -cp "$JSON_JAR" -d "$ROOT/build/test-classes" "$ROOT/src/dev/watchnotes/Note.java" "$ROOT/src/dev/watchnotes/MarkdownNotes.java" "$ROOT/src/dev/watchnotes/ShareNotes.java" "$ROOT/src/dev/watchnotes/MarkdownPreview.java" "$ROOT/tests/NotesTest.java"
java -Xmx64m -cp "$ROOT/build/test-classes:$JSON_JAR" dev.watchnotes.NotesTest
