"""Generate a full CRUD vertical slice (entity, DTOs, repository, service, controller, tests).

Usage: python gen_resource.py <service-dir> <Entity> "field:Type,field:Type" [--table name] [--plural word]
Types: String Long Integer Double BigDecimal Boolean LocalDate Instant
"""
import argparse, re, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
TYPES = {
    "String": (None, '"sample"'), "Long": (None, "1"), "Integer": (None, "1"),
    "Double": (None, "1.5"), "Boolean": (None, "true"),
    "BigDecimal": ("java.math.BigDecimal", "10.50"),
    "LocalDate": ("java.time.LocalDate", '"2026-01-01"'),
    "Instant": ("java.time.Instant", '"2026-01-01T00:00:00Z"'),
}
JAVA_VAL = {"String": '"sample"', "Long": "1L", "Integer": "1", "Double": "1.5", "Boolean": "true",
            "BigDecimal": 'new BigDecimal("10.50")', "LocalDate": "LocalDate.of(2026, 1, 1)",
            "Instant": 'Instant.parse("2026-01-01T00:00:00Z")'}


JAVA_KEYWORDS = set("""abstract assert boolean break byte case catch char class const continue default do double
else enum extends final finally float for goto if implements import instanceof int interface long native new
package private protected public return short static strictfp super switch synchronized this throw throws
transient try void volatile while true false null record var yield sealed permits""".split())
# simple names imported by the generated files; an entity with one of these names would not compile
IMPORTED = set("""Entity Table Id Column GeneratedValue GenerationType NotBlank NotNull JpaRepository NotFoundException
List PageRequest Service Transactional Valid Max Min URI ResponseEntity Test Autowired WebMvcTest MediaType
MockitoBean MockMvc Optional ExtendWith InjectMocks Mock MockitoExtension SpringBootTest BigDecimal LocalDate
Instant Object String Long Integer Double Boolean Override""".split())
# H2/MySQL reserved words; such columns/tables are quoted so DDL does not fail
SQL_RESERVED = set("""all and any array as asymmetric authorization between both case cast check constraint cross
current_date current_time current_timestamp current_user day default distinct else end except exists false fetch
for foreign from full group groups having hour if ilike in inner intersect interval is join key leading left like
limit localtime localtimestamp minus minute month natural not null offset on or order over partition primary
qualify range regexp right row rownum rows second select session_user set some symmetric system_user table to top
trailing true union unique unknown user using value values when where window with year index desc asc
""".split())


def snake(s):
    s = re.sub(r"([A-Z]+)([A-Z][a-z])", r"\1_\2", s)  # URLMapping -> URL_Mapping
    return re.sub(r"([a-z0-9])([A-Z])", r"\1_\2", s).lower()


def pluralize(word):
    if re.search(r"[^aeiou]y$", word):
        return word[:-1] + "ies"
    if re.search(r"(s|x|z|ch|sh)$", word):
        return word + "es"
    return word + "s"


def quoted(name):
    return f"`{name}`" if name in SQL_RESERVED else name


def write(path, text):
    rel = path.relative_to(ROOT) if path.is_relative_to(ROOT) else path
    if path.exists():
        print(f"skip (exists) {rel}")
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")
    print(f"wrote {rel}")


def parse_fields(spec):
    fields, seen = [], set()
    for part in spec.split(","):
        m = re.fullmatch(r"\s*([a-z][A-Za-z0-9]*)\s*:\s*([A-Za-z]+)\s*", part)
        if not m:
            sys.exit(f"bad field spec '{part}': expected name:Type, e.g. \"name:String,price:BigDecimal\"")
        n, t = m.groups()
        if n == "id":
            sys.exit("'id' is generated automatically; remove it from the field list")
        if n in JAVA_KEYWORDS:
            sys.exit(f"field name '{n}' is a Java keyword")
        if n in seen:
            sys.exit(f"duplicate field '{n}'")
        if t not in TYPES:
            sys.exit(f"unsupported type {t}; use one of {', '.join(TYPES)}")
        seen.add(n)
        fields.append((n, t))
    return fields


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("service")
    ap.add_argument("entity")
    ap.add_argument("fields")
    ap.add_argument("--table")
    ap.add_argument("--plural", help="URL/table plural if the default rule is wrong, e.g. --plural people")
    a = ap.parse_args()

    E = a.entity
    if not re.fullmatch(r"[A-Z][A-Za-z0-9]*", E) or E.lower() in JAVA_KEYWORDS:
        sys.exit(f"entity '{E}' must be an UpperCamelCase Java identifier")
    if E in IMPORTED:
        sys.exit(f"entity '{E}' clashes with a class the generated code imports; pick another name")
    fields = parse_fields(a.fields)

    svc = (ROOT / a.service).resolve()
    if not svc.is_relative_to(ROOT.resolve()) or svc == ROOT.resolve():
        sys.exit(f"{a.service} must be a service folder inside {ROOT}")
    app = next((svc / "src/main/java").rglob("*Application.java"), None)
    if not app:
        sys.exit(f"no *Application.java under {a.service}; run new-service first")
    pkg = app.read_text(encoding="utf-8").split("package ", 1)[1].split(";", 1)[0]
    pdir = Path(*pkg.split("."))
    main_dir, test_dir = svc / "src/main/java" / pdir, svc / "src/test/java" / pdir

    v = E[0].lower() + E[1:]
    plural = snake(a.plural) if a.plural else pluralize(snake(E))
    table = quoted(a.table or plural)
    path = "/api/v1/" + plural.replace("_", "-")
    imports = sorted({TYPES[t][0] for _, t in fields if TYPES[t][0]})
    imp = "".join(f"import {i};\n" for i in imports)
    cap = lambda n: n[0].upper() + n[1:]

    # --- entity
    def column(n):
        col = snake(n)
        return f'@Column(name = "{quoted(col)}", nullable = false)' if col in SQL_RESERVED else "@Column(nullable = false)"
    cols = "\n".join(f"    {column(n)}\n    private {t} {n};\n" for n, t in fields)
    acc = "\n".join(f"    public {t} get{cap(n)}() {{ return {n}; }}\n"
                    f"    public void set{cap(n)}({t} {n}) {{ this.{n} = {n}; }}\n" for n, t in fields)
    write(main_dir / "domain" / f"{E}.java", f"""package {pkg}.domain;

import jakarta.persistence.*;
{imp}
@Entity
@Table(name = "{table}")
public class {E} {{

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

{cols}
    public Long getId() {{ return id; }}

{acc}}}
""")

    # --- DTOs
    def ann(t):
        return "@NotBlank " if t == "String" else "@NotNull "
    req = ", ".join(f"{ann(t)}{t} {n}" for n, t in fields)
    write(main_dir / "dto" / f"{E}Request.java", f"""package {pkg}.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
{imp}
public record {E}Request({req}) {{
}}
""")
    resp = ", ".join(["Long id"] + [f"{t} {n}" for n, t in fields])
    from_args = ", ".join([f"e.getId()"] + [f"e.get{cap(n)}()" for n, _ in fields])
    write(main_dir / "dto" / f"{E}Response.java", f"""package {pkg}.dto;

import {pkg}.domain.{E};
{imp}
public record {E}Response({resp}) {{
    public static {E}Response from({E} e) {{
        return new {E}Response({from_args});
    }}
}}
""")

    # --- repository
    write(main_dir / "repository" / f"{E}Repository.java", f"""package {pkg}.repository;

import {pkg}.domain.{E};
import org.springframework.data.jpa.repository.JpaRepository;

public interface {E}Repository extends JpaRepository<{E}, Long> {{
}}
""")

    # --- service
    apply = "\n".join(f"        e.set{cap(n)}(r.{n}());" for n, _ in fields)
    write(main_dir / "service" / f"{E}Service.java", f"""package {pkg}.service;

import {pkg}.common.NotFoundException;
import {pkg}.domain.{E};
import {pkg}.dto.{E}Request;
import {pkg}.dto.{E}Response;
import {pkg}.repository.{E}Repository;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class {E}Service {{

    private final {E}Repository repository;

    public {E}Service({E}Repository repository) {{
        this.repository = repository;
    }}

    @Transactional
    public {E}Response create({E}Request r) {{
        {E} e = new {E}();
        apply(e, r);
        return {E}Response.from(repository.save(e));
    }}

    public {E}Response get(Long id) {{
        return {E}Response.from(find(id));
    }}

    public List<{E}Response> list(int page, int size) {{
        return repository.findAll(PageRequest.of(page, size)).map({E}Response::from).getContent();
    }}

    @Transactional
    public {E}Response update(Long id, {E}Request r) {{
        {E} e = find(id);
        apply(e, r);
        return {E}Response.from(e);
    }}

    @Transactional
    public void delete(Long id) {{
        repository.delete(find(id));
    }}

    private {E} find(Long id) {{
        return repository.findById(id).orElseThrow(() -> new NotFoundException("{E}", id));
    }}

    private static void apply({E} e, {E}Request r) {{
{apply}
    }}
}}
""")

    # --- controller
    write(main_dir / "web" / f"{E}Controller.java", f"""package {pkg}.web;

import {pkg}.dto.{E}Request;
import {pkg}.dto.{E}Response;
import {pkg}.service.{E}Service;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("{path}")
public class {E}Controller {{

    private final {E}Service service;

    public {E}Controller({E}Service service) {{
        this.service = service;
    }}

    @PostMapping
    public ResponseEntity<{E}Response> create(@Valid @RequestBody {E}Request request) {{
        {E}Response created = service.create(request);
        return ResponseEntity.created(URI.create("{path}/" + created.id())).body(created);
    }}

    @GetMapping("/{{id}}")
    public {E}Response get(@PathVariable Long id) {{
        return service.get(id);
    }}

    @GetMapping
    public List<{E}Response> list(@RequestParam(defaultValue = "0") @Min(0) int page,
                                  @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {{
        return service.list(page, size);
    }}

    @PutMapping("/{{id}}")
    public {E}Response update(@PathVariable Long id, @Valid @RequestBody {E}Request request) {{
        return service.update(id, request);
    }}

    @DeleteMapping("/{{id}}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {{
        service.delete(id);
        return ResponseEntity.noContent().build();
    }}
}}
""")

    # --- tests
    json_body = "{" + ",".join(f'\\"{n}\\":{TYPES[t][1].replace(chr(34), chr(92) + chr(34))}' for n, t in fields) + "}"
    req_vals = ", ".join(JAVA_VAL[t] for _, t in fields)
    write(test_dir / "web" / f"{E}ControllerTest.java", f"""package {pkg}.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import {pkg}.common.NotFoundException;
import {pkg}.dto.{E}Request;
import {pkg}.dto.{E}Response;
import {pkg}.service.{E}Service;
{imp}import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest({E}Controller.class)
class {E}ControllerTest {{

    @Autowired MockMvc mvc;
    @MockitoBean {E}Service service;

    @Test
    void createReturns201() throws Exception {{
        when(service.create(any({E}Request.class))).thenReturn(new {E}Response(1L, {req_vals}));
        mvc.perform(post("{path}").contentType(MediaType.APPLICATION_JSON).content("{json_body}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "{path}/1"))
                .andExpect(jsonPath("$.id").value(1));
    }}

    @Test
    void createWithEmptyBodyReturns400() throws Exception {{
        mvc.perform(post("{path}").contentType(MediaType.APPLICATION_JSON).content("{{}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors").exists());
    }}

    @Test
    void getMissingReturns404() throws Exception {{
        when(service.get(99L)).thenThrow(new NotFoundException("{E}", 99L));
        mvc.perform(get("{path}/99"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }}

    @Test
    void badRequestsReturnProblemDetail() throws Exception {{
        mvc.perform(get("{path}/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
        mvc.perform(get("{path}").param("size", "1000"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
        mvc.perform(post("{path}").contentType(MediaType.APPLICATION_JSON).content("{{bad"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }}
}}
""")
    setters = "\n".join(f"        e.set{cap(n)}({JAVA_VAL[t]});" for n, t in fields)
    write(test_dir / "repository" / f"{E}RepositoryTest.java", f"""package {pkg}.repository;

import static org.assertj.core.api.Assertions.assertThat;

import {pkg}.domain.{E};
{imp}import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/** Real schema round-trip: catches DDL problems (reserved words, column types) that mocks cannot. */
@SpringBootTest
@Transactional
class {E}RepositoryTest {{

    @Autowired {E}Repository repository;

    @Test
    void savesAndReloads() {{
        {E} e = new {E}();
{setters}
        Long id = repository.saveAndFlush(e).getId();
        assertThat(repository.findById(id)).isPresent();
    }}
}}
""")
    write(test_dir / "service" / f"{E}ServiceTest.java", f"""package {pkg}.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import {pkg}.common.NotFoundException;
import {pkg}.domain.{E};
import {pkg}.dto.{E}Request;
import {pkg}.repository.{E}Repository;
{imp}import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class {E}ServiceTest {{

    @Mock {E}Repository repository;
    @InjectMocks {E}Service service;

    @Test
    void createMapsAllFields() {{
        when(repository.save(any({E}.class))).thenAnswer(i -> i.getArgument(0));
        var request = new {E}Request({req_vals});
        var response = service.create(request);
{chr(10).join(f"        assertThat(response.{n}()).isEqualTo(request.{n}());" for n, _ in fields)}
    }}

    @Test
    void getMissingThrows() {{
        when(repository.findById(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(1L)).isInstanceOf(NotFoundException.class);
    }}
}}
""")
    with (ROOT / ".claude" / ".dirty").open("a", encoding="utf-8") as f:
        f.write(svc.relative_to(ROOT.resolve()).parts[0] + "\n")


if __name__ == "__main__":
    main()
