package cn.zjnu.a02.m2cognition;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cn.zjnu.a02.common.api.ApiResponse;
import cn.zjnu.a02.common.api.BizException;
import cn.zjnu.a02.common.api.ErrorCode;
import cn.zjnu.a02.m1perception.PerceptionService;
import cn.zjnu.a02.platform.security.AuthContext;

/**
 * M2 认知层接口：知识图谱查询、岗位能力模型、差距分析、行业趋势。
 *
 * <p>对应需求：FR-2.1 / FR-2.2 / FR-2.3 / FR-2.4。
 */
@RestController
@RequestMapping("/api")
public class CognitionController {

    private final KnowledgeGraphService graph;
    private final CognitionService cognition;
    private final PerceptionService perception;

    public CognitionController(KnowledgeGraphService graph, CognitionService cognition,
                               PerceptionService perception) {
        this.graph = graph;
        this.cognition = cognition;
        this.perception = perception;
    }

    @GetMapping("/jobs")
    public ApiResponse<List<Map<String, Object>>> jobs() {
        return ApiResponse.ok(graph.jobs());
    }

    @GetMapping("/jobs/{id}/competency")
    public ApiResponse<Map<String, Object>> competency(@PathVariable long id) {
        return ApiResponse.ok(Map.of(
                "job", graph.entity(id),
                "skills", graph.competencyModel(id),
                "note", "权重（1-5）来自岗位能力模型，用于匹配度与差距分析"));
    }

    @GetMapping("/skills/{id}/courses")
    public ApiResponse<List<Map<String, Object>>> skillCourses(@PathVariable long id) {
        return ApiResponse.ok(graph.coursesForSkill(id));
    }

    @GetMapping("/kg/stats")
    public ApiResponse<Map<String, Object>> kgStats() {
        return ApiResponse.ok(graph.stats());
    }

    @GetMapping("/kg/migration")
    public ApiResponse<Map<String, Object>> migration(@RequestParam long from, @RequestParam long to) {
        return ApiResponse.ok(graph.migrationMap(from, to));
    }

    @GetMapping("/certs")
    public ApiResponse<List<Map<String, Object>>> certs() {
        return ApiResponse.ok(graph.certificates());
    }

    /** 差距分析（FR-2.4）。未指定 jobId 时使用画像中的目标岗位。 */
    @GetMapping("/gap")
    public ApiResponse<Map<String, Object>> gap(@RequestParam(required = false) Long jobId) {
        long userId = AuthContext.userId();
        Long target = jobId != null ? jobId : perception.targetJobId(userId);
        if (target == null) {
            throw new BizException(ErrorCode.PROFILE_INCOMPLETE, "请先设置目标岗位");
        }
        return ApiResponse.ok(cognition.computeGap(userId, target));
    }

    @GetMapping("/gap/latest")
    public ApiResponse<Map<String, Object>> latestGap() {
        return ApiResponse.ok(cognition.latestGap(AuthContext.userId()));
    }

    /** 趋势分析（FR-2.2）：输出必带 sources。 */
    @PostMapping("/trends/analyze")
    public ApiResponse<Map<String, Object>> analyzeTrend(@RequestBody(required = false) TrendRequest request) {
        String topic = request == null ? null : request.topic();
        return ApiResponse.ok(cognition.analyzeTrend(AuthContext.userId(), topic));
    }

    @GetMapping("/trends")
    public ApiResponse<List<Map<String, Object>>> trends() {
        return ApiResponse.ok(cognition.trendHistory(AuthContext.userId()));
    }

    public record TrendRequest(String topic) {
    }
}
