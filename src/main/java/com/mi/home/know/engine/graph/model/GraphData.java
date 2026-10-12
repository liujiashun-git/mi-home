package com.mi.home.know.engine.graph.model;

import java.util.List;

/**
 * 一次抽取的产物：一个 chunk 抽出来的实体 + 关系。
 * <p>
 * {@code text} 是来源正文，只用于日志与排查，图中不存正文——
 * 正文只有一份，永远在 chunk 库里。
 */
public record GraphData(String text, List<GraphNode> nodes, List<GraphRelation> relations) {

    public boolean isEmpty() {
        return (nodes == null || nodes.isEmpty()) && (relations == null || relations.isEmpty());
    }
}
