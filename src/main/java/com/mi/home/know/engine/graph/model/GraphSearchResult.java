package com.mi.home.know.engine.graph.model;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 一次图查询的结果：命中的节点 + 一跳邻接关系。
 * <p>
 * <b>只有一跳邻接</b>，不做变长路径遍历——第一版够用，延迟可控。
 * 节点顺序有含义：<b>直接命中的实体排在前面，邻接节点排在后面</b>，
 * 因为前者才是用户问题里问到的对象，后者只是"顺带把它提到的内容也捞进来"。
 *
 * @param nodes     命中节点（直接命中在前，邻接节点在后）
 * @param relations 一跳邻接关系，仅用于观测/日志，不直接进 prompt
 */
public record GraphSearchResult(List<GraphNode> nodes, List<GraphRelation> relations) {

    public static GraphSearchResult empty() {
        return new GraphSearchResult(List.of(), List.of());
    }

    /**
     * 图检索的最终产出：<b>chunk ID 列表</b>。
     * <p>
     * 这是本功能的核心不变量——图不产出"给模型看的内容"，只产出"该看哪些 chunk"。
     * 于是图结果与向量结果天然同构（都是 chunk），合并、去重、精排、引用回填全部复用既有管线，
     * 图不需要任何特殊通道。
     */
    public List<String> chunkIds() {
        Set<String> ordered = new LinkedHashSet<>();
        for (GraphNode node : nodes) {
            if (node.chunks() != null) {
                ordered.addAll(node.chunks());
            }
        }
        return new ArrayList<>(ordered);
    }
}
