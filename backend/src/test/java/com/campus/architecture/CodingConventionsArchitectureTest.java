package com.campus.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import jakarta.persistence.Entity;
import org.springframework.web.bind.annotation.RestController;

/**
 * Architecture tests enforcing team coding conventions defined in AGENTS.md §3 and §5:
 * <ul>
 *   <li>Constructor injection only (no {@code @Autowired} on fields).</li>
 *   <li>Controllers must return/accept request/response records (DTOs), never JPA {@code @Entity} classes.</li>
 *   <li>Entities must not use forbidden Lombok annotations ({@code @Data}, {@code @AllArgsConstructor}, etc.).</li>
 * </ul>
 */
@AnalyzeClasses(packages = "com.campus", importOptions = ImportOption.DoNotIncludeTests.class)
class CodingConventionsArchitectureTest {

    // Rule 1: Constructor injection only, never field @Autowired.
    @ArchTest
    static final ArchRule no_field_injection = NO_CLASSES_SHOULD_USE_FIELD_INJECTION.because(
            "Constructor injection ensures immutability, simpler unit testing, and explicit dependencies");

    // Rule 2: Controllers must never return or accept @Entity classes directly.
    @ArchTest
    static final ArchRule controllers_must_not_return_entities = noMethods()
            .that()
            .areDeclaredInClassesThat()
            .areAnnotatedWith(RestController.class)
            .and()
            .arePublic()
            .should()
            .haveRawReturnType(Entity.class)
            .because(
                    "Controllers must return request/response records (DTOs) in web/ to avoid leaking JPA domain entities");

    // Rule 3: Entities must not use Lombok @Data or @AllArgsConstructor (AGENTS.md §5).
    // Lombok @Data generates unwanted equals/hashCode and full setters breaking JPA proxy and dirty checking.
    @ArchTest
    static final ArchRule entities_must_not_use_lombok_data = noClasses()
            .that()
            .areAnnotatedWith(Entity.class)
            .should()
            .beAnnotatedWith("lombok.Data")
            .orShould()
            .beAnnotatedWith("lombok.AllArgsConstructor")
            .because(
                    "Entities must use @Getter, @Setter, and protected @NoArgsConstructor; @Data and @AllArgsConstructor are forbidden");
}
