package cn.zjnu.a02;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 零依赖测试执行器。
 *
 * <p><b>为什么需要它</b>：本机为离线环境，本地 Maven 仓库缺少
 * {@code surefire-junit-platform} 与 {@code junit-platform-launcher}，
 * 导致 {@code mvn test} 无法在离线状态下启动 JUnit 5。
 *
 * <p>本执行器不做任何"简化版测试"：它通过反射**直接调用真实的 JUnit 测试方法**
 * （即 {@code RadarCalculatorTest}、{@code StructuredOutputContractTest}、
 * {@code SecurityAndPrivacyTest} 中的 {@code @Test} 方法），断言仍由 JUnit API 抛出。
 * 因此在离线机器上也能得到与 {@code mvn test} 等价的结论；
 * 联网环境下正常使用 {@code mvn test} 即可。
 *
 * <p>用法：
 * <pre>
 *   mvn -o test-compile
 *   java -cp "target/classes;target/test-classes;&lt;test-classpath&gt;" cn.zjnu.a02.SelfTestRunner
 * </pre>
 */
public final class SelfTestRunner {

    /** 需要执行的测试类（与 JUnit 测试源保持一致）。 */
    private static final List<String> TEST_CLASSES = List.of(
            "cn.zjnu.a02.m3decision.RadarCalculatorTest",
            "cn.zjnu.a02.ai.StructuredOutputContractTest",
            "cn.zjnu.a02.platform.SecurityAndPrivacyTest");

    public static void main(String[] args) throws Exception {
        int passed = 0;
        List<String> failed = new ArrayList<>();

        System.out.println("A02 单元测试自检（离线执行器）");
        System.out.println("=".repeat(74));

        for (String className : TEST_CLASSES) {
            Class<?> testClass = Class.forName(className);
            System.out.println("\n▸ " + testClass.getSimpleName());

            // 测试类为包级可见（与 JUnit 惯例一致），需开放访问
            java.lang.reflect.Constructor<?> constructor = testClass.getDeclaredConstructor();
            constructor.setAccessible(true);
            Object instance = constructor.newInstance();
            for (Method method : testClass.getDeclaredMethods()) {
                if (method.getAnnotation(Test.class) == null) {
                    continue;
                }
                method.setAccessible(true);
                String label = displayNameOf(method);
                long start = System.nanoTime();
                try {
                    method.invoke(instance);
                    passed++;
                    System.out.printf("  ✓ %-52s %5.1f ms%n", label, (System.nanoTime() - start) / 1e6);
                } catch (Exception e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    failed.add(testClass.getSimpleName() + " · " + label
                            + "\n      " + cause.getClass().getSimpleName() + ": " + cause.getMessage());
                    System.out.printf("  ✗ %-52s %s%n", label, cause.getClass().getSimpleName());
                }
            }
        }

        System.out.println("\n" + "=".repeat(74));
        System.out.printf("通过 %d 项，失败 %d 项%n", passed, failed.size());
        if (!failed.isEmpty()) {
            System.out.println("\n失败明细：");
            failed.forEach(f -> System.out.println("  ✗ " + f));
            System.exit(1);
        }
        System.out.println("✅ 雷达公式 / 结构化输出契约 / 安全与隐私 —— 全部断言通过。");
    }

    private static String displayNameOf(Method method) {
        DisplayName annotation = method.getAnnotation(DisplayName.class);
        return annotation == null ? method.getName() : annotation.value();
    }
}
