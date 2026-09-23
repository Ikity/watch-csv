"""Test-only Android-compatible org.json implementation; never packaged in either APK."""
from pathlib import Path
import sys
import urllib.request

root = Path(__file__).resolve().parents[1]
target = root / "deps" / "test-only" / "android-json-0.0.20131108.vaadin1.jar"
if not target.exists():
    target.parent.mkdir(parents=True, exist_ok=True)
    url = "https://repo.maven.apache.org/maven2/com/vaadin/external/google/android-json/0.0.20131108.vaadin1/android-json-0.0.20131108.vaadin1.jar"
    print("Downloading Android-compatible JSON for JVM preset tests", file=sys.stderr)
    with urllib.request.urlopen(url, timeout=60) as response:
        data = response.read()
    temporary = target.with_suffix(".download")
    temporary.write_bytes(data)
    temporary.replace(target)
print(target)
