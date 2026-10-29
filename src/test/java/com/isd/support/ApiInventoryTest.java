package com.isd.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 接口清单快照（文档数字的守护者）。
 *
 * 为什么需要它：README 与面试资料里写着"共 N 个接口"，但接口是会变的——
 * 第 34 个接口加进来时没有人会记得改简历。把清单做成快照文件后，
 * 接口一增删 CI 就红，维护者只能看见"同步快照和 README"这句话，数字不会再悄悄漂移。
 *
 * 口径：只收以 {@code /api} 开头的路由。{@code SpaForwardController} 的页面转发
 * （如 {@code /tickets/**}）不算 REST 接口，天然被排除；{@code /api/ping} 算，
 * 它是给探活/压测用的真实接口。
 */
@SpringBootTest
class ApiInventoryTest extends AbstractIntegrationTest {

    /** 快照路径：放 test/resources，评审时能直接看到接口增删 */
    private static final String SNAPSHOT = "api-endpoints.txt";

    @Autowired
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    @DisplayName("REST 接口清单与快照一致（增删接口必须同步快照与 README）")
    void apiRoutesMatchSnapshot() throws IOException {
        List<String> actual = collectApiRoutes();
        assertThat(actual).as("没扫到任何 /api 路由，说明取路由的方式失效了").isNotEmpty();

        ClassPathResource snapshot = new ClassPathResource(SNAPSHOT);
        if (!snapshot.exists()) {
            throw new AssertionError("缺少路由快照 src/test/resources/" + SNAPSHOT
                    + "，把下面 " + actual.size() + " 行原样保存即可：\n" + String.join("\n", actual));
        }

        List<String> expected = snapshot.getContentAsString(StandardCharsets.UTF_8)
                .lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .toList();

        assertThat(actual)
                .as("接口清单与快照不一致：改接口请同步 src/test/resources/%s，并更新 README 里「共 N 个接口」的数字",
                        SNAPSHOT)
                .containsExactlyElementsOf(expected);
    }

    /**
     * 展开成 "METHOD /path" 并排序：排序保证跨平台稳定，
     * 展开是因为一个方法可以同时声明 GET/POST 与多个路径。
     */
    private List<String> collectApiRoutes() {
        Set<String> routes = new TreeSet<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = entry.getKey();
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            for (String pattern : info.getPatternValues()) {
                if (!pattern.startsWith("/api")) {
                    continue;
                }
                if (methods.isEmpty()) {
                    // 没限定方法的映射（极少）显式标 ANY，避免快照里出现无法解释的裸路径
                    routes.add("ANY " + pattern);
                    continue;
                }
                for (RequestMethod method : methods) {
                    routes.add(method.name() + " " + pattern);
                }
            }
        }
        return new ArrayList<>(routes);
    }
}
