package com.yourorg.omp.maintenance;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 维护模式拦截器。
 * <p>
 * 当维护开关开启时，拦截「会触发新 LLM 工作」的请求，返回 503 Service Unavailable。
 * 白名单内的请求（登录、只读查询、管理操作、终止会话等）正常放行。
 */
@Component
public class MaintenanceInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceInterceptor.class);
    private static final AntPathMatcher MATCHER = new AntPathMatcher();
    private static final ObjectMapper OM = new ObjectMapper();

    /**
     * 维护模式下允许通过的请求（白名单）。
     * 格式："METHOD:/path/pattern"，或无 METHOD 前缀则匹配任意 HTTP 方法。
     */
    private static final List<String> ALLOW = List.of(
        // ---------- 认证 ----------
        "POST:/api/auth/**",
        // ---------- 读取（不触发新工作）----------
        "GET:/api/sessions/**",
        "GET:/api/repos/**",
        "GET:/api/system/**",
        // ---------- 会话清理（允许终止 / 归档）----------
        "POST:/api/sessions/*/abort",
        "POST:/api/sessions/*/archive",
        // ---------- IDE（仅返回 URL，不触发 omp）----------
        "POST:/api/sessions/*/ide/open",
        // ---------- 管理员操作（全部放行）----------
        "/admin/**",
        "/api/system/**"
    );

    private final MaintenanceService maintenance;

    public MaintenanceInterceptor(MaintenanceService maintenance) {
        this.maintenance = maintenance;
    }

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws IOException {
        if (!maintenance.isEnabled()) {
            return true;  // 非维护模式，直接放行
        }

        String method = request.getMethod();
        String path = request.getRequestURI();

        // 检查白名单
        if (isAllowed(method, path)) {
            return true;
        }

        // 拦截：返回 503 + 明确提示
        log.warn("[maintenance] blocked {} {} — system in maintenance mode", method, path);

        response.setStatus(503);
        response.setContentType("application/json;charset=UTF-8");
        String msg = "系统维护中，暂不接受新请求，请稍后再试";
        // 同时输出 error / message 两个字段，兼容前端不同 ErrorHandler
        // （ChatView 用 e.response.data.error，系统管理页用 e.response.data.message）
        Map<String, Object> body = Map.of(
            "code", 503,
            "error", msg,
            "message", msg,
            "maintenance", true,
            "retryAfterSeconds", 300
        );
        response.getWriter().write(OM.writeValueAsString(body));
        return false;
    }

    private boolean isAllowed(String method, String path) {
        for (String pattern : ALLOW) {
            if (pattern.indexOf(':') > 0) {
                // 带 METHOD 前缀的精确匹配
                String[] parts = pattern.split(":", 2);
                if (parts[0].equalsIgnoreCase(method) && MATCHER.match(parts[1], path)) {
                    return true;
                }
            } else {
                // 无前缀 = 任意 METHOD 都放行
                if (MATCHER.match(pattern, path)) {
                    return true;
                }
            }
        }
        return false;
    }
}
