package com.raster.moneycraft.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Enforces MoneyCraft bounded-context layering, exact decimal discipline (zero float/double),
 * modern java.time types, and air-gapped outbound connector isolation.
 */
class ArchUnitModuleBoundaryTest {

    private static JavaClasses serverClasses;

    @BeforeAll
    static void importServerClasses() {
        serverClasses = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.raster.moneycraft");
    }

    @Test
    void foundationMustNotDependOnDownstreamErpContexts() {
        ArchRule rule = ArchRuleDefinition.noClasses()
            .that().resideInAPackage("com.raster.moneycraft.foundation..")
            .should().dependOnClassesThat()
            .resideInAnyPackage(
                "com.raster.moneycraft.accounting..",
                "com.raster.moneycraft.inventory..",
                "com.raster.moneycraft.statutory..",
                "com.raster.moneycraft.banking..",
                "com.raster.moneycraft.reporting..",
                "com.raster.moneycraft.ops.."
            );
        rule.check(serverClasses);
    }

    @Test
    void accountingMustDependOnlyOnFoundationAndNeverOnDownstreamContexts() {
        ArchRule rule = ArchRuleDefinition.noClasses()
            .that().resideInAPackage("com.raster.moneycraft.accounting..")
            .should().dependOnClassesThat()
            .resideInAnyPackage(
                "com.raster.moneycraft.inventory..",
                "com.raster.moneycraft.statutory..",
                "com.raster.moneycraft.banking..",
                "com.raster.moneycraft.reporting..",
                "com.raster.moneycraft.migration..",
                "com.raster.moneycraft.exchange..",
                "com.raster.moneycraft.ops.."
            );
        rule.check(serverClasses);
    }

    @Test
    void inventoryMustDependOnlyOnFoundationAndAccountingAndNeverOnDownstreamContexts() {
        ArchRule rule = ArchRuleDefinition.noClasses()
            .that().resideInAPackage("com.raster.moneycraft.inventory..")
            .should().dependOnClassesThat()
            .resideInAnyPackage(
                "com.raster.moneycraft.statutory..",
                "com.raster.moneycraft.banking..",
                "com.raster.moneycraft.reporting..",
                "com.raster.moneycraft.migration..",
                "com.raster.moneycraft.exchange..",
                "com.raster.moneycraft.ops.."
            );
        rule.check(serverClasses);
    }

    @Test
    void opsMustNotDependOnBusinessAccountingContexts() {
        ArchRule rule = ArchRuleDefinition.noClasses()
            .that().resideInAPackage("com.raster.moneycraft.ops..")
            .should().dependOnClassesThat()
            .resideInAnyPackage(
                "com.raster.moneycraft.accounting..",
                "com.raster.moneycraft.inventory..",
                "com.raster.moneycraft.statutory..",
                "com.raster.moneycraft.banking..",
                "com.raster.moneycraft.reporting.."
            );
        rule.check(serverClasses);
    }

    @Test
    void noFloatingPointFieldsAnywhereInServer() {
        ArchRule rule = ArchRuleDefinition.fields()
            .that().areDeclaredInClassesThat().resideInAPackage("com.raster.moneycraft..")
            .should().notHaveRawType(float.class)
            .andShould().notHaveRawType(Float.class)
            .andShould().notHaveRawType(double.class)
            .andShould().notHaveRawType(Double.class);
        rule.check(serverClasses);
    }

    @Test
    void noLegacyDateOrCalendarFieldsAnywhereInServer() {
        ArchRule rule = ArchRuleDefinition.fields()
            .that().areDeclaredInClassesThat().resideInAPackage("com.raster.moneycraft..")
            .should().notHaveRawType("java.util.Date")
            .andShould().notHaveRawType("java.sql.Date")
            .andShould().notHaveRawType("java.util.Calendar");
        rule.check(serverClasses);
    }

    @Test
    void outboundHttpClientUsageRestrictedToAuthOidcAndConnectorPackages() {
        ArchRule rule = ArchRuleDefinition.noClasses()
            .that().resideOutsideOfPackages(
                "com.raster.moneycraft.ops.connector..",
                "com.raster.moneycraft.foundation.auth.."
            )
            .should().dependOnClassesThat()
            .resideInAnyPackage("java.net.http..", "org.springframework.web.client..");
        rule.check(serverClasses);
    }
}
