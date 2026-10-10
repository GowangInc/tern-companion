#!/usr/bin/env python3
"""Run host checks, plus real Android checks when --serial is supplied.

Build the debug APK first. Android checks use synthetic data, push to
/data/local/tmp and do not install an APK or change saved host settings.
"""
import argparse
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "io.github.gowanginc.terncompanion"
SOURCE = ROOT / "app/src/main/java/io/github/gowanginc/terncompanion"


def run(*args, env=None):
    result = subprocess.run([str(arg) for arg in args], cwd=ROOT, env=env, check=False)
    if result.returncode:
        raise SystemExit(result.returncode)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", type=Path, default=os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT"))
    parser.add_argument("--serial", help="Explicit adb device/emulator serial; enables native Android checks")
    parser.add_argument("--apk", type=Path, default=ROOT / "app/build/outputs/apk/debug/app-debug.apk")
    parser.add_argument("--update-apk", type=Path, help="Newer production APK; requires installed older same-signer app")
    parser.add_argument("--wrong-signer-apk", type=Path, help="Same package/version APK with a different test signer")
    parser.add_argument("--wrong-package-apk", type=Path, help="Test APK with an unrelated package")
    options = parser.parse_args()
    java_home = Path(os.environ["JAVA_HOME"]) / "bin" if os.environ.get("JAVA_HOME") else None
    javac = java_home / "javac" if java_home else shutil.which("javac")
    java = java_home / "java" if java_home else shutil.which("java")
    jar = java_home / "jar" if java_home else shutil.which("jar")
    if not all((javac, java, jar)):
        parser.error("Install JDK 21 and set JAVA_HOME")
    run(sys.executable, "-m", "unittest", "discover", "-s", "checks", "-p", "test_*.py", "-v")
    run(sys.executable, "checks/BridgeBehaviorCheck.py")
    with tempfile.TemporaryDirectory(prefix="tern-suite-") as folder:
        work = Path(folder)
        desktop = work / "desktop"
        desktop.mkdir()
        run(javac, "-d", desktop, SOURCE / "Host.java", ROOT / "checks/HostCheck.java",
            SOURCE / "UpdatePolicy.java", ROOT / "checks/UpdateCheck.java")
        for name in ("HostCheck", "UpdateCheck"):
            run(java, "-cp", desktop, PACKAGE + "." + name)
        if not options.serial:
            print("Host suite passed. Android checks NOT RUN; add --sdk SDK --serial SERIAL.")
            return
        if not options.sdk or not options.sdk.is_dir():
            parser.error("--serial requires --sdk or ANDROID_HOME")
        sdk = options.sdk
        android = sdk / "platforms/android-35/android.jar"
        classes = ROOT / "app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes"
        if not android.is_file() or not classes.is_dir() or not options.apk.is_file():
            parser.error("Build assembleDebug first using Android SDK platform 35")
        zxing = list((Path.home() / ".gradle/caches/modules-2/files-2.1/com.google.zxing/core/3.4.1").glob("*/*.jar"))
        if len(zxing) != 1:
            parser.error("Expected the ZXing 3.4.1 dependency downloaded by Gradle")
        native = work / "native"
        native.mkdir()
        cp = os.pathsep.join(str(path) for path in (android, classes, zxing[0]))
        native_names = ("QrCheck", "SessionCheck")
        extra = ROOT / "checks/AndroidCheck.java"
        if extra.is_file():
            native_names += ("AndroidCheck",)
        run(javac, "-source", "8", "-target", "8", "-cp", cp, "-d", native,
            *(ROOT / "checks" / (name + ".java") for name in native_names))
        archive = work / "native.jar"
        run(jar, "cf", archive, "-C", native, ".")
        tools = sorted((sdk / "build-tools").glob("*/d8"), key=lambda p: tuple(int(x) for x in p.parent.name.split(".") if x.isdigit()))
        if not tools:
            parser.error("Android SDK build-tools d8 is missing")
        dex = work / "dex"
        dex.mkdir()
        run(tools[-1], "--lib", android, "--classpath", classes, "--classpath", zxing[0], "--output", dex, archive)
        dex_jar = work / "checks.jar"
        run(jar, "cf", dex_jar, "-C", dex, "classes.dex")
        adb = str(sdk / "platform-tools/adb")
        target = [adb, "-s", options.serial]
        run(*target, "get-state")
        run(*target, "push", options.apk, "/data/local/tmp/tern-suite-app.apk")
        run(*target, "push", dex_jar, "/data/local/tmp/tern-suite-checks.jar")
        guard_paths = (options.update_apk, options.wrong_signer_apk, options.wrong_package_apk)
        guard_args = []
        if any(guard_paths):
            if not all(guard_paths):
                parser.error("APK guard checks require all three APK arguments")
            for index, path in enumerate(guard_paths):
                remote = "/data/local/tmp/tern-suite-guard-" + str(index) + ".apk"
                run(*target, "push", path, remote)
                guard_args.append(remote)
        for name in native_names:
            run(*target, "shell", "CLASSPATH=/data/local/tmp/tern-suite-app.apk:/data/local/tmp/tern-suite-checks.jar",
                "app_process", "/", PACKAGE + "." + name, *(guard_args if name == "AndroidCheck" else []))
        print("Host and native Android suites passed.")


if __name__ == "__main__":
    main()
