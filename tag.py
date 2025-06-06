#!/usr/bin/env python3

# made by chatgpt

# Updates versionCode and versionName in build.gradle, commits, tags, and pushes


import re
import subprocess
import sys
from pathlib import Path

def fail(msg):
    print(f"Error: {msg}", file=sys.stderr)
    sys.exit(1)

def run(cmd):
    return subprocess.run(cmd, check=True, capture_output=True, text=True).stdout.strip()

def find_build_gradle():
    for pattern in ["app/build.gradle", "app/build.gradle.kts"]:
        path = Path(pattern)
        if path.exists():
            return path
    fail("Could not find app/build.gradle or app/build.gradle.kts")


def get_current_version(file_path):
    content = file_path.read_text()
    version_code_match = re.search(r"versionCode\s*=\s*(\d+)", content)
    version_name_match = re.search(r'versionName\s*=\s*"([^"]+)"', content)

    if version_code_match and version_name_match:
        return version_name_match.group(1), int(version_code_match.group(1))
    else:
        fail("Could not parse current version from build.gradle")

def get_latest_git_tag():
    try:
        return run(["git", "describe", "--tags", "--abbrev=0"])
    except subprocess.CalledProcessError:
        return "(no tags)"

def update_version_file(file_path, version_name, version_code):
    """
    Replace:
        versionCode = <digits>
        versionName = "<something>"
    with new values. Uses regex to match possible whitespace around `=`.
    """
    text = file_path.read_text()

    new_text, cnt_code = re.subn(
        r"versionCode\s*=\s*\d+",
        f"versionCode = {version_code}",
        text,
    )
    new_text, cnt_name = re.subn(
        r'versionName\s*=\s*"[^"]+"',
        f'versionName = "{version_name}"',
        new_text,
    )

    if cnt_code == 0 or cnt_name == 0:
        fail("Failed to update versionCode or versionName in " + str(file_path))
    file_path.write_text(new_text)

def main():
    gradle_file = find_build_gradle()

    if len(sys.argv) == 1:
        version_name, version_code = get_current_version(gradle_file)
        print(f"Current versionName: {version_name}")
        print(f"Current versionCode: {version_code}")
        print(f"Latest Git tag: {get_latest_git_tag()}")
        sys.exit(0)

    if len(sys.argv) != 2:
        fail("Usage: ./tag vX.Y.Z")

    version = sys.argv[1]

    if not re.match(r"^v\d+\.\d+\.\d+$", version):
        fail("Version must follow semantic versioning, e.g., v1.2.3")

    version_name = version[1:]  # strip 'v'
    major, minor, patch = map(int, version_name.split("."))
    version_code = major * 10000 + minor * 100 + patch

    print(f"Updating to versionName: {version_name}, versionCode: {version_code}")

    print(f"Updating {gradle_file}")
    update_version_file(gradle_file, version_name, version_code)

    run(["git", "add", str(gradle_file)])
    run(["git", "commit", "-m", version])
    run(["git", "tag", version])
    run(["git", "push"])
    run(["git", "push", "origin", version])

    print(f"Tagged and pushed {version} successfully.")

if __name__ == "__main__":
    main()
