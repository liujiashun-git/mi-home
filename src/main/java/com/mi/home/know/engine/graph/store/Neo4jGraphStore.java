package com.mi.home.know.engine.graph.store;

import com.mi.home.know.engine.graph.model.GraphData;
import com.mi.home.know.engine.graph.model.GraphNode;
import com.mi.home.know.engine.graph.model.GraphRelation;
import com.mi.home.know.engine.graph.model.GraphSearchResult;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Neo4j 图存储实现。
 * <p>
 * <b>Schema</b>（与手工维护的业务图 Brand/CarModel/CarVersion/Part 完全隔离，用独立 label）：
 * <pre>
 * (:Entity { kbId, name, type, desc, chunks: [] })          // chunks 是溯源，追加语义
 * (:Entity)-[:RELATES { type, desc, strength, chunks: [] }]-&gt;(:Entity)
 * </pre>
 * 唯一约束打在 {@code (kbId, name)} 上：不同知识库的同名实体是不同节点，同库内同名实体 MERGE 合一。
 * <p>
 * <b>与业务图的关系</b>：项目里原有的 Text2Cypher 图（品牌-车型-版本-配件）是人工维护的结构化数据，
 * 回答"某车型的续航是多少"这类结构化问题；这里的 Entity 图是从文档正文自动抽出来的，
 * 回答"这两个东西之间有什么关联"这类多跳关系问题。两者共存、互不干扰。
 */
@Slf4j
@Component
public class Neo4jGraphStore implements GraphStore {

    /**
     * 节点唯一键：同库同名合一，跨库互不干扰
     */
    private static final String CONSTRAINT_CYPHER = """
            CREATE CONSTRAINT entity_kb_name_unique IF NOT EXISTS
            FOR (e:Entity) REQUIRE (e.kbId, e.name) IS UNIQUE
            """;

    /**
     * 建索引，避免按 name 查节点时全表扫
     */
    private static final String INDEX_CYPHER = """
            CREATE INDEX entity_kb_name_index IF NOT EXISTS
            FOR (e:Entity) ON (e.kbId, e.name)
            """;

    /**
     * 节点 MERGE：不存在则建，存在则把本次 chunk 追加进 chunks（去重）。
     * <p>
     * 用列表推导式做"追加且去重"，等价于多次 {@code node.Chunks = []string{chunk.ID}} 之后累积的效果。
     * type/desc 用 coalesce 保留首次抽取的值——同一个实体被多个块提到时，
     * 每次都覆盖会让描述随最后一个块漂移，反而不稳定。
     */
    private static final String MERGE_NODES_CYPHER = """
            UNWIND $nodes AS n
            MERGE (e:Entity {kbId: $kbId, name: n.name})
            ON CREATE SET e.chunks = []
            SET e.chunks = e.chunks + [c IN n.chunks WHERE NOT c IN e.chunks],
                e.type = coalesce(e.type, n.type),
                e.desc = coalesce(e.desc, n.desc)
            """;

    /**
     * 关系 MERGE：两端实体必须已存在（同事务内先写节点），按 (起点, 类型, 终点) 合一。
     * <p>
     * 用 MERGE 而不是 CREATE：同一个关系可能被多个 chunk 提到（甚至被多个文档提到），
     * 重复建会得到一堆平行边，查询时把同一个 chunk 重复带回来。
     */
    private static final String MERGE_RELATIONS_CYPHER = """
            UNWIND $relations AS r
            MATCH (a:Entity {kbId: $kbId, name: r.source})
            MATCH (b:Entity {kbId: $kbId, name: r.target})
            WHERE r.source <> r.target
            MERGE (a)-[rel:RELATES {type: r.type}]->(b)
            ON CREATE SET rel.desc = r.desc, rel.strength = r.strength, rel.chunks = []
            SET rel.chunks = rel.chunks + [c IN r.chunks WHERE NOT c IN rel.chunks]
            """;

    /**
     * 删除第一步：把要清理的 chunk 从节点溯源里摘掉
     */
    private static final String PRUNE_NODE_CHUNKS_CYPHER = """
            MATCH (e:Entity {kbId: $kbId})
            WHERE any(c IN $chunkIds WHERE c IN e.chunks)
            SET e.chunks = [c IN e.chunks WHERE NOT c IN $chunkIds]
            """;

    /**
     * 删除第二步：溯源被摘空的节点才删（引用计数语义）
     */
    private static final String DELETE_ORPHAN_NODES_CYPHER = """
            MATCH (e:Entity {kbId: $kbId}) WHERE size(e.chunks) = 0
            DETACH DELETE e
            """;

    /**
     * 删除第三步：同样的引用计数规则作用于关系
     */
    private static final String PRUNE_RELATION_CHUNKS_CYPHER = """
            MATCH ()-[r:RELATES]->()
            WHERE any(c IN $chunkIds WHERE c IN r.chunks)
            SET r.chunks = [c IN r.chunks WHERE NOT c IN $chunkIds]
            """;

    private static final String DELETE_ORPHAN_RELATIONS_CYPHER = """
            MATCH ()-[r:RELATES]->() WHERE size(r.chunks) = 0
            DELETE r
            """;

    /**
     * 查询：按实体名命中 + 一跳邻接。只做一跳，不做变长路径
     */
    private static final String SEARCH_CYPHER = """
            MATCH (e:Entity {kbId: $kbId})
            WHERE toLower(e.name) IN $names
            OPTIONAL MATCH (e)-[r:RELATES]-(n:Entity)
            RETURN e.name AS name, e.type AS type, e.desc AS desc, e.chunks AS chunks,
                   r.type AS relType, r.desc AS relDesc, r.strength AS relStrength,
                   startNode(r).name AS relSource, endNode(r).name AS relTarget,
                   n.name AS neighborName, n.type AS neighborType,
                   n.desc AS neighborDesc, n.chunks AS neighborChunks
            LIMIT $rowLimit
            """;

    @Autowired
    private Driver driver;

    /**
     * 图存储可用性。启动时探测一次：
     * <ul>
     *   <li>Neo4j 没起来 → 不阻塞应用启动，只是整段图谱功能静默跳过</li>
     *   <li>启动后才起来 → 需要重启应用（本项目不做连接自愈，避免为可选组件引入额外状态机）</li>
     * </ul>
     */
    private volatile boolean available;

    @PostConstruct
    public void init() {
        try (Session session = driver.session()) {
            session.run("RETURN 1").consume();
            session.run(CONSTRAINT_CYPHER).consume();
            session.run(INDEX_CYPHER).consume();
            available = true;
            log.info("Neo4j 图存储就绪（entity_kb_name_unique 约束已确认）");
        } catch (Exception e) {
            available = false;
            log.warn("Neo4j 不可用，图谱抽取与图检索整体跳过（主干检索不受影响）。"
                    + "启动 Neo4j 后需重启应用。原因: {}", e.getMessage());
        }
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    @Override
    public void addGraph(String kbId, GraphData graph) {
        if (!available || graph == null || graph.isEmpty()) {
            return;
        }
        List<Map<String, Object>> nodeMaps = new ArrayList<>();
        Set<String> chunkIds = new LinkedHashSet<>();
        for (GraphNode node : graph.nodes()) {
            if (node == null || node.name() == null || node.name().isBlank()) {
                continue;
            }
            List<String> chunks = node.chunks() == null ? List.of() : node.chunks();
            chunkIds.addAll(chunks);
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", node.name());
            map.put("type", node.type());
            map.put("desc", node.desc());
            map.put("chunks", chunks);
            nodeMaps.add(map);
        }

        // 关系与节点同批写：抽取阶段的关系一定来自当前这次块，溯源复用同一批 chunk ID
        List<String> chunks = new ArrayList<>(chunkIds);
        List<Map<String, Object>> relationMaps = new ArrayList<>();
        if (graph.relations() != null) {
            for (GraphRelation relation : graph.relations()) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("source", relation.source());
                map.put("target", relation.target());
                map.put("type", relation.type());
                map.put("desc", relation.desc());
                map.put("strength", relation.strength());
                map.put("chunks", chunks);
                relationMaps.add(map);
            }
        }

        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                if (!nodeMaps.isEmpty()) {
                    tx.run(MERGE_NODES_CYPHER, Map.of("kbId", kbId, "nodes", nodeMaps)).consume();
                }
                if (!relationMaps.isEmpty()) {
                    // 关系的两端实体已由上句写入（同事务，可见）
                    tx.run(MERGE_RELATIONS_CYPHER, Map.of("kbId", kbId, "relations", relationMaps)).consume();
                }
                return null;
            });
        } catch (Exception e) {
            // 写入失败不当成"要重试"：LLM 已经跑完了，重试等于再烧一遍 token。
            // 不写幂等标记，下次整篇重抽时会自然带上这块
            log.error("图写入失败, kbId={}, nodes={}, relations={}", kbId, nodeMaps.size(), relationMaps.size(), e);
            throw e;
        }
    }

    @Override
    public void delGraph(String kbId, Collection<String> chunkIds) {
        if (!available || chunkIds == null || chunkIds.isEmpty()) {
            return;
        }
        List<String> ids = chunkIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return;
        }
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run(PRUNE_NODE_CHUNKS_CYPHER, Map.of("kbId", kbId, "chunkIds", ids)).consume();
                tx.run(PRUNE_RELATION_CHUNKS_CYPHER, Map.of("chunkIds", ids)).consume();
                tx.run(DELETE_ORPHAN_RELATIONS_CYPHER).consume();
                tx.run(DELETE_ORPHAN_NODES_CYPHER, Map.of("kbId", kbId)).consume();
                return null;
            });
            log.info("图溯源清理完成, kbId={}, chunkIds={}", kbId, ids.size());
        } catch (Exception e) {
            // 清理失败不阻塞文档删除：图里残留的溯源最多让图检索多召回几条已删正文，
            // 而 chunk 取正文那步会查不到（分段已物理删除）自然丢弃
            log.error("图溯源清理失败（不阻塞文档删除）, kbId={}, chunkIds={}", kbId, ids.size(), e);
        }
    }

    @Override
    public GraphSearchResult searchNodes(String kbId, Collection<String> entityNames, int rowLimit) {
        if (!available || entityNames == null || entityNames.isEmpty()) {
            return GraphSearchResult.empty();
        }
        List<String> names = entityNames.stream()
                .filter(name -> name != null && !name.isBlank())
                .map(name -> name.trim().toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
        if (names.isEmpty()) {
            return GraphSearchResult.empty();
        }

        try (Session session = driver.session()) {
            List<Record> records = session.executeRead(tx ->
                    tx.run(SEARCH_CYPHER, Map.of("kbId", kbId, "names", names, "rowLimit", rowLimit)).list());

            // 直接命中的实体在前，邻接节点在后
            Map<String, GraphNode> matched = new LinkedHashMap<>();
            Map<String, GraphNode> neighbors = new LinkedHashMap<>();
            Map<String, GraphRelation> relations = new LinkedHashMap<>();

            for (Record record : records) {
                String name = asString(record.get("name"));
                if (name != null) {
                    matched.putIfAbsent(name.toLowerCase(Locale.ROOT), new GraphNode(
                            name, asString(record.get("type")), asString(record.get("desc")),
                            asStringList(record.get("chunks"))));
                }
                String neighborName = asString(record.get("neighborName"));
                if (neighborName != null) {
                    neighbors.putIfAbsent(neighborName.toLowerCase(Locale.ROOT), new GraphNode(
                            neighborName, asString(record.get("neighborType")), asString(record.get("neighborDesc")),
                            asStringList(record.get("neighborChunks"))));
                }
                String relType = asString(record.get("relType"));
                String relSource = asString(record.get("relSource"));
                String relTarget = asString(record.get("relTarget"));
                if (relType != null && relSource != null && relTarget != null) {
                    relations.putIfAbsent(relSource + "|" + relType + "|" + relTarget, new GraphRelation(
                            relSource, relTarget, relType, asString(record.get("relDesc")),
                            record.get("relStrength").isNull() ? null : record.get("relStrength").asDouble()));
                }
            }

            // 邻接节点若同时也是直接命中节点，不重复计入
            neighbors.keySet().removeAll(matched.keySet());

            List<GraphNode> ordered = new ArrayList<>(matched.values());
            ordered.addAll(neighbors.values());
            return new GraphSearchResult(ordered, new ArrayList<>(relations.values()));
        } catch (Exception e) {
            // 图路失败不影响主干：返回空结果，让向量/关键词继续答
            log.warn("图检索失败，本次跳过图路, kbId={}, names={}", kbId, names, e);
            return GraphSearchResult.empty();
        }
    }

    /**
     * 关系也带上 chunk 溯源：抽取阶段的关系一定来自当前块，溯源就是本次块携带的 chunk ID。
     */
    private String asString(Value value) {
        return value == null || value.isNull() ? null : value.asString();
    }

    private List<String> asStringList(Value value) {
        if (value == null || value.isNull()) {
            return List.of();
        }
        return value.asList(Value::asString);
    }
}
