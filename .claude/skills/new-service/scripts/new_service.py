"""Scaffold a Spring Boot microservice from start.spring.io plus project conventions.

Usage: python new_service.py <name>-service [--port N] [--deps web,data-jpa,...] [--boot 4.1.1]
"""
import argparse, io, re, shutil, sys, tempfile, urllib.error, urllib.parse, urllib.request, zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
GROUP = "com.example"
BOOT_VERSION = "4.1.1"
DEFAULT_DEPS = "web,data-jpa,validation,mysql,actuator,cloud-eureka"
INFRA_PORTS = {"cloud-eureka-server": 8761, "cloud-gateway": 8080, "cloud-gateway-reactive": 8080}
JAVA_KEYWORDS = set("""abstract assert boolean break byte case catch char class const continue default do double
else enum extends final finally float for goto if implements import instanceof int interface long native new
package private protected public return short static strictfp super switch synchronized this throw throws
transient try void volatile while true false null record var yield sealed permits""".split())


def next_port():
    """Highest server.port among business services (8081-8699) + 1; infra ports (8080, 8761) are fixed."""
    ports = []
    for f in ROOT.glob("*/src/main/resources/application.yml"):
        m = re.search(r"^server:[ \t]*\n(?:[ \t]+.*\n)*?[ \t]+port:[ \t]*(\d+)",
                      f.read_text(encoding="utf-8"), re.M)
        if m and 8081 <= int(m.group(1)) < 8700:
            ports.append(int(m.group(1)))
    return max(ports, default=8080) + 1


def write(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def application_yml(name, short, port, deps):
    y = f"server:\n  port: {port}\nspring:\n  application:\n    name: {name}\n"
    if "data-jpa" in deps:
        if "mysql" in deps:
            y += (f"  datasource:\n    url: jdbc:mysql://${{DB_HOST:localhost}}:${{DB_PORT:3306}}/{short}_db"
                  f"?createDatabaseIfNotExist=true\n    username: ${{DB_USER:root}}\n    password: ${{DB_PASSWORD:}}\n")
        else:
            y += f"  datasource:\n    url: jdbc:h2:mem:{short};DB_CLOSE_DELAY=-1\n"
        y += "  jpa:\n    hibernate:\n      ddl-auto: update\n    open-in-view: false\n"
    if "web" in deps:
        y += "  mvc:\n    problemdetails:\n      enabled: true\n"
    if "cloud-eureka-server" in deps:
        y += "eureka:\n  client:\n    register-with-eureka: false\n    fetch-registry: false\n"
    elif "cloud-eureka" in deps:
        y += ("eureka:\n  client:\n    service-url:\n      defaultZone: ${EUREKA_URL:http://localhost:8761/eureka}\n"
              "  instance:\n    prefer-ip-address: true\n")
    y += "management:\n  endpoints:\n    web:\n      exposure:\n        include: health,info\n"
    return y


def test_properties(short, deps):
    """Test overrides. Must be .properties: a test application.yml would shadow the main one entirely,
    while application.properties loads alongside it and wins on conflicts."""
    lines = []
    if "data-jpa" in deps and "mysql" in deps:
        lines += [f"spring.datasource.url=jdbc:h2:mem:{short};MODE=MySQL;DB_CLOSE_DELAY=-1",
                  "spring.datasource.username=sa", "spring.datasource.password="]
    if "cloud-eureka" in deps and "cloud-eureka-server" not in deps:
        lines.append("eureka.client.enabled=false")  # the server needs its client beans; its main yml is standalone
    return "".join(l + "\n" for l in lines)


def add_test_h2(pom):
    text = pom.read_text(encoding="utf-8")
    dep = ("<dependencies>\n\t\t<dependency>\n\t\t\t<groupId>com.h2database</groupId>\n"
           "\t\t\t<artifactId>h2</artifactId>\n\t\t\t<scope>test</scope>\n\t\t</dependency>")
    pom.write_text(text.replace("<dependencies>", dep, 1), encoding="utf-8")


def exception_classes(base, pkg, jpa):
    write(base / "common/NotFoundException.java", f"""package {pkg}.common;

public class NotFoundException extends RuntimeException {{
    public NotFoundException(String resource, Object id) {{
        super(resource + " " + id + " not found");
    }}
}}
""")
    jpa_import = "import org.springframework.dao.DataIntegrityViolationException;\n" if jpa else ""
    jpa_handler = """
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail conflict(DataIntegrityViolationException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Request conflicts with existing data");
    }
""" if jpa else ""
    write(base / "common/GlobalExceptionHandler.java", f"""package {pkg}.common;

import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
{jpa_import}import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Every error response is an RFC 7807 ProblemDetail (bad JSON, type mismatch, method validation, 500s included). */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {{

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail notFound(NotFoundException ex) {{
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }}
{jpa_handler}
    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception ex) {{
        log.error("Unhandled exception", ex);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error");
    }}

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {{
        ProblemDetail pd = ex.getBody();
        pd.setDetail("Validation failed");
        Map<String, String> errors = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(e -> e.getField(), e -> String.valueOf(e.getDefaultMessage()), (x, y) -> x));
        pd.setProperty("errors", errors);
        return handleExceptionInternal(ex, pd, headers, status, request);
    }}
}}
""")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("name")
    ap.add_argument("--port", type=int)
    ap.add_argument("--deps", default=DEFAULT_DEPS)
    ap.add_argument("--boot", default=BOOT_VERSION)
    a = ap.parse_args()
    if not re.fullmatch(r"[a-z][a-z0-9]*(-[a-z0-9]+)*-service", a.name):
        sys.exit("name must be kebab-case ending in -service, e.g. order-service")
    short = a.name.removesuffix("-service").replace("-", "")
    if short in JAVA_KEYWORDS:
        sys.exit(f"'{short}' is a Java keyword and cannot be a package name; pick another service name")
    target = ROOT / a.name
    if target.exists():
        sys.exit(f"{a.name} already exists")

    deps = [d.strip() for d in a.deps.split(",") if d.strip()]
    pkg = f"{GROUP}.{short}"
    port = a.port or next(iter(INFRA_PORTS[d] for d in deps if d in INFRA_PORTS), None) or next_port()
    q = urllib.parse.urlencode(dict(type="maven-project", language="java", javaVersion="17",
                                    bootVersion=a.boot, groupId=GROUP, artifactId=a.name, name=a.name,
                                    packageName=pkg, dependencies=",".join(deps)))
    try:
        data = urllib.request.urlopen(f"https://start.spring.io/starter.zip?{q}", timeout=60).read()
    except urllib.error.HTTPError as e:
        sys.exit(f"start.spring.io rejected the request ({e.code}): {e.read().decode(errors='replace')[:300]}")
    except (urllib.error.URLError, TimeoutError) as e:
        sys.exit(f"cannot reach start.spring.io: {e}")

    with tempfile.TemporaryDirectory(dir=ROOT) as tmp:  # build fully, then move: no half-created service
        work = Path(tmp) / a.name
        zipfile.ZipFile(io.BytesIO(data)).extractall(work)
        (work / "HELP.md").unlink(missing_ok=True)
        (work / "mvnw").chmod(0o755)
        res = work / "src/main/resources"
        (res / "application.properties").unlink(missing_ok=True)
        write(res / "application.yml", application_yml(a.name, short, port, deps))
        if t := test_properties(short, deps):
            write(work / "src/test/resources/application.properties", t)
        if "data-jpa" in deps and "mysql" in deps:
            add_test_h2(work / "pom.xml")
        if "web" in deps:
            exception_classes(work / "src/main/java" / Path(*pkg.split(".")), pkg, "data-jpa" in deps)
        shutil.move(str(work), str(target))

    with (ROOT / ".claude" / ".dirty").open("a", encoding="utf-8") as f:
        f.write(a.name + "\n")
    print(f"created {a.name} package={pkg} port={port} deps={','.join(deps)}")


if __name__ == "__main__":
    main()
