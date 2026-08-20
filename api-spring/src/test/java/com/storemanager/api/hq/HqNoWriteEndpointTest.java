package com.storemanager.api.hq;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

/**
 * H8 회귀 방지 — hq 패키지의 컴파일된 클래스 전체를 스캔해 POST/PUT/PATCH/DELETE 매핑이
 * 단 하나도 없음을 단언한다. 본부는 조회 전용이다. 나중에 누군가 실수로(또는 다른 에이전트가)
 * 쓰기 엔드포인트를 추가하면 이 테스트가 실패해야 한다.
 */
class HqNoWriteEndpointTest {

    @SuppressWarnings("unchecked")
    private static final List<Class<? extends Annotation>> WRITE_ANNOTATIONS = List.of(
            PostMapping.class, PutMapping.class, PatchMapping.class, DeleteMapping.class);

    @Test
    void hq_패키지에는_쓰기_매핑이_하나도_없다() throws Exception {
        List<Class<?>> classes = loadHqPackageClasses();
        // 스캔 자체가 아무것도 못 찾아 테스트가 거짓양성으로 통과하는 것을 방지한다.
        assertThat(classes).isNotEmpty();
        assertThat(classes).anyMatch(c -> c.getSimpleName().equals("HqController"));

        List<String> violations = new ArrayList<>();
        for (Class<?> clazz : classes) {
            for (Method m : clazz.getDeclaredMethods()) {
                for (Class<? extends Annotation> writeAnno : WRITE_ANNOTATIONS) {
                    if (m.isAnnotationPresent(writeAnno)) {
                        violations.add(clazz.getSimpleName() + "#" + m.getName() + " -> @" + writeAnno.getSimpleName());
                    }
                }
                RequestMapping rm = m.getAnnotation(RequestMapping.class);
                if (rm != null) {
                    for (RequestMethod method : rm.method()) {
                        if (method != RequestMethod.GET) {
                            violations.add(clazz.getSimpleName() + "#" + m.getName()
                                    + " -> @RequestMapping(method=" + method + ")");
                        }
                    }
                }
            }
        }
        assertThat(violations).as("hq 패키지는 조회 전용이어야 합니다. 쓰기 매핑 발견: %s", violations).isEmpty();
    }

    /** com.storemanager.api.hq 패키지(테스트 클래스 포함)의 컴파일된 .class 를 전부 로드한다. */
    private static List<Class<?>> loadHqPackageClasses() throws Exception {
        String packagePath = "com/storemanager/api/hq";
        List<Class<?>> result = new ArrayList<>();
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        Enumeration<URL> resources = loader.getResources(packagePath);
        while (resources.hasMoreElements()) {
            URL resource = resources.nextElement();
            File dir = new File(resource.toURI());
            if (!dir.isDirectory()) {
                continue;
            }
            File[] files = dir.listFiles((d, name) -> name.endsWith(".class"));
            if (files == null) {
                continue;
            }
            for (File file : files) {
                String simpleName = file.getName().substring(0, file.getName().length() - ".class".length());
                if (simpleName.contains("$")) {
                    continue; // 중첩 클래스(레코드 등)는 바깥 클래스로 이미 로드된다
                }
                result.add(Class.forName("com.storemanager.api.hq." + simpleName));
            }
        }
        return result;
    }
}
