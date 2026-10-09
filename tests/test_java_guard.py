import pytest

from conftest import JAVA_GUARD, run_hook


def write_java(root, rel, body):
    p = root / rel
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(body)
    return p


def guard(root, path):
    return run_hook(root, JAVA_GUARD, {"tool_name": "Edit", "tool_input": {"file_path": str(path)}})


MAIN = "x-service/src/main/java/com/example/x/Foo.java"


@pytest.mark.parametrize("body, message", [
    ("class Foo { @Autowired private Bar bar; }", "field injection"),
    ("class Foo { @Autowired\n    Bar bar; }", "field injection"),
    ('class Foo { void a() { System.out.println("x"); } }', "System.out"),
    ('class Foo { void a() { System.err.print("x"); } }', "System.out"),
    ("class Foo { void a(Exception e) { e.printStackTrace(); } }", "printStackTrace"),
    ("class Foo { void a() { try { b(); } catch (Exception e) { } } }", "empty catch"),
    ('class Foo { String password = "hunter2"; }', "hard-coded secret"),
    ('class Foo { String apiKey = "abc123"; }', "hard-coded secret"),
    ("import com.example.x.domain.Order;\n@RestController\nclass Foo {}", "controller imports a JPA entity"),
])
def test_violations_block_with_exit_2(project, body, message):
    write_java(project, "x-service/src/main/java/com/example/x/domain/Order.java", "@Entity class Order {}")
    r = guard(project, write_java(project, MAIN, body))
    assert r.returncode == 2
    assert message in r.stderr


@pytest.mark.parametrize("body", [
    "class Foo { private final Bar bar; Foo(Bar bar) { this.bar = bar; } }",
    "class Foo { private static final Logger log = LoggerFactory.getLogger(Foo.class); }",
    "import com.example.x.dto.OrderResponse;\n@RestController\nclass Foo {}",
    "class Foo { void a() { try { b(); } catch (Exception e) { log.warn(\"x\", e); } } }",
])
def test_clean_code_passes(project, body):
    r = guard(project, write_java(project, MAIN, body))
    assert r.returncode == 0, r.stderr


def test_test_sources_may_use_field_injection(project):
    p = write_java(project, "x-service/src/test/java/com/example/x/FooTest.java",
                   "class FooTest { @Autowired MockMvc mvc; }")
    assert guard(project, p).returncode == 0


def test_non_java_files_ignored(project):
    p = project / "x-service/README.md"
    p.parent.mkdir(parents=True)
    p.write_text("System.out.println")
    assert guard(project, p).returncode == 0
    assert not (project / ".claude/.dirty").exists()


def test_marks_service_dirty(project):
    guard(project, write_java(project, MAIN, "class Foo {}"))
    guard(project, write_java(project, "y-service/src/main/java/Y.java", "class Y {}"))
    assert (project / ".claude/.dirty").read_text().split() == ["x-service", "y-service"]


def test_missing_file_and_empty_payload_are_noops(project):
    assert guard(project, project / "nope.java").returncode == 0
    assert run_hook(project, JAVA_GUARD, {}).returncode == 0
