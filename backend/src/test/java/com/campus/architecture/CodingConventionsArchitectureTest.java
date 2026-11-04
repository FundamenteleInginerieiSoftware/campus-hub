package com.campus.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION;

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
import java.time.Instant;
import java.time.LocalDateTime;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;
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
    // Never call LocalDateTime.now() or Instant.now() without passing the shared Clock.
    @ArchTest
    static final ArchRule no_unparameterized_datetime_now = noClasses()
            .should()
            .callMethod(LocalDateTime.class, "now")
            .orShould()
            .callMethod(Instant.class, "now")
            .because(
                    "All date and time calculations must use the shared Clock bean (e.g. LocalDateTime.now(clock)) to ensure deterministic tests and proper time zone handling");

    // Rule 5: @Transactional belongs on service methods only, never on controllers or repositories (AGENTS.md §5).
    @ArchTest
    static final ArchRule controllers_must_not_be_transactional = noClasses()
            .that()
            .areAnnotatedWith(RestController.class)
            .should()
            .beAnnotatedWith(Transactional.class)
            .orShould(new ArchCondition<JavaClass>("have @Transactional methods") {
                @Override
                public void check(JavaClass javaClass, ConditionEvents events) {
                    for (JavaMethod method : javaClass.getMethods()) {
                        if (method.isAnnotatedWith(Transactional.class)) {
                            String message = String.format(
                                    "Controller method %s must not be annotated with @Transactional",
                                    method.getFullName());
                            events.add(SimpleConditionEvent.violated(method, message));
                        }
                    }
                }
            })
            .because("@Transactional belongs on service methods only, never on controllers (AGENTS.md §5)");

    @ArchTest
    static final ArchRule repositories_must_not_be_transactional = noClasses()
            .that()
            .areAssignableTo(Repository.class)
            .should()
            .beAnnotatedWith(Transactional.class)
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
}
