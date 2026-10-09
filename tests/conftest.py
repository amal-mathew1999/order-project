import importlib.util, json, os, shutil, subprocess, sys
from pathlib import Path

import pytest

REPO = Path(__file__).resolve().parents[1]
NEW_SERVICE = ".claude/skills/new-service/scripts/new_service.py"
GEN_RESOURCE = ".claude/skills/add-resource/scripts/gen_resource.py"
JAVA_GUARD = ".claude/hooks/java_guard.py"
BUILD_GATE = ".claude/hooks/build_gate.py"


@pytest.fixture
def project(tmp_path):
    """Isolated copy of the harness; scripts resolve ROOT from their own location, so they act on tmp_path."""
    shutil.copytree(REPO / ".claude", tmp_path / ".claude",
                    ignore=shutil.ignore_patterns(".dirty", ".gate_retries", "__pycache__", "worktrees"))
    return tmp_path


def run_script(root, script, *args):
    return subprocess.run([sys.executable, str(root / script), *map(str, args)],
                          cwd=root, capture_output=True, text=True, timeout=900)


def run_hook(root, script, payload):
    env = {**os.environ, "CLAUDE_PROJECT_DIR": str(root)}
    return subprocess.run([sys.executable, str(root / script)], input=json.dumps(payload),
                          env=env, capture_output=True, text=True, timeout=900)


def load_module(root, script):
    spec = importlib.util.spec_from_file_location(Path(script).stem, root / script)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def fake_service(root, name="shop-service", pkg="com.example.shop"):
    """Minimal service layout gen_resource can target without network access."""
    svc = root / name
    src = svc / "src/main/java" / Path(*pkg.split("."))
    src.mkdir(parents=True)
    (src / "ShopServiceApplication.java").write_text(f"package {pkg};\n\nclass ShopServiceApplication {{}}\n")
    (svc / "pom.xml").write_text("<project/>")
    return svc, src
