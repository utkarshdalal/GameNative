#!/usr/bin/env python3
"""
update-arrays-xml.py
Appends a new version entry to the fexcore_version_entries array in arrays.xml.

Usage:
    python3 tools/update-arrays-xml.py <arrays.xml path> <version>
    e.g.  python3 tools/update-arrays-xml.py app/src/main/res/values/arrays.xml 2604
"""

import re
import sys


def main() -> None:
    if len(sys.argv) != 3:
        print(f"Usage: {sys.argv[0]} <arrays.xml> <version>", file=sys.stderr)
        sys.exit(1)

    path, version = sys.argv[1], sys.argv[2]

    with open(path, "r", encoding="utf-8") as f:
        content = f.read()

    # Idempotency check
    if f"<item>{version}</item>" in content:
        print(f"Version {version} already present in arrays.xml — nothing to do.")
        sys.exit(0)

    # Find the fexcore_version_entries block and append the new item before its closing tag.
    pattern = r'(name="fexcore_version_entries".*?)(</string-array>)'
    replacement = r'\g<1>        <item>' + version + r'</item>\n    \g<2>'
    new_content, count = re.subn(pattern, replacement, content, count=1, flags=re.DOTALL)

    if count == 0:
        print("ERROR: fexcore_version_entries array not found in arrays.xml", file=sys.stderr)
        sys.exit(1)

    with open(path, "w", encoding="utf-8") as f:
        f.write(new_content)

    print(f"Appended <item>{version}</item> to fexcore_version_entries.")


if __name__ == "__main__":
    main()
