package com.mi.home.know.engine.rag.modules;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;

import static com.mi.home.know.engine.rag.constant.MetadataKeyConstant.RETRIEVAL_SOURCE;
import static com.mi.home.know.engine.rag.constant.MetadataKeyConstant.RRF_SCORE;
import static com.mi.home.know.engine.rag.constant.MetadataKeyConstant.SKIP_RERANK;

/**
 * RAG 内容工具类
 * <p>
 * 所有标记都写在 {@link TextSegment} 的 metadata 上（String 键），
 * 而不是 {@link Content#metadata()}（{@code ContentMetadata} 枚举键，无法扩展）。
 */
public class ContentUtil {

    private ContentUtil() {
    }

    /**
     * 将内容标记为跳过重排序/融合
     * <p>
     * SQL/Cypher 等结构化查询结果带有该标记后，聚合器会直接透传，不再进行 RRF 融合和 scoring model 重排序。
     *
     * @param content 原始内容
     * @return 带有 skipRerank 标记的内容
     */
    public static Content markAsSkipRerank(Content content) {
        return withSegmentMetadata(content, SKIP_RERANK, "true");
    }

    /**
     * 判断内容是否标记为跳过重排序/融合
     */
    public static boolean isSkipRerank(Content content) {
        if (content == null || content.textSegment() == null || content.textSegment().metadata() == null) {
            return false;
        }
        return "true".equals(String.valueOf(content.textSegment().metadata().toMap().get(SKIP_RERANK)));
    }

    /**
     * 标注内容的检索来源。
     * <p>
     * 用途有二：
     * ① RRF 按来源取权重（两路检索结果分数口径不同，不能直接比分数，只能比名次 + 权重）；
     * ② 让模型看到"这是不同来源的证据"，而不是把图/SQL 结果硬编一个相似度分去和向量分混排。
     *
     * @param content 原始内容
     * @param source  来源，见 {@code MetadataKeyConstant.SOURCE_*}
     */
    public static Content markRetrievalSource(Content content, String source) {
        return withSegmentMetadata(content, RETRIEVAL_SOURCE, source);
    }

    /**
     * 读取内容的检索来源，没有标注时返回 {@code null}
     */
    public static String getRetrievalSource(Content content) {
        return readSegmentMetadata(content, RETRIEVAL_SOURCE);
    }

    /**
     * 读取内容的 RRF 融合分（已归一化到 [0,1]），没有时返回 {@code null}
     */
    public static Double getRrfScore(Content content) {
        String value = readSegmentMetadata(content, RRF_SCORE);
        if (value == null) {
            return null;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 写入 RRF 融合分
     */
    public static Content withRrfScore(Content content, double rrfScore) {
        return withSegmentMetadata(content, RRF_SCORE, String.valueOf(rrfScore));
    }

    /**
     * 复制出一个 metadata 上多写了一个键值的新 Content，原 Content 的
     * {@link Content#metadata()}（score / embeddingId 等）原样保留。
     */
    public static Content withSegmentMetadata(Content content, String key, String value) {
        if (content == null || content.textSegment() == null) {
            return content;
        }
        TextSegment original = content.textSegment();
        Metadata metadata = original.metadata() != null
                ? Metadata.from(original.metadata().toMap())
                : new Metadata();
        metadata.put(key, value);
        return Content.from(TextSegment.from(original.text(), metadata), content.metadata());
    }

    private static String readSegmentMetadata(Content content, String key) {
        if (content == null || content.textSegment() == null || content.textSegment().metadata() == null) {
            return null;
        }
        Object value = content.textSegment().metadata().toMap().get(key);
        return value == null ? null : String.valueOf(value);
    }
}
