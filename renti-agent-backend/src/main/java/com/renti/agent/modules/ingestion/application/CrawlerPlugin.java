package com.renti.agent.modules.ingestion.application;

import java.util.List;
import java.util.Map;

/**
 * 房源采集插件接口：内置插件实现为 Spring 组件，由 {@link CrawlerPluginRegistry} 汇集。
 */
public interface CrawlerPlugin {

    /** 插件 ID（下划线风格，如 lianjia_shanghai） */
    String id();

    String label();

    String provider();

    String city();

    String description();

    Map<String, Object> defaultOptions();

    List<String> capabilities();

    /**
     * 执行采集：返回旧版风格结果 Map（ok/summary/jobId/...）。
     * 网络失败等异常应尽量在实现内降级为 {ok:false} 结果而非抛出。
     */
    Map<String, Object> run(Map<String, Object> options);

    /** 是否支持停止正在运行的采集（默认不支持） */
    default boolean supportsStop() {
        return false;
    }

    /** 请求停止当前运行；返回是否确实有运行中的任务被终止 */
    default boolean stopCurrentRun() {
        return false;
    }
}
