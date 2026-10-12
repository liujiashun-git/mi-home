package com.mi.home.know.engine.rag.constant;

/**
 * 元数据的键常量
 */
public class MetadataKeyConstant {
    /**
     * 文件名称
     */
    public static final String FILE_NAME = "fileName";


    public static final String DOC_ID = "docId";

    public static final String CHUNK_ID = "chunkId";

    public static final String EMBEDDING_ID = "EMBEDDING_ID";

    /**
     * 父块ID
     */
    public static final String PARENT_CHUNK_ID = "parentChunkId";

    /**
     * 同级块ID
     */
    public static final String BROTHER_CHUNK_ID = "brotherChunkId";


    public static final String BROTHER_CHUNK_INDEX = "brotherChunkIndex";

    public static final String BROTHER_CHUNK_TOTAL = "brotherChunkTotal";

    /**
     * 头级别
     */
    public static final String HEADER_LEVEL = "headerLevel";

    /**
     * 访问权限
     */
    public static final String ACCESSIBLE_BY = "accessibleBy";

    /**
     * 文件地址
     */
    public static final String URL = "url";

    /**
     * 文件版本
     */
    public static final String VERSION = "version";

    /**
     * 分类
     */
    public static final String CATEGORY = "category";

    /**
     * 摘要
     */
    public static final String SUMMARY = "summary";

    /**
     * 关键字
     */
    public static final String KEYWORDS = "keywords";

    /**
     * 跳过embedding标记，true表示不需要做embedding
     */
    public static final String SKIP_EMBEDDING = "skipEmbedding";

    /**
     * 跳过重排序/融合标记，true表示该内容来自结构化查询（SQL/Cypher），不需要参与重排序和融合
     */
    public static final String SKIP_RERANK = "skipRerank";

    // ==================== 表格类 chunk 相关 ====================

    /**
     * chunk 内容类型。用于区分普通文本块、表格行块、表摘要块、列描述块。
     * 取值见 {@link #TYPE_TABLE_ROW} / {@link #TYPE_TABLE_SUMMARY} / {@link #TYPE_TABLE_COLUMN}
     */
    public static final String CONTENT_TYPE = "contentType";

    /**
     * 表格数据行块。行转文本产生，承载"具体值"
     */
    public static final String TYPE_TABLE_ROW = "table_row";

    /**
     * 表摘要块。概括表的主题/字段/规模/场景，承载"这张表是什么"，用于被检索发现
     */
    public static final String TYPE_TABLE_SUMMARY = "table_summary";

    /**
     * 列描述块。逐列说明字段含义/类型/用途，承载"这张表能回答什么"
     */
    public static final String TYPE_TABLE_COLUMN = "table_column";

    /**
     * 文档摘要块。主干入库完成后异步生成，承载"这份文档讲什么"（概览形态）。
     * gist 放正文最前——向量模型对开头的权重更高。
     */
    public static final String TYPE_DOC_SUMMARY = "doc_summary";

    /**
     * 生成问题条目（doc2query）。问题不落 chunk，只做指回原 chunk 的索引条目：
     * 索引里的文本 = 问题（问句形态），metadata.chunkId 指回原 chunk。
     * 检索命中后由检索器换回原 chunk 正文。存储归存储、索引归索引。
     */
    public static final String TYPE_GENERATED_QUESTION = "generated_question";

    /**
     * chunk 关联的生成问题列表。存于原 chunk 的 metadata JSON 中
     */
    public static final String GENERATED_QUESTIONS = "generatedQuestions";

    /**
     * 问题条目的唯一 ID（索引里同一 chunk 会有多条问题记录，用于区分）
     */
    public static final String QUESTION_ID = "questionId";

    /**
     * 图谱抽取完成标记（写在原 chunk 的 metadata 上）。
     * <p>
     * 用途有二：
     * ① <b>幂等</b>：已有该标记的块直接跳过，抽取任务可安全重跑；
     * ② <b>区分"抽空"与"失败"</b>：模型正常返回空数组、或用散文拒绝输出（目录页这类没有实体的块），
     *    都算"已完成"并打上标记——同样的输入重放没有意义，不打标记就会被反复重抽、白烧 LLM 额度。
     *    只有调用异常/输出不合契约才不留标记，留给下次整篇重抽时再试。
     */
    public static final String GRAPH_EXTRACTED = "graphExtracted";

    /**
     * 检索命中的原 chunk 附带"是通过哪个问题命中"的标注，供引用卡片展示
     */
    public static final String MATCHED_QUESTION = "matchedQuestion";

    /**
     * sheet 名称（Excel 定位用）
     */
    public static final String SHEET_NAME = "sheetName";

    /**
     * 行号区间（Excel 定位用，1-based，含首尾）
     */
    public static final String ROW_START = "rowStart";

    public static final String ROW_END = "rowEnd";

    /**
     * 引用锚点的序列化表示。不同格式用不同定位方式：
     * PDF 用页码 + bbox，Excel 用 sheet + 行号，文本用字符偏移。
     */
    public static final String LOCATOR = "locator";

    // ==================== 检索来源标注 ====================

    /**
     * 检索来源。RRF 按来源取权重，同时让模型看到"这是不同来源的证据"。
     * 取值见 {@link #SOURCE_VECTOR} / {@link #SOURCE_KEYWORD} / {@link #SOURCE_SQL} / {@link #SOURCE_GRAPH}
     */
    public static final String RETRIEVAL_SOURCE = "retrievalSource";

    /**
     * 向量检索（KNN）
     */
    public static final String SOURCE_VECTOR = "vector";

    /**
     * 关键词检索（BM25 / 全文）
     */
    public static final String SOURCE_KEYWORD = "keyword";

    /**
     * 结构化查询（SQL）
     */
    public static final String SOURCE_SQL = "sql";

    /**
     * 图检索（Cypher）
     */
    public static final String SOURCE_GRAPH = "graph";

    /**
     * RRF 融合分（按名次融合、再按 maxRRF 归一化到 [0,1]）
     */
    public static final String RRF_SCORE = "rrfScore";
}
