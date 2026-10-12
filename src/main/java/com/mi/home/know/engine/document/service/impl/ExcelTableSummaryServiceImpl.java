package com.mi.home.know.engine.document.service.impl;

import com.mi.home.know.engine.document.service.ExcelTableSummaryService;
import com.mi.home.know.engine.infra.snowflake.SnowflakeIdGenerator;
import com.mi.home.know.engine.rag.modules.splitter.ExcelSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.input.PromptTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.mi.home.know.engine.rag.constant.MetadataKeyConstant.CHUNK_ID;
import static com.mi.home.know.engine.rag.constant.MetadataKeyConstant.CONTENT_TYPE;
import static com.mi.home.know.engine.rag.constant.MetadataKeyConstant.LOCATOR;
import static com.mi.home.know.engine.rag.constant.MetadataKeyConstant.ROW_END;
import static com.mi.home.know.engine.rag.constant.MetadataKeyConstant.ROW_START;
import static com.mi.home.know.engine.rag.constant.MetadataKeyConstant.SHEET_NAME;
import static com.mi.home.know.engine.rag.constant.MetadataKeyConstant.TYPE_TABLE_COLUMN;
import static com.mi.home.know.engine.rag.constant.MetadataKeyConstant.TYPE_TABLE_SUMMARY;

/**
 * 表摘要 / 列描述生成服务实现
 * <p>
 * 一次 LLM 调用同时产出两段内容，按固定标记切分后各自包成一个 chunk：
 * <ul>
 *   <li>{@code 【表摘要】} → contentType = table_summary</li>
 *   <li>{@code 【列描述】} → contentType = table_column</li>
 * </ul>
 * prompt 模板见 {@code resources/prompts/table-summary-prompt.txt}，
 * 其中含一条硬约束「严禁输出任何具体的数据值」——理由有三：
 * ① 数据可能敏感，把真实数值写进向量库等于把泄露风险搬到更难管控的地方；
 * ② 描述要稳定，带具体数值会因数据更新而失效；
 * ③ 描述的作用是「让人判断要不要用这张表」，不是替代数据本身。
 * <p>
 * 任何一步失败都只记录日志并返回空，绝不阻塞入库主流程。
 */
@Slf4j
@Service
public class ExcelTableSummaryServiceImpl implements ExcelTableSummaryService {

    private static final String SUMMARY_MARKER = "【表摘要】";
    private static final String COLUMN_MARKER = "【列描述】";

    /**
     * 拼进 prompt 的字段数量上限，防止宽表把 prompt 撑爆
     */
    private static final int MAX_COLUMNS_IN_SCHEMA = 80;

    @Autowired
    private ChatModel chatModel;

    @Value("classpath:prompts/table-summary-prompt.txt")
    private Resource tableSummaryPromptResource;

    private PromptTemplate promptTemplate;

    @Override
    public List<TextSegment> generateSummarySegments(String tableName, ExcelSplitter.ExcelTableMeta meta) {
        if (meta == null || meta.isEmpty()) {
            return Collections.emptyList();
        }

        List<TextSegment> segments = new ArrayList<>();
        for (ExcelSplitter.SheetMeta sheet : meta.sheets()) {
            try {
                segments.addAll(generateForSheet(tableName, sheet));
            } catch (Exception e) {
                // 摘要生成失败不影响入库：行文本 chunk 仍然是可检索的
                log.warn("表摘要生成失败，降级为仅入库行文本 chunk。table={}, sheet={}, error={}",
                        tableName, sheet.sheetName(), e.getMessage(), e);
            }
        }
        return segments;
    }

    private List<TextSegment> generateForSheet(String tableName, ExcelSplitter.SheetMeta sheet) throws IOException {
        if (sheet.headers() == null || sheet.headers().isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, Object> variables = new HashMap<>();
        variables.put("tableName", safe(tableName));
        variables.put("schema", buildSchema(sheet.headers()));
        variables.put("rowCount", String.valueOf(sheet.totalDataRows()));
        variables.put("sample", buildSample(sheet));

        String response = chatModel.chat(getPromptTemplate().apply(variables).text());
        if (response == null || response.isBlank()) {
            log.warn("表摘要返回为空。table={}, sheet={}", tableName, sheet.sheetName());
            return Collections.emptyList();
        }

        String[] sections = splitSections(response);
        String summary = sections[0];
        String columnDescriptions = sections[1];

        List<TextSegment> result = new ArrayList<>(2);
        String sheetName = sheet.sheetName();
        int fullRowEnd = sheet.totalDataRows() + 1; // 表头占第 1 行

        if (!summary.isBlank()) {
            // 前缀带上表名与 sheet 名：让关键词检索也能命中「表名」这个最强信号
            String text = "表：" + safe(tableName) + "（sheet：" + sheetName + "）\n" + summary;
            result.add(new TextSegment(text, Metadata.from(baseMetadata(sheetName, fullRowEnd, TYPE_TABLE_SUMMARY))));
        }
        if (!columnDescriptions.isBlank()) {
            String text = "表：" + safe(tableName) + "（sheet：" + sheetName + "）的字段说明\n" + columnDescriptions;
            result.add(new TextSegment(text, Metadata.from(baseMetadata(sheetName, fullRowEnd, TYPE_TABLE_COLUMN))));
        }
        return result;
    }

    private Map<String, Object> baseMetadata(String sheetName, int rowEnd, String contentType) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(CHUNK_ID, SnowflakeIdGenerator.getInstance().nextIdStr());
        metadata.put(CONTENT_TYPE, contentType);
        metadata.put(SHEET_NAME, sheetName);
        metadata.put(ROW_START, 1);
        metadata.put(ROW_END, rowEnd);
        metadata.put(LOCATOR, ExcelSplitter.buildLocator(sheetName, 1, rowEnd));
        return metadata;
    }

    private PromptTemplate getPromptTemplate() throws IOException {
        if (promptTemplate == null) {
            promptTemplate = PromptTemplate.from(tableSummaryPromptResource.getContentAsString(StandardCharsets.UTF_8));
        }
        return promptTemplate;
    }

    /**
     * 字段清单：{@code 字段名(VARCHAR(500))}，与 ExcelProcessServiceImpl 建表时的类型保持一致
     */
    private String buildSchema(List<String> headers) {
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(headers.size(), MAX_COLUMNS_IN_SCHEMA);
        for (int i = 0; i < limit; i++) {
            if (i > 0) {
                sb.append("、");
            }
            String header = headers.get(i) == null ? "" : headers.get(i).trim();
            sb.append(header.isEmpty() ? ("列" + (i + 1)) : header).append("(VARCHAR(500))");
        }
        if (headers.size() > limit) {
            sb.append("…（共 ").append(headers.size()).append(" 列，此处省略 ").append(headers.size() - limit).append(" 列）");
        }
        return sb.toString();
    }

    /**
     * 采样数据仅用于让模型理解字段含义，prompt 里已明确禁止把这些值写进输出
     */
    private String buildSample(ExcelSplitter.SheetMeta sheet) {
        if (sheet.sampleRows() == null || sheet.sampleRows().isEmpty()) {
            return "（无数据行）";
        }
        List<String> headers = sheet.headers();
        StringBuilder sb = new StringBuilder();
        for (List<String> row : sheet.sampleRows()) {
            StringBuilder line = new StringBuilder();
            for (int j = 0; j < headers.size() && j < row.size(); j++) {
                String header = headers.get(j) == null ? "" : headers.get(j).trim();
                String value = row.get(j) == null ? "" : row.get(j).trim();
                if (header.isEmpty() && value.isEmpty()) {
                    continue;
                }
                if (line.length() > 0) {
                    line.append("; ");
                }
                line.append(header).append("：").append(value);
            }
            if (line.length() > 0) {
                sb.append(line).append('\n');
            }
        }
        return sb.length() == 0 ? "（无数据行）" : sb.toString();
    }

    /**
     * 按固定标记把模型输出切成 [表摘要, 列描述]。
     * <p>
     * 模型经常不守格式（多写前言、加代码块符号），这里做保守清洗：
     * 去掉 ``` 围栏；找不到列描述标记时把整段当摘要，宁可只产出一个 chunk 也不瞎猜。
     */
    private String[] splitSections(String raw) {
        String text = raw.replace("```", "").trim();

        int columnIndex = text.indexOf(COLUMN_MARKER);
        int summaryIndex = text.indexOf(SUMMARY_MARKER);

        String summary = "";
        String columns = "";

        if (columnIndex >= 0) {
            columns = text.substring(columnIndex + COLUMN_MARKER.length()).trim();
            if (summaryIndex >= 0 && summaryIndex < columnIndex) {
                summary = text.substring(summaryIndex + SUMMARY_MARKER.length(), columnIndex).trim();
            } else {
                // 有列描述但没找到摘要标记：把列描述之前的内容当作摘要
                summary = text.substring(0, columnIndex).trim();
            }
        } else if (summaryIndex >= 0) {
            summary = text.substring(summaryIndex + SUMMARY_MARKER.length()).trim();
        } else {
            summary = text;
        }

        return new String[]{summary, columns};
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
