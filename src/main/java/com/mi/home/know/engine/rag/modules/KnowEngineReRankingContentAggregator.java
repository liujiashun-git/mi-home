package com.mi.home.know.engine.rag.modules;

import com.mi.home.know.engine.rag.modules.reranker.KnowEngineReciprocalRankFuser;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.scoring.ScoringModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.aggregator.ContentAggregator;
import dev.langchain4j.rag.content.aggregator.DefaultContentAggregator;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.rag.query.transformer.ExpandingQueryTransformer;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static dev.langchain4j.internal.Exceptions.illegalArgument;
import static dev.langchain4j.internal.Utils.getOrDefault;
import static dev.langchain4j.rag.content.ContentMetadata.RERANKED_SCORE;
import static java.util.Collections.emptyList;

/**
 * 带精排的聚合器：先做加权 RRF 融合，再用 {@link ScoringModel}（本项目是 BGE-Reranker）精排。
 * <p>
 * <b>"失败不空手"的降级链</b>（对齐生产做法）：
 * <ol>
 *   <li>正常：按 {@link #minScore} 过滤</li>
 *   <li>全被拒：阈值 ×0.7 再筛一次，但不低于 {@link #DEFAULT_DEGRADE_FLOOR}</li>
 *   <li>仍为空：保留 top1，前提是它的分数 ≥ {@link #DEFAULT_FALLBACK_MIN_SCORE}</li>
 *   <li>精排本身抛异常：退回 RRF 顺序，不做模型打分</li>
 * </ol>
 * 不为"空答案"让整条链路崩掉——召回不足是常态，不是异常。
 *
 * @see DefaultContentAggregator
 */
@Slf4j
public class KnowEngineReRankingContentAggregator implements ContentAggregator {

    /**
     * 阈值降级的下限：无论怎么降，都不低于这个分
     */
    public static final double DEFAULT_DEGRADE_FLOOR = 0.3;

    /**
     * 最终兜底时，top1 至少要达到的分数
     */
    public static final double DEFAULT_FALLBACK_MIN_SCORE = 0.15;

    /**
     * 阈值降级的倍率
     */
    private static final double DEGRADE_RATIO = 0.7;

    public static final Function<Map<Query, Collection<List<Content>>>, Query> DEFAULT_QUERY_SELECTOR =
            (queryToContents) -> {
                if (queryToContents.size() > 1) {
                    throw illegalArgument(
                            "The 'queryToContents' contains %s queries, making the re-ranking ambiguous. " +
                                    "Because there are multiple queries, it is unclear which one should be " +
                                    "used for re-ranking. Please provide a 'querySelector' in the constructor/builder.",
                            queryToContents.size()
                    );
                }
                return queryToContents.keySet().iterator().next();
            };

    /**
     * 精排模型。<b>允许为 {@code null}</b>：表示未启用精排（模型文件缺失），
     * 此时 {@link #reRankAndFilter} 会直接按 RRF 融合分排序，链路照常走完。
     */
    private final ScoringModel scoringModel;
    private final Function<Map<Query, Collection<List<Content>>>, Query> querySelector;
    private final Double minScore;
    private final Integer maxResults;

    /**
     * RRF 名次平滑常数
     */
    private final int rrfK;

    /**
     * 来源 → RRF 权重
     */
    private final Map<String, Double> weightsBySource;

    private final double defaultWeight;

    public KnowEngineReRankingContentAggregator(ScoringModel scoringModel) {
        this(scoringModel, DEFAULT_QUERY_SELECTOR, null);
    }

    public KnowEngineReRankingContentAggregator(ScoringModel scoringModel,
                                                Function<Map<Query, Collection<List<Content>>>, Query> querySelector,
                                                Double minScore) {
        this(scoringModel, querySelector, minScore, null);
    }

    public KnowEngineReRankingContentAggregator(ScoringModel scoringModel,
                                                Function<Map<Query, Collection<List<Content>>>, Query> querySelector,
                                                Double minScore,
                                                Integer maxResults) {
        this(scoringModel, querySelector, minScore, maxResults, 60, null,
                KnowEngineReciprocalRankFuser.DEFAULT_WEIGHT);
    }

    public KnowEngineReRankingContentAggregator(ScoringModel scoringModel,
                                                Function<Map<Query, Collection<List<Content>>>, Query> querySelector,
                                                Double minScore,
                                                Integer maxResults,
                                                int rrfK,
                                                Map<String, Double> weightsBySource,
                                                double defaultWeight) {
        // scoringModel 允许为 null：表示未启用精排，走"按 RRF 融合分排序"的降级路径
        this.scoringModel = scoringModel;
        this.querySelector = getOrDefault(querySelector, DEFAULT_QUERY_SELECTOR);
        this.minScore = minScore;
        this.maxResults = getOrDefault(maxResults, Integer.MAX_VALUE);
        this.rrfK = rrfK;
        this.weightsBySource = weightsBySource;
        this.defaultWeight = defaultWeight;
    }

    public static ReRankingContentAggregatorBuilder builder() {
        return new ReRankingContentAggregatorBuilder();
    }

    @Override
    public List<Content> aggregate(Map<Query, Collection<List<Content>>> queryToContents) {

        if (queryToContents == null || queryToContents.isEmpty()) {
            return emptyList();
        }

        // 精排需要一个"基准 query"，由 querySelector 选出
        Query query = querySelector.apply(queryToContents);

        // 1. 把各路结果摊平，做一次带权重、带归一化的 RRF 融合。
        //    同一个 list 内的内容来自同一路（同一 retriever），权重按 list 内第一条内容的来源取。
        List<List<Content>> lists = queryToContents.values().stream()
                .flatMap(Collection::stream)
                .filter(list -> list != null && !list.isEmpty())
                .collect(Collectors.toList());

        List<Content> fusedContents = KnowEngineReciprocalRankFuser.fuseWeighted(
                lists, rrfK, weightsBySource, defaultWeight);

        if (fusedContents.isEmpty()) {
            return fusedContents;
        }

        // 2. 精排 + 降级兜底
        return reRankAndFilter(fusedContents, query);
    }

    /**
     * 精排，并带三级降级。
     * <p>
     * 第 0 级：根本没有精排模型（{@code scoringModel == null}）→ 直接按 RRF 融合分排序截断。
     * 这条路径让"没下模型"和"模型加载失败"都不至于把检索链路拖垮。
     */
    protected List<Content> reRankAndFilter(List<Content> contents, Query query) {
        if (contents.isEmpty()) {
            return emptyList();
        }

        // 第 0 级：未启用精排 → 按 RRF 融合分排序
        if (scoringModel == null) {
            return topKByRrfScore(contents);
        }

        List<TextSegment> segments = contents.stream()
                .map(Content::textSegment)
                .collect(Collectors.toList());

        List<Double> scores;
        try {
            scores = scoringModel.scoreAll(segments, query.text()).content();
        } catch (Exception e) {
            // 精排不可用时退回 RRF 顺序，保证还有答案可给
            log.warn("精排模型调用失败，降级为按 RRF 融合分排序. query={}", query.text(), e);
            return topKByRrfScore(contents);
        }

        List<Map.Entry<TextSegment, Double>> scored = new ArrayList<>(segments.size());
        for (int i = 0; i < segments.size(); i++) {
            double score = i < scores.size() && scores.get(i) != null ? scores.get(i) : 0.0;
            scored.add(new AbstractMap.SimpleEntry<>(segments.get(i), score));
        }
        scored.sort(Map.Entry.<TextSegment, Double>comparingByValue().reversed());

        double threshold = minScore != null ? minScore : 0.0;

        // 第 1 级：按配置阈值过滤
        List<Map.Entry<TextSegment, Double>> kept = filterAbove(scored, threshold);

        // 第 2 级：全被拒 → 阈值降级，但不低于 DEGRADE_FLOOR
        if (kept.isEmpty() && threshold > DEFAULT_DEGRADE_FLOOR) {
            double degraded = Math.max(threshold * DEGRADE_RATIO, DEFAULT_DEGRADE_FLOOR);
            kept = filterAbove(scored, degraded);
            if (!kept.isEmpty()) {
                log.info("精排结果全部低于阈值 {}，降级到 {} 后保留 {} 条", threshold, degraded, kept.size());
            }
        }

        // 第 3 级：仍为空 → 保留 top1（前提是分数达标）
        if (kept.isEmpty()) {
            Map.Entry<TextSegment, Double> top = scored.get(0);
            if (top.getValue() >= DEFAULT_FALLBACK_MIN_SCORE) {
                kept = List.of(top);
                log.info("精排降级后仍为空，兜底保留 top1（score={}）", top.getValue());
            }
        }

        return kept.stream()
                .limit(maxResults)
                .map(entry -> Content.from(entry.getKey(), Map.of(RERANKED_SCORE, entry.getValue())))
                .collect(Collectors.toList());
    }

    private List<Map.Entry<TextSegment, Double>> filterAbove(List<Map.Entry<TextSegment, Double>> scored, double threshold) {
        return scored.stream()
                .filter(entry -> entry.getValue() >= threshold)
                .collect(Collectors.toList());
    }

    /**
     * 未启用精排时的排序：按 RRF 融合分（已归一化到 [0,1]）降序取 top-K。
     * <p>
     * 这就是"粗排的结果直接当最终结果"——少了精排的语义打分，但保留了多路融合的排序信息，
     * 比直接截断前 K 条要合理。
     */
    private List<Content> topKByRrfScore(List<Content> contents) {
        return contents.stream()
                .sorted(Comparator.comparingDouble(
                        (Content c) -> Optional.ofNullable(ContentUtil.getRrfScore(c)).orElse(0.0)).reversed())
                .limit(maxResults)
                .collect(Collectors.toList());
    }

    public static class ReRankingContentAggregatorBuilder {
        private ScoringModel scoringModel;
        private Function<Map<Query, Collection<List<Content>>>, Query> querySelector;
        private Double minScore;
        private Integer maxResults;
        private int rrfK = 60;
        private Map<String, Double> weightsBySource;
        private double defaultWeight = KnowEngineReciprocalRankFuser.DEFAULT_WEIGHT;

        ReRankingContentAggregatorBuilder() {
        }

        public ReRankingContentAggregatorBuilder scoringModel(ScoringModel scoringModel) {
            this.scoringModel = scoringModel;
            return this;
        }

        public ReRankingContentAggregatorBuilder querySelector(Function<Map<Query, Collection<List<Content>>>, Query> querySelector) {
            this.querySelector = querySelector;
            return this;
        }

        public ReRankingContentAggregatorBuilder minScore(Double minScore) {
            this.minScore = minScore;
            return this;
        }

        public ReRankingContentAggregatorBuilder maxResults(Integer maxResults) {
            this.maxResults = maxResults;
            return this;
        }

        public ReRankingContentAggregatorBuilder rrfK(int rrfK) {
            this.rrfK = rrfK;
            return this;
        }

        public ReRankingContentAggregatorBuilder weightsBySource(Map<String, Double> weightsBySource) {
            this.weightsBySource = weightsBySource;
            return this;
        }

        public ReRankingContentAggregatorBuilder defaultWeight(double defaultWeight) {
            this.defaultWeight = defaultWeight;
            return this;
        }

        public KnowEngineReRankingContentAggregator build() {
            return new KnowEngineReRankingContentAggregator(this.scoringModel, this.querySelector,
                    this.minScore, this.maxResults, this.rrfK, this.weightsBySource, this.defaultWeight);
        }
    }
}
