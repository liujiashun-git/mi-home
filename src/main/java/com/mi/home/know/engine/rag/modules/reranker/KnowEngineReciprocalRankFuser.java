package com.mi.home.know.engine.rag.modules.reranker;

import com.mi.home.know.engine.rag.model.KnowEngineDefaultContent;
import com.mi.home.know.engine.rag.modules.ContentUtil;
import dev.langchain4j.rag.content.Content;

import java.util.*;

import static dev.langchain4j.internal.ValidationUtils.ensureBetween;

/**
 * Implementation of Reciprocal Rank Fusion.
 * <br>
 * A comprehensive explanation can be found
 * <a href="https://learn.microsoft.com/en-us/azure/search/hybrid-search-ranking">here</a>.
 * <p>
 * <b>本项目的两个自选动作：</b>
 * <ol>
 *   <li><b>带权重</b>。标准 RRF 各路等权；这里按检索来源（{@code retrievalSource}）取权重，
 *       因为不同路（向量 / 关键词 / 图 / SQL）在业务上的可信度不同。</li>
 *   <li><b>融合后归一化</b>（{@link #fuseWeighted}）。标准 RRF 不归一化，
 *       原始值极小（k=60 时单路最高约 1/61 ≈ 0.016），
 *       后续跟单路结果或去冗算法比较时会被压过去，所以除以 maxRRF 压到 [0,1]。</li>
 * </ol>
 * <p>
 * 按「名次」融合而不是按「分数」融合是 RRF 的立身之本：两路的分数量纲不同，不可直接比较。
 */
public class KnowEngineReciprocalRankFuser {

    /**
     * 未标注来源时的默认权重
     */
    public static final double DEFAULT_WEIGHT = 1.0;

    /**
     * Fuses multiple {@code List<Content>} into a single {@code List<Content>}
     * using the Reciprocal Rank Fusion (RRF) algorithm with k=60.
     *
     * @param listsOfContents A {@link Collection} of {@code List<Content>} to be fused together.
     * @return A single {@code List<Content>}, the result of the fusion.
     */
    public static List<Content> fuse(Collection<List<KnowEngineDefaultContent>> listsOfContents) {
        return fuse(listsOfContents, 60);
    }

    /**
     * Fuses multiple {@code List<Content>} into a single {@code List<Content>}
     * using the Reciprocal Rank Fusion (RRF) algorithm.
     *
     * @param listsOfContents A {@link Collection} of {@code List<Content>} to be fused together.
     * @param k               A ranking constant used to control the influence of individual ranks
     *                        from different ranked lists being combined. A common value used is 60,
     *                        based on empirical studies. However, the optimal value may vary depending
     *                        on the specific application and the characteristics of the data.
     *                        A larger value diminishes the differences between the ranks,
     *                        leading to a more uniform distribution of fusion scores.
     *                        A smaller value amplifies the importance of the top-ranked items in each list.
     *                        K must be greater than or equal to 1.
     * @return A single {@code List<Content>}, the result of the fusion.
     */
    public static List<Content> fuse(Collection<List<KnowEngineDefaultContent>> listsOfContents, int k) {
        ensureBetween(k, 1, Integer.MAX_VALUE, "k");

        Map<Content, Double> scores = new LinkedHashMap<>();
        for (List<KnowEngineDefaultContent> singleListOfContent : listsOfContents) {
            for (int i = 0; i < singleListOfContent.size(); i++) {
                Content content = singleListOfContent.get(i);
                double currentScore = scores.getOrDefault(content, 0.0);
                int rank = i + 1;
                double newScore = currentScore + 1.0 / (k + rank);
                scores.put(content, newScore);
            }
        }

        List<Content> fused = new ArrayList<>(scores.keySet());
        fused.sort(Comparator.comparingDouble(scores::get).reversed());
        return fused;
    }

    /**
     * 带权重、带归一化的 RRF 融合。
     * <p>
     * 每一路先按内容自身的 {@code retrievalSource} 取权重，再按名次累加
     * {@code weight / (k + rank)}；最后整体除以 maxRRF 归一化到 [0,1]，
     * 并把归一化后的分数写回 {@code rrfScore} 元数据。
     *
     * @param listsOfContents 各路检索结果。同一个 list 内的内容应来自同一路（同一来源）
     * @param k               名次平滑常数，常用 60
     * @param weightsBySource 来源 → 权重。缺省用 {@code defaultWeight}
     * @param defaultWeight   未命中 weightsBySource 时的默认权重
     * @return 融合并按分数降序排列的内容列表
     */
    public static List<Content> fuseWeighted(Collection<List<Content>> listsOfContents,
                                            int k,
                                            Map<String, Double> weightsBySource,
                                            double defaultWeight) {
        ensureBetween(k, 1, Integer.MAX_VALUE, "k");

        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, Content> contentByKey = new LinkedHashMap<>();
        double maxRrf = 0.0;

        for (List<Content> singleList : listsOfContents) {
            if (singleList == null || singleList.isEmpty()) {
                continue;
            }
            double weight = resolveWeight(singleList, weightsBySource, defaultWeight);
            // 该路理论上能拿到的最高分：所有相关块都排在第 1 名时的和
            maxRrf += weight / (k + 1);

            int rank = 0;
            for (Content content : singleList) {
                rank++;
                String key = contentKey(content);
                contentByKey.putIfAbsent(key, content);
                scores.merge(key, weight / (k + rank), Double::sum);
            }
        }

        if (scores.isEmpty() || maxRrf <= 0.0) {
            return Collections.emptyList();
        }

        final double normalizer = maxRrf;
        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(entry -> ContentUtil.withRrfScore(contentByKey.get(entry.getKey()), entry.getValue() / normalizer))
                .toList();
    }

    /**
     * 一路的权重取该路第一条内容的来源。同一路内部来源一致，取第一条即可。
     */
    private static double resolveWeight(List<Content> singleList,
                                        Map<String, Double> weightsBySource,
                                        double defaultWeight) {
        if (weightsBySource == null || weightsBySource.isEmpty()) {
            return defaultWeight;
        }
        String source = ContentUtil.getRetrievalSource(singleList.get(0));
        if (source == null) {
            return defaultWeight;
        }
        return weightsBySource.getOrDefault(source, defaultWeight);
    }

    /**
     * 跨路去重的 key：优先用 ES 文档 id（同一篇文档被向量路和关键词路同时召回时能对上），
     * 其次用 chunkId，最后退回文本本身。
     */
    private static String contentKey(Content content) {
        Object embeddingId = content.metadata() == null ? null
                : content.metadata().get(dev.langchain4j.rag.content.ContentMetadata.EMBEDDING_ID);
        if (embeddingId != null) {
            return "e:" + embeddingId;
        }
        if (content.textSegment() != null && content.textSegment().metadata() != null) {
            Object chunkId = content.textSegment().metadata().toMap().get("chunkId");
            if (chunkId != null) {
                return "c:" + chunkId;
            }
        }
        return "t:" + (content.textSegment() == null ? "" : content.textSegment().text());
    }
}
