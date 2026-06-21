package com.yourorg.omp.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String username;

    @Column(length = 64)
    private String name;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(nullable = false, length = 16)
    private String role = "user";

    @Column(name = "identity_level", nullable = false, length = 16)
    private String identityLevel = "user";

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "disk_limit_mb", nullable = false)
    private int diskLimitMb = 100;

    @Column(name = "token_limit", nullable = false)
    private long tokenLimit = 0;

    @Column(name = "token_used", nullable = false)
    private long tokenUsed = 0;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getIdentityLevel() { return identityLevel; }
    public void setIdentityLevel(String identityLevel) { this.identityLevel = identityLevel; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getDiskLimitMb() { return diskLimitMb; }
    public void setDiskLimitMb(int diskLimitMb) { this.diskLimitMb = diskLimitMb; }
    public long getTokenLimit() { return tokenLimit; }
    public void setTokenLimit(long tokenLimit) { this.tokenLimit = tokenLimit; }
    public long getTokenUsed() { return tokenUsed; }
    public void setTokenUsed(long tokenUsed) { this.tokenUsed = tokenUsed; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastLoginAt() { return lastLoginAt; }
    public void setLastLoginAt(Instant lastLoginAt) { this.lastLoginAt = lastLoginAt; }

    public boolean isSuperAdmin() { return "super_admin".equals(identityLevel); }
    public boolean isAdmin() { return "admin".equals(identityLevel) || isSuperAdmin(); }
}