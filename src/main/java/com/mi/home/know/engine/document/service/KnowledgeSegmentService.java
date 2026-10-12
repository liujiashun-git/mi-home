package com.mi.home.know.engine.document.service;

import com.mi.home.know.engine.document.entity.KnowledgeSegment;
import com.baomidou.mybatisplus.extension.service.IService;

import java.io.Serializable;

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
