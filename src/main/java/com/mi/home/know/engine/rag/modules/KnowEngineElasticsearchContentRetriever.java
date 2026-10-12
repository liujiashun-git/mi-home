package com.mi.home.know.engine.rag.modules;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.mi.home.know.engine.document.entity.KnowledgeSegment;
import com.mi.home.know.engine.document.service.KnowledgeSegmentService;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.elasticsearch.ElasticsearchContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.elasticsearch.*;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import dev.langchain4j.store.embedding.filter.logical.Or;
import org.elasticsearch.client.RestClient;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import static com.mi.home.know.engine.rag.constant.MetadataKeyConstant.CHUNK_ID;
import static com.mi.home.know.engine.rag.constant.MetadataKeyConstant.CONTENT_TYPE;
import static com.mi.home.know.engine.rag.constant.MetadataKeyConstant.MATCHED_QUESTION;
import static com.mi.home.know.engine.rag.constant.MetadataKeyConstant.BROTHER_CHUNK_ID;
import static com.mi.home.know.engine.rag.constant.MetadataKeyConstant.BROTHER_CHUNK_TOTAL;
import static com.mi.home.know.engine.rag.constant.MetadataKeyConstant.PARENT_CHUNK_ID;
import static com.mi.home.know.engine.rag.constant.MetadataKeyConstant.SOURCE_KEYWORD;
import static com.mi.home.know.engine.rag.constant.MetadataKeyConstant.SOURCE_VECTOR;
import static com.mi.home.know.engine.rag.constant.MetadataKeyConstant.TYPE_DOC_SUMMARY;
import static com.mi.home.know.engine.rag.constant.MetadataKeyConstant.TYPE_GENERATED_QUESTION;
import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;
import static java.util.stream.Collectors.toList;

/**
 * KnowEngine Elasticsearch 内容检索器
 * <p>
 * 基于 Elasticsearch 的检索实现，扩展了父分段/兄弟分段的关联内容检索。
 * 根据配置的 ElasticsearchConfiguration 类型，决定使用哪种检索模式。
 * <ul>
 *   <li><b>向量检索 (KNN)</b>：ElasticsearchConfigurationKnn → 将查询文本向量化后进行 KNN 相似度搜索</li>
 *   <li><b>全文检索</b>：ElasticsearchConfigurationFullText → 直接执行全文搜索（支持自定义权限过滤）</li>
 *   <li><b>混合检索</b>：ElasticsearchConfigurationHybrid → 结合向量检索和全文检索</li>
 * </ul>
 * <p>
 * <b>关联内容扩展机制：</b>
 * <ul>
 *   <li>兄弟分段扩展：检索命中某个子分段时，根据 brotherChunkId 查出同一父分段下的其他兄弟子分段，补全上下文</li>
 *   <li>父分段替换：检索命中某个子分段时，根据 parentChunkId 从 Redis 中读取父分段的完整文本，
 *       替换当前子分段以获得更完整的语义（父分段本身不存向量，只存完整文本）</li>
 * </ul>
 * <p>
 * 关联内容扩展仅发生在向量检索或混合检索模式下，全文检索模式不做扩展。
 *
 * @see ElasticsearchContentRetriever
 * @see ContentRetriever
 */
public class KnowEngineElasticsearchContentRetriever extends AbstractElasticsearchEmbeddingStore implements ContentRetriever {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchContentRetriever.class);

    /**
     * 邻域扩展一次最多补全的块数上限。
     * 与 ExcelSplitter 的兄弟窗口同量级，防止一次命中把整张大表拉进上下文。
     */
    private static final int MAX_SIBLING_RESULTS = 15;

    private final EmbeddingModel embeddingModel;
    private final int maxResults;
    private final double minScore;
    private final Filter filter;
    private final KnowledgeSegmentService knowledgeSegmentService;

    /**
     * Creates an instance of ElasticsearchContentRetriever using a RestClient.
     *
     * @param configuration  Elasticsearch retriever configuration to use (knn, script, full text, hybrid, hybrid with reranker)
     * @param restClient     Elasticsearch Rest Client (mandatory)
     * @param indexName      Elasticsearch index name (optional). Default value: "default".
     *                       Index will be created automatically if not exists.
     * @param embeddingModel Embedding model to be used by the retriever
     * @param maxResults     Maximum number of results to retrieve
     * @param minScore       Minimum score threshold for retrieved results
     * @param filter         Filter to apply during retrieval
     */
    public KnowEngineElasticsearchContentRetriever(
            ElasticsearchConfiguration configuration,
            RestClient restClient,
            String indexName,
            EmbeddingModel embeddingModel,
            final int maxResults,
            final double minScore,
            final Filter filter,
            KnowledgeSegmentService knowledgeSegmentService) {
        this.embeddingModel = embeddingModel;
        this.maxResults = maxResults;
        this.minScore = minScore;
        this.filter = filter;
        this.knowledgeSegmentService = knowledgeSegmentService;
        this.initialize(configuration, restClient, indexName);
    }

    private List<TextSegment> toTextList(SearchResponse<Document> response) {
        return response.hits().hits().stream()
                .map(hit -> Optional.ofNullable(hit.source())
                        .map(document -> document.getText() == null
                                ? null
                                : TextSegment.from(
                                document.getText(),
                                new Metadata(document.getMetadata())
                                        .put(ContentMetadata.SCORE.name(), hit.score())
                                        .put(ContentMetadata.EMBEDDING_ID.name(), hit.id())))
                        .orElse(null))
                .collect(toList());
    }

    /**
     * 检索入口：根据 query 文本检索 ES，并扩展关联内容。
     * <p>
     * <b>项目实际用法：</b>
     * 创建两个检索器实例，一个配 Knn 调向量检索，一个配 FullText 调全文检索，
     * 并行执行，结果汇合后在聚合器层做 RRF 融合。
     * <p>
     * <b>代码内部逻辑：</b>
     * 每个实例在构造时已固定 configuration，内部走对应的检索路径：
     * <ul>
     *   <li>FullText → 全文检索（不走向量化，直接 match text 字段）</li>
     *   <li>Hybrid → 混合检索（向量 + 全文同时查，ES 内部合并）</li>
     *   <li>Knn → 向量检索（用查询向量做 KNN 相似度搜索）</li>
     * </ul>
     * 检索完成后调用 {@link #proccessParentAndBrotherContent} 扩展关联内容。
     *
     * @param query 查询对象，包含待检索的文本内容
     * @return 检索到的内容列表（含关联扩展后的结果）
     */
    @Override
    public List<Content> retrieve(final Query query) {
        // 将用户问题转为向量（全文检索模式下不会用到这个向量，但代码统一执行）
        Embedding referenceEmbedding = embeddingModel.embed(query.text()).content();

        // 构造向量搜索请求的参数：向量、topN、最低分、权限过滤
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(referenceEmbedding)
                .maxResults(maxResults)//5
                .minScore(minScore)
                .filter(filter)
                .build();

        List<Content> searchContents;
        // 根据构造时传入的 configuration 类型，选择不同的检索策略
        if (configuration instanceof ElasticsearchConfigurationFullText) {
            // 全文检索：不走向量，直接用 query.text() 做 match 查询
            searchContents = doFullTextQuery(query);
        } else if (configuration instanceof ElasticsearchConfigurationHybrid) {
            // 混合检索：向量 KNN + 全文同时查，ES 内部合并结果
            searchContents = mapResultsToContentList(this.hybridSearch(request, query.text()));
        } else {
            // 默认（KNN 向量检索）：用查询向量做相似度搜索
            searchContents = mapResultsToContentList(this.search(request));
        }

        // 问题条目（doc2query）换回原 chunk：命中"问句形态"的索引条目，
        // 返回给下游的是原 chunk 的正文，并按 chunkId 去重（正文命中 + 问题命中同一块只留最高分）
        searchContents = resolveGeneratedQuestions(searchContents);

        // 对检索到的内容做关联扩展：查兄弟分段补全上下文、用父分段替换子分段
        List<Content> finalContents = proccessParentAndBrotherContent(searchContents, referenceEmbedding);

        // 标注检索来源：RRF 按来源取权重，同时让模型看到"这是不同来源的证据"
        String source = (configuration instanceof ElasticsearchConfigurationFullText)
                ? SOURCE_KEYWORD : SOURCE_VECTOR;
        return finalContents.stream()
                .map(content -> ContentUtil.markRetrievalSource(content, source))
                .collect(Collectors.toList());
    }

    /**
     * 把"问题条目"命中换回原 chunk 正文（doc2query 的检索端约定）。
     * <p>
     * 问题条目在索引里的文本是问句，metadata.chunkId 指回原 chunk。
     * 存储里只有一份正文，索引里允许"正文 + 问句"两种表示——命中任何一条问题，
     * 都定位回同一个原 chunk，存储不膨胀、正文与问题天然同步。
     * <p>
     * 解析成功的条目会带上 matchedQuestion 标注（供引用卡片展示"通过问题命中"）；
     * 解析失败（原 chunk 已被清理等）保留问题原文兜底，不让命中凭空消失。
     * 完成后按 chunkId 去重：同一原 chunk 被正文和问题同时命中时只留最高分。
     */
    private List<Content> resolveGeneratedQuestions(List<Content> contents) {
        if (contents.isEmpty()) {
            return contents;
        }
        List<Content> all = new ArrayList<>(contents.size());
        boolean hasQuestionHits = false;
        for (Content content : contents) {
            String contentType = content.textSegment().metadata().getString(CONTENT_TYPE);
            if (!TYPE_GENERATED_QUESTION.equals(contentType)) {
                all.add(content);
                continue;
            }
            hasQuestionHits = true;
            Content resolved = mapQuestionToSource(content);
            all.add(resolved != null ? resolved : content);
        }
        if (!hasQuestionHits) {
            return contents;
        }

        // 按 chunkId 去重，同块留最高分（LinkedHashMap 保序）
        Map<String, Content> bestByChunkId = new LinkedHashMap<>();
        for (Content content : all) {
            String chunkId = content.textSegment().metadata().getString(CHUNK_ID);
            if (chunkId == null) {
                bestByChunkId.put(UUID.randomUUID().toString(), content);
                continue;
            }
            Content previous = bestByChunkId.get(chunkId);
            if (previous == null || scoreOf(content) > scoreOf(previous)) {
                bestByChunkId.put(chunkId, content);
            }
        }
        return new ArrayList<>(bestByChunkId.values());
    }

    /**
     * 按 chunkId 从分段表取回原 chunk，用其正文与元数据构造替换后的 Content。
     * metadata 逐项转字符串再重建：检索链路只按字符串读取，
     * 避免对 Integer/Long 调严格类型 getter 再踩类型异常的坑
     * （langchain4j 的 Metadata 对类型不匹配是抛异常，不是返回 null）。
     */
    private Content mapQuestionToSource(Content questionHit) {
        String chunkId = questionHit.textSegment().metadata().getString(CHUNK_ID);
        if (chunkId == null || knowledgeSegmentService == null) {
            return null;
        }
        try {
            KnowledgeSegment segment = knowledgeSegmentService.getByChunkId(chunkId);
            if (segment == null || segment.getText() == null) {
                log.warn("问题条目指向的原 chunk 不存在, chunkId={}", chunkId);
                return null;
            }
            Map<String, String> metadata = new LinkedHashMap<>();
            if (segment.getMetadata() != null && !segment.getMetadata().isBlank()) {
                JSONObject raw = JSON.parseObject(segment.getMetadata());
                for (Map.Entry<String, Object> entry : raw.entrySet()) {
                    if (entry.getValue() != null) {
                        metadata.put(entry.getKey(), String.valueOf(entry.getValue()));
                    }
                }
            }
            String questionText = questionHit.textSegment().text();
            int titleSeparator = questionText.indexOf('\n');
            metadata.put(MATCHED_QUESTION, titleSeparator >= 0 ? questionText.substring(titleSeparator + 1) : questionText);

            TextSegment sourceSegment = TextSegment.from(segment.getText(), Metadata.from(metadata));
            return Content.from(sourceSegment, questionHit.metadata());
        } catch (Exception e) {
            log.warn("问题条目回查原 chunk 失败, chunkId={}", chunkId, e);
            return null;
        }
    }

    private double scoreOf(Content content) {
        Object score = content.metadata().get(ContentMetadata.SCORE);
        return score instanceof Number number ? number.doubleValue() : 0.0;
    }

    /**
     * 关联内容扩展：对 ES 检索命中的结果，补全上下文信息。
     * <p>
     * 扩展方式有两种：
     * <ul>
     *   <li><b>兄弟分段扩展（邻域扩展）</b>：切片时同一组相邻块共享一个 brotherChunkId
     *       （Markdown 侧是同一父段下的兄弟子段；Excel 侧是同一窗口内的相邻行）。
     *       检索命中其中一块时，把同组的其他块也查出来，补全上下文。
     *       取多少块由块自身携带的 {@code brotherChunkTotal} 决定，避免把整张表拉进上下文。</li>
     *   <li><b>父分段替换</b>：子分段的内容可能只是父分段的一部分，语义不完整。
     *       如果命中子分段，用 parentChunkId 去 Redis 读取父分段的完整文本，
     *       替换当前子分段。父分段本身不做向量化（skipEmbedding），
     *       只存完整文本用于检索后的替换。</li>
     * </ul>
     * <p>
     * 使用缓存避免重复查询同一个 brotherChunkId 或 parentChunkId。
     * 扩展完成后，按 score 降序排列返回。
     *
     * @param searchContents     主检索命中的内容
     * @param referenceEmbedding 本次查询的向量。兄弟扩展时复用该向量做 KNN，
     *                           否则 EmbeddingSearchRequest 缺少 queryEmbedding 会直接抛错
     */
    @NotNull
    private List<Content> proccessParentAndBrotherContent(List<Content> searchContents, Embedding referenceEmbedding) {
        EmbeddingSearchRequest request;
        // 先去重（同一个文档可能被多次命中），再按文本排序保证稳定性
        searchContents = searchContents.stream().distinct().sorted(Comparator.comparing(content -> content.textSegment().text())).toList();
        List<Content> finalContents = new ArrayList<>(searchContents);

        // 兄弟分段缓存 key=brotherChunkId，父分段缓存 key=parentChunkId
        // 避免对同一个 brotherChunkId 或 parentChunkId 重复查询 ES 或 Redis
        Map<String, List<Content>> parentDocMap = new HashMap<>();
        Map<String, List<Content>> brotherDocMap = new HashMap<>();

        Iterator<Content> iterator = searchContents.iterator();

        for (; iterator.hasNext(); ) {
            Content content = iterator.next();

            // ========== 特殊 chunk 防误替换（类型白名单） ==========
            // 文档摘要（doc_summary）虽然挂了 parentChunkId（为了进 chunk 树不当孤儿），
            // 但它以"概览形态"被命中本身就是目的——被替换成第一个正文块就失去了概览价值。
            // 父块替换/邻域扩展是为普通 chunk 设计的，特殊用途的 chunk 必须
            // 用类型白名单【有意识地挡】，不能依赖"碰巧没坏"。
            String protectedContentType = content.textSegment().metadata().getString(CONTENT_TYPE);
            if (TYPE_DOC_SUMMARY.equals(protectedContentType)
                    || TYPE_GENERATED_QUESTION.equals(protectedContentType)) {
                continue;
            }

            // ========== 兄弟分段扩展（邻域扩展） ==========
            // 场景：检索命中了某一块，但该块只是同组文本的一小段
            // 把同组的其他块也查出来，LLM 能看到更完整的上下文
            String brotherChunkId = content.textSegment().metadata().getString(BROTHER_CHUNK_ID);
            if (brotherChunkId != null) {
                // 已经命中过的同组块不重复补全，避免同一组被反复拉取
                if (!brotherDocMap.containsKey(brotherChunkId)) {
                    // 同一组只会被扩展一次，用空列表占位表示已处理
                    brotherDocMap.put(brotherChunkId, Collections.emptyList());

                    int siblingLimit = resolveSiblingLimit(content);
                    Filter brotherFilter = metadataKey(BROTHER_CHUNK_ID).isEqualTo(brotherChunkId);
                    request = EmbeddingSearchRequest.builder()
                            .queryEmbedding(referenceEmbedding)
                            .maxResults(siblingLimit)
                            .filter(brotherFilter)
                            .build();

                    // 兄弟块不过检索器的 minScore 阈值：它们只是上下文，不是"候选答案"
                    List<Content> brotherDocs = mapResultsToContentList(this.search(request), 0.0);
                    brotherDocMap.put(brotherChunkId, brotherDocs);
                    finalContents.addAll(brotherDocs);
                }
            }

            // ========== 父分段替换 ==========
            // 场景：文档切片时，父分段只存完整文本（不做向量化，skipEmbedding=1）
            // 子分段做向量化用于检索。检索命中子分段后，用父分段的完整文本替换它
            // 这样 LLM 得到的是完整的段落，而不是一个片段
            String parentChunkId = content.textSegment().metadata().getString(PARENT_CHUNK_ID);
            if (parentChunkId != null) {
                List<Content> cachedParentDocs = parentDocMap.get(parentChunkId);
                if (cachedParentDocs != null) {
                    // 这个父分段之前已经被其他子分段触发替换过，
                    // 直接删除当前子分段即可，不再重复添加父分段
                    finalContents.remove(content);
                } else if (knowledgeSegmentService != null) {
                    // 从 Redis 读取父分段完整文本（父分段未向量化，存储在 Redis 中）
                    String segmentText = knowledgeSegmentService.getTextByChunkId(parentChunkId);
                    if (segmentText != null) {
                        // 用父分段的完整文本构造新的 Content，替换当前子分段
                        TextSegment parentSegment = TextSegment.from(segmentText, content.textSegment().metadata());
                        Content parentContent = Content.from(parentSegment, content.metadata());
                        List<Content> parentDocs = List.of(parentContent);
                        parentDocMap.put(parentChunkId, parentDocs);
                        finalContents.remove(content);
                        finalContents.addAll(parentDocs);
                    } else {
                        // Redis 中找不到父分段文本，可能是数据不一致，直接丢弃该子分段
                        log.warn("parentChunk not found in Redis, chunkId: {}", parentChunkId);
                        finalContents.remove(content);
                    }
                }
            }
        }

        // 最终按 score 降序排列（兄弟分段和父分段也有各自的 score）
        finalContents = finalContents.stream().sorted(new Comparator<Content>() {
            @Override
            public int compare(Content content1, Content content2) {
                return Double.compare((double) content2.metadata().get(ContentMetadata.SCORE), (double) content1.metadata().get(ContentMetadata.SCORE));
            }
        }).collect(Collectors.toList());
        return finalContents;
    }

    /**
     * 全文检索：不走向量化，直接用 query.text() 做 ES match 查询。
     * <p>
     * LangChain4j 默认的 {@link ElasticsearchContentRetriever} 全文检索不支持自定义 Filter，
     * 但本项目需要根据用户权限过滤文档（accessibleBy 字段），所以重写了全文检索逻辑。
     * <p>
     * <b>核心区别：</b>
     * <ul>
     *   <li>无权限过滤（accessibleValues 为空）：简单 match 查询，只匹配 text 字段</li>
     *   <li>有权限过滤（accessibleValues 非空）：bool 查询，
     *       must 子句对 text 字段做 match 全文匹配，
     *       filter 子句用 terms 查询限定 metadata.accessibleBy 字段，
     *       确保只返回当前用户或角色有权访问的文档</li>
     * </ul>
     * <p>
     * <b>权限过滤的数据来源：</b>
     * accessibleBy 在文档入库时根据文档的可见范围（全员可见/指定角色）写入 metadata，
     * 检索时从 Filter 树中提取当前用户的角色值列表，传给 ES 做过滤。
     *
     * @param query 查询对象，包含检索文本
     * @return 带 SCORE 和 EMBEDDING_ID 元数据的 Content 列表
     */
    @NotNull
    private List<Content> doFullTextQuery(Query query) {
        try {
            // 从 Filter 树中递归提取所有 IsEqualTo 条件的 value，即当前用户的角色/权限值列表
            List<String> accessibleValues = extractFilterValues(filter);

            SearchResponse<Document> response = client.search(
                    s -> s.index(indexName)
                            .query(q -> accessibleValues.isEmpty()
                                    // 无权限过滤：直接 match text 字段
                                    ? q.match(m -> m.field("text").query(query.text()))
                                    // 有权限过滤：must match 全文 + filter 限定 accessibleBy
                                    : q.bool(b -> b
                                    .must(m -> m.match(mm -> mm.field("text").query(query.text())))
                                    .filter(f -> f.terms(t -> t
                                            .field("metadata.accessibleBy")
                                            .terms(tv -> tv.value(accessibleValues.stream()
                                                    .map(co.elastic.clients.elasticsearch._types.FieldValue::of)
                                                    .toList())))))),
                    Document.class);

            List<TextSegment> results = toTextList(response);
            // 将 TextSegment 转为 Content，携带 score 和 embeddingId 元数据
            return results.stream()
                    .map(t -> Content.from(
                            t,
                            Map.of(
                                    ContentMetadata.SCORE, t.metadata().getDouble(ContentMetadata.SCORE.name()),
                                    ContentMetadata.EMBEDDING_ID,
                                    t.metadata().getString(ContentMetadata.EMBEDDING_ID.name()))))
                    .toList();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private List<Content> mapResultsToContentList(EmbeddingSearchResult<TextSegment> searchResult) {
        return mapResultsToContentList(searchResult, minScore);
    }

    /**
     * @param minScoreOverride 覆盖检索器的 minScore 阈值。兄弟块扩展时传 0，
     *                         因为邻域块只是"补上下文"，不应被答案阈值筛掉。
     */
    private List<Content> mapResultsToContentList(EmbeddingSearchResult<TextSegment> searchResult, double minScoreOverride) {
        List<Content> result = searchResult.matches().stream()
                .filter(f -> f.score() > minScoreOverride)
                .map(m -> Content.from(
                        m.embedded(),
                        Map.of(
                                ContentMetadata.SCORE, m.score(),
                                ContentMetadata.EMBEDDING_ID, m.embeddingId())))
                .toList();
        log.debug("Found [{}] relevant documents in Elasticsearch index [{}].", result.size(), indexName);
        return result;
    }

    /**
     * 决定一次邻域扩展最多补多少块。
     * <p>
     * 优先读块自己携带的 {@code brotherChunkTotal}（切片时写死的组大小），
     * 保证"一组只有 10 行就只拉 10 行"；拿不到时退回默认上限。
     */
    private int resolveSiblingLimit(Content content) {
        Integer total = content.textSegment().metadata().getInteger(BROTHER_CHUNK_TOTAL);
        if (total != null && total > 0) {
            return Math.min(total, MAX_SIBLING_RESULTS);
        }
        return MAX_SIBLING_RESULTS;
    }

    /**
     * 递归遍历 langchain4j Filter 树，提取所有 IsEqualTo 的 value 字符串列表。
     * <p>
     * 支持 Or(IsEqualTo, ...) 结构，适配权限过滤场景。
     */
    private List<String> extractFilterValues(Filter filter) {
        List<String> values = new ArrayList<>();
        collectFilterValues(filter, values);
        return values;
    }

    private void collectFilterValues(Filter filter, List<String> values) {
        if (filter == null) {
            return;
        }
        if (filter instanceof IsEqualTo isEqualTo) {
            Object value = isEqualTo.comparisonValue();
            if (value != null) {
                values.add(value.toString());
            }
        } else if (filter instanceof Or or) {
            collectFilterValues(or.left(), values);
            collectFilterValues(or.right(), values);
        }
    }

    public static KnowEngineElasticsearchContentRetriever.Builder builder() {
        return new KnowEngineElasticsearchContentRetriever.Builder();
    }

    public static class Builder {

        private RestClient restClient;
        private String indexName = "default";
        private ElasticsearchConfiguration configuration =
                ElasticsearchConfigurationKnn.builder().build();
        private EmbeddingModel embeddingModel;
        private int maxResults;
        private double minScore;
        private Filter filter;
        private KnowledgeSegmentService knowledgeSegmentService;

        /**
         * @param restClient Elasticsearch RestClient.
         * @return builder
         */
        public KnowEngineElasticsearchContentRetriever.Builder restClient(RestClient restClient) {
            this.restClient = restClient;
            return this;
        }

        /**
         * @param indexName Elasticsearch index name (optional). Default value: "default".
         * @return builder
         */
        public KnowEngineElasticsearchContentRetriever.Builder indexName(String indexName) {
            this.indexName = indexName;
            return this;
        }

        /**
         * @param configuration the configuration to use
         * @return builder
         */
        public KnowEngineElasticsearchContentRetriever.Builder configuration(ElasticsearchConfiguration configuration) {
            this.configuration = configuration;
            return this;
        }

        public KnowEngineElasticsearchContentRetriever.Builder embeddingModel(EmbeddingModel embeddingModel) {
            this.embeddingModel = embeddingModel;
            return this;
        }

        public KnowEngineElasticsearchContentRetriever.Builder maxResults(int maxResults) {
            this.maxResults = maxResults;
            return this;
        }

        public KnowEngineElasticsearchContentRetriever.Builder minScore(double minScore) {
            this.minScore = minScore;
            return this;
        }

        public KnowEngineElasticsearchContentRetriever.Builder filter(Filter filter) {
            this.filter = filter;
            return this;
        }


        public KnowEngineElasticsearchContentRetriever.Builder knowledgeSegmentService(KnowledgeSegmentService knowledgeSegmentService) {
            this.knowledgeSegmentService = knowledgeSegmentService;
            return this;
        }

        public KnowEngineElasticsearchContentRetriever build() {
            return new KnowEngineElasticsearchContentRetriever(
                    configuration, restClient, indexName, embeddingModel, maxResults, minScore, filter, knowledgeSegmentService);
        }
    }
}