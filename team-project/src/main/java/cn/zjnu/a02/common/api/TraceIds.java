package cn.zjnu.a02.common.api;

import org.slf4j.MDC;

/** traceId 的生成与透传（TraceIdFilter 写入 MDC，API 响应体读取）。 */
public final class TraceIds {

    public static final String MDC_KEY = "traceId";
    public static final String HEADER = "X-Trace-Id";

    private TraceIds() {
    }

    public static String current() {
        String id = MDC.get(MDC_KEY);
        return id == null ? "-" : id;
    }

    public static void set(String traceId) {
        MDC.put(MDC_KEY, traceId);
    }

    public static void clear() {
        MDC.remove(MDC_KEY);
    }
}
