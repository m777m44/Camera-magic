#!/usr/bin/env python3
"""Optional syntax-only check. Does not replace javac/Android Lint/device testing."""

from pathlib import Path
import sys
import javalang

root = Path(__file__).resolve().parents[1]
files = sorted(
    path for module in ("hdr-kit", "camera-app") for path in (root / module / "src").rglob("*.java")
)
for path in files:
    try:
        javalang.parse.parse(path.read_text(encoding="utf-8"))
    except (javalang.parser.JavaSyntaxError, javalang.tokenizer.LexerError) as error:
        print(f"Java syntax error in {path}: {error}", file=sys.stderr)
        sys.exit(1)
print(f"Java 8 syntax parsed: {len(files)} files. Not an Android compilation/runtime result.")
