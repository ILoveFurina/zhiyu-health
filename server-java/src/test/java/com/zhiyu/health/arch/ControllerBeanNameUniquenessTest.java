package com.zhiyu.health.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.beans.Introspector;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.web.bind.annotation.RestController;

/**
 * 票 56 启动事故回归：B/C 端同名 controller 类的默认 bean 名冲突会让真实启动直接失败，
 * 而套件全是 sliced 测试（@WebMvcTest 只装单 controller），永远不启动全上下文，
 * 必须显式钉住 controller bean 名跨包唯一（含显式命名值）。
 */
class ControllerBeanNameUniquenessTest {

    @Test
    void restControllerBeanNamesAreUniqueAcrossPackages() throws Exception {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Map<String, String> owners = new HashMap<>();
        for (Resource resource : resolver.getResources("classpath*:com/zhiyu/health/controller/**/*.class")) {
            String path = resource.getURI().toString();
            String className = path.substring(path.indexOf("com/zhiyu/health"))
                    .replace('/', '.')
                    .replace(".class", "");
            Class<?> type = Class.forName(className);
            RestController annotation = type.getAnnotation(RestController.class);
            if (annotation == null) {
                continue;
            }
            String beanName =
                    annotation.value().isEmpty() ? Introspector.decapitalize(type.getSimpleName()) : annotation.value();
            String previous = owners.put(beanName, type.getName());
            assertThat(previous)
                    .as("controller bean 名 %s 冲突：%s 与 %s", beanName, previous, type.getName())
                    .isNull();
        }
        assertThat(owners).as("应扫描到至少一个 @RestController").isNotEmpty();
    }
}
