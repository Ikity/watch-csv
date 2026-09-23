"""Build the two same-package, same-signer Data Layer apps using Termux tools."""
import os
from pathlib import Path
import shutil
import subprocess
import sys
import zipfile
from prepare_deps import ROOT, prepare


def run(*args):
    subprocess.run([str(a) for a in args], check=True)


def build(variant, libs):
    buildroot = ROOT / "build"
    work = buildroot / variant
    work.mkdir(parents=True, exist_ok=True)
    for name in ("classes", "generated", "dex"):
        path = work / name
        if path.exists():
            shutil.rmtree(path)
        path.mkdir()
    sdk = Path(os.environ.get("ANDROID_HOME", Path.home() / "android-sdk"))
    android = Path(os.environ.get("ANDROID_JAR", sdk / "platforms/android-36/android.jar"))
    jars, resources, packages = [], [], set()
    for i, lib in enumerate(libs):
        jars.extend(lib["jars"])
        if "package" in lib:
            packages.add(lib["package"])
            res = Path(lib["root"]) / "res"
            if res.exists() and any(p.is_file() for p in res.rglob("*")):
                compiled = work / f"lib-{i}.zip"
                run("aapt2", "compile", "--dir", res, "-o", compiled)
                resources.extend(["-R", compiled])
    run("aapt2", "compile", "--dir", ROOT / "res", "-o", work / "resources.zip")
    manifest = ROOT / ("phone/AndroidManifest.xml" if variant == "phone" else "AndroidManifest.xml")
    unsigned = work / "unsigned.apk"
    run("aapt2", "link", "--auto-add-overlay", "-o", unsigned, "-I", android,
        "--manifest", manifest, "--java", work / "generated", "--extra-packages", ":".join(sorted(packages)),
        *resources, "-R", work / "resources.zip")
    java = sorted((ROOT / "src").rglob("*.java")) + sorted((work / "generated").rglob("*.java"))
    classpath = os.pathsep.join([str(android)] + jars)
    run("javac", "-encoding", "UTF-8", "-source", "8", "-target", "8", "-Xlint:-options", "-cp", classpath, "-d", work / "classes", *java)
    run("jar", "cf", work / "classes.jar", "-C", work / "classes", ".")
    run("d8", "--lib", android, "--min-api", "30", "--output", work / "dex", work / "classes.jar", *jars)
    with zipfile.ZipFile(unsigned, "a", compression=zipfile.ZIP_STORED) as apk:
        for dex in sorted((work / "dex").glob("*.dex")):
            apk.write(dex, dex.name)
    aligned = work / "aligned.apk"
    run("zipalign", "-f", "4", unsigned, aligned)
    key = ROOT / "keys/development.jks"
    key.parent.mkdir(exist_ok=True)
    if not key.exists():
        run("keytool", "-genkeypair", "-keystore", key, "-storepass", "android", "-keypass", "android", "-alias", "watchcsv",
            "-dname", "CN=Watch CSV Development", "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000")
    output = buildroot / ("watch-csv-companion.apk" if variant == "phone" else "watch-csv.apk")
    run("apksigner", "sign", "--ks", key, "--ks-pass", "pass:android", "--key-pass", "pass:android", "--out", output, aligned)
    run("apksigner", "verify", "--verbose", output)
    run("zipalign", "-c", "4", output)
    if variant == "watch":
        # Keep the existing optional instrumentation builder's classpath stable.
        shutil.copy2(work / "classes.jar", buildroot / "classes.jar")
        old = buildroot / "classes"
        if old.exists():
            shutil.rmtree(old)
        shutil.copytree(work / "classes", old)
    print(f"\n{variant.upper()} APK: {output} ({output.stat().st_size:,} bytes)", flush=True)


if __name__ == "__main__":
    variants = sys.argv[1:] or ["watch", "phone"]
    if any(v not in ("watch", "phone") for v in variants):
        raise SystemExit("Usage: python build_apks.py [watch] [phone]")
    libraries = prepare()
    for variant in variants:
        build(variant, libraries)
