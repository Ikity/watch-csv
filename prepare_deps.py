"""Resolve pinned Google Maven dependencies for the ARM/Termux APK builder."""
import hashlib
import json
from pathlib import Path
import re
import time
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parent
CACHE = ROOT / "deps"
NS = {"m": "http://maven.apache.org/POM/4.0.0"}
BASE = "https://dl.google.com/dl/android/maven2/"


def version_key(value):
    return tuple(int(part) if part.isdigit() else part for part in re.split(r"[.-]", value))


def fetch(group, artifact, version, extension):
    name = f"{artifact}-{version}.{extension}"
    target = CACHE / group / artifact / version / name
    if not target.exists():
        target.parent.mkdir(parents=True, exist_ok=True)
        url = BASE + group.replace(".", "/") + f"/{artifact}/{version}/{name}"
        print("Downloading", url, flush=True)
        for attempt in range(4):
            try:
                with urllib.request.urlopen(url, timeout=45) as response:
                    data = response.read()
                break
            except (OSError, TimeoutError):
                if attempt == 3:
                    raise
                time.sleep(2)
        temporary = target.with_suffix(target.suffix + ".download")
        temporary.write_bytes(data)
        temporary.replace(target)
    return target


def prepare():
    selected = {}
    pending = [("com.google.android.gms", "play-services-wearable", "19.0.0")]
    while pending:
        group, artifact, version = pending.pop(0)
        version = version.strip("[]")
        key = (group, artifact)
        previous = selected.get(key)
        if previous and version_key(previous["version"]) >= version_key(version):
            continue
        pom = ET.parse(fetch(group, artifact, version, "pom")).getroot()
        kind = pom.findtext("m:packaging", "jar", NS)
        selected[key] = {"group": group, "artifact": artifact, "version": version, "kind": kind}
        for dep in pom.findall("m:dependencies/m:dependency", NS):
            if dep.findtext("m:scope", "compile", NS) not in ("compile", "runtime"):
                continue
            if dep.findtext("m:optional", "false", NS) == "true":
                continue
            coords = tuple(dep.findtext("m:" + field, namespaces=NS) for field in ("groupId", "artifactId", "version"))
            if not all(coords) or any("${" in x for x in coords):
                raise RuntimeError("Unresolved dependency: " + str(coords))
            pending.append(coords)
    libraries = []
    for key in sorted(selected):
        item = selected[key]
        artifact = fetch(item["group"], item["artifact"], item["version"], item["kind"])
        item["sha256"] = hashlib.sha256(artifact.read_bytes()).hexdigest()
        if item["kind"] == "aar":
            extracted = artifact.parent / "unpacked"
            if not (extracted / "AndroidManifest.xml").exists():
                with zipfile.ZipFile(artifact) as archive:
                    archive.extractall(extracted)
            manifest = ET.parse(extracted / "AndroidManifest.xml").getroot()
            item["package"] = manifest.attrib.get("package")
            item["root"] = str(extracted)
            item["jars"] = [str(extracted / "classes.jar")] if (extracted / "classes.jar").exists() else []
            item["jars"] += [str(p) for p in sorted((extracted / "libs").glob("*.jar"))]
        elif item["kind"] == "jar":
            item["jars"] = [str(artifact)]
        else:
            raise RuntimeError("Unsupported packaging: " + item["kind"])
        libraries.append(item)
    CACHE.mkdir(parents=True, exist_ok=True)
    (CACHE / "resolved.json").write_text(json.dumps(libraries, indent=2) + "\n")
    print(f"Ready: {len(libraries)} Google Maven libraries", flush=True)
    return libraries


if __name__ == "__main__":
    prepare()
