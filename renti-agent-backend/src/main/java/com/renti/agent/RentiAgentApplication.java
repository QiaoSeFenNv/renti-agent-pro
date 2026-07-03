package com.renti.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Renti Agent 平台后端启动类。
 *
 * <p>承载地图驱动租房决策平台的业务 API：用户认证、城市与房源库、
 * 采集审核流水线、RAG/图谱管理以及 AI Agent 编排的接入层。</p>
 */
@SpringBootApplication
@EnableScheduling
public class RentiAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(RentiAgentApplication.class, args);
    }
}
