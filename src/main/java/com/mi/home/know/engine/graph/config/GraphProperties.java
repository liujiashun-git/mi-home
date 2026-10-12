package com.mi.home.know.engine.graph.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 图谱抽取配置（入库侧 + 运行时共用一套）。
 * <p>
 * 做成一个 Properties 类而不是散落的 {@code @Value}，核心原因是
 * <b>{@code knowledgeBase} 必须是全局唯一的值</b>：入库侧按它写节点，运行时按它查节点，
 * 两处取值一旦不一致，表现就是"图里明明有数据，检索却什么都查不到"这种最难查的问题。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = GraphProperties.PREFIX)
public class GraphProperties {

    public static final String PREFIX = "know-engine.graph-extraction";

    /**
     * 总开关。关闭时入库不抽（每块两次 LLM 调用，整条流水线里最贵的一环），运行时也不查
     */
    private boolean enabled = true;

    /**
     * 图的一级命名空间（知识库）。
     * <p>
     * 本项目只有一个知识库，所以从配置取；多知识库场景改成从文档 entity 上取，
     * 其余逻辑（写入 MERGE 键、查询 WHERE、删除范围）都不用动。
     */
    private String knowledgeBase = "default";

    /**
     * 单块 LLM 调用失败的重试次数（不含首次）。
     * <p>
     * 注意：<b>"模型用散文拒绝输出"不在重试范围内</b>——那是合法结果，重试只会白烧额度。
     */
    private int maxRetry = 3;

    /**
     * 拼进 prompt 的单块正文上限，防止超长块把 prompt 撑爆
     */
    private int chunkTextMaxChars = 2000;

    /**
     * 单块最多落库多少实体（防模型刷量稀释图密度）
     */
    private int maxEntitiesPerChunk = 30;

    /**
     * 单块最多落库多少关系
     */
    private int maxRelationsPerChunk = 50;

    /**
     * 运行时：一次查询最多抽几个实体去查图。
     * 实体太多会把各自主体的邻接子图都拉进来，召回变宽、精度变差。
     */
    private int queryMaxEntities = 6;

    /**
     * 运行时：图查询最大返回行数，防一个高频实体把整张图拉出来
     */
    private int queryRowLimit = 300;

    /**
     * 运行时：一次图检索最多带回几个 chunk，与主干 pool-size 同量级
     */
    private int queryMaxChunks = 20;
}
