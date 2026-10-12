package com.mi.home.know.engine.document.service;

import com.mi.home.know.engine.rag.modules.splitter.ExcelSplitter;
import dev.langchain4j.data.segment.TextSegment;

import java.util.List;

/**
 * 表摘要 / 列描述生成服务
 * <p>
 * 表格入库时除了「行转文本」chunk（承载具体值）之外，还要额外产出两类 chunk：
 * <ul>
 *   <li>{@code table_summary} —— 200-300 字的表摘要，说明数据主题 / 核心字段 / 数据规模 / 业务场景</li>
 *   <li>{@code table_column}  —— 逐列的字段描述，说明字段含义 / 类型 / 业务用途</li>
 * </ul>
 * 这两类 chunk 的作用不是承载数据，而是让「这张表」本身能被检索到——
 * 检索命中它们即意味着「这次的问题和这张表有关」，可以据此决定是否启用后续的昂贵操作。
 *
 * @see ExcelSplitter#parseMeta(byte[])
 */
public interface ExcelTableSummaryService {

    /**
     * 为表格生成摘要与列描述 chunk。
     * <p>
     * 实现不得抛出影响入库的异常；生成失败时应返回空列表，让入库流程只保留行文本 chunk。
     *
     * @param tableName 表名（一般用文档标题）
     * @param meta      表格结构信息（表头 + 采样行 + 行数）
     * @return 摘要 chunk（contentType=table_summary）与列描述 chunk（contentType=table_column）
     */
    List<TextSegment> generateSummarySegments(String tableName, ExcelSplitter.ExcelTableMeta meta);
}
