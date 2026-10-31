package app.basera.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** Guard rails that keep the multi-tenancy design from eroding as the code base grows. */
@AnalyzeClasses(packages = "app.basera", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    /** The RLS-bypassing pool must stay reachable only from the packages that legitimately run before a tenant exists. */
    @ArchTest
    static final ArchRule platformPoolIsReachableOnlyFromPlatformAuthAndTenancy =
            noClasses().that().resideOutsideOfPackages("app.basera.platform..", "app.basera.auth..", "app.basera.tenancy..")
                    .should().dependOnClassesThat().haveFullyQualifiedName("app.basera.platform.PlatformJdbc")
                    .because("PlatformJdbc connects as basera_platform, which bypasses Row Level Security");

    @ArchTest
    static final ArchRule controllersDoNotTouchRepositoriesDirectly =
            noClasses().that().haveSimpleNameEndingWith("Controller")
                    .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository")
                    .because("controllers go through services so transactions and tenant context are always in place");

    @ArchTest
    static final ArchRule businessModulesDoNotUseTheDataSourceDirectly =
            noClasses().that().resideInAnyPackage("app.basera.identity..", "app.basera.tenant..")
                    .should().dependOnClassesThat().haveFullyQualifiedName("javax.sql.DataSource")
                    .because("raw connections would bypass the tenant-aware transaction manager");
}
