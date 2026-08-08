package com.zhiyu.health.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.zhiyu.health.service.scheduling.SlotAccounting;
import com.zhiyu.health.service.scheduling.SlotCounter;
import org.neo4j.driver.Driver;
import org.springframework.data.redis.core.RedisTemplate;

/**
 * 分层与号源收口的架构护栏：任一规则变红即说明对应重构成果被回退。
 * 与 server-py 的 import-linter 分层契约互为双栈镜像，详细取舍见 ADR-0011。
 */
@AnalyzeClasses(packages = "com.zhiyu.health", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    /** controller 只做校验与装配，持久层访问一律经 service。 */
    @ArchTest
    static final ArchRule controller不直接依赖mapper = noClasses()
            .that()
            .resideInAPackage("..controller..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..mapper..");

    /** controller 不做缓存与号源计数，Redis 访问一律经 service 层收口。 */
    @ArchTest
    static final ArchRule controller不依赖RedisTemplate = noClasses()
            .that()
            .resideInAPackage("..controller..")
            .should()
            .dependOnClassesThat()
            .areAssignableTo(RedisTemplate.class);

    /** Neo4j 只允许 rule/ 的事实适配器访问；入口与业务 mapper 不得绕过确定性规则 seam。 */
    @ArchTest
    static final ArchRule controller和mapper不依赖Neo4j驱动 = noClasses()
            .that()
            .resideInAnyPackage("..controller..", "..mapper..")
            .should()
            .dependOnClassesThat()
            .areAssignableTo(Driver.class);

    /** 分层单向：service 不得反向依赖入口层。 */
    @ArchTest
    static final ArchRule service不依赖controller = noClasses()
            .that()
            .resideInAPackage("..service..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..controller..");

    /**
     * 号源只经 SlotAccounting：除 SlotAccounting 家族（含 Deduction/Refund 等内部类，
     * 字节码上是独立顶层类）与 SlotCounter 实现类（实现接口不算"使用号源"）外，
     * 任何类不得声明、注入或调用 SlotCounter。
     */
    @ArchTest
    static final ArchRule slotCounter只被SlotAccounting访问 = noClasses()
            .that(outsideSlotAccountingFamily())
            .and()
            .areNotAssignableTo(SlotCounter.class)
            .should()
            .dependOnClassesThat()
            .areAssignableTo(SlotCounter.class);

    private static DescribedPredicate<JavaClass> outsideSlotAccountingFamily() {
        return new DescribedPredicate<>("outside SlotAccounting (outer or nested class)") {
            @Override
            public boolean test(JavaClass javaClass) {
                // 内部类名形如 SlotAccounting$Deduction，统一以宿主类名前缀归并
                return !javaClass.getName().startsWith(SlotAccounting.class.getName());
            }
        };
    }
}
