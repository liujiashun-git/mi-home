package com.mi.home.know.engine.rag.modules;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.mi.home.know.engine.document.entity.KnowledgeSegment;
import com.mi.home.know.engine.document.service.KnowledgeSegmentService;
import com.mi.home.know.engine.graph.model.GraphSearchResult;
import com.mi.home.know.engine.graph.service.GraphQueryEntityService;
import com.mi.home.know.engine.graph.store.GraphStore;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.mi.home.know.engine.rag.constant.MetadataKeyConstant.ACCESSIBLE_BY;
import static com.mi.home.know.engine.rag.constant.MetadataKeyConstant.SOURCE_GRAPH;

/**
 * 基于实体图的检索器：图 → chunk ID → 正文 → 与向量结果同构的 {@link Content}。
 *
 * <h3>核心不变量：图只当路由器，不当内容源</h3>
 * 图里存的是"哪些 chunk 提到了哪些实体"，查图的结果是 <b>chunk ID 列表</b>；
 * 正文始终从 chunk 库按 ID 取。这样图结果和向量结果天然同构（都是 chunk），
 * 合并、去重、精排、引用回填全部复用既有管线，图<b>不需要任何特殊通道</b>。
 * <p>
 * 反过来，如果让图直接返回"实体描述 + 关系文本"塞进 prompt，
 * 就会走出一条和向量检索完全不同的管线（不同的去重、不同的排序、不同的引用），
 * 复杂度翻倍且行为不可预期。保持"图的产出 = chunk"这个不变量。
 *
 * <h3>三处与主干的协作约定</h3>
 * <ol>
 *   <li><b>去重不在这里做</b>：本检索器不去查"这些 chunk 是不是已经被向量路召回了"——
 *       那需要跨检索器共享状态，会把并行检索串行化。RRF 融合按 contentKey 去重
 *       （优先 ES 向量 ID，其次 chunkId），所以这里只要把 {@code embeddingId} 原样带上，
 *       同一块被图路和向量路同时命中时就会在融合阶段自然合一</li>
 *   <li><b>不打 skipRerank</b>：SQL/Cypher 的结构化结果是"最终答案文本"，所以透传不参与融合；
 *       而图路的产出是待排序的候选 chunk，必须进 RRF + 精排</li>
 *   <li><b>不给图命中编相似度分</b>：图命中没有向量相似度，别造一个假分数去和向量分混排。
 *       它按名次参与 RRF（{@code defaultWeight}），最终分数由精排模型的模型分给出</li>
 * </ol>
 */
@Slf4j
public class KnowEngineEntityGraphContentRetriever implements ContentRetriever {

    /**
     * Metadata 只接受这几种值类型，其余一律转字符串，否则 {@link Metadata#from} 会直接抛错
     */
    private static final Set<Class<?>> SUPPORTED_METADATA_TYPES = Set.of(
            String.class, Integer.class, Long.class, Float.class, Double.class, UUID.class);

    private final GraphStore graphStore;

    private final KnowledgeSegmentService knowledgeSegmentService;

    /**
     * 查询侧实体抽取（用改写后的 query）
     */
    private final GraphQueryEntityService queryEntityService;

    private final String knowledgeBase;

    /**
     * 图查询返回行数上限
     */
    private final int rowLimit;

    /**
     * 一次图检索最多带回几个 chunk
     */
    private final int maxChunks;

    /**
     * 本轮允许的可见范围（与向量/关键词路的 accessibleBy 过滤器同源）。
     * 图路没有 ES 那样的 filter 下推，所以在内存里按同一个白名单校验；
     * 不校验就会出现"向量路挡住了、图路却把无权文档的正文捞出来"的越权。
     */
    private final Collection<String> allowedAccessibleBy;

    public KnowEngineEntityGraphContentRetriever(GraphStore graphStore,
                                                 KnowledgeSegmentService knowledgeSegmentService,
                                                 GraphQueryEntityService queryEntityService,
                                                 String knowledgeBase,
                                                 int rowLimit,
                                                 int maxChunks,
                                                 Collection<String> allowedAccessibleBy) {
        this.graphStore = graphStore;
        this.knowledgeSegmentService = knowledgeSegmentService;
        this.queryEntityService = queryEntityService;
        this.knowledgeBase = knowledgeBase;
        this.rowLimit = rowLimit;
        this.maxChunks = maxChunks;
        this.allowedAccessibleBy = allowedAccessibleBy;
    }

    @Override
    public List<Content> retrieve(Query query) {
        // 闸门：图库不可用直接返回空。不做任何 LLM 调用、不碰 Neo4j
        if (!graphStore.isAvailable()) {
            return List.of();
        }
        try {
            List<String> entityNames = queryEntityService.extract(query.text());
            if (entityNames.isEmpty()) {
                // 抽不出实体是常态（不是错误）：本次跳过图路，主干照常回答
                log.info("本次查询未抽出实体，跳过图检索。query={}", query.text());
                return List.of();
            }

            GraphSearchResult graphResult = graphStore.searchNodes(knowledgeBase, entityNames, rowLimit);
            List<String> chunkIds = graphResult.chunkIds();
            if (chunkIds.isEmpty()) {
                log.info("图中未命中实体节点，跳过图检索。entities={}", entityNames);
                return List.of();
            }
            if (chunkIds.size() > maxChunks) {
                chunkIds = chunkIds.subList(0, maxChunks);
            }

            // 图 → chunk ID → 回 chunk 库取正文
            // 注：这里不做父块替换/兄弟块扩展（那是 ES 检索器的邻域扩展逻辑）。
            // 不需要重复实现——两条路命中同一块时 RRF 会按 embeddingId 合一，
            // 正文版本由先跑的那一路（主干）决定，图路只负责把"没被向量/关键词召回的块"补进来。
            Map<String, KnowledgeSegment> segments = knowledgeSegmentService.getByChunkIds(chunkIds);
            List<Content> contents = new ArrayList<>(chunkIds.size());
            int skipped = 0;
            for (String chunkId : chunkIds) {
                KnowledgeSegment segment = segments.get(chunkId);
                // 溯源指向的块已经不存在（文档被删/版本被换）→ 丢弃，图里的残留溯源不值得报错
                if (segment == null || segment.getText() == null || segment.getText().isBlank()) {
                    skipped++;
                    continue;
                }
                if (!isAccessible(segment)) {
                    skipped++;
                    continue;
                }
                contents.add(toContent(segment));
            }

            log.info("图检索完成, entities={}, 命中节点 {} 个, 关联关系 {} 条, 溯源 chunk {} 个, 取回正文 {} 条（跳过 {} 条）",
                    entityNames, graphResult.nodes().size(), graphResult.relations().size(),
                    chunkIds.size(), contents.size(), skipped);

            return contents.stream()
                    .map(content -> ContentUtil.markRetrievalSource(content, SOURCE_GRAPH))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            // 图是叠加层：这一路挂了绝不能把主干拖下水
            log.warn("图检索异常，本次跳过图路。query={}", query.text(), e);
            return List.of();
        }
    }

    /**
     * 把 chunk 库里的分段还原成标准 {@link Content}，与原实现中"问题条目换回原 chunk"完全同构。
     * <p>
     * <b>metadata 逐项类型保真</b>（而不是无脑转字符串）：
     * 下游 {@code ReferenceUtil} 用 {@code toMap()} 原样取值，但引用组装处会对
     * {@code docId} 调 {@code getInteger}——把一个 Integer 存成字符串会让它直接抛
     * ClassCastException，进而把整批引用回写吞掉。所以这里只放行 Metadata 支持的类型，
     * 其余（布尔、列表、BigDecimal 等）才退化成字符串。
     */
    private Content toContent(KnowledgeSegment segment) {
        Map<String, Object> metadata = buildMetadataMap(segment.getMetadata());
        // 与向量路对齐：embeddingId 是 RRF 去重的首选 key，带上它才能和向量命中合一
        Map<ContentMetadata, Object> contentMetadata = new HashMap<>(2);
        if (segment.getEmbeddingId() != null) {
            contentMetadata.put(ContentMetadata.EMBEDDING_ID, segment.getEmbeddingId());
        }
        return Content.from(TextSegment.from(segment.getText(), Metadata.from(metadata)), contentMetadata);
    }

    private Map<String, Object> buildMetadataMap(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return Map.of();
        }
        try {
            JSONObject raw = JSON.parseObject(metadataJson);
            Map<String, Object> metadata = new LinkedHashMap<>(raw.size());
            for (Map.Entry<String, Object> entry : raw.entrySet()) {
                Object value = entry.getValue();
                if (value == null) {
                    continue;
                }
                metadata.put(entry.getKey(),
                        SUPPORTED_METADATA_TYPES.contains(value.getClass()) ? value : String.valueOf(value));
            }
            return metadata;
        } catch (Exception e) {
            log.warn("分段 metadata 解析失败，仅用正文构造图检索结果", e);
            return Map.of();
        }
    }

    /**
     * 可见范围校验。白名单为空时表示"不限制"（调用方没给出限制，不要在这里擅自加码）。
     */
    private boolean isAccessible(KnowledgeSegment segment) {
        if (allowedAccessibleBy == null || allowedAccessibleBy.isEmpty()) {
            return true;
        }
        Map<String, Object> metadata = buildMetadataMap(segment.getMetadata());
        Object accessibleBy = metadata.get(ACCESSIBLE_BY);
        return accessibleBy != null && allowedAccessibleBy.contains(String.valueOf(accessibleBy));
    }

    public GraphQueryEntityService getQueryEntityService() {
        return queryEntityService;
    }

    public String getKnowledgeBase() {
        return knowledgeBase;
    }
}
