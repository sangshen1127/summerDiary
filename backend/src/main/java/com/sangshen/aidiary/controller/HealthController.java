package com.sangshen.aidiary.controller;

import com.sangshen.aidiary.common.Result;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 项目自检接口（Phase 0 验收用）。
 *
 * <p>与 {@code /actuator/health} 的区别：
 * <ul>
 *   <li>{@code /actuator/health} 是运维接口，只回 {@code {"status":"UP"}}，
 *       用于 Docker / K8s 探针</li>
 *   <li>本接口回<b>应用自身信息</b>，用于确认「统一响应体 + 全局异常 + 时区」
 *       这几条骨架约定是否生效，以及前端能否正常打通</li>
 * </ul>
 *
 * <p>这不是业务接口，属于 Phase 0 的临时产物。后续保留也无害 ——
 * 部署后可以用它快速判断「服务活着吗、跑的是哪个 profile」。
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    private final String activeProfile;

    public HealthController(@Value("${spring.profiles.active:default}") String activeProfile) {
        this.activeProfile = activeProfile;
    }

    /**
     * GET /api/health
     *
     * <p>预期响应（HTTP 200）：
     * <pre>
     * {
     *   "code": 0,
     *   "message": "success",
     *   "data": {
     *     "status": "UP",
     *     "application": "ai-diary-backend",
     *     "profile": "dev",
     *     "server_time_utc": "2025-03-15T08:30:00Z",
     *     "timezone": "UTC"
     *   },
     *   "timestamp": 1730000000000
     * }
     * </pre>
     *
     * <p><b>为什么要把时间返回到秒以外的精度</b>：验证 UTC 约定。
     * {@code server_time_utc} 带 {@code Z} 后缀说明序列化成了 ISO-8601 UTC，
     * 这是开发文档 §4.4 的要求。
     */
    @GetMapping
    public Result<Map<String, Object>> health() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "UP");
        data.put("application", "ai-diary-backend");
        data.put("profile", activeProfile);
        // Instant 序列化为 ISO-8601 UTC（末尾有 Z），验证时区约定
        data.put("server_time_utc", Instant.now());
        data.put("timezone", "UTC");
        return Result.ok(data);
    }
}
