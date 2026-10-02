"""Pinned Java 8-compatible Markdown and HTML sanitizing libraries from Maven Central."""
from pathlib import Path
import urllib.request

ROOT = Path(__file__).resolve().parent
ARTIFACTS = [
    ("org/commonmark", "commonmark", "0.27.1"),
    ("org/commonmark", "commonmark-ext-gfm-tables", "0.27.1"),
    ("org/commonmark", "commonmark-ext-gfm-strikethrough", "0.27.1"),
    ("org/commonmark", "commonmark-ext-task-list-items", "0.27.1"),
    ("org/commonmark", "commonmark-ext-autolink", "0.27.1"),
    ("org/commonmark", "commonmark-ext-footnotes", "0.27.1"),
    ("org/nibor/autolink", "autolink", "0.12.0"),
    ("org/jsoup", "jsoup", "1.18.3"),
]


def prepare():
    jars = []
    for group, artifact, version in ARTIFACTS:
        name = f"{artifact}-{version}.jar"
        path = ROOT / "deps" / "markdown" / name
        if not path.exists():
            path.parent.mkdir(parents=True, exist_ok=True)
            url = f"https://repo.maven.apache.org/maven2/{group}/{artifact}/{version}/{name}"
            print("Downloading", url, flush=True)
            with urllib.request.urlopen(url, timeout=60) as response:
                data = response.read()
            temporary = path.with_suffix(".download")
            temporary.write_bytes(data)
            temporary.replace(path)
        jars.append(str(path))
    return jars


if __name__ == "__main__":
    print(":".join(prepare()))
