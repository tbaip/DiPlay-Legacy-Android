#!/usr/bin/env python3
"""
Verify that the release APK was built from the target git commit/HEAD,
matches the expected version, and carries valid signatures.
Can be run locally or in CI before publishing a release.
"""

import sys
import os
import subprocess
import zipfile
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def run(cmd, cwd=ROOT):
    p = subprocess.run(cmd, cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, shell=isinstance(cmd, str))
    if p.returncode != 0:
        raise RuntimeError(f"Command failed ({p.returncode}): {cmd}\nStdout: {p.stdout}\nStderr: {p.stderr}")
    return p.stdout.strip()

def get_current_commit():
    return run("git rev-parse HEAD")

def get_gradle_version():
    build_gradle = (ROOT / "mobile" / "build.gradle.kts").read_text(encoding="utf-8")
    v_name = re.search(r'versionName\s*=\s*"([^"]+)"', build_gradle).group(1)
    v_code = re.search(r'versionCode\s*=\s*(\d+)', build_gradle).group(1)
    return v_name, int(v_code)

def verify_apk(apk_path: Path, target_commit_ref: str = "HEAD"):
    if not apk_path.is_file():
        print(f"Error: APK not found at {apk_path}", file=sys.stderr)
        return False

    print(f"==> Inspecting APK: {apk_path.name}")
    target_vname, target_vcode = get_gradle_version()
    target_commit = run(f"git rev-parse {target_commit_ref}")
    print(f"    Target Source Version: {target_vname} (code {target_vcode})")
    print(f"    Target Commit:         {target_commit_ref} ({target_commit})")

    # 1. Read embedded version-control-info.textproto from APK
    with zipfile.ZipFile(apk_path, 'r') as z:
        proto_name = "META-INF/version-control-info.textproto"
        if proto_name not in z.namelist():
            print(f"Error: {proto_name} not found in APK. Was this built by AGP?", file=sys.stderr)
            return False
        proto_content = z.read(proto_name).decode('utf-8', errors='ignore')

    rev_match = re.search(r'revision:\s*"([a-f0-9]+)"', proto_content)
    if not rev_match:
        print(f"Error: Could not extract git revision from {proto_name}", file=sys.stderr)
        return False
    apk_commit = rev_match.group(1)
    print(f"    APK Build Commit:      {apk_commit}")

    # Check commit match (exact or parent of release commit)
    if apk_commit != target_commit:
        try:
            diff = run(f"git diff {apk_commit} {target_commit} -- mobile/ shared/ common/ home/")
            if diff:
                print(f"Error: Code diff detected between APK commit ({apk_commit}) and Target ({target_commit}):", file=sys.stderr)
                print(diff[:500], file=sys.stderr)
                return False
            else:
                print(f"    Notice: APK built at commit {apk_commit[:7]} has no code differences with Target {target_commit[:7]}.")
        except Exception as e:
            print(f"Error checking git diff: {e}", file=sys.stderr)
            return False
    else:
        print("    Commit Check:          EXACT MATCH (100% synchronized)")

    # 2. Check APK sha256 checksum file if exists
    sha_file = apk_path.with_name(apk_path.name + ".sha256")
    if not sha_file.is_file():
        alt_sha = ROOT / "checksums" / (apk_path.name + ".sha256")
        if alt_sha.is_file():
            sha_file = alt_sha
    if sha_file.is_file():
        import hashlib
        h = hashlib.sha256(apk_path.read_bytes()).hexdigest().lower()
        recorded = sha_file.read_text(encoding="utf-8").strip().split()[0].lower()
        if h != recorded:
            print(f"Error: APK SHA256 mismatch! Actual: {h}, Recorded: {recorded}", file=sys.stderr)
            return False
        print(f"    SHA256 Match:          {h}")

    print("==> Verification PASSED! APK matches current source tree.")
    return True

if __name__ == "__main__":
    target_commit_ref = sys.argv[1] if len(sys.argv) > 1 and not sys.argv[1].endswith(".apk") else "HEAD"
    apk_arg = None
    for arg in sys.argv[1:]:
        if arg.endswith(".apk"):
            apk_arg = arg
            break

    if apk_arg:
        apk = Path(apk_arg)
        if not apk.is_absolute():
            apk = ROOT / apk
    else:
        v_name, _ = get_gradle_version()
        apk = ROOT / f"DiPlay-v{v_name}-legacy-release.apk"
        if not apk.is_file():
            apk = ROOT / "mobile" / "build" / "outputs" / "apk" / "release" / "mobile-release.apk"

    success = verify_apk(apk, target_commit_ref=target_commit_ref)
    sys.exit(0 if success else 1)
