#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
ROOT="$(dirname "$(realpath "$0")")"
mkdir -p "$ROOT/build/test-classes"
JSON_JAR="$(python "$ROOT/tests/fetch_json.py")"
javac -encoding UTF-8 -cp "$JSON_JAR" -d "$ROOT/build/test-classes" "$ROOT/src/dev/watchcsv/viewer/CsvReader.java" "$ROOT/src/dev/watchcsv/viewer/TextSearch.java" "$ROOT/src/dev/watchcsv/viewer/ViewSpec.java" "$ROOT/src/dev/watchcsv/viewer/TransferProtocol.java" "$ROOT/src/dev/watchcsv/viewer/RemoteProtocol.java" "$ROOT/tests/CoreTest.java" "$ROOT/tests/TransferTest.java" "$ROOT/tests/ViewSpecTest.java" "$ROOT/tests/RemoteProtocolTest.java"
java -Xmx32m -cp "$ROOT/build/test-classes" dev.watchcsv.viewer.CoreTest
java -Xmx32m -cp "$ROOT/build/test-classes" dev.watchcsv.viewer.TransferTest "$ROOT/build"
java -Xmx32m -cp "$ROOT/build/test-classes:$JSON_JAR" dev.watchcsv.viewer.ViewSpecTest
java -Xmx32m -cp "$ROOT/build/test-classes:$JSON_JAR" dev.watchcsv.viewer.RemoteProtocolTest
