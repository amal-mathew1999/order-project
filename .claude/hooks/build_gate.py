"""Stop hook: run `mvnw test` only for services edited this turn. Blocks the stop with a compact error digest."""
import json, os, re, subprocess, sys
from pathlib import Path

MAX_RETRIES = 3
TIMEOUT = 540  # stay under the 600 s hook timeout in settings.json
NOISE = re.compile(r"^\[ERROR\]\s*(Re-run Maven|For more information|\[Help|-> \[Help|See |To see the full stack trace"
                   r"|Failed to execute goal|$)")
FAILURE_MARK = re.compile(r"<<< (FAILURE|ERROR)!")


def digest(output, svc_dir):
    lines, seen, follow = [], set(), 0
    for line in output.splitlines():
        for prefix in ("/" + svc_dir.as_posix(), svc_dir.as_posix(), str(svc_dir)):
            line = line.replace(prefix, "")
        line = line.rstrip()
        if follow and line and not line.lstrip().startswith("at "):
            follow -= 1
            keep = True
        else:
            keep = ("ERROR" in line or "FAIL" in line) and not NOISE.match(line)
        if FAILURE_MARK.search(line):
            follow = 3  # the assertion message follows the failure marker
        if keep and line not in seen:
            seen.add(line)
            lines.append(line)
    return "\n".join(lines[:25])


def build(root, svc):
    d = root / svc
    wrapper = d / ("mvnw.cmd" if os.name == "nt" else "mvnw")
    if not wrapper.exists():
        return f"[{svc}] Maven wrapper {wrapper.name} missing; regenerate the service or restore the wrapper"
    cmd = (["cmd", "/c", r".\mvnw.cmd"] if os.name == "nt" else ["./mvnw"]) + ["-q", "-B", "test"]
    try:
        r = subprocess.run(cmd, cwd=d, capture_output=True, text=True, encoding="utf-8", errors="replace",
                           timeout=TIMEOUT)
    except subprocess.TimeoutExpired:
        return f"[{svc}] mvnw test timed out after {TIMEOUT}s"
    if r.returncode == 0:
        return None
    out = r.stdout + r.stderr
    return f"[{svc}]\n" + (digest(out, d) if out.strip() else f"mvnw exited {r.returncode} with no output")


def main():
    root = Path(os.environ.get("CLAUDE_PROJECT_DIR") or Path(__file__).resolve().parents[2]).resolve()
    dirty, retries = root / ".claude" / ".dirty", root / ".claude" / ".gate_retries"
    try:
        data = json.loads(sys.stdin.read() or "{}")
    except ValueError:
        data = {}

    if not dirty.exists():
        return 0
    entries = {s.strip() for s in dirty.read_text(encoding="utf-8").splitlines()}  # one path per line; may contain spaces
    services = sorted(s for s in entries if s and (root / s / "pom.xml").exists())
    if not services:
        dirty.unlink()
        return 0

    try:
        n = int(retries.read_text()) if data.get("stop_hook_active") and retries.exists() else 0
    except ValueError:
        n = 0

    failures = [f for f in (build(root, s) for s in services) if f]
    if not failures:
        dirty.unlink()
        retries.unlink(missing_ok=True)
        return 0

    if n >= MAX_RETRIES:
        dirty.unlink()
        retries.unlink(missing_ok=True)
        print(json.dumps({"systemMessage": f"Build gate: tests still failing after {MAX_RETRIES} fix attempts.\n"
                                           + "\n".join(failures)}))
        return 0

    retries.write_text(str(n + 1))
    last = " This is the final attempt; if it still fails, explain the failure to the user." \
        if n + 1 == MAX_RETRIES else ""
    print("Build/tests failed - fix before finishing." + last + "\n" + "\n".join(failures), file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main())
