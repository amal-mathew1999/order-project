from conftest import BUILD_GATE, run_hook

MAVEN_FAIL = [
    "[ERROR] COMPILATION ERROR :",
    "[ERROR] /src/main/java/Foo.java:[3,9] cannot find symbol",
    "[ERROR] ",
    "[ERROR] Re-run Maven using the -X switch to enable full debug logging.",
    "[ERROR] For more information about the errors and possible solutions, please read the following articles:",
    "[ERROR] [Help 1] http://cwiki.apache.org/confluence/display/MAVEN/MojoFailureException",
]


def fake_maven(project, svc, exit_code, lines=()):
    d = project / svc
    d.mkdir(parents=True, exist_ok=True)
    (d / "pom.xml").write_text("<project/>")
    echo_cmd = "".join(f"echo {l.replace('[', '^[').replace(']', '^]') or '.'}\r\n" for l in lines)
    (d / "mvnw.cmd").write_text(f"@echo off\r\n{echo_cmd}exit /b {exit_code}\r\n")
    sh = d / "mvnw"
    sh.write_text("#!/bin/sh\n" + "".join(f"echo '{l}'\n" for l in lines) + f"exit {exit_code}\n")
    sh.chmod(0o755)


def mark_dirty(project, *services):
    (project / ".claude/.dirty").write_text("".join(s + "\n" for s in services))


def gate(project, active=False):
    return run_hook(project, BUILD_GATE, {"hook_event_name": "Stop", "stop_hook_active": active})


def test_noop_when_nothing_edited(project):
    assert gate(project).returncode == 0


def test_ignores_dirs_without_pom(project):
    mark_dirty(project, ".claude", "docs")
    assert gate(project).returncode == 0
    assert not (project / ".claude/.dirty").exists()


def test_pass_clears_state(project):
    fake_maven(project, "ok-service", 0)
    mark_dirty(project, "ok-service", "ok-service")
    assert gate(project).returncode == 0
    assert not (project / ".claude/.dirty").exists()
    assert not (project / ".claude/.gate_retries").exists()


def test_failure_blocks_with_compact_digest(project):
    fake_maven(project, "bad-service", 1, MAVEN_FAIL)
    mark_dirty(project, "bad-service")
    r = gate(project)
    assert r.returncode == 2
    assert "[bad-service]" in r.stderr and "cannot find symbol" in r.stderr
    for noise in ("Re-run Maven", "For more information", "[Help 1]"):
        assert noise not in r.stderr
    assert (project / ".claude/.dirty").exists()  # stays dirty so the next stop re-checks


def test_only_failing_service_reported(project):
    fake_maven(project, "ok-service", 0)
    fake_maven(project, "bad-service", 1, MAVEN_FAIL)
    mark_dirty(project, "ok-service", "bad-service")
    r = gate(project)
    assert "[bad-service]" in r.stderr and "[ok-service]" not in r.stderr


def test_silent_failure_still_reported(project):
    fake_maven(project, "bad-service", 1)
    mark_dirty(project, "bad-service")
    r = gate(project)
    assert r.returncode == 2 and "no output" in r.stderr


def test_gives_up_after_three_retries(project):
    fake_maven(project, "bad-service", 1, MAVEN_FAIL)
    mark_dirty(project, "bad-service")
    codes = [gate(project, active=i > 0).returncode for i in range(4)]
    assert codes == [2, 2, 2, 0]
    assert not (project / ".claude/.dirty").exists()


def test_fresh_stop_resets_retry_counter(project):
    fake_maven(project, "bad-service", 1, MAVEN_FAIL)
    mark_dirty(project, "bad-service")
    for i in range(3):
        gate(project, active=i > 0)
    assert gate(project, active=False).returncode == 2  # new user turn: counter starts over
