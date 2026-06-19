package com.yourorg.omp.security;

import com.yourorg.omp.entity.User;
import com.yourorg.omp.repo.UserRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

/**
 * Issues and validates HS256 JWTs for the orchestrator's own auth (independent of omp's per-process auth).
 *
 * <p>Bootstrap admin (from {@code app.omp.security.bootstrap-admin.*}) is created on startup
 * if no admin user exists. Used for the first login in a fresh deployment.
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    private final UserRepository users;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private SecretKey key;

    @Value("${app.omp.security.jwt-secret:local-dev-secret-please-change-32chars-or-more-yes}")
    private String jwtSecret;

    @Value("${app.omp.security.jwt-ttl-hours:24}")
    private int jwtTtlHours;

    @Value("${app.omp.security.bootstrap-admin.username:admin}")
    private String adminUsername;

    @Value("${app.omp.security.bootstrap-admin.password:admin}")
    private String adminPassword;

    public JwtService(UserRepository users) {
        this.users = users;
    }

    @PostConstruct
    public void init() {
        byte[] secret = jwtSecret.getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            throw new IllegalStateException("app.omp.security.jwt-secret must be at least 32 chars (HS256)");
        }
        this.key = Keys.hmacShaKeyFor(secret);
        bootstrapAdmin();
    }

    private void bootstrapAdmin() {
        if (adminUsername == null || adminPassword == null) return;
        Optional<User> existing = users.findByUsername(adminUsername);
        if (existing.isPresent()) {
            log.info("Bootstrap admin '{}' already exists; skipping", adminUsername);
            return;
        }
        User u = new User();
        u.setUsername(adminUsername);
        u.setPasswordHash(encoder.encode(adminPassword));
        u.setRole("admin");
        u.setEnabled(true);
        users.save(u);
        log.info("Bootstrap admin '{}' created", adminUsername);
    }

    public String issue(User user) {
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(jwtTtlHours * 3600L);
        return Jwts.builder()
                .subject(user.getUsername())
                .claim("uid", user.getId())
                .claim("role", user.getRole())
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(key)
                .compact();
    }

    public Optional<Claims> parse(String token) {
        try {
            return Optional.of(Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public BCryptPasswordEncoder encoder() {
        return encoder;
    }
}