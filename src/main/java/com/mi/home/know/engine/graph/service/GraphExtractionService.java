package com.mi.home.know.engine.graph.service;

import com.mi.home.know.engine.graph.model.GraphData;

/**
 * 图谱抽取服务（入库侧）。
 * <p>
 * 把"哪些 chunk 提到了哪些实体、这些实体之间是什么关系"从文档正文里抽出来写进图库，
 * 供运行时按实体名做多跳召回。
 * <p>
 * <b>成本定位</b>：这是整条后处理流水线里最贵的一环——<b>每个 chunk 两次 LLM 调用</b>，
 * 长文档几十上百块就是几百次调用。所以它必须是独立开关、独立线程池、独立重试策略，
 * 且失败绝不影响文档可用性。
 */
public interface GraphExtractionService {

    /**
     * 异步抽取一份文档的图谱。任何失败只记日志，不影响文档可用性。
     * <p>
     * 调用方（文档入库完成后）立即返回，实际工作在文档级任务池里 fan-out 到块级任务池。
     *
     * @param docId     文档 ID
     * @param versionId 本次完成向量化的版本 ID
     */
    void extractAsync(Long docId, Long versionId);

    /**
     * 同步抽取一段文本，<b>只返回结果、不落库</b>。
     * <p>
     * 给验收/调试用：不传文档、不碰数据库，直接看模型从一段文本里抽出了什么，
     * 方便单独调 prompt。
     *
     * @param text 待抽取文本
     * @return 抽取结果；模型拒绝输出时返回"空实体空关系"而不是抛错
     */
    GraphData extractText(String text);
}
