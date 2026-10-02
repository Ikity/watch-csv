#!/usr/bin/env bash
set -euo pipefail
ROOT="$(dirname "$(realpath "$0")")"
mkdir -p "$ROOT/build/test-classes"
JSON_JAR="$(python "$ROOT/tests/fetch_json.py")"
MARKDOWN_JARS="$(python "$ROOT/prepare_markdown.py" | python -c 'import sys; print(sys.stdin.read().splitlines()[-1])')"
javac -encoding UTF-8 -cp "$JSON_JAR:$MARKDOWN_JARS" -d "$ROOT/build/test-classes" "$ROOT/src/dev/watchnotes/Note.java" "$ROOT/src/dev/watchnotes/MarkdownNotes.java" "$ROOT/src/dev/watchnotes/ShareNotes.java" "$ROOT/src/dev/watchnotes/MarkdownPreview.java" "$ROOT/tests/NotesTest.java"
java -Xmx96m -cp "$ROOT/build/test-classes:$JSON_JAR:$MARKDOWN_JARS" dev.watchnotes.NotesTest "$ROOT"
