package com.mi.home.know.engine.graph.store;

import com.mi.home.know.engine.graph.model.GraphData;
import com.mi.home.know.engine.graph.model.GraphSearchResult;

import java.util.Collection;

/**
 * 图引擎接口——<b>故意只有三个方法</b>。
 * <p>
 * 图检索的第一版<b>不需要</b>图算法（最短路径、社区发现、变长遍历），
 * 就是「按实体名找节点 + 一跳邻接」。接口越小，换图引擎（Neo4j / Nebula / 内存实现）越便宜，
 * 也越不容易把图的功能膨胀成第二个检索系统。
 * <p>
 * 命名空间只做了<b>一级</b>（kbId）。原始设计是两级（知识库 → 文档），
 * 目的是"删某个文档时能精确清掉它的子图"。本项目改为<b>用 chunk 溯源做二级粒度</b>：
 * chunkId 是雪花 ID、全局唯一，按 chunkId 清理比按文档名清理更精确，
 * 且天然满足"只有节点的 chunks 被删空才删节点"的引用计数语义。
 */
public interface GraphStore {

    /**
     * 图存储当前是否可用。
     * <p>
     * 图是可选组件：Neo4j 没起来时，入库侧跳过整段抽取（<b>一次 LLM 调用都不烧</b>），
     * 运行时跳过图检索，主干（向量 ∥ 关键词）不受任何影响。
     */
    boolean isAvailable();

    /**
     * 写入一次抽取的产物：节点带 chunks 溯源，关系按两端名字 MERGE。
     * <p>
     * 一个 chunk 的写入放在一个事务里，失败整体回滚，避免留下半个图的脏状态。
     *
     * @param kbId  一级命名空间（知识库）
     * @param graph 一次抽取的实体 + 关系，节点的 {@code chunks} 即本次来源 chunk
     */
    void addGraph(String kbId, GraphData graph);

    /**
     * 按 chunk ID 清理溯源：从所有节点的 chunks 数组里摘掉这些 chunk，
     * <b>只有 chunks 被删空的节点才真正删除</b>（其他文档/其他块可能也提到同一实体）。
     * <p>
     * 这是引用计数式清理，不是"删一个 namespace"那么简单——文档删除、版本失效时调用。
     *
     * @param kbId     一级命名空间
     * @param chunkIds 要摘除的 chunk ID（所属文档被删除 / 版本被替换）
     */
    void delGraph(String kbId, Collection<String> chunkIds);

    /**
     * 按实体名查节点，并带回一跳邻接节点。
     *
     * @param kbId        一级命名空间
     * @param entityNames 实体名列表（大小写不敏感）
     * @param rowLimit    返回行数上限，防止一个高频实体把整张图拉出来
     * @return 命中节点（直接命中在前、邻接在后）+ 一跳关系
     */
    GraphSearchResult searchNodes(String kbId, Collection<String> entityNames, int rowLimit);
}
