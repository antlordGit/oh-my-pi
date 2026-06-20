package com.yourorg.omp.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "prompt_audit")
public class PromptAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "tenant_id")
    private Long tenantId;

    @Lob
    @Column(name = "prompt_text", columnDefinition = "MEDIUMTEXT")
    private String promptText;

    @Column(name = "prompt_images", columnDefinition = "TEXT")
    private String promptImagesJson;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt = Instant.now();

    public Long getId() { return id; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public String getPromptText() { return promptText; }
    public void setPromptText(String promptText) { this.promptText = promptText; }
    public String getPromptImagesJson() { return promptImagesJson; }
    public void setPromptImagesJson(String promptImagesJson) { this.promptImagesJson = promptImagesJson; }
    public Instant getSentAt() { return sentAt; }
    public void setSentAt(Instant sentAt) { this.sentAt = sentAt; }
}