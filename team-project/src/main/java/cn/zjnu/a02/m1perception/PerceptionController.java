package cn.zjnu.a02.m1perception;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cn.zjnu.a02.common.api.ApiResponse;
import cn.zjnu.a02.platform.privacy.DataErasureService;
import cn.zjnu.a02.platform.security.AuthContext;

/**
 * M1 感知层接口：个人基础信息、隐私授权、能力测评、简历抽取。
 *
 * <p>对应需求：FR-1.2 / FR-1.3 / FR-1.4 / FR-7.4。
 */
@RestController
@RequestMapping("/api")
public class PerceptionController {

    private final PerceptionService perception;
    private final DataErasureService dataErasure;

    public PerceptionController(PerceptionService perception, DataErasureService dataErasure) {
        this.perception = perception;
        this.dataErasure = dataErasure;
    }

    /** 公共健康检查（不鉴权）。 */
    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health() {
        return ApiResponse.ok(Map.of("status", "UP", "app", "A02 职业导航与终身学习伙伴系统"));
    }

    /** 当前登录用户 + 建档状态。 */
    @GetMapping("/users/me")
    public ApiResponse<Map<String, Object>> me() {
        var user = AuthContext.require();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", user.userId());
        result.put("username", user.username());
        result.put("role", user.role());
        result.put("profileComplete", perception.isProfileComplete(user.userId()));
        result.put("context", perception.userContext(user.userId()));
        return ApiResponse.ok(result);
    }

    @GetMapping("/users/me/profile")
    public ApiResponse<Map<String, Object>> getProfile() {
        return ApiResponse.ok(perception.getProfile(AuthContext.userId()));
    }

    @PutMapping("/users/me/profile")
    public ApiResponse<Map<String, Object>> saveProfile(@RequestBody PerceptionService.ProfileInput input) {
        return ApiResponse.ok(perception.saveProfile(AuthContext.userId(), input));
    }

    @GetMapping("/users/me/consent")
    public ApiResponse<Map<String, Object>> getConsent() {
        return ApiResponse.ok(perception.getConsent(AuthContext.userId()));
    }

    @PostMapping("/users/me/consent")
    public ApiResponse<Map<String, Object>> saveConsent(@RequestBody ConsentRequest request) {
        return ApiResponse.ok(perception.saveConsent(AuthContext.userId(),
                Boolean.TRUE.equals(request.privacyAgreed()),
                Boolean.TRUE.equals(request.aiNoticeAgreed())));
    }

    /** 数据可携带（FR-7.4）。 */
    @GetMapping("/users/me/data")
    public ApiResponse<Map<String, Object>> exportData() {
        return ApiResponse.ok(dataErasure.export(AuthContext.userId()));
    }

    /** 数据删除（FR-7.4）：级联删除并返回回执。 */
    @DeleteMapping("/users/me/data")
    public ApiResponse<Map<String, Object>> eraseData() {
        return ApiResponse.ok(dataErasure.erase(AuthContext.userId()));
    }

    @GetMapping("/profile/dimensions")
    public ApiResponse<List<Map<String, String>>> dimensions() {
        return ApiResponse.ok(PerceptionService.RADAR_DIMENSIONS);
    }

    @GetMapping("/assessments/questions")
    public ApiResponse<List<Map<String, Object>>> questions() {
        return ApiResponse.ok(perception.questions());
    }

    @PostMapping("/assessments")
    public ApiResponse<Map<String, Object>> submitAssessment(@RequestBody AssessmentRequest request) {
        return ApiResponse.ok(perception.submitAssessment(AuthContext.userId(), request.answers()));
    }

    @GetMapping("/assessments/latest")
    public ApiResponse<Map<String, Object>> latestAssessment() {
        return ApiResponse.ok(perception.latestAssessment(AuthContext.userId()));
    }

    @PostMapping("/resume/extract")
    public ApiResponse<Map<String, Object>> extractResume(@RequestBody ResumeRequest request) {
        return ApiResponse.ok(perception.extractResumeTags(AuthContext.userId(), request.text()));
    }

    @GetMapping("/resume/tags")
    public ApiResponse<List<Map<String, Object>>> resumeTags() {
        return ApiResponse.ok(perception.resumeTags(AuthContext.userId()));
    }

    @PutMapping("/resume/tags")
    public ApiResponse<Map<String, Object>> confirmResumeTags(@RequestBody ResumeTagsRequest request) {
        return ApiResponse.ok(perception.confirmResumeTags(AuthContext.userId(), request.tags()));
    }

    public record ConsentRequest(Boolean privacyAgreed, Boolean aiNoticeAgreed) {
    }

    public record AssessmentRequest(List<PerceptionService.AnswerInput> answers) {
    }

    public record ResumeRequest(String text) {
    }

    public record ResumeTagsRequest(List<String> tags) {
    }
}
