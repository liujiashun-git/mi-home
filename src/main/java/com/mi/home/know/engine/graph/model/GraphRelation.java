package com.mi.home.know.engine.graph.model;

/**
 * 图谱关系。
 * <p>
 * {@code source} / {@code target} 存的是<b>两端实体的名字</b>而不是节点 ID：
 * 抽取阶段还没有节点身份，只有从原文里读到的名字；
 * 写库时再按 {@code (kbId, name)} MERGE 到具体节点上。
 * <p>
 * 关系必须来自原文，禁止模型编造；没有关系就返回空列表——宁可图稀疏，不要图污染。
 *
 * @param source   起端实体名
 * @param target   终端实体名
 * @param type     关系类型（如"使用""配套""属于"）
 * @param desc     关系在原文中的具体表述
 * @param strength 关系强弱，0~1
 */
public record GraphRelation(String source, String target, String type, String desc, Double strength) {
}
