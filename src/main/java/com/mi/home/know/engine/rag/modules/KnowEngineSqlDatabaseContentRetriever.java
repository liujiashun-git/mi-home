package com.mi.home.know.engine.rag.modules;

import dev.langchain4j.experimental.rag.content.retriever.sql.SqlDatabaseContentRetriever;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.input.PromptTemplate;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import lombok.extern.slf4j.Slf4j;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static com.mi.home.know.engine.rag.constant.MetadataKeyConstant.SOURCE_SQL;

/**
 * 自定义 SQL 数据库内容检索器
 * <p>
 * 基于 {@link SqlDatabaseContentRetriever}，增加了兜底检索逻辑：
 * 当 SQL 查询结果为空时，自动降级使用知识库检索器（fallbackRetriever）进行检索，
 * 确保用户总能获得有意义的回答。
 * <p>
 * <b>使用场景：</b>
 * <ul>
 *   <li>SQL 数据库中没有匹配数据时，自动切换到向量/全文知识库检索</li>
 *   <li>数据库查询异常时，优雅降级到知识库检索</li>
 * </ul>
 *
 * @see SqlDatabaseContentRetriever
 * @see ContentRetriever
 */
@Slf4j
public class KnowEngineSqlDatabaseContentRetriever implements ContentRetriever {

    private final SqlDatabaseContentRetriever sqlDatabaseContentRetriever;
    private final ContentRetriever fallbackRetriever;

    /**
     * 表格类 chunk 探测检索器（可选）。
     * <p>
     * 只用它来回答一个问题：「这次的问题有没有把某张表的相关内容召回来？」
     * 召回为空即说明这次的问题跟结构化数据无关，可以直接跳过 Text2SQL，
     * 省下一次 LLM 调用 + 一次 SQL 生成。
     */
    private final ContentRetriever dataTableProbe;

    /**
     * 意图识别是否抽到了强结构化实体（car_id / order_id）。
     * <p>
     * 这是另一个零成本的闸门判据：有实体说明问题天然指向结构化数据，
     * 无论有没有上传过表格都应该放行。
     */
    private final boolean structuredEntityPresent;

    public KnowEngineSqlDatabaseContentRetriever(SqlDatabaseContentRetriever sqlDatabaseContentRetriever,
                                                 ContentRetriever fallbackRetriever) {
        this(sqlDatabaseContentRetriever, fallbackRetriever, null, false);
    }

    public KnowEngineSqlDatabaseContentRetriever(SqlDatabaseContentRetriever sqlDatabaseContentRetriever,
                                                 ContentRetriever fallbackRetriever,
                                                 ContentRetriever dataTableProbe,
                                                 boolean structuredEntityPresent) {
        this.sqlDatabaseContentRetriever = sqlDatabaseContentRetriever;
        this.fallbackRetriever = fallbackRetriever;
        this.dataTableProbe = dataTableProbe;
        this.structuredEntityPresent = structuredEntityPresent;
    }

    @Override
    public List<Content> retrieve(Query query) {
        // ========== 闸门：这次的问题真的跟结构化数据有关吗？ ==========
        // 判据有二，任一成立即放行。两个都是"判有/无"，不是"猜该不该"：
        //   ① 意图识别抽到了强结构化实体（car_id / order_id）
        //   ② 检索召回了表格类 chunk（table_summary / table_column）
        // 闸门而不是分类器：判错也只是少跑一路，主干检索结果照常在。
        if (!isSqlGateOpen(query)) {
            log.info("未召回表格类 chunk 且无结构化实体，跳过 Text2SQL 检索, query: {}", query.text());
            return Collections.emptyList();
        }

        List<Content> results;
        try {
            String newQuery = "我的问题是：" + query.text() + ", 我的用户Id是: 123321" + ", 现在是：" + LocalDateTime.now();
            query = new Query(newQuery, query.metadata());
            results = sqlDatabaseContentRetriever.retrieve(query);
        } catch (Exception e) {
            log.warn("SQL 检索异常，降级使用知识库检索, query: {}", query.text(), e);
            return fallbackRetriever.retrieve(query);
        }

        if (results == null || results.isEmpty() || isSqlResultEmpty(results)) {
            log.info("SQL 检索结果为空，降级使用知识库检索, query: {}", query.text());
            return fallbackRetriever.retrieve(query);
        }

        // SQL 结构化查询结果直接透传，不参与后续重排序/融合；同时标注来源为 sql
        return results.stream()
                .map(ContentUtil::markAsSkipRerank)
                .map(content -> ContentUtil.markRetrievalSource(content, SOURCE_SQL))
                .collect(Collectors.toList());
    }

    /**
     * SQL 闸门判定。任一条成立即放行；探测本身失败时不拦截（宁可多跑一次，也不要漏答）。
     */
    private boolean isSqlGateOpen(Query query) {
        if (structuredEntityPresent) {
            return true;
        }
        if (dataTableProbe == null) {
            return true;
        }
        try {
            return !dataTableProbe.retrieve(query).isEmpty();
        } catch (Exception e) {
            log.warn("表格 chunk 探测失败，放行 SQL 检索。query={}", query.text(), e);
            return true;
        }
    }

    /**
     * 判断 SQL 查询结果是否实际为空
     * <p>
     * SqlDatabaseContentRetriever 在查询无数据时不会返回空 list，
     * 而是返回形如：
     * <pre>
     * Result of executing '...SQL...':
     * column1,column2
     * </pre>
     * 即只包含列名头部，没有实际数据行。
     * <p>
     * 通过定位最后一个 "':\n" 标记（SQL 语句描述结束位置），
     * 判断列名行之后是否存在实际数据行，避免 SQL 语句本身含换行符导致误判。
     */
    private boolean isSqlResultEmpty(List<Content> results) {
        if (results.size() != 1) {
            return false;
        }
        String text = results.get(0).textSegment().text();
        if (!text.startsWith("Result of executing '")) {
            return false;
        }
        // ":\n" 标记列名开始，列名后的第一个 "\n" 标记数据开始
        int columnStartIndex = text.indexOf(":\n");
        if (columnStartIndex == -1) {
            return false;
        }
        // ":\n" 之后是列名行，找列名行结束的 "\n"（即数据开始位置）
        int dataStartIndex = text.indexOf('\n', columnStartIndex + 2);
        // 列名后没有换行符，或换行符后无实际内容，则表示无数据
        return dataStartIndex == -1 || text.substring(dataStartIndex + 1).trim().isEmpty();
    }

    /**
     * 获取内部的 SqlDatabaseContentRetriever 实例
     */
    public SqlDatabaseContentRetriever getSqlDatabaseContentRetriever() {
        return sqlDatabaseContentRetriever;
    }

    /**
     * 获取兜底的知识库检索器
     */
    public ContentRetriever getFallbackRetriever() {
        return fallbackRetriever;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private DataSource dataSource;
        private PromptTemplate promptTemplate;
        private String databaseStructure;
        private ChatModel chatModel;
        private ContentRetriever fallbackRetriever;
        private ContentRetriever dataTableProbe;
        private boolean structuredEntityPresent;

        public Builder dataSource(DataSource dataSource) {
            this.dataSource = dataSource;
            return this;
        }

        /**
         * 表格类 chunk 探测检索器。传入后，未召回任何表格类 chunk 且无结构化实体时跳过 Text2SQL。
         */
        public Builder dataTableProbe(ContentRetriever dataTableProbe) {
            this.dataTableProbe = dataTableProbe;
            return this;
        }

        /**
         * 意图识别是否抽到了强结构化实体（car_id / order_id）
         */
        public Builder structuredEntityPresent(boolean structuredEntityPresent) {
            this.structuredEntityPresent = structuredEntityPresent;
            return this;
        }

        public Builder promptTemplate(PromptTemplate promptTemplate) {
            this.promptTemplate = promptTemplate;
            return this;
        }

        public Builder databaseStructure(String databaseStructure) {
            this.databaseStructure = databaseStructure;
            return this;
        }

        public Builder chatModel(ChatModel chatModel) {
            this.chatModel = chatModel;
            return this;
        }

        public Builder fallbackRetriever(ContentRetriever fallbackRetriever) {
            this.fallbackRetriever = fallbackRetriever;
            return this;
        }

        public KnowEngineSqlDatabaseContentRetriever build() {
            SqlDatabaseContentRetriever sqlRetriever = SqlDatabaseContentRetriever.builder()
                    .dataSource(dataSource)
                    .promptTemplate(promptTemplate)
                    .databaseStructure(databaseStructure)
                    .chatModel(chatModel)
                    .build();
            return new KnowEngineSqlDatabaseContentRetriever(sqlRetriever, fallbackRetriever,
                    dataTableProbe, structuredEntityPresent);
        }
    }
}
