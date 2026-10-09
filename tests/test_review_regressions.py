"""Regression tests for the independent review findings (one test per fixed bug)."""
import json, shutil

import pytest

from conftest import (BUILD_GATE, GEN_RESOURCE, JAVA_GUARD, NEW_SERVICE, fake_service, load_module, run_hook,
                      run_script)
from test_build_gate import MAVEN_FAIL, fake_maven, gate, mark_dirty
from test_gen_resource import files


def guard_file(project, rel, body):
    p = project / rel
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(body, encoding="utf-8")
    return run_hook(project, JAVA_GUARD, {"tool_input": {"file_path": str(p)}})


MAIN = "x-service/src/main/java/com/example/x/web/FooController.java"


# ---------------- java_guard ----------------

def test_project_under_src_test_folder_is_still_linted(tmp_path):
    from conftest import REPO
    root = tmp_path / "src" / "testbed" / "proj"
    shutil.copytree(REPO / ".claude", root / ".claude")
    r = guard_file(root, MAIN, 'class Foo { void a() { System.out.println("x"); } }')
    assert r.returncode == 2


def test_non_ascii_path_is_linted(project):
    r = guard_file(project, "café-service/src/main/java/Foo.java", 'class Foo { void a() { System.out.println(1); } }')
    assert r.returncode == 2
    assert "café-service" in (project / ".claude/.dirty").read_text(encoding="utf-8")


@pytest.mark.parametrize("body", [
    "class Foo { private final Bar b;\n  @Autowired\n  public Foo(Bar b) { this.b = b; } }",
    "class Foo { @Autowired Foo(Bar b) {} }",
    'class Foo { static final String DB_PASSWORD = "spring.datasource.password"; }',
    'class Foo { static final String HEADER_API_KEY = "X-API-Key"; }',
    'class Foo { @Value("${db.password}") String password; }',
    "import com.example.x.domain.OrderStatus;\n@RestController\nclass FooController {}",
    "import com.example.x.domain.Order;\n@RestControllerAdvice\nclass FooAdvice {}",
    "class Foo { // never use System.out.println here\n }",
    'class Foo { String s = "e.printStackTrace()"; }',
])
def test_no_false_positives(project, body):
    (project / "x-service/src/main/java/com/example/x/domain").mkdir(parents=True)
    (project / "x-service/src/main/java/com/example/x/domain/Order.java").write_text("@Entity class Order {}")
    (project / "x-service/src/main/java/com/example/x/domain/OrderStatus.java").write_text("enum OrderStatus {}")
    r = guard_file(project, MAIN, body)
    assert r.returncode == 0, r.stderr


@pytest.mark.parametrize("body, message", [
    ("class Foo { @Autowired(required = false) private Bar b; }", "field injection"),
    ('class Foo { @Autowired @Qualifier("x") private Bar b; }', "field injection"),
    ("class Foo { @Inject private Bar b; }", "field injection"),
    ("class Foo { @Autowired public void setBar(Bar b) {} }", "setter injection"),
    ('class Foo { String secretKey = "s3cr3t-value"; }', "hard-coded secret"),
    ('class Foo { String token = "eyJhbGciOi"; }', "hard-coded secret"),
    ('class Foo { Map<String,String> m = Map.of("password", "hunter2"); }', "hard-coded secret"),
    ("class Foo { void a() { try { b(); } catch (Exception e) { /* ignore */ } } }", "empty catch"),
    ("class Foo { void a(Exception e) { e.printStackTrace(System.err); } }", "printStackTrace"),
    ("import com.example.x.domain.*;\n@RestController\nclass FooController {}", "JPA entity"),
    ("import com.example.x.domain.Order;\n@Controller\nclass FooController {}", "JPA entity"),
])
def test_no_false_negatives(project, body, message):
    (project / "x-service/src/main/java/com/example/x/domain").mkdir(parents=True)
    (project / "x-service/src/main/java/com/example/x/domain/Order.java").write_text("@Entity class Order {}")
    r = guard_file(project, MAIN, body)
    assert r.returncode == 2 and message in r.stderr


def test_test_sources_still_checked_for_secrets_and_stdout(project):
    r = guard_file(project, "x-service/src/test/java/FooTest.java",
                   'class FooTest { @Autowired MockMvc mvc; String password = "hunter2"; void a() { System.out.println(1); } }')
    assert r.returncode == 2
    assert "hard-coded secret" in r.stderr and "System.out" in r.stderr and "injection" not in r.stderr


def test_works_without_project_dir_env(project):
    import os, subprocess, sys
    p = project / "x-service/src/main/java/Foo.java"
    p.parent.mkdir(parents=True)
    p.write_text("class Foo {}")
    env = {k: v for k, v in os.environ.items() if k != "CLAUDE_PROJECT_DIR"}
    r = subprocess.run([sys.executable, str(project / JAVA_GUARD)], input=json.dumps({"tool_input": {"file_path": str(p)}}),
                       cwd=project / "x-service", env=env, capture_output=True, text=True)
    assert r.returncode == 0, r.stderr
    assert (project / ".claude/.dirty").read_text().split() == ["x-service"]


# ---------------- build_gate ----------------

def test_missing_wrapper_reported(project):
    (project / "nowrap-service").mkdir()
    (project / "nowrap-service/pom.xml").write_text("<project/>")
    mark_dirty(project, "nowrap-service")
    r = gate(project)
    assert r.returncode == 2 and "wrapper" in r.stderr and "missing" in r.stderr


@pytest.mark.parametrize("dirname", ["r&d", "par(en)"])
def test_special_chars_in_path(tmp_path, dirname):
    from conftest import REPO
    root = tmp_path / dirname
    shutil.copytree(REPO / ".claude", root / ".claude")
    fake_maven(root, "ok-service", 0)
    mark_dirty(root, "ok-service")
    assert gate(root).returncode == 0


def test_digest_keeps_assertion_message_and_strips_paths(project):
    svc = project / "bad-service"
    lines = [
        f"[ERROR] /{svc.as_posix()}/src/main/java/Foo.java:[3,9] cannot find symbol",
        "[ERROR] com.example.FooTest.works -- Time elapsed: 0.03 s <<< FAILURE!",
        "org.opentest4j.AssertionFailedError: expected: <1> but was: <2>",
        "\tat com.example.FooTest.works(FooTest.java:12)",
        '[ERROR] expected: "see the docs"',
    ]
    fake_maven(project, "bad-service", 1, lines)
    mark_dirty(project, "bad-service")
    r = gate(project)
    assert "but was: <2>" in r.stderr
    assert '"see the docs"' in r.stderr
    assert svc.as_posix() not in r.stderr and "/src/main/java/Foo.java" in r.stderr
    assert "\tat " not in r.stderr


def test_corrupt_state_and_empty_stdin(project):
    import os, subprocess, sys
    fake_maven(project, "ok-service", 0)
    mark_dirty(project, "ok-service")
    (project / ".claude/.gate_retries").write_text("garbage")
    r = subprocess.run([sys.executable, str(project / BUILD_GATE)], input="",
                       env={**os.environ, "CLAUDE_PROJECT_DIR": str(project)}, capture_output=True, text=True)
    assert r.returncode == 0, r.stderr


# ---------------- gen_resource ----------------

@pytest.mark.parametrize("entity, path, table", [
    ("Category", "/api/v1/categories", "categories"),
    ("Address", "/api/v1/addresses", "addresses"),
    ("Status", "/api/v1/statuses", "statuses"),
    ("Day", "/api/v1/days", "days"),
    ("URLMapping", "/api/v1/url-mappings", "url_mappings"),
])
def test_pluralisation(project, entity, path, table):
    svc, _ = fake_service(project)
    assert run_script(project, GEN_RESOURCE, "shop-service", entity, "note:String").returncode == 0
    f = files(svc, entity)
    assert f'@RequestMapping("{path}")' in f["ctrl"].read_text()
    assert f'@Table(name = "{table}")' in f["entity"].read_text()


def test_plural_override(project):
    svc, _ = fake_service(project)
    run_script(project, GEN_RESOURCE, "shop-service", "Person", "note:String", "--plural", "people")
    assert '@RequestMapping("/api/v1/people")' in files(svc, "Person")["ctrl"].read_text()


@pytest.mark.parametrize("spec, message", [
    ("", "bad field spec"), ("name", "bad field spec"), ("name:String,", "bad field spec"),
    ("id:Long,name:String", "'id' is generated"), ("class:String", "Java keyword"),
    ("a:String,a:Integer", "duplicate field"), ("Name:String", "bad field spec"),
])
def test_bad_field_specs_fail_cleanly(project, spec, message):
    fake_service(project)
    r = run_script(project, GEN_RESOURCE, "shop-service", "Order", spec)
    assert r.returncode != 0 and message in r.stderr and "Traceback" not in r.stderr
    assert not (project / "shop-service/src/main/java/com/example/shop/domain").exists()


@pytest.mark.parametrize("entity", ["lower", "Bad-Name", "Service", "List", "Test"])
def test_bad_entity_names_fail_cleanly(project, entity):
    fake_service(project)
    r = run_script(project, GEN_RESOURCE, "shop-service", entity, "note:String")
    assert r.returncode != 0 and "Traceback" not in r.stderr


def test_reserved_sql_words_are_quoted(project):
    svc, _ = fake_service(project)
    run_script(project, GEN_RESOURCE, "shop-service", "Value", "value:Integer,year:Integer,user:String,note:String")
    src = files(svc, "Value")["entity"].read_text()
    assert '@Table(name = "`values`")' in src
    assert '@Column(name = "`value`", nullable = false)' in src and '@Column(name = "`year`"' in src
    assert "@Column(nullable = false)\n    private String note;" in src


def test_service_outside_root_rejected(project, tmp_path_factory):
    outside = tmp_path_factory.mktemp("outside")
    fake_service(outside)
    r = run_script(project, GEN_RESOURCE, str(outside / "shop-service"), "Order", "note:String")
    assert r.returncode != 0 and "inside" in r.stderr
    assert not (outside / "shop-service/src/main/java/com/example/shop/domain").exists()


def test_generation_marks_service_dirty(project):
    fake_service(project)
    run_script(project, GEN_RESOURCE, "shop-service", "Order", "note:String")
    assert (project / ".claude/.dirty").read_text().split() == ["shop-service"]


def test_controller_test_checks_problem_detail(project):
    svc, _ = fake_service(project)
    run_script(project, GEN_RESOURCE, "shop-service", "Order", "note:String")
    src = files(svc)["ctest"].read_text()
    assert "APPLICATION_PROBLEM_JSON" in src and '"/api/v1/orders/abc"' in src and '"size", "1000"' in src


# ---------------- new_service ----------------

def test_next_port_ignores_other_port_keys_and_comments(project):
    p = project / "a-service/src/main/resources/application.yml"
    p.parent.mkdir(parents=True)
    p.write_text("server:\n  port: 8082\n  # old port: 9999\nspring:\n  data:\n    redis:\n      port: 6379\n"
                 "# 丁 utf-8 comment\n", encoding="utf-8")
    g = project / "gateway-service/src/main/resources/application.yml"
    g.parent.mkdir(parents=True)
    g.write_text("server:\n  port: 8080\n")
    d = project / "discovery-service/src/main/resources/application.yml"
    d.parent.mkdir(parents=True)
    d.write_text("server:\n  port: 8761\n")
    assert load_module(project, NEW_SERVICE).next_port() == 8083


def test_keyword_service_name_rejected(project):
    r = run_script(project, NEW_SERVICE, "class-service")
    assert r.returncode != 0 and "Java keyword" in r.stderr


def test_yml_for_each_service_kind(project):
    m = load_module(project, NEW_SERVICE)
    biz = m.application_yml("order-service", "order", 8081, m.DEFAULT_DEPS.split(","))
    assert "jdbc:mysql://${DB_HOST:localhost}:${DB_PORT:3306}/order_db" in biz
    assert "${DB_PASSWORD:}" in biz and "defaultZone: ${EUREKA_URL:http://localhost:8761/eureka}" in biz
    assert "open-in-view: false" in biz
    test = m.test_properties("order", m.DEFAULT_DEPS.split(","))
    assert "spring.datasource.url=jdbc:h2:mem:order;MODE=MySQL" in test and "eureka.client.enabled=false" in test
    eureka = m.application_yml("discovery-service", "discovery", 8761, ["cloud-eureka-server", "actuator"])
    assert "register-with-eureka: false" in eureka and "datasource" not in eureka
    # disabling the Eureka client breaks the server's own context, so the server gets no test override
    assert m.test_properties("discovery", ["cloud-eureka-server", "actuator"]) == ""


def test_network_error_is_clean_and_leaves_nothing(project, monkeypatch):
    import os, subprocess, sys
    env = {**os.environ, "HTTPS_PROXY": "http://127.0.0.1:9", "https_proxy": "http://127.0.0.1:9"}
    r = subprocess.run([sys.executable, str(project / NEW_SERVICE), "net-service"], env=env,
                       capture_output=True, text=True, timeout=120)
    assert r.returncode != 0 and "Traceback" not in r.stderr and "start.spring.io" in r.stderr
    assert not (project / "net-service").exists()


def test_nested_service_paths_detect_tests_and_service(project):
    rel = ".claude/worktrees/agent-1/order-service/src/test/java/FooTest.java"
    r = guard_file(project, rel, "class FooTest { @Autowired MockMvc mvc; }")
    assert r.returncode == 0, r.stderr  # test source under a worktree: field injection allowed
    assert (project / ".claude/.dirty").read_text().splitlines() == [".claude/worktrees/agent-1/order-service"]


def test_gate_builds_nested_service_paths(project):
    fake_maven(project, "nested dir/bad-service", 1, MAVEN_FAIL)
    mark_dirty(project, "nested dir/bad-service")
    r = gate(project)
    assert r.returncode == 2 and "[nested dir/bad-service]" in r.stderr
