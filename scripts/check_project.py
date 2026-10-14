"""Java ownership checks and fresh Maven/JUnit evidence; no changes to generated tools."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
FAST = ("FPBRendererTest,FastRasterTest,MacroRoundTripTest,FPBWizardTest,"
        "Step1ImagesTest,Step2ChannelsTest,Step3ChooserTest,LayoutTest,PanelWriterTest,EndToEndTest")


def structure():
    problems = []
    for package in ("io", "meta", "render", "stats"):
        for source in (ROOT / "src/main/java/fpb" / package).glob("*.java"):
            if re.search(r"^import\s+fpb\.ui\.", source.read_text(encoding="utf-8"), re.M):
                problems.append(f"{source.relative_to(ROOT)} imports UI into a scientific/data package")
    plugins = (ROOT / "src/main/resources/plugins.config").read_text(encoding="utf-8")
    if 'fpb.FPB_' not in plugins:
        problems.append("Fiji's registered plugin entry point is missing")
    if not (ROOT / "src/test/resources/fixtures/basic/Control_S3.tif").is_file():
        problems.append("The README example fixture is missing")
    if problems:
        raise RuntimeError("\n".join(problems))
    print("Java data/science packages do not depend on the UI; Fiji entry point and example fixture exist.")


def tests(scope):
    started = time.time()
    prefix = ["cmd.exe", "/d", "/c", "mvnw.cmd"] if os.name == "nt" else ["bash", "mvnw"]
    options = ["-q", "-Dtest=" + FAST, "test"] if scope == "fast" else ["-q", "clean", "package"]
    subprocess.run(prefix + options, cwd=ROOT, check=True)
    passed = failed = skipped = reports = 0
    exceptions = []
    for path in (ROOT / "target/surefire-reports").glob("TEST-*.xml"):
        if path.stat().st_mtime < started - 2:
            continue
        reports += 1
        for case in ET.parse(path).findall(".//testcase"):
            if case.find("failure") is not None or case.find("error") is not None:
                failed += 1
            elif case.find("skipped") is not None:
                skipped += 1
                if case.get("name") == "headerNavigationCommitsMetadataAndRejectsNewlyInvalidatedDestination":
                    exceptions.append("Native wizard navigation requires a graphical display")
                else:
                    raise RuntimeError(f"Required test skipped: {case.get('classname')}.{case.get('name')}")
            else:
                passed += 1
    result = {"scope": scope, "reports": reports, "passed": passed, "failed": failed,
              "skipped": skipped, "explicit_exceptions": exceptions,
              "seconds": time.time() - started}
    print(json.dumps(result, indent=2))
    if not reports or not passed or failed:
        raise RuntimeError("Fresh JUnit execution evidence is missing or failed")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("scope", choices=("structure", "fast", "release"))
    args = parser.parse_args()
    structure() if args.scope == "structure" else tests(args.scope)
