import json, re

import pytest

from conftest import REPO

AGENTS = sorted((REPO / ".claude/agents").glob("*.md"))
SKILLS = sorted((REPO / ".claude/skills").glob("*/SKILL.md"))


def frontmatter(path):
    m = re.match(r"---\n(.*?)\n---\n", path.read_text(encoding="utf-8"), re.S)
    assert m, f"{path.name}: missing frontmatter"
    return dict(line.split(": ", 1) for line in m.group(1).splitlines() if ": " in line)


def test_settings_json_hooks_point_to_existing_scripts():
    s = json.loads((REPO / ".claude/settings.json").read_text())
    cmds = [h["command"] for groups in s["hooks"].values() for g in groups for h in g["hooks"]]
    assert cmds
    for c in cmds:
        script = re.search(r"\.claude/hooks/\w+\.py", c).group(0)
        assert (REPO / script).exists(), script


@pytest.mark.parametrize("path", AGENTS, ids=lambda p: p.stem)
def test_agent_frontmatter(path):
    fm = frontmatter(path)
    assert fm["name"] == path.stem
    assert fm["description"] and fm["tools"]
    assert fm["model"] in {"opus", "sonnet", "haiku", "inherit"}


@pytest.mark.parametrize("path", SKILLS, ids=lambda p: p.parent.name)
def test_skill_frontmatter_and_script_exists(path):
    fm = frontmatter(path)
    assert fm["name"] == path.parent.name and fm["description"]
    for script in re.findall(r"python (\.claude/\S+\.py)", path.read_text(encoding="utf-8")):
        assert (REPO / script).exists(), script


def test_docs_reference_only_existing_agents_and_skills():
    names = {p.stem for p in AGENTS} | {p.parent.name for p in SKILLS}
    text = (REPO / "CLAUDE.md").read_text(encoding="utf-8")
    for ref in re.findall(r"`([a-z]+(?:-[a-z]+)+)`", text):
        if ref.endswith(("-service", "-kebab")) or ref in {"open-in-view"}:
            continue
        assert ref in names, ref
