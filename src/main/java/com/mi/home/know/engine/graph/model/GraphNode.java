package com.mi.home.know.engine.graph.model;

import java.util.List;

/**
 * 图谱节点（实体）。
 * <p>
 * <b>{@code chunks} 是整个图谱设计里最关键的一个字段</b>：它记录"哪些 chunk 提到了这个实体"。
 * 它是「图当路由器、不当内容源」这个架构成立的前提——运行时图检索命中实体后，
 * 靠这个字段回到 chunk 库取<b>正文</b>，图本身不产出给模型看的内容。
 * <p>
 * 一个实体被多个 chunk 提到时，chunks 是<b>追加累积</b>的（同一个文档内多块、多个文档都可能贡献）。
 *
 * @param name   实体名。按原文提取，不带引号等修饰；同一知识库内唯一（与 kbId 组成唯一键）
 * @param type   实体类型。必须来自 prompt 里的封闭清单，不允许模型自造
 * @param desc   实体在来源文本中的含义，一句话
 * @param chunks 溯源：提到该实体的 chunk ID 列表
 */
public record GraphNode(String name, String type, String desc, List<String> chunks) {

    /**
     * 由抽取结果构造节点，并把来源 chunk 挂上溯源。
     * <p>
     * 抽取配置产出的节点一定是"来自某一个 chunk"的，所以这里直接带上溯源；
     * 查询期从图里读回来的节点则携带累积后的完整 chunks。
     */
    public static GraphNode of(String name, String type, String desc, String chunkId) {
        return new GraphNode(name, type, desc, chunkId == null ? List.of() : List.of(chunkId));
    }
}
