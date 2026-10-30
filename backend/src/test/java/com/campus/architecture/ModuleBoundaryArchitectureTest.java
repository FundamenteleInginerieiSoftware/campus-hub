package com.campus.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Architecture tests enforcing modular monolith boundaries across packages under {@code com.campus}.
 *
 * <p>Rules defined in AGENTS.md §5:
 * <ul>
 *   <li>Only {@code api} packages may be imported across modules. Internal packages ({@code domain},
 *       {@code service}, {@code repository}, {@code web}) are strictly encapsulated inside each module.</li>
 *   <li>{@code catalog} is the core inventory foundation and must not depend on {@code reservation},
 *       {@code incident}, or {@code reassignment}.</li>
 *   <li>{@code reservation} must not depend on {@code incident} or {@code reassignment}.</li>
 *   <li>{@code incident} publishes domain events and must not depend on {@code reassignment}.</li>
 *   <li>No circular dependencies between top-level feature modules.</li>
 * </ul>
 */
@AnalyzeClasses(packages = "com.campus", importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleBoundaryArchitectureTest {

    private static final String[] FEATURE_MODULES = {"catalog", "incident", "reassignment", "reservation", "user"};

    // Rule 1: Cross-module communication must go through public api facades or events.
    // No module may access internal domain, service, repository, or web packages of another module.
    @ArchTest
    static void only_api_packages_are_accessible_across_modules(JavaClasses classes) {
        for (String sourceModule : FEATURE_MODULES) {
            for (String targetModule : FEATURE_MODULES) {
                if (!sourceModule.equals(targetModule)) {
                    noClasses()
                            .that()
                            .resideInAPackage("com.campus." + sourceModule + "..")
                            .should()
                            .dependOnClassesThat()
                            .resideInAnyPackage(
                                    "com.campus." + targetModule + ".domain..",
                                    "com.campus." + targetModule + ".service..",
                                    "com.campus." + targetModule + ".repository..",
                                    "com.campus." + targetModule + ".web..")
                            .because(String.format(
                                    "Module '%s' must not access internal packages of module '%s'; use '%s.api' instead",
                                    sourceModule, targetModule, targetModule))
                            .check(classes);
                }
            }
        }
    }

    // Rule 2: Catalog is the core asset foundation. It must not depend on reservations, incidents or reassignment.
    @ArchTest
    static final ArchRule catalog_does_not_depend_on_other_feature_modules = noClasses()
            .that()
            .resideInAPackage("com.campus.catalog..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("com.campus.reservation..", "com.campus.incident..", "com.campus.reassignment..")
            .because(
                    "Catalog is the foundational inventory module and must not know about bookings, incidents or reassignment");

    // Rule 3: Reservation manages bookings independently. It must not depend on incidents or reassignment.
    @ArchTest
    static final ArchRule reservation_does_not_depend_on_incident_or_reassignment = noClasses()
            .that()
            .resideInAPackage("com.campus.reservation..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("com.campus.incident..", "com.campus.reassignment..")
            .because("Reservations must not depend on incident reports or the auto-reassignment engine");

    // Rule 4: Incident reporting publishes AssetDamagedEvent; the reassignment engine listens independently.
    @ArchTest
    static final ArchRule incident_does_not_depend_on_reassignment = noClasses()
            .that()
            .resideInAPackage("com.campus.incident..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.campus.reassignment..")
            .because(
                    "Incident reporting publishes AssetDamagedEvent; the auto-reassignment engine listens independently");

    // Rule 5: Directed acyclic dependency graph between top-level modules.
    @ArchTest
    static final ArchRule no_cycles_between_modules = slices().matching("com.campus.(*)..")
            .should()
            .beFreeOfCycles()
            .because("The modular monolith must maintain a clean acyclic dependency structure");
}
