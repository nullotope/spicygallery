package io.github.nullotope.spicygallery;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** Spec §4: spicygallery-core is pure Java — Spring is confined to the server module. */
@AnalyzeClasses(packages = "io.github.nullotope.spicygallery",
        importOptions = ImportOption.DoNotIncludeTests.class)
class CorePurityTest {

    @ArchTest
    static final ArchRule noSpring = noClasses()
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework..");
}
