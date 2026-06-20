package com.yourorg.omp.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reads {@code Authorization: Bearer <jwt>} on every request and populates SecurityContext if valid.
 * Skips when no header is present — SecurityConfig then decides via permitAll/authorize rules.
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    public JwtAuthFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        String authz = req.getHeader("Authorization");
        if (authz != null && authz.startsWith("Bearer ")) {
            String token = authz.substring("Bearer ".length());
            Optional<Claims> claims = jwtService.parse(token);
            claims.ifPresent(c -> {
                String username = c.getSubject();
                String role = c.get("role", String.class);
                String identityLevel = c.get("identityLevel", String.class);
                List<SimpleGrantedAuthority> authorities = new ArrayList<>();
                // base role 来自 User.role 字段（user/admin）
                authorities.add(new SimpleGrantedAuthority("ROLE_" + (role == null ? "USER" : role.toUpperCase())));
                // identityLevel 为 admin/super_admin 时也拥有 ADMIN 角色（满足 /admin、/system 路由守卫）
                if ("admin".equals(identityLevel) || "super_admin".equals(identityLevel)) {
                    authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
                }
                // 超级管理员拥有额外权限
                if ("super_admin".equals(identityLevel)) {
                    authorities.add(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"));
                }
                var auth = new UsernamePasswordAuthenticationToken(
                        username, null, authorities);
                auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(req));
                SecurityContextHolder.getContext().setAuthentication(auth);
            });
        }
        chain.doFilter(req, resp);
    }
}