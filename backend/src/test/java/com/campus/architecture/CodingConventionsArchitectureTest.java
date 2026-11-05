package com.campus.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION;

import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaType;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Architecture tests enforcing team coding conventions defined in AGENTS.md §3 and §5:
 * <ul>
 *   <li><b>Rule 1:</b> Constructor injection only (no {@code @Autowired} on fields).</li>
 *   <li><b>Rule 2:</b> Controllers must return/accept request/response records (DTOs), never JPA {@code @Entity} classes
 *       (including generic wrappers such as {@code ResponseEntity<Asset>} or {@code List<Asset>}).</li>
 *   <li><b>Rule 3:</b> Entities must not define {@code equals}, {@code hashCode}, or {@code toString}
 *       (forbidden Lombok {@code @Data}, {@code @EqualsAndHashCode}, {@code @ToString} or hand-written).</li>
 *   <li><b>Rule 4:</b> Time operations must inject and query the shared {@code Clock} bean
 *       (no zero-argument {@code LocalDateTime.now()} or {@code Instant.now()}).</li>
 *   <li><b>Rule 5:</b> {@code @Transactional} annotations must only be declared on service methods,
 *       never on controllers or repositories.</li>
 *   <li><b>Rule 6:</b> Enums mapped in JPA entities must use {@code @Enumerated(EnumType.STRING)},
 *       never {@code EnumType.ORDINAL}.</li>
 * </ul>
 */
@AnalyzeClasses(packages = "com.campus", importOptions = ImportOption.DoNotIncludeTests.class)
class CodingConventionsArchitectureTest {

    // Rule 1: Constructor injection only, never field @Autowired.
    @ArchTest
    static final ArchRule no_field_injection = NO_CLASSES_SHOULD_USE_FIELD_INJECTION.because(
            "Constructor injection ensures immutability, simpler unit testing, and explicit dependencies");

    // Rule 2: Controllers must never expose JPA @Entity classes in return types or parameters
    // (including generic wrappers like ResponseEntity<Asset>, List<Asset>, Optional<Asset>, etc.).
    @ArchTest
    static final ArchRule controllers_must_not_expose_entities = methods()
            .that()
            .areDeclaredInClassesThat()
            .areAnnotatedWith(RestController.class)
            .and()
            .arePublic()
            .should(new ArchCondition<JavaMethod>("not expose JPA @Entity classes in return types or parameters") {
                @Override
                public void check(JavaMethod method, ConditionEvents events) {
                    for (JavaClass involved : method.getReturnType().getAllInvolvedRawTypes()) {
                        if (involved.isAnnotatedWith(Entity.class)) {
                            String message = String.format(
                                    "Method %s exposes @Entity %s in its return type signature",
                                    method.getFullName(), involved.getName());
                            events.add(SimpleConditionEvent.violated(method, message));
                        }
                    }
                    for (JavaType paramType : method.getParameterTypes()) {
                        for (JavaClass involved : paramType.getAllInvolvedRawTypes()) {
                            if (involved.isAnnotatedWith(Entity.class)) {
                                String message = String.format(
                                        "Method %s exposes @Entity %s in its parameter signature",
                                        method.getFullName(), involved.getName());
                                events.add(SimpleConditionEvent.violated(method, message));
                            }
                        }
                    }
                }
            })
            .because(
                    "Controllers must return and accept request/response records (DTOs) in web/, never JPA domain entities");

    // Rule 3: Entities must not define equals, hashCode, or toString (AGENTS.md §5).
    // Lombok annotations (@Data, @EqualsAndHashCode, @ToString) have SOURCE retention and disappear from .class files,
    // so we verify that the methods (generated by Lombok or written by hand) do not exist on entities.
    @ArchTest
    static final ArchRule entities_must_not_override_equals_hashcode_tostring = noMethods()
            .that()
            .areDeclaredInClassesThat()
            .areAnnotatedWith(Entity.class)
            .should()
            .haveName("equals")
            .orShould()
            .haveName("hashCode")
            .orShould()
            .haveName("toString")
            .because(
                    "Entities must not define equals, hashCode, or toString (via Lombok @Data / @EqualsAndHashCode / @ToString or hand-written); "
                            + "JPA proxy identity and dirty checking are managed by the database primary key and EntityManager");

    // Rule 4: Shared Clock bean enforcement (AGENTS.md §3).
    // Never call LocalDateTime.now(), Instant.now(), LocalDate.now(), LocalTime.now(),
    // ZonedDateTime.now(), or OffsetDateTime.now() without passing the shared Clock.
    @ArchTest
    static final ArchRule no_unparameterized_datetime_now = noClasses()
            .should()
            .callMethod(LocalDateTime.class, "now")
            .orShould()
            .callMethod(Instant.class, "now")
            .orShould()
            .callMethod(LocalDate.class, "now")
            .orShould()
            .callMethod(LocalTime.class, "now")
            .orShould()
            .callMethod(ZonedDateTime.class, "now")
            .orShould()
            .callMethod(OffsetDateTime.class, "now")
            .because(
                    "All date and time calculations must use the shared Clock bean (e.g. LocalDateTime.now(clock)) to ensure deterministic tests and proper time zone handling");

    // Rule 5: @Transactional belongs on service methods only, never on controllers or repositories (AGENTS.md §5).
    @ArchTest
    static final ArchRule controller_classes_must_not_be_transactional = noClasses()
            .that()
            .areAnnotatedWith(RestController.class)
            .should()
            .beAnnotatedWith(Transactional.class)
            .orShould()
            .beAnnotatedWith(jakarta.transaction.Transactional.class)
            .because("@Transactional belongs on service methods only, never on controllers (AGENTS.md §5)");

    // Bug fixed: method-level checks must use noMethods().that().areDeclaredInClassesThat() rather than
    // noClasses().should(customCondition) because noClasses() inverts custom condition events (#76 trap).
    @ArchTest
    static final ArchRule controller_methods_must_not_be_transactional = noMethods()
            .that()
            .areDeclaredInClassesThat()
            .areAnnotatedWith(RestController.class)
            .should()
            .beAnnotatedWith(Transactional.class)
            .orShould()
            .beAnnotatedWith(jakarta.transaction.Transactional.class)
            .because("@Transactional belongs on service methods only, never on controllers (AGENTS.md §5)");

    @ArchTest
    static final ArchRule repository_classes_must_not_be_transactional = noClasses()
            .that()
            .areAssignableTo(Repository.class)
            .should()
            .beAnnotatedWith(Transactional.class)
            .orShould()
            .beAnnotatedWith(jakarta.transaction.Transactional.class)
            .because("@Transactional belongs on service methods only, never on repositories (AGENTS.md §5)");

    // Bug fixed: verifies individual repository methods are never annotated with @Transactional.
    @ArchTest
    static final ArchRule repository_methods_must_not_be_transactional = noMethods()
            .that()
            .areDeclaredInClassesThat()
            .areAssignableTo(Repository.class)
            .should()
            .beAnnotatedWith(Transactional.class)
            .orShould()
            .beAnnotatedWith(jakarta.transaction.Transactional.class)
            .because("@Transactional belongs on service methods only, never on repositories (AGENTS.md §5)");

    // Rule 6: Enums in entities must use EnumType.STRING, never ORDINAL (AGENTS.md §5).
    @ArchTest
    static final ArchRule entities_must_not_use_ordinal_enum_mapping = fields().that()
            .areDeclaredInClassesThat()
            .areAnnotatedWith(Entity.class)
            .and()
            .areAnnotatedWith(Enumerated.class)
            .should(new ArchCondition<JavaField>("use EnumType.STRING instead of ORDINAL") {
                @Override
                public void check(JavaField field, ConditionEvents events) {
                    Enumerated enumerated = field.getAnnotationOfType(Enumerated.class);
                    if (enumerated.value() == EnumType.ORDINAL) {
                        String message = String.format(
                                "Entity field %s uses EnumType.ORDINAL; must use EnumType.STRING", field.getFullName());
                        events.add(SimpleConditionEvent.violated(field, message));
                    }
                }
            })
            .because(
                    "Database enums must be VARCHAR(32) with EnumType.STRING to prevent silent data corruption if enum constants are reordered (AGENTS.md §5)");

    // Rule 7: Forbidden Spring Boot 2/3 legacy imports (AGENTS.md §3 table).
    @ArchTest
    static final ArchRule no_legacy_spring_boot_2_or_3_imports = noClasses()
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "javax.persistence..",
                    "javax.validation..",
                    "com.fasterxml.jackson.databind..",
                    "io.jsonwebtoken..")
            .because(
                    "Spring Boot 4 uses jakarta.persistence, jakarta.validation, Jackson 3 (tools.jackson.*), and Spring OAuth2 Resource Server (AGENTS.md §3)");

    // Rule 8: Never use @CrossOrigin on controllers (AGENTS.md §3 table).
    @ArchTest
    static final ArchRule controller_classes_must_not_use_cross_origin = noClasses()
            .that()
            .areAnnotatedWith(RestController.class)
            .should()
            .beAnnotatedWith(CrossOrigin.class)
            .because(
                    "Vite proxy (dev) and CloudFront (prod) serve frontend and API from one origin; @CrossOrigin is forbidden (AGENTS.md §3)");

    // Bug fixed: use noMethods() to catch method-level @CrossOrigin without noClasses() inverted event trap (#76).
    @ArchTest
    static final ArchRule controller_methods_must_not_use_cross_origin = noMethods()
            .that()
            .areDeclaredInClassesThat()
            .areAnnotatedWith(RestController.class)
            .should()
            .beAnnotatedWith(CrossOrigin.class)
            .because(
                    "Vite proxy (dev) and CloudFront (prod) serve frontend and API from one origin; @CrossOrigin is forbidden (AGENTS.md §3)");

    // Rule 9: Controllers must return PageResponse<T>, never Spring Data's Page<T> (AGENTS.md §5).
    @ArchTest
    static final ArchRule controllers_must_not_return_spring_page = methods()
            .that()
            .areDeclaredInClassesThat()
            .areAnnotatedWith(RestController.class)
            .and()
            .arePublic()
            .should(new ArchCondition<JavaMethod>("not return org.springframework.data.domain.Page") {
                @Override
                public void check(JavaMethod method, ConditionEvents events) {
                    for (JavaClass involved : method.getReturnType().getAllInvolvedRawTypes()) {
                        if (involved.isAssignableTo(Page.class)) {
                            String message = String.format(
                                    "Method %s exposes Spring Data Page (%s); must return PageResponse<T> instead",
                                    method.getFullName(), involved.getName());
                            events.add(SimpleConditionEvent.violated(method, message));
                        }
                    }
                }
            })
            .because(
                    "Paginated endpoints must return PageResponse<T> (content, page, size, totalElements, totalPages), never Spring's Page (AGENTS.md §5)");

    // Rule 10: JPA entities must not use @OneToMany and any @ManyToOne must use FetchType.LAZY (AGENTS.md §5).
    @ArchTest
    static final ArchRule entities_must_not_use_one_to_many = noFields()
            .that()
            .areDeclaredInClassesThat()
            .areAnnotatedWith(Entity.class)
            .should()
            .beAnnotatedWith(OneToMany.class)
            .because("Entities must avoid @OneToMany and query the repository instead (AGENTS.md §5)");

    @ArchTest
    static final ArchRule entities_many_to_one_must_be_lazy = fields().that()
            .areDeclaredInClassesThat()
            .areAnnotatedWith(Entity.class)
            .and()
            .areAnnotatedWith(ManyToOne.class)
            .should(new ArchCondition<JavaField>("use FetchType.LAZY on @ManyToOne") {
                @Override
                public void check(JavaField field, ConditionEvents events) {
                    ManyToOne manyToOne = field.getAnnotationOfType(ManyToOne.class);
                    if (manyToOne.fetch() != FetchType.LAZY) {
                        String message = String.format(
                                "Entity field %s uses @ManyToOne(fetch = %s); must use FetchType.LAZY",
                                field.getFullName(), manyToOne.fetch());
                        events.add(SimpleConditionEvent.violated(field, message));
                    }
                }
            })
            .because("@ManyToOne associations on entities must always use FetchType.LAZY (AGENTS.md §5)");

    // Rule 11: Admin controllers and admin endpoints must map under /api/v1/admin/** so SecurityConfig protects them
    // (AGENTS.md §5).
    @ArchTest
    static final ArchRule admin_controllers_must_map_under_admin_prefix = classes()
            .that()
            .areAnnotatedWith(RestController.class)
            .and()
            .haveSimpleNameStartingWith("Admin")
            .should(new ArchCondition<JavaClass>("be mapped under /api/v1/admin") {
                @Override
                public void check(JavaClass javaClass, ConditionEvents events) {
                    if (!javaClass.isAnnotatedWith(RequestMapping.class)) {
                        events.add(SimpleConditionEvent.violated(
                                javaClass, javaClass.getName() + " is missing @RequestMapping under /api/v1/admin/**"));
                        return;
                    }
                    RequestMapping mapping = javaClass.getAnnotationOfType(RequestMapping.class);
                    String[] paths = mapping.value().length > 0 ? mapping.value() : mapping.path();
                    if (paths.length == 0) {
                        events.add(SimpleConditionEvent.violated(
                                javaClass, javaClass.getName() + " has an empty @RequestMapping"));
                        return;
                    }
                    for (String path : paths) {
                        if (!path.startsWith("/api/v1/admin")) {
                            events.add(SimpleConditionEvent.violated(
                                    javaClass,
                                    String.format(
                                            "Admin controller %s maps to '%s'; must start with '/api/v1/admin'",
                                            javaClass.getName(), path)));
                        }
                    }
                }
            })
            .because(
                    "Admin-only controllers must be under /api/v1/admin/** because SecurityConfig protects that prefix (AGENTS.md §5)");

    // Covers admin endpoint mappings declared in mixed controllers (such as catalog's AssetController)
    // so they are strictly mapped under /api/v1/admin/** where SecurityConfig protects them.
    @ArchTest
    static final ArchRule admin_endpoints_must_map_under_admin_prefix = methods()
            .that()
            .areDeclaredInClassesThat()
            .areAnnotatedWith(RestController.class)
            .and()
            .arePublic()
            .should(new ArchCondition<JavaMethod>("map admin paths under /api/v1/admin") {
                @Override
                public void check(JavaMethod method, ConditionEvents events) {
                    for (JavaAnnotation<?> annotation : method.getAnnotations()) {
                        String name = annotation.getRawType().getSimpleName();
                        if (name.endsWith("Mapping")) {
                            checkMappingProperty(method, events, annotation, "value");
                            checkMappingProperty(method, events, annotation, "path");
                        }
                    }
                }
            })
            .because(
                    "All admin endpoints (including in catalog controllers) must start with /api/v1/admin so SecurityConfig protects them (AGENTS.md §5)");

    private static void checkMappingProperty(
            JavaMethod method, ConditionEvents events, JavaAnnotation<?> annotation, String property) {
        annotation.get(property).ifPresent(val -> {
            if (val instanceof String[] paths) {
                for (String path : paths) {
                    validateAdminPath(method, events, path);
                }
            } else if (val instanceof String path) {
                validateAdminPath(method, events, path);
            }
        });
    }

    private static void validateAdminPath(JavaMethod method, ConditionEvents events, String path) {
        if (path.contains("admin") && !path.startsWith("/api/v1/admin")) {
            events.add(SimpleConditionEvent.violated(
                    method,
                    String.format(
                            "Endpoint method %s maps to '%s' which contains 'admin' but does not start with '/api/v1/admin'",
                            method.getFullName(), path)));
        }
    }

    // Rule 12: Public module api facades must never expose JPA @Entity classes in return types or parameters (AGENTS.md
    // §5).
    @ArchTest
    static final ArchRule api_packages_must_not_expose_entities = methods()
            .that()
            .areDeclaredInClassesThat()
            .resideInAPackage("com.campus.*.api..")
            .and()
            .arePublic()
            .should(new ArchCondition<JavaMethod>("not expose JPA @Entity classes in public api facade signatures") {
                @Override
                public void check(JavaMethod method, ConditionEvents events) {
                    for (JavaClass involved : method.getReturnType().getAllInvolvedRawTypes()) {
                        if (involved.isAnnotatedWith(Entity.class)) {
                            String message = String.format(
                                    "API method %s exposes @Entity %s in its return type signature",
                                    method.getFullName(), involved.getName());
                            events.add(SimpleConditionEvent.violated(method, message));
                        }
                    }
                    for (JavaType paramType : method.getParameterTypes()) {
                        for (JavaClass involved : paramType.getAllInvolvedRawTypes()) {
                            if (involved.isAnnotatedWith(Entity.class)) {
                                String message = String.format(
                                        "API method %s exposes @Entity %s in its parameter signature",
                                        method.getFullName(), involved.getName());
                                events.add(SimpleConditionEvent.violated(method, message));
                            }
                        }
                    }
                }
            })
            .because(
                    "Public api packages must expose immutable summary records and events, never internal JPA @Entity classes (AGENTS.md §5)");

    // Rule 13: No System.out / System.err usage; use SLF4J logging instead.
    @ArchTest
    static final ArchRule no_standard_streams = NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS.because(
            "Services and components must use SLF4J (@Slf4j) instead of System.out or System.err");

    // Rule 14: Cross-module references on JPA entities must be plain IDs (Long assetId), never @Entity references from
    // another module (AGENTS.md §5).
    @ArchTest
    static final ArchRule entities_must_not_reference_entities_from_other_modules = fields().that()
            .areDeclaredInClassesThat()
            .areAnnotatedWith(Entity.class)
            .should(new ArchCondition<JavaField>("not reference @Entity classes from other modules") {
                @Override
                public void check(JavaField field, ConditionEvents events) {
                    String ownerPackage = field.getOwner().getPackageName();
                    for (JavaClass involved : field.getType().getAllInvolvedRawTypes()) {
                        if (involved.isAnnotatedWith(Entity.class)
                                && !involved.getPackageName().equals(ownerPackage)) {
                            String message = String.format(
                                    "Entity field %s references @Entity %s from another package (%s); cross-module references must be plain IDs (AGENTS.md §5)",
                                    field.getFullName(), involved.getName(), involved.getPackageName());
                            events.add(SimpleConditionEvent.violated(field, message));
                        }
                    }
                }
            })
            .because(
                    "Cross-module references in JPA entities must be plain IDs (e.g. Long assetId) rather than cross-module @Entity associations (AGENTS.md §5)");
}
