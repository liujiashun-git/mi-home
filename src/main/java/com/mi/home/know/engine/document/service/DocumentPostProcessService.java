package com.mi.home.know.engine.document.service;

/**
 * 文档后处理服务。
 * <p>
 * 主干入库（解析→切块→向量化）完成后的异步增强阶段，往索引里补
 * "语义形态对得上的文本"——用户的问题是问句/概览，库内正文是陈述句/细节，
 * 形态对不上换什么 embedding 模型都没用：
 * <ul>
 *   <li><b>文档摘要</b>：补"概览形态"，让概览型提问（"有没有讲质保的资料"）能被命中</li>
 *   <li><b>问题生成（doc2query）</b>：补"问句形态"，让口语化提问直接命中；
 *       生成的问题自带型号与参数词，顺带缓解向量对数字的失明</li>
 * </ul>
 * 两类任务共用同一套工程约束：异步执行不阻塞主干、幂等可重跑、
 * 输出过契约校验、失败有确定性兜底、每批检查文档是否已取消。
 */
public interface DocumentPostProcessService {

    /**
     * 异步执行后处理（摘要 + 问题生成）。任何失败只记日志，绝不影响文档可用性。
     *
     * @param docId     文档 ID
     * @param versionId 本次完成向量化的版本 ID
     */
    void postProcessAsync(Long docId, Long versionId);
}
