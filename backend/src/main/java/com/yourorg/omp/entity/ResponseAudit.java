package com.yourorg.omp.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "response_audit")
public class ResponseAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "message_id", length = 128)
    private String messageId;

    @Lob
    @Column(name = "full_text", columnDefinition = "TEXT")
    private String fullText;

    @Lob
    @Column(name = "thinking", columnDefinition = "TEXT")
    private String thinking;

    @Column(name = "is_error", nullable = false)
    private boolean isError;

    @Column(name = "stop_reason", length = 32)
    private String stopReason;

    @Column(name = "finished_at", nullable = false)
    private Instant finishedAt = Instant.now();

    public Long getId() { return id; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }
    public String getFullText() { return fullText; }
    public void setFullText(String fullText) { this.fullText = fullText; }
    public String getThinking() { return thinking; }
    public void setThinking(String thinking) { this.thinking = thinking; }
    public boolean isError() { return isError; }
    public void setError(boolean error) { isError = error; }
    public String getStopReason() { return stopReason; }
    public void setStopReason(String stopReason) { this.stopReason = stopReason; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
}