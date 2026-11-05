#!/usr/bin/env python3
"""Rewrite text paths in an official Termux bootstrap; never modify ELF bytes."""
import sys
from zipfile import ZipFile

source, destination = sys.argv[1:3]
old_paths = (b"/data/data/com.termux", b"/data/user/0/com.termux")
new_path = b"/data/user/0/com.dc.andagent"
with ZipFile(source) as original, ZipFile(destination, "w") as rewritten:
    for entry in original.infolist():
        content = original.read(entry)
        if not content.startswith(b"\x7fELF") and b"\0" not in content[:1024]:
            for old_path in old_paths:
                content = content.replace(old_path, new_path)
        rewritten.writestr(entry, content)
