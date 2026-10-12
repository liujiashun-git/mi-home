package com.mi.home.know.engine.rag.modules;

import com.mi.home.know.engine.ai.model.IntentRecognitionResult;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.rag.query.router.QueryRouter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 闸门式查询路由器（替代 {@link KnowEngineQueryRouter} 的互斥路由）。
 * <p>
 * <b>核心结构：主干无条件先跑 + 其他路各自挂闸门叠加</b>
 * <pre>
 * ├─ 主干（无条件并行，永远跑，不受任何路由影响）
 * │     ├─ 向量检索
 * │     └─ 关键词检索
 * │
 * └─ 叠加层（各自独立二元判定，可同时成立）
 *       ├─ 图检索（实体图）：查询侧抽出实体才查图
 *       ├─ 图检索（Text2Cypher）：意图识别抽到结构化实体才查图
 *       └─ SQL 检索：由检索器内部的表格 chunk 探测闸门控制
 * </pre>
 * <p>
 * <b>为什么不用"多选一"的互斥路由：</b>
 * <table border="1">
 *   <tr><th></th><th>模式</th><th>判错后果</th></tr>
 *   <tr><td>闸门</td><td>二元：「要不要」走这条路</td><td>不致命 —— 少了这一路，别的路还在</td></tr>
 *   <tr><td>路由</td><td>互斥：「选哪个」</td><td>致命 —— 选错就永久丢掉其他路</td></tr>
 * </table>
 * 凡是"猜"的地方，都改成并行（都能跑）或闸门（只看有/无）。
 * <p>
 * <b>闸门判据为什么可靠：</b>
 * 图闸门判的是「意图识别有没有抽出实体」，这是<b>判有/无</b>；
 * 而不是「猜这个问题该不该查图」，那是<b>猜概率</b>。前者明显更可靠，
 * 而且复用已有的意图识别结果，零额外 LLM 调用。
 * <p>
 * <b>两条图路的闸门为什么不同：</b>
 * Text2Cypher 查的是人工维护的结构化业务图，靠意图识别的槽位（car_id / part_name 等）
 * 就能判"该不该查"，所以挂零成本的闸门；
 * 实体图查的是从文档正文抽出来的实体，需要的是"问题里提到了什么实体"，
 * 意图识别的固定槽位覆盖不到，于是它自己用改写后的 query 做一次实体抽取，
 * 抽不出实体就返回空（<b>判的仍然是有/无，不是该不该</b>）。
 */
@Slf4j
public class KnowEngineGateQueryRouter implements QueryRouter {

    /**
     * 进度提示文案。前端按 {@code [PROGRESS]:} 前缀识别，文案本身可自由替换。
     */
    public static final String PROGRESS_ROUTING = "[PROGRESS]:正在路由您的问题...";

    /**
     * 主干检索器集合（向量 + 关键词）。无条件并行，永远参与。
     */
    private final List<ContentRetriever> trunkRetrievers;

    /**
     * 图检索器（可选）
     */
    private final ContentRetriever graphRetriever;

    /**
     * 实体图检索器（可选）。为 null 表示图谱抽取整体关闭
     */
    private final ContentRetriever entityGraphRetriever;

    /**
     * SQL 检索器（可选）
     */
    private final ContentRetriever sqlRetriever;

    /**
     * 图闸门是否打开
     */
    private final boolean graphGateOpen;

    private final Consumer<String> progressCallback;

    /**
     * 确保路由进度只发送一次（DefaultRetrievalAugmentor 可能对多个 query 多次调用 route）
     */
    private final AtomicBoolean progressSent = new AtomicBoolean(false);

    public KnowEngineGateQueryRouter(List<ContentRetriever> trunkRetrievers,
                                     ContentRetriever graphRetriever,
                                     ContentRetriever entityGraphRetriever,
                                     ContentRetriever sqlRetriever,
                                     boolean graphGateOpen,
                                     Consumer<String> progressCallback) {
        this.trunkRetrievers = trunkRetrievers == null ? Collections.emptyList() : trunkRetrievers;
        this.graphRetriever = graphRetriever;
        this.entityGraphRetriever = entityGraphRetriever;
        this.sqlRetriever = sqlRetriever;
        this.graphGateOpen = graphGateOpen;
        this.progressCallback = progressCallback;
    }

    @Override
    public Collection<ContentRetriever> route(Query query) {
        if (progressCallback != null && progressSent.compareAndSet(false, true)) {
            progressCallback.accept(PROGRESS_ROUTING);
        }

        List<ContentRetriever> selected = new ArrayList<>(trunkRetrievers.size() + 3);

        // 主干：无条件并行，不做任何判断
        selected.addAll(trunkRetrievers);

        // 叠加层：各自独立判定，互不影响，可以同时成立
        if (graphGateOpen && graphRetriever != null) {
            selected.add(graphRetriever);
        } else if (graphRetriever != null) {
            log.debug("图闸门关闭，本次跳过 Text2Cypher 图检索。query={}", query.text());
        }

        // 实体图：不做"该不该查"的判断，它自己会先抽实体，抽不出就返回空
        if (entityGraphRetriever != null) {
            selected.add(entityGraphRetriever);
        }

        // SQL 检索器总是参与；它内部还有一道「表格 chunk 探测」闸门决定是否真的跑 Text2SQL
        if (sqlRetriever != null) {
            selected.add(sqlRetriever);
        }

        return selected;
    }

    /**
     * 图闸门判据：意图识别有没有抽出"图能回答"的实体。
     * <p>
     * 判的是「有没有」，不是「该不该」。任一实体非空即开闸。
     */
    public static boolean hasGraphEntity(IntentRecognitionResult intentResult) {
        if (intentResult == null || intentResult.entities() == null) {
            return false;
        }
        IntentRecognitionResult.Entities e = intentResult.entities();
        return hasText(e.car_id())
                || hasText(e.car_model())
                || hasText(e.order_id())
                || hasText(e.dealer())
                || hasText(e.part_name())
                || hasText(e.function_name());
    }

    /**
     * SQL 闸门的前置判据：意图识别有没有抽出强结构化实体。
     * <p>
     * 有 {@code car_id} / {@code order_id} 说明问题天然指向结构化数据，
     * 无论用户有没有上传过表格，都应该放行 Text2SQL（即业务库查询路径）。
     */
    public static boolean hasStructuredEntity(IntentRecognitionResult intentResult) {
        if (intentResult == null || intentResult.entities() == null) {
            return false;
        }
        IntentRecognitionResult.Entities e = intentResult.entities();
        return hasText(e.car_id()) || hasText(e.order_id());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
