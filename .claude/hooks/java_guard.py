"""PostToolUse(Edit|Write): fast regex lint on Java sources + mark the service dirty for the Stop build gate.

The edit is already applied when this runs; exit 2 feeds the problems back to Claude so it fixes them.
"""
import json, os, re, sys
from pathlib import Path

INJECT = r"@(?:Autowired|Inject|Resource)\b(?:\([^)]*\))?(?:\s*@\w+(?:\([^)]*\))?)*\s+"
FIELD_INJECTION = re.compile(INJECT + r"(?:(?:private|protected|public|static|final|transient)\s+)*"
                             r"[\w<>\[\],.?\s]+?\s+\w+\s*(?:=[^;]*)?;")
SETTER_INJECTION = re.compile(INJECT + r"(?:public\s+|protected\s+)?void\s+set\w*\s*\(")
SECRET_ASSIGN = re.compile(r"(?i)\b(\w*(?:password|passwd|secret|token|api_?key|credential)\w*)\s*=\s*\"([^\"]+)\"")
SECRET_PAIR = re.compile(r"(?i)\"(password|passwd|secret|token|api_?key|apikey|credentials?)\"\s*,\s*\"([^\"]+)\"")
NOT_A_SECRET = re.compile(r"^\$\{.*\}$|^[A-Za-z][\w-]*(\.[\w-]+)+$|^X-[\w-]+$|^(Authorization|Bearer)$")
STRUCTURAL = [
    (re.compile(r"System\.(out|err)\.print"), "System.out/err: use org.slf4j Logger"),
    (re.compile(r"\.printStackTrace\s*\("), "printStackTrace: log the exception or rethrow"),
    (re.compile(r"catch\s*\([^)]*\)\s*\{\s*\}"), "empty catch block"),
]


def strip(src, strings):
    """Remove comments (and string/char literal contents when strings=True) so rules only see code."""
    out, i, n = [], 0, len(src)
    while i < n:
        c = src[i]
        if src.startswith("//", i):
            i = src.find("\n", i) if "\n" in src[i:] else n
        elif src.startswith("/*", i):
            end = src.find("*/", i + 2)
            i = n if end < 0 else end + 2
            out.append(" ")
        elif src.startswith('"""', i) or c in "\"'":
            q = '"""' if src.startswith('"""', i) else c
            j = i + len(q)
            while j < n and not src.startswith(q, j):
                j += 2 if src[j] == "\\" else 1
            j = min(j + len(q), n)
            out.append(q + q if strings else src[i:j])
            i = j
        else:
            out.append(c)
            i += 1
    return "".join(out)


def secrets(code):
    hits = [v for _, v in SECRET_ASSIGN.findall(code)] + [v for _, v in SECRET_PAIR.findall(code)]
    return any(not NOT_A_SECRET.match(v) for v in hits)


def imports_entity(code, service_java):
    for pkg, name in re.findall(r"import\s+([\w.]+\.domain)\.(\w+|\*)\s*;", code):
        d = service_java / Path(*pkg.split("."))
        candidates = d.glob("*.java") if name == "*" else [d / f"{name}.java"]
        if any(f.exists() and re.search(r"@Entity\b", f.read_text(encoding="utf-8")) for f in candidates):
            return True
    return False


def main():
    raw = sys.stdin.buffer.read().decode("utf-8", errors="replace")
    data = json.loads(raw) if raw.strip() else {}
    path = data.get("tool_input", {}).get("file_path", "")
    p = Path(path)
    if p.suffix != ".java" or not p.is_file():
        return 0

    root = Path(os.environ.get("CLAUDE_PROJECT_DIR") or Path(__file__).resolve().parents[2]).resolve()
    try:
        rel = p.resolve().relative_to(root).parts
    except ValueError:
        rel = None
    if rel and len(rel) > 1:
        with (root / ".claude" / ".dirty").open("a", encoding="utf-8") as f:
            f.write(rel[0] + "\n")
    is_test = rel[1:3] == ("src", "test") if rel else "/src/test/" in p.as_posix()

    src = p.read_text(encoding="utf-8")
    no_comments = strip(src, strings=False)
    code = strip(src, strings=True)
    problems = []
    if not is_test and FIELD_INJECTION.search(code):
        problems.append("field injection: use constructor injection")
    if not is_test and SETTER_INJECTION.search(code):
        problems.append("setter injection: use constructor injection")
    problems += [msg for rx, msg in STRUCTURAL if rx.search(code)]
    if secrets(no_comments):
        problems.append("hard-coded secret: use config/env")
    if not is_test and rel and re.search(r"@(Rest)?Controller\b", code) \
            and imports_entity(code, root / rel[0] / "src" / "main" / "java"):
        problems.append("controller imports a JPA entity: expose DTO records only")

    if problems:
        print(f"{p.name}: " + "; ".join(problems), file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
