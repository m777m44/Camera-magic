"""Version-aware tool discovery. Tools are supplied by the operator, never vendored."""

from __future__ import annotations

import os
from pathlib import Path
import re
import shutil

from .core import MagicError, read_json, run, version_tuple

MIN_APKTOOL = (2, 11, 1)
MIN_JADX = (1, 5, 1)
MIN_BUILD_TOOLS = (35, 0, 0)


class Tools:
    def __init__(self, config: Path | None = None):
        self.config = read_json(config) if config else {}
        allowed = {
            "java",
            "keytool",
            "apktool_jar",
            "apktool",
            "jadx",
            "sdk_root",
            "build_tools",
            "adb",
        }
        if set(self.config) - allowed:
            raise MagicError(
                "Unknown toolchain key(s): " + ", ".join(sorted(set(self.config) - allowed))
            )
        if any(not isinstance(value, str) or not value.strip() for value in self.config.values()):
            raise MagicError("Toolchain paths/versions must be non-empty strings.")
        root = (
            self.config.get("sdk_root")
            or os.getenv("ANDROID_SDK_ROOT")
            or os.getenv("ANDROID_HOME")
        )
        self.sdk = Path(root).expanduser() if root else None

    def binary(self, name: str) -> str:
        value = self.config.get(name) or os.getenv(name.upper())
        if value:
            return os.path.expanduser(str(value))
        if name == "adb" and self.sdk:
            return str(self.sdk / "platform-tools" / "adb")
        if name == "keytool" and os.getenv("JAVA_HOME"):
            return str(Path(os.environ["JAVA_HOME"]) / "bin" / "keytool")
        return shutil.which(name) or name

    def java(self) -> str:
        if self.config.get("java") or os.getenv("JAVA"):
            return self.binary("java")
        if os.getenv("JAVA_HOME"):
            return str(Path(os.environ["JAVA_HOME"]) / "bin" / "java")
        return self.binary("java")

    def check_java(self) -> str:
        result = run([self.java(), "-version"])
        text = result.stderr + result.stdout
        # Ignore unrelated digits in JAVA_TOOL_OPTIONS warnings and wrapper output.
        match = re.search(
            r'(?:openjdk|java)\s+(?:version\s+)?"?(\d+)(?:\.(\d+))?', text, re.IGNORECASE
        )
        if not match:
            raise MagicError("Cannot identify Java version.")
        major = int(match[1]) if match[1] != "1" else int(match[2] or 0)
        if major < 17:
            raise MagicError(f"Java 17+ is required; found {major}.")
        return text[match.start() :].splitlines()[0]

    def apktool(self) -> list[str]:
        jar = self.config.get("apktool_jar") or os.getenv("APKTOOL_JAR")
        if jar:
            path = Path(jar).expanduser()
            if not path.is_file():
                raise MagicError(f"APKTOOL_JAR does not exist: {path}")
            return [self.java(), "-jar", str(path)]
        return [self.binary("apktool")]

    def check_apktool(self) -> str:
        self.check_java()
        output = run(self.apktool() + ["--version"]).stdout.strip()
        if version_tuple(output) < MIN_APKTOOL:
            raise MagicError(f"Apktool >= 2.11.1 required; found {output}.")
        return output

    def check_jadx(self) -> str:
        self.check_java()
        output = run([self.binary("jadx"), "--version"]).stdout.strip()
        if version_tuple(output) < MIN_JADX:
            raise MagicError(f"jadx >= 1.5.1 required; found {output}.")
        return output

    def build_dir(self) -> tuple[Path, str]:
        if not self.sdk:
            raise MagicError("Set sdk_root in toolchain.json, ANDROID_SDK_ROOT, or ANDROID_HOME.")
        root = self.sdk / "build-tools"
        requested = self.config.get("build_tools")
        if requested:
            candidates = [root / str(requested)]
        else:
            candidates = sorted(
                (p for p in root.glob("*") if re.fullmatch(r"\d+\.\d+\.\d+", p.name)),
                key=lambda p: version_tuple(p.name),
                reverse=True,
            )
        for candidate in candidates:
            properties = candidate / "source.properties"
            if not properties.is_file():
                continue
            text = properties.read_text(encoding="utf-8")
            match = re.search(r"^Pkg\.Revision\s*=\s*(.+)$", text, re.MULTILINE)
            if not match or re.search(r"(?:rc|preview|beta|alpha)", match[1], re.IGNORECASE):
                continue
            if version_tuple(match[1]) >= MIN_BUILD_TOOLS:
                return candidate, match[1].strip()
        raise MagicError(
            "Android SDK Build-Tools stable >= 35.0.0 not found (source.properties required)."
        )

    def sdk_tool(self, name: str) -> str:
        directory, _ = self.build_dir()
        tool = directory / name
        if not tool.is_file():
            raise MagicError(f"Missing SDK tool: {tool}")
        return str(tool)

    def apktool_aapt2(self, directory: Path) -> str:
        """Give Apktool an owned executable; it chmods --aapt even when already executable."""
        source = Path(self.sdk_tool("aapt2"))
        directory.mkdir(parents=True, exist_ok=False)
        target = directory / source.name
        shutil.copyfile(source, target)
        target.chmod(0o700)
        # Preserve relative loader dependencies on SDK distributions that supply them.
        libraries = source.parent / "lib64"
        if libraries.is_dir():
            shutil.copytree(libraries, directory / "lib64")
        return str(target.resolve())

    def doctor(self) -> dict:
        result = {"required": {}, "optional": {}}
        probes = {
            "java_17_plus": self.check_java,
            "apktool_2_11_1_plus": self.check_apktool,
            "build_tools_35_plus": lambda: self.build_dir()[1],
            "apksigner": lambda: run([self.sdk_tool("apksigner"), "version"]).stdout.strip(),
            "zipalign": lambda: self.sdk_tool("zipalign"),
            "aapt2": lambda: run([self.sdk_tool("aapt2"), "version"]).stdout.strip(),
        }
        for name, probe in probes.items():
            try:
                result["required"][name] = {"ok": True, "value": probe()}
            except (MagicError, OSError) as exc:
                result["required"][name] = {"ok": False, "error": str(exc)}
        for name, probe in {
            "jadx_1_5_1_plus": self.check_jadx,
            "adb": lambda: run([self.binary("adb"), "version"]).stdout.strip(),
            "emulator": lambda: run(
                [str(self.sdk / "emulator" / "emulator") if self.sdk else "emulator", "-version"]
            ).stdout.strip(),
        }.items():
            try:
                result["optional"][name] = {"ok": True, "value": probe()}
            except (MagicError, OSError) as exc:
                result["optional"][name] = {"ok": False, "error": str(exc)}
        result["ready_for_rebrand_build"] = all(v["ok"] for v in result["required"].values())
        result["standalone_smali"] = (
            "Not used or version-verified. Apktool supplies its own DEX assembler/disassembler."
        )
        result["runtime_compatibility"] = "not_tested"
        return result
