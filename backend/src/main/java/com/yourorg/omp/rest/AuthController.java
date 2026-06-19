package com.yourorg.omp.rest;

import com.yourorg.omp.entity.User;
import com.yourorg.omp.repo.UserRepository;
import com.yourorg.omp.security.CurrentUser;
import com.yourorg.omp.security.JwtService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationManager authManager;
    private final UserRepository users;
    private final JwtService jwt;
    private final CurrentUser currentUser;

    public AuthController(AuthenticationManager authManager, UserRepository users,
                          JwtService jwt, CurrentUser currentUser) {
        this.authManager = authManager;
        this.users = users;
        this.jwt = jwt;
        this.currentUser = currentUser;
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}
    public record LoginResponse(String token, long expiresInSeconds, String username, String role) {}

    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest req) {
        try {
            authManager.authenticate(new UsernamePasswordAuthenticationToken(req.username(), req.password()));
        } catch (BadCredentialsException | DisabledException e) {
            throw new RuntimeException("Invalid credentials");
        }
        User u = users.findByUsername(req.username()).orElseThrow();
        u.setLastLoginAt(Instant.now());
        users.save(u);
        String token = jwt.issue(u);
        return new LoginResponse(token, 24 * 3600, u.getUsername(), u.getRole());
    }

    @GetMapping("/me")
    public Map<String, Object> me() {
        User u = currentUser.require();
        return Map.of(
                "id", u.getId(),
                "username", u.getUsername(),
                "role", u.getRole()
        );
    }
}