from conftest import GEN_RESOURCE, fake_service, run_script

FIELDS = "customerId:Long,note:String,total:BigDecimal,paid:Boolean,due:LocalDate,at:Instant,qty:Integer,rate:Double"


def gen(project, entity="Order", fields=FIELDS, *extra):
    return run_script(project, GEN_RESOURCE, "shop-service", entity, fields, *extra)


def files(svc, e="Order"):
    m, t = svc / "src/main/java/com/example/shop", svc / "src/test/java/com/example/shop"
    return {
        "entity": m / f"domain/{e}.java", "req": m / f"dto/{e}Request.java", "resp": m / f"dto/{e}Response.java",
        "repo": m / f"repository/{e}Repository.java", "svc": m / f"service/{e}Service.java",
        "ctrl": m / f"web/{e}Controller.java",
        "ctest": t / f"web/{e}ControllerTest.java", "stest": t / f"service/{e}ServiceTest.java",
        "rtest": t / f"repository/{e}RepositoryTest.java",
    }


def test_generates_full_slice(project):
    svc, _ = fake_service(project)
    r = gen(project)
    assert r.returncode == 0, r.stderr
    f = files(svc)
    assert all(p.exists() for p in f.values())
    for p in f.values():
        assert p.read_text().startswith("package com.example.shop.")


def test_entity_table_and_columns(project):
    svc, _ = fake_service(project)
    gen(project)
    src = files(svc)["entity"].read_text()
    assert '@Table(name = "orders")' in src  # "order" is a reserved SQL word
    assert "import java.math.BigDecimal;" in src and "import java.time.Instant;" in src
    assert "private Long customerId;" in src and "public void setTotal(BigDecimal total)" in src


def test_table_override(project):
    svc, _ = fake_service(project)
    gen(project, "Order", "note:String", "--table", "purchase_orders")
    assert '@Table(name = "purchase_orders")' in files(svc)["entity"].read_text()


def test_request_validation_annotations(project):
    svc, _ = fake_service(project)
    gen(project)
    src = files(svc)["req"].read_text()
    assert "@NotBlank String note" in src
    assert "@NotNull Long customerId" in src and "@NotNull BigDecimal total" in src


def test_controller_contract(project):
    svc, _ = fake_service(project)
    gen(project, "LineItem", "sku:String")
    src = files(svc, "LineItem")["ctrl"].read_text()
    assert '@RequestMapping("/api/v1/line-items")' in src
    for verb in ("@PostMapping", "@GetMapping(\"/{id}\")", "@PutMapping", "@DeleteMapping"):
        assert verb in src
    assert "@Valid @RequestBody" in src and "@Max(100)" in src and "noContent()" in src
    assert "domain." not in src  # controllers never see entities


def test_service_is_transactional_with_constructor_injection(project):
    svc, _ = fake_service(project)
    gen(project)
    src = files(svc)["svc"].read_text()
    assert "@Transactional(readOnly = true)" in src
    assert src.count("@Transactional\n") == 3  # create, update, delete
    assert "public OrderService(OrderRepository repository)" in src
    assert "@Autowired" not in src


def test_unsupported_type_fails(project):
    fake_service(project)
    r = gen(project, "Order", "items:List")
    assert r.returncode != 0 and "unsupported type List" in r.stderr


def test_missing_service_fails(project):
    r = run_script(project, GEN_RESOURCE, "ghost-service", "Order", "note:String")
    assert r.returncode != 0 and "run new-service first" in r.stderr


def test_existing_files_not_overwritten(project):
    svc, _ = fake_service(project)
    gen(project)
    entity = files(svc)["entity"]
    entity.write_text("// customized")
    r = gen(project)
    assert "skip (exists)" in r.stdout
    assert entity.read_text() == "// customized"
