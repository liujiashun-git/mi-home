package com.mi.home.know.engine.graph.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.mi.home.know.engine.document.constant.SegmentStatus;
import com.mi.home.know.engine.document.entity.KnowledgeDocument;
import com.mi.home.know.engine.document.entity.KnowledgeSegment;
import com.mi.home.know.engine.document.service.KnowledgeDocumentService;
import com.mi.home.know.engine.document.service.KnowledgeSegmentService;
import com.mi.home.know.engine.graph.config.GraphProperties;
import com.mi.home.know.engine.graph.model.GraphData;
import com.mi.home.know.engine.graph.model.GraphNode;
import com.mi.home.know.engine.graph.model.GraphRelation;
import com.mi.home.know.engine.graph.model.ModelDeclinedException;
import com.mi.home.know.engine.graph.service.GraphExtractionService;
import com.mi.home.know.engine.graph.store.GraphStore;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.mi.home.know.engine.rag.constant.MetadataKeyConstant.CONTENT_TYPE;
import static com.mi.home.know.engine.rag.constant.MetadataKeyConstant.GRAPH_EXTRACTED;
import static com.mi.home.know.engine.rag.constant.MetadataKeyConstant.TYPE_DOC_SUMMARY;
import static com.mi.home.know.engine.rag.constant.MetadataKeyConstant.TYPE_TABLE_COLUMN;
import static com.mi.home.know.engine.rag.constant.MetadataKeyConstant.TYPE_TABLE_SUMMARY;

/**
 * 图谱抽取实现：两段式 prompt + per-chunk 独立任务。
 *
 * <h3>为什么两段式（实体先、关系后）</h3>
 * 一次抽完看起来省一次调用，但模型要在同一次生成里同时完成"识别实体"和"在实体之间连边"
 * 两件不同难度的事，输出更不稳、更容易漏。两段各自简单：
 * 第一段只做封闭类型清单下的实体识别，第二段拿着已确定的实体清单+原文判断关系。
 * 代价是多一次调用，换来的是输出稳定性和可控性。
 *
 * <h3>六条必须处理的边界情况（都实现在 {@link #extractChunk}）</h3>
 * <ol>
 *   <li><b>任务已过期</b>：文档被重新上传/切版本，旧版本的块不该再抽——LLM 调用<b>前</b>查一次文档状态</li>
 *   <li><b>模型用散文拒绝输出</b>：目录页这类没有实体的块，模型会回一句"这段没有实体"而不是 {@code []}。
 *       这是<b>合法结果，不是错误</b>，正常结束且不重试——同样输入重放没有意义，重试只是烧钱</li>
 *   <li><b>抽取配置未启用 / 图库不可用</b>：整段跳过，一次 LLM 都不调</li>
 *   <li><b>chunk 在 LLM 调用期间消失</b>：写图前<b>重读一次</b> chunk，读不到就丢弃结果</li>
 *   <li><b>chunk 在 LLM 调用期间被编辑</b>：比对 updatedAt，变了说明抽的是旧正文，结果作废</li>
 *   <li><b>写入前挂溯源</b>：{@code node.chunks = [chunkId]}，图 → 正文的回链全靠它</li>
 * </ol>
 *
 * <h3>与原始设计的一处偏离</h3>
 * 原始设计是"一个 chunk 一个异步任务 + 独立队列 + MaxRetry/Timeout 控制"，
 * 依赖的是 Go 侧的异步任务框架。本项目没有这类框架，改为
 * <b>文档级任务（{@code graphExtractionExecutor}）fan-out 块级任务（{@code graphChunkExecutor}）</b>：
 * 块与块之间互不依赖，仍然做到"一块失败不拖累其他块、每块独立重试、每块独立取消检查"，
 * 但没有做块级超时熔断（交给 HTTP 客户端自己的超时）。
 */
@Slf4j
@Service
public class GraphExtractionServiceImpl implements GraphExtractionService {

    /**
     * 实体类型封闭清单。与 prompt 里给的清单严格一致，做输出侧的第二道校验。
     */
    private static final Set<String> ENTITY_TYPES = Set.of(
            "Person", "Organization", "Location", "Product", "Event", "Date",
            "Work", "Concept", "Resource", "Category", "Operation");

    private static final int ENTITY_NAME_MAX_CHARS = 60;

    private static final int ENTITY_DESC_MAX_CHARS = 200;

    private static final int RELATION_TYPE_MAX_CHARS = 20;

    private static final int RELATION_DESC_MAX_CHARS = 200;

    private static final Pattern HTML_TAG = Pattern.compile("<[^>]*>");

    @Autowired
    private ChatModel chatModel;

    @Autowired
    private GraphStore graphStore;

    @Autowired
    private KnowledgeSegmentService knowledgeSegmentService;

    @Autowired
    private KnowledgeDocumentService knowledgeDocumentService;

    /**
     * 块级任务池。文档级任务在这里 fan-out，不占用文档级任务的线程
     */
    @Autowired
    @Qualifier("graphChunkExecutor")
    private Executor graphChunkExecutor;

    @Value("classpath:prompts/graph-extract-entities-prompt.txt")
    private Resource entityPromptResource;

    @Value("classpath:prompts/graph-extract-relations-prompt.txt")
    private Resource relationPromptResource;

    @Autowired
    private GraphProperties properties;

    @Async("graphExtractionExecutor")
    @Override
    public void extractAsync(Long docId, Long versionId) {
        if (!properties.isEnabled()) {
            return;
        }
        if (!graphStore.isAvailable()) {
            // 图库不可用就别烧 LLM：抽取的产物没地方落，白花钱
            log.info("跳过图谱抽取：Neo4j 不可用, docId={}", docId);
            return;
        }
        try {
            KnowledgeDocument document = knowledgeDocumentService.getById(docId);
            if (document == null) {
                log.info("图谱抽取跳过：文档不存在（可能已删除）, docId={}", docId);
                return;
            }
            if (!Objects.equals(document.getCurrentVersionId(), versionId)) {
                log.info("图谱抽取跳过：当前版本已切换, docId={}, expect={}, actual={}",
                        docId, versionId, document.getCurrentVersionId());
                return;
            }

            List<KnowledgeSegment> targets = collectTargets(docId, versionId);
            if (targets.isEmpty()) {
                log.info("图谱抽取跳过：没有待抽取的正文块, docId={}, versionId={}", docId, versionId);
                return;
            }

            long start = System.currentTimeMillis();
            // 一个 chunk 一个独立任务：块之间无依赖，抽一块的耗时就是并发单位
            List<CompletableFuture<Integer>> futures = new ArrayList<>(targets.size());
            for (KnowledgeSegment target : targets) {
                futures.add(CompletableFuture
                        .supplyAsync(() -> extractChunk(docId, versionId, target), graphChunkExecutor)
                        .exceptionally(e -> {
                            log.warn("块级图谱抽取任务异常, chunkId={}", target.getChunkId(), e);
                            return 0;
                        }));
            }
            int written = futures.stream().mapToInt(CompletableFuture::join).sum();

            log.info("图谱抽取完成, docId={}, versionId={}, 正文块 {} 个, 落库实体+关系 {} 条, 耗时 {} ms",
                    docId, versionId, targets.size(), written, System.currentTimeMillis() - start);
        } catch (Exception e) {
            // 图谱是增强而非主干：失败不影响文档可检索性
            log.error("图谱抽取失败（不影响文档可用性）, docId={}, versionId={}", docId, versionId, e);
        }
    }

    @Override
    public GraphData extractText(String text) {
        if (text == null || text.isBlank()) {
            return new GraphData("", List.of(), List.of());
        }
        String limited = truncate(text, properties.getChunkTextMaxChars());
        List<GraphNode> entities;
        try {
            entities = extractEntities(limited, "debug");
        } catch (ModelDeclinedException e) {
            log.info("调试抽取：模型拒绝输出，按无实体处理");
            return new GraphData(limited, List.of(), List.of());
        }
        List<GraphRelation> relations;
        try {
            relations = extractRelations(limited, entities);
        } catch (Exception e) {
            log.warn("调试抽取：关系抽取失败，只返回实体", e);
            relations = List.of();
        }
        return new GraphData(limited, entities, relations);
    }

    // ==================== 目标块筛选 ====================

    /**
     * 挑出参与抽取的块：
     * <ul>
     *   <li>只抽<b>正文形态</b>的块：普通文本块 + 表格行块。
     *       表摘要 / 列描述 / 文档摘要本身是索引侧的"替身"，不是正文，抽出来的实体也不指向具体内容</li>
     *   <li>已抽过的块跳过（幂等，任务可安全重跑）</li>
     *   <li>空文本块跳过</li>
     * </ul>
     */
    private List<KnowledgeSegment> collectTargets(Long docId, Long versionId) {
        List<KnowledgeSegment> segments = knowledgeSegmentService.list(new QueryWrapper<KnowledgeSegment>()
                .eq("document_id", docId)
                .eq("document_version", versionId)
                .eq("skip_embedding", 0)
                .eq("status", SegmentStatus.VECTOR_STORED)
                .orderByAsc("chunk_order"));

        List<KnowledgeSegment> targets = new ArrayList<>();
        for (KnowledgeSegment segment : segments) {
            if (segment.getText() == null || segment.getText().isBlank()) {
                continue;
            }
            Map<String, Object> metadata = parseMetadata(segment.getMetadata());
            Object contentType = metadata.get(CONTENT_TYPE);
            if (TYPE_TABLE_SUMMARY.equals(contentType)
                    || TYPE_TABLE_COLUMN.equals(contentType)
                    || TYPE_DOC_SUMMARY.equals(contentType)) {
                continue;
            }
            if (metadata.containsKey(GRAPH_EXTRACTED)) {
                continue;
            }
            targets.add(segment);
        }
        return targets;
    }

    // ==================== 单块抽取 ====================

    /**
     * 抽取一个 chunk。返回落库的实体+关系条数。
     * <p>
     * 全程不抛异常（调用方是块级任务，异常会导致整块静默丢失）：
     * 失败都转成"返回 0 且不留幂等标记"——这块下次整篇重抽时还会再试一次。
     */
    private int extractChunk(Long docId, Long versionId, KnowledgeSegment segment) {
        String chunkId = segment.getChunkId();
        try {
            // ① 取消检查放在 LLM 之前：图谱抽取是最贵的 fan-out，不给已放弃的文档烧钱
            if (!isStillCurrent(docId, versionId)) {
                log.info("文档已删除或版本已切换，跳过图谱抽取, chunkId={}", chunkId);
                return 0;
            }
            String text = truncate(segment.getText(), properties.getChunkTextMaxChars());

            // ② 第一段：实体
            List<GraphNode> entities;
            try {
                entities = extractEntities(text, chunkId);
            } catch (ModelDeclinedException e) {
                // 模型用散文拒绝输出：合法结果，正常结束且不重试
                markExtracted(segment);
                log.info("模型拒绝输出（该块可能本就没有实体），正常结束不重试, chunkId={}", chunkId);
                return 0;
            } catch (Exception e) {
                log.warn("实体抽取失败，本块留待下次整篇重抽, chunkId={}, error={}", chunkId, e.getMessage());
                return 0;
            }
            if (entities.isEmpty()) {
                markExtracted(segment);
                return 0;
            }

            // ③ 第二段：关系（输入 = 第一段的实体清单 + 原文）
            List<GraphRelation> relations;
            try {
                relations = extractRelations(text, entities);
            } catch (ModelDeclinedException e) {
                relations = List.of();
                log.info("关系抽取：模型拒绝输出，按「无关系」处理, chunkId={}", chunkId);
            } catch (Exception e) {
                // 关系段失败不丢弃实体段：宁可图稀疏，也不要因为后半段把前半段的收获扔掉
                relations = List.of();
                log.warn("关系抽取失败，本次只落实体, chunkId={}, error={}", chunkId, e.getMessage());
            }

            // ④ 写图前重读：LLM 跑了几十秒，期间原文可能被编辑或删除
            KnowledgeSegment fresh = knowledgeSegmentService.getById(segment.getId());
            if (fresh == null) {
                log.info("chunk 在抽取期间已消失，丢弃本次结果, chunkId={}", chunkId);
                return 0;
            }
            if (!Objects.equals(fresh.getUpdatedAt(), segment.getUpdatedAt())) {
                log.warn("chunk 在抽取期间被编辑，丢弃本次结果（避免给旧正文建图）, chunkId={}", chunkId);
                return 0;
            }

            // ⑤ 挂溯源后落图（一个 chunk 一个事务，失败整体回滚）
            List<GraphNode> nodesWithProvenance = entities.stream()
                    .map(node -> GraphNode.of(node.name(), node.type(), node.desc(), chunkId))
                    .collect(Collectors.toList());
            try {
                graphStore.addGraph(properties.getKnowledgeBase(), new GraphData(fresh.getText(), nodesWithProvenance, relations));
            } catch (Exception e) {
                // 不留幂等标记：下次整篇重抽会带上这块（LLM 已经跑完，本次不重试，避免重复烧 token）
                log.error("图写入失败，本块不留完成标记, chunkId={}", chunkId, e);
                return 0;
            }

            // ⑥ 只有真正落地（或确认为合法空结果）的块才打标记
            markExtracted(fresh);
            return nodesWithProvenance.size() + relations.size();
        } catch (Exception e) {
            log.warn("图谱抽取异常, chunkId={}", chunkId, e);
            return 0;
        }
    }

    // ==================== 两段式 LLM ====================

    /**
     * 第一段：实体抽取。
     * <p>
     * 重试只针对"可重试的失败"（调用异常、输出不合契约）；
     * 模型用散文拒绝输出属于<b>合法结果</b>，直接抛 {@link ModelDeclinedException} 让调用方正常结束。
     */
    private List<GraphNode> extractEntities(String text, String chunkId) {
        String prompt = loadPrompt(entityPromptResource).replace("{{content}}", text);
        Exception last = null;
        for (int attempt = 1; attempt <= Math.max(properties.getMaxRetry(), 1); attempt++) {
            String response;
            try {
                response = chatModel.chat(prompt);
            } catch (Exception e) {
                last = e;
                log.warn("实体抽取调用失败（第 {}/{} 次）, chunkId={}, error={}",
                        attempt, properties.getMaxRetry(), chunkId, e.getMessage());
                continue;
            }
            if (response == null || response.isBlank()) {
                last = new IllegalStateException("模型返回空响应");
                continue;
            }
            String json = extractJsonContainer(response, '[', ']');
            if (json == null) {
                // 连 JSON 容器都没有 → 模型在跟我们讲道理，不是格式错。别再重试
                throw new ModelDeclinedException(response);
            }
            try {
                return sanitizeEntities(JSON.parseArray(json), chunkId);
            } catch (Exception e) {
                last = e;
                log.warn("实体输出不符合契约（第 {}/{} 次）, chunkId={}", attempt, properties.getMaxRetry(), chunkId);
            }
        }
        throw new IllegalStateException("实体抽取重试 " + properties.getMaxRetry() + " 次仍失败, chunkId=" + chunkId, last);
    }

    /**
     * 第二段：关系抽取。输出侧同样做两道校验——
     * 两端必须是本轮实体清单里的人（防止模型编造出图里不存在的节点），且不能自环。
     */
    private List<GraphRelation> extractRelations(String text, List<GraphNode> entities) {
        if (entities.isEmpty()) {
            return List.of();
        }
        String entityNames = entities.stream()
                .map(GraphNode::name)
                .map(name -> "\"" + name + "\"")
                .collect(Collectors.joining(", ", "[", "]"));
        String prompt = loadPrompt(relationPromptResource)
                .replace("{{content}}", text)
                .replace("{{entities}}", entityNames);

        Exception last = null;
        for (int attempt = 1; attempt <= Math.max(properties.getMaxRetry(), 1); attempt++) {
            String response;
            try {
                response = chatModel.chat(prompt);
            } catch (Exception e) {
                last = e;
                log.warn("关系抽取调用失败（第 {}/{} 次）, error={}", attempt, properties.getMaxRetry(), e.getMessage());
                continue;
            }
            if (response == null || response.isBlank()) {
                last = new IllegalStateException("模型返回空响应");
                continue;
            }
            String json = extractJsonContainer(response, '[', ']');
            if (json == null) {
                throw new ModelDeclinedException(response);
            }
            try {
                return sanitizeRelations(JSON.parseArray(json), entities);
            } catch (Exception e) {
                last = e;
                log.warn("关系输出不符合契约（第 {}/{} 次）", attempt, properties.getMaxRetry());
            }
        }
        throw new IllegalStateException("关系抽取重试 " + properties.getMaxRetry() + " 次仍失败", last);
    }

    // ==================== 输出契约校验 ====================

    /**
     * 实体清洗：去空、截断、同名去重、封顶、类型校验。
     * <p>
     * 类型不合规时<b>保留实体、清空类型</b>而不是丢掉实体：
     * 类型不参与任何检索（图查询只用 name 匹配），丢实体是净损失；
     * 封闭清单由 prompt + 这里的校验共同守住，类型词表不会膨胀。
     */
    private List<GraphNode> sanitizeEntities(JSONArray array, String chunkId) {
        List<GraphNode> nodes = new ArrayList<>();
        if (array == null) {
            return nodes;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < array.size(); i++) {
            JSONObject obj = array.getJSONObject(i);
            if (obj == null) {
                continue;
            }
            String name = clean(obj.getString("name"));
            if (name.isEmpty() || name.length() > ENTITY_NAME_MAX_CHARS) {
                continue;
            }
            if (!seen.add(name)) {
                continue;
            }
            String type = normalizeType(obj.getString("type"), name);
            String desc = truncate(clean(obj.getString("desc")), ENTITY_DESC_MAX_CHARS);
            nodes.add(GraphNode.of(name, type, desc, chunkId));
            if (nodes.size() >= Math.max(properties.getMaxEntitiesPerChunk(), 1)) {
                break;
            }
        }
        return nodes;
    }

    /**
     * 关系清洗：两端必须在实体清单里（并回写成清单里的规范名）、不自环、按内容去重、封顶。
     * <p>
     * 回写规范名这一步是必须的：写库时按 {@code (kbId, name)} MERGE 两端节点，
     * 名字大小写或空格差一个字符就 MATCH 不上，关系会被静默丢掉。
     */
    private List<GraphRelation> sanitizeRelations(JSONArray array, List<GraphNode> entities) {
        List<GraphRelation> relations = new ArrayList<>();
        if (array == null) {
            return relations;
        }
        Map<String, String> canonicalNames = new HashMap<>();
        for (GraphNode entity : entities) {
            canonicalNames.put(entity.name().toLowerCase(Locale.ROOT), entity.name());
        }
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < array.size(); i++) {
            JSONObject obj = array.getJSONObject(i);
            if (obj == null) {
                continue;
            }
            String source = canonicalNames.get(clean(obj.getString("source")).toLowerCase(Locale.ROOT));
            String target = canonicalNames.get(clean(obj.getString("target")).toLowerCase(Locale.ROOT));
            String type = truncate(clean(obj.getString("type")), RELATION_TYPE_MAX_CHARS);
            if (source == null || target == null || type.isEmpty() || source.equals(target)) {
                continue;
            }
            if (!seen.add(source + "|" + type + "|" + target)) {
                continue;
            }
            Double strength = clampStrength(obj.getDouble("strength"));
            String desc = truncate(clean(obj.getString("desc")), RELATION_DESC_MAX_CHARS);
            relations.add(new GraphRelation(source, target, type, desc, strength));
            if (relations.size() >= Math.max(properties.getMaxRelationsPerChunk(), 1)) {
                break;
            }
        }
        return relations;
    }

    private String normalizeType(String rawType, String entityName) {
        String type = clean(rawType);
        for (String candidate : ENTITY_TYPES) {
            if (candidate.equalsIgnoreCase(type)) {
                return candidate;
            }
        }
        log.debug("实体类型不在封闭清单内，保留实体但清空类型, name={}, type={}", entityName, rawType);
        return null;
    }

    private Double clampStrength(Double strength) {
        if (strength == null) {
            return null;
        }
        return Math.max(0d, Math.min(1d, strength));
    }

    // ==================== 工具方法 ====================

    /**
     * 标记该块已完成抽取。
     * <p>
     * 与问题生成的产物一样写在原 chunk 的 metadata 上——存储里只有一份正文，
     * 派生信息挂在同一条记录上，天然同步、天然幂等。
     */
    private void markExtracted(KnowledgeSegment segment) {
        try {
            Map<String, Object> metadata = parseMetadata(segment.getMetadata());
            metadata.put(GRAPH_EXTRACTED, true);
            segment.setMetadata(JSON.toJSONString(metadata));
            knowledgeSegmentService.updateById(segment);
        } catch (Exception e) {
            log.warn("写图谱抽取标记失败（不影响已落库的图）, chunkId={}", segment.getChunkId(), e);
        }
    }

    private boolean isStillCurrent(Long docId, Long versionId) {
        KnowledgeDocument document = knowledgeDocumentService.getById(docId);
        return document != null && Objects.equals(document.getCurrentVersionId(), versionId);
    }

    /**
     * 从模型输出里抠出 JSON 容器（剥 BOM、剥代码围栏、取第一个开括号到最后一个闭括号）。
     * 抠不到返回 null——调用方据此判定"模型用散文拒绝输出"。
     */
    private String extractJsonContainer(String raw, char open, char close) {
        String text = stripBom(raw).replace("```", "").trim();
        int start = text.indexOf(open);
        int end = text.lastIndexOf(close);
        if (start < 0 || end <= start) {
            return null;
        }
        return text.substring(start, end + 1);
    }

    private String loadPrompt(Resource resource) {
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取 prompt 模板失败: " + resource, e);
        }
    }

    private String stripBom(String text) {
        return text != null && text.startsWith("\uFEFF") ? text.substring(1) : text;
    }

    private String clean(String text) {
        if (text == null) {
            return "";
        }
        return HTML_TAG.matcher(text.trim()).replaceAll("").trim();
    }

    private String truncate(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        return trimmed.length() <= maxChars ? trimmed : trimmed.substring(0, maxChars);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseMetadata(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return JSON.parseObject(metadataJson, LinkedHashMap.class);
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }
}
