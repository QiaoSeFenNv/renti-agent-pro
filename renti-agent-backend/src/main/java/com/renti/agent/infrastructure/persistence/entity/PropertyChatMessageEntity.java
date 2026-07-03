package com.renti.agent.infrastructure.persistence.entity;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 房源问答消息（对齐旧 renti_property_chat_messages 表结构）。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "renti_property_chat_messages", indexes = {
        @Index(name = "idx_property_chat_messages_session", columnList = "session_id, created_at")
})
public class PropertyChatMessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 20)
    private String role;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<Map<String, Object>> citations = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tool_trace", columnDefinition = "jsonb")
    private List<Map<String, Object>> toolTrace = new ArrayList<>();

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
