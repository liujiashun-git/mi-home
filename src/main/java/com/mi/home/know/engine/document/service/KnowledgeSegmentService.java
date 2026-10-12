package com.mi.home.know.engine.document.service;

import com.mi.home.know.engine.document.entity.KnowledgeSegment;
import com.baomidou.mybatisplus.extension.service.IService;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 知识片段表 Service 接口
 */
public interface KnowledgeSegmentService extends IService<KnowledgeSegment> {

    public String getTextByChunkId(Serializable chunkId);

    /**
     * 按 chunkId 查完整分段（含 metadata JSON）。
     * chunkId 为雪花 ID、全局唯一。问题条目（doc2query）命中后靠它换回原 chunk。
     *
     * @param chunkId 分片 ID
     * @return 分段，不存在返回 null
     */
    KnowledgeSegment getByChunkId(String chunkId);

    /**
     * 按 chunkId 批量查分段，key 为 chunkId。
     * <p>
     * 图检索的产出是一批 chunk ID，逐个 {@link #getByChunkId} 会打成 N 次单查；
     * 这里一次 IN 查询取回，顺序由调用方按 chunk ID 列表自己还原（图命中的先后有含义）。
     *
     * @param chunkIds 分片 ID 列表
     * @return chunkId → 分段；不存在的 ID 不会出现在结果里
     */
    Map<String, KnowledgeSegment> getByChunkIds(Collection<String> chunkIds);

    /**
     * 查某个文档下所有分段的 chunkId。
     * 删除文档时用它反查"这篇文档给图贡献了哪些块"，再按块清理图溯源。
     *
     * @param docId 文档 ID
     * @return chunkId 列表
     */
    List<String> listChunkIdsByDocId(Long docId);

    /**
     * 查某个文档版本下所有分段的 chunkId。
     * 版本失效（影子更新切版本）时按版本清理图溯源，避免旧版本的块继续被图召回。
     *
     * @param docId     文档 ID
     * @param versionId 版本 ID
     * @return chunkId 列表
     */
    List<String> listChunkIdsByDocIdAndVersion(Long docId, Long versionId);

    /**
     * 根据文档ID逻辑删除所有分段
     *
     * @param docId 文档ID
     * @return 删除的分段数量
     */
    int removeSegmentsByDocId(Long docId);

    /**
     * 根据ID更新分段信息
     * @param entity
     * @param updateVectorStore
     * @return
     */
    public boolean updateById(KnowledgeSegment entity, Boolean updateVectorStore);
}
