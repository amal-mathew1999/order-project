"""Stop hook: run `mvnw test` only for services edited this turn. Blocks the stop with a compact error digest."""
import json, os, subprocess, sys
from pathlib import Path

MAX_RETRIES = 3
root = Path(os.environ.get("CLAUDE_PROJECT_DIR", ".")).resolve()
dirty, retries = root / ".claude" / ".dirty", root / ".claude" / ".gate_retries"
data = json.load(sys.stdin)

if not dirty.exists():
    sys.exit(0)
services = sorted({s for s in dirty.read_text().split() if (root / s / "pom.xml").exists()})
if not services:
    dirty.unlink()
    sys.exit(0)

n = int(retries.read_text()) if data.get("stop_hook_active") and retries.exists() else 0
if n >= MAX_RETRIES:
    retries.unlink(missing_ok=True)
    dirty.unlink()
    print(f"build gate gave up after {MAX_RETRIES} attempts; report the failure to the user", file=sys.stderr)
    sys.exit(0)

failures = []
for s in services:
    mvnw = ["cmd", "/c", str(root / s / "mvnw.cmd")] if os.name == "nt" else [str(root / s / "mvnw")]
    r = subprocess.run(mvnw + ["-q", "-B", "test"], cwd=root / s, capture_output=True, text=True)
    if r.returncode and not (r.stdout + r.stderr).strip():
        failures.append(f"[{s}] mvnw exited {r.returncode} with no output")
        continue
    if r.returncode:
        noise = ("re-run maven", "[help", "for more information", "see ", "dump files", "failed to execute goal")
        lines, seen = [], set()
        for l in (r.stdout + r.stderr).splitlines():
            l = l.replace(str(root / s), "").strip()
            if ("ERROR" in l or "FAIL" in l or "expected" in l) and l.strip("[ERROR] ") \
                    and not any(x in l.lower() for x in noise) and l not in seen:
                seen.add(l)
                lines.append(l)
        failures.append(f"[{s}]\n" + "\n".join(lines[:20]))

if failures:
    retries.write_text(str(n + 1))
    print("Build/tests failed - fix before finishing:\n" + "\n".join(failures), file=sys.stderr)
    sys.exit(2)

dirty.unlink()
retries.unlink(missing_ok=True)
