"""Full pipeline against real start.spring.io + Maven. Run with: python -m pytest -m slow"""
import pytest

from conftest import BUILD_GATE, GEN_RESOURCE, NEW_SERVICE, run_hook, run_script

pytestmark = [pytest.mark.slow, pytest.mark.network]


def test_generated_service_builds_and_gate_catches_breakage(project):
    r = run_script(project, NEW_SERVICE, "shop-service")
    assert r.returncode == 0, r.stderr
    assert "port=8081" in r.stdout
    svc = project / "shop-service"
    yml = (svc / "src/main/resources/application.yml").read_text()
    assert "port: 8081" in yml and "open-in-view: false" in yml and "jdbc:mysql://" in yml
    assert not (svc / "src/main/resources/application.properties").exists()

    r = run_script(project, GEN_RESOURCE, "shop-service", "Order",
                   "customerId:Long,note:String,total:BigDecimal,paid:Boolean,due:LocalDate,at:Instant,value:Integer,year:Integer")
    assert r.returncode == 0, r.stderr

    assert (project / ".claude/.dirty").read_text().split() == ["shop-service", "shop-service"]  # generators mark it
    r = run_hook(project, BUILD_GATE, {"stop_hook_active": False})
    assert r.returncode == 0, r.stderr
    reports = list((svc / "target/surefire-reports").glob("*.txt"))
    assert len(reports) == 4  # app context + controller + service + repository (real H2 schema) tests

    service = svc / "src/main/java/com/example/shop/service/OrderService.java"
    service.write_text(service.read_text().replace("return OrderResponse.from(find(id));", "return null;"))
    (project / ".claude/.dirty").write_text("shop-service\n")
    r = run_hook(project, BUILD_GATE, {"stop_hook_active": False})
    assert r.returncode == 2
    assert "getMissingThrows" in r.stderr
