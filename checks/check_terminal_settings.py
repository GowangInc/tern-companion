#!/usr/bin/env python3
"""Check font bounds, live preview, persistence and reset on an isolated Android test device.

Requires the app installed. Restores the original font setting; force-stops the app.
Run: python3 checks/check_terminal_settings.py --serial DEVICE [--adb PATH]
No saved PCs, credentials or backend sessions are read or changed.
"""
import argparse
import re
import shutil
import subprocess
import time
import xml.etree.ElementTree as ET

PACKAGE = "io.github.gowanginc.terncompanion"
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--serial", required=True)
parser.add_argument("--adb", default=shutil.which("adb"))
args = parser.parse_args()
if not args.adb:
    parser.error("adb not found; supply --adb")


def adb(*command):
    return subprocess.run([args.adb, "-s", args.serial, *map(str, command)],
                          check=True, capture_output=True, text=True, timeout=30).stdout


def ui():
    adb("shell", "uiautomator", "dump", "/sdcard/tern-settings-check.xml")
    root = ET.fromstring(adb("exec-out", "cat", "/sdcard/tern-settings-check.xml"))
    return [n for n in root.iter("node") if n.get("package") == PACKAGE]


def find(nodes, name):
    return next((n for n in nodes if n.get("text", "").casefold() == name.casefold()
                 or n.get("content-desc", "").casefold() == name.casefold()), None)


def wait_for(predicate):
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        nodes = ui()
        if predicate(nodes):
            return nodes
        time.sleep(0.3)
    raise AssertionError("Expected settings UI state did not appear")


def tap(node):
    assert node is not None, "Expected settings control is absent"
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds", "")))
    adb("shell", "input", "tap", (x1 + x2) // 2, (y1 + y2) // 2)


def press(code, count=1):
    if count:
        adb("shell", "input", "keyevent", *([str(code)] * count))


def size(nodes):
    return next(int(m.group(1)) for n in nodes
                if (m := re.fullmatch(r"Terminal text size: (\d+) sp", n.get("text", ""))))


def open_settings():
    for _ in range(14):
        node = find(ui(), "Settings")
        if node is not None and node.get("clickable") == "true":
            tap(node)
            return wait_for(lambda ns: find(ns, "Terminal text size") is not None)
        match = re.search(r"(?:Override|Physical) size: (\d+)x(\d+)", adb("shell", "wm", "size"))
        width, height = map(int, match.groups())
        adb("shell", "input", "swipe", width // 2, height * 3 // 4, width // 2, height // 3, 400)
    raise AssertionError("PC list Settings button is absent")


def restart():
    adb("shell", "am", "force-stop", PACKAGE)
    adb("shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity")
    wait_for(lambda ns: find(ns, "Your PCs.") is not None)
    return open_settings()


def focus_slider():
    # Native D-pad navigation can move focus to a button at a slider boundary.
    for _ in range(8):
        node = find(ui(), "Terminal text size")
        if node.get("focused") == "true":
            break
        press(61)
    else:
        raise AssertionError("SeekBar cannot receive keyboard focus")


def choose(value):
    focus_slider()
    press(21, 30)
    assert size(ui()) == 12, "Lower font bound failed"
    focus_slider()
    press(22, 30 if value == 24 else value - 12)
    nodes = wait_for(lambda ns: size(ns) == value)
    preview = find(nodes, "Terminal text size preview")
    assert preview is not None, "Live font preview is absent"
    _, top, _, bottom = map(int, re.findall(r"\d+", preview.get("bounds", "")))
    return bottom - top


def dismiss(button="Done"):
    tap(find(ui(), button))
    wait_for(lambda ns: find(ns, "Terminal text size") is None)
    # Activity pause flushes SharedPreferences.apply before the deliberate force-stop.
    press(3)
    time.sleep(1)


original = None
try:
    assert adb("get-state").strip() == "device"
    original = size(restart())
    small = choose(12)
    large = choose(24)
    assert large > small, "Font adjustment did not enlarge the live preview"
    dismiss()
    assert size(restart()) == 24, "Maximum size was not persisted"
    choose(20)
    dismiss()
    assert size(restart()) == 20, "Chosen size was not persisted"
    dismiss("Reset")
    assert size(restart()) == 16, "Reset did not persist the default font size"
    print("PASS: 12–24sp bounds, live preview enlargement, saved 20/24sp, reset to 16sp")
finally:
    try:
        if original is not None:
            press(4)
            restart()
            choose(original)
            dismiss()
            assert size(restart()) == original, "Original font size was not restored"
            dismiss()
            print("PASS: original font size restored")
    finally:
        adb("shell", "am", "force-stop", PACKAGE)
