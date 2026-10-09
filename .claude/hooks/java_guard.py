"""PostToolUse(Edit|Write): fast regex lint on Java sources + mark the service dirty for the Stop build gate."""
import json, os, re, sys
from pathlib import Path

RULES = [
    (r"@Autowired\s+(private|protected|public)?\s*\w", "field injection: use constructor injection"),
    (r"System\.(out|err)\.print", "System.out/err: use org.slf4j Logger"),
    (r"\.printStackTrace\(\)", "printStackTrace: log the exception or rethrow"),
    (r"catch\s*\([^)]*\)\s*\{\s*\}", "empty catch block"),
    (r"(?i)(password|secret|api[_-]?key)\s*=\s*\"[^\"]+\"", "hard-coded secret: use config/env"),
]

data = json.load(sys.stdin)
path = data.get("tool_input", {}).get("file_path", "")
p = Path(path)
if p.suffix != ".java" or not p.exists():
    sys.exit(0)

root = Path(os.environ.get("CLAUDE_PROJECT_DIR", ".")).resolve()
try:
    service = p.resolve().relative_to(root).parts[0]
    (root / ".claude" / ".dirty").open("a").write(service + "\n")
except ValueError:
    pass

if "src" + os.sep + "test" in str(p) or "/src/test/" in path:
    sys.exit(0)  # tests may use @Autowired fields

src = p.read_text(encoding="utf-8")
problems = [msg for rx, msg in RULES if re.search(rx, src)]
if "@RestController" in src and re.search(r"import [\w.]+\.domain\.\w+;", src):
    problems.append("controller imports a JPA entity: expose DTO records only")

if problems:
    print(f"{p.name}: " + "; ".join(problems), file=sys.stderr)
    sys.exit(2)
