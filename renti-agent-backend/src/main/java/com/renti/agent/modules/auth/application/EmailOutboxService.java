package com.renti.agent.modules.auth.application;

import com.renti.agent.infrastructure.persistence.entity.EmailOutboxEntity;
import com.renti.agent.infrastructure.persistence.repository.EmailOutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 模拟发信服务：与旧系统一致，邮件只写 email_outbox 表并打日志，不做真实投递。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailOutboxService {

    private final EmailOutboxRepository emailOutboxRepository;

    @Transactional
    public void send(String recipient, String purpose, String subject, String body) {
        var mail = new EmailOutboxEntity();
        mail.setRecipient(recipient);
        mail.setPurpose(purpose);
        mail.setSubject(subject);
        mail.setBody(body);
        emailOutboxRepository.save(mail);
        log.info("[email-outbox] purpose={} recipient={} subject={}", purpose, recipient, subject);
    }
}
