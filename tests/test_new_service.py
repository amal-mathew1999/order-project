import pytest

from conftest import NEW_SERVICE, load_module, run_script


@pytest.mark.parametrize("name", ["Order-service", "order", "order_service", "1-service", "orders-svc"])
def test_rejects_bad_names(project, name):
    r = run_script(project, NEW_SERVICE, name)
    assert r.returncode != 0 and "kebab-case" in r.stderr


def test_rejects_existing_service(project):
    (project / "order-service").mkdir()
    r = run_script(project, NEW_SERVICE, "order-service")
    assert r.returncode != 0 and "already exists" in r.stderr


def write_yml(project, svc, text):
    p = project / svc / "src/main/resources/application.yml"
    p.parent.mkdir(parents=True)
    p.write_text(text)


def test_next_port_defaults_to_8081(project):
    assert load_module(project, NEW_SERVICE).next_port() == 8081


def test_next_port_is_max_plus_one(project):
    write_yml(project, "a-service", "server:\n  port: 8081\n")
    write_yml(project, "b-service", "server:\n  port: 8084\n")
    assert load_module(project, NEW_SERVICE).next_port() == 8085
