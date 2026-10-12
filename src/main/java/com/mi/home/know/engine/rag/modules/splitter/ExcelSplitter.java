package com.mi.home.know.engine.rag.modules.splitter;

import com.mi.home.know.engine.infra.snowflake.SnowflakeIdGenerator;
import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.read.listener.ReadListener;
import com.alibaba.fastjson2.JSON;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

import static com.mi.home.know.engine.rag.constant.MetadataKeyConstant.BROTHER_CHUNK_ID;
import static com.mi.home.know.engine.rag.constant.MetadataKeyConstant.BROTHER_CHUNK_INDEX;
import static com.mi.home.know.engine.rag.constant.MetadataKeyConstant.BROTHER_CHUNK_TOTAL;
import static com.mi.home.know.engine.rag.constant.MetadataKeyConstant.CHUNK_ID;
import static com.mi.home.know.engine.rag.constant.MetadataKeyConstant.CONTENT_TYPE;
import static com.mi.home.know.engine.rag.constant.MetadataKeyConstant.LOCATOR;
import static com.mi.home.know.engine.rag.constant.MetadataKeyConstant.ROW_END;
import static com.mi.home.know.engine.rag.constant.MetadataKeyConstant.ROW_START;
import static com.mi.home.know.engine.rag.constant.MetadataKeyConstant.SHEET_NAME;
import static com.mi.home.know.engine.rag.constant.MetadataKeyConstant.TYPE_TABLE_ROW;

/**
 * RAGFlow风格的Excel解析器 - Java实现
 * 参考: https://github.com/infiniflow/ragflow
 * <p>
 * 功能特性:
 * <ol>
 *   <li>支持 .xlsx / .xls / .csv 三种格式</li>
 *   <li><b>多 sheet 支持</b>：逐个 sheet 解析并按顺序输出，每个 sheet 独立分组</li>
 *   <li>双模式输出: 键值对模式 / HTML表格模式</li>
 *   <li>智能分块: HTML模式按字符数分块（同一行不会被拆分到不同分块）</li>
 *   <li>编码自动检测 (CSV)</li>
 * </ol>
 * <p>
 * <b>元数据设计（与 Markdown 侧对齐）：</b>
 * 每个行块都会带上
 * <ul>
 *   <li>{@code chunkId} —— 自身唯一 ID</li>
 *   <li>{@code contentType=table_row} —— 供检索侧识别并剔除"发现型" chunk</li>
 *   <li>{@code brotherChunkId} —— 相邻行窗口 ID，命中一行时可补全同窗口的其他行。
 *       窗口大小受 {@link #DEFAULT_BROTHER_WINDOW} 限制，避免整表灌进上下文。</li>
 *   <li>{@code locator} —— 引用锚点：{@code {type:sheet, sheet, rowStart, rowEnd}}，
 *       与 PDF 的"页码+bbox"是同一套锚点抽象的不同格式实现</li>
 * </ul>
 */
public class ExcelSplitter {
    /**
     * 是否使用HTML表格模式
     */
    private boolean htmlMode;

    /**
     * 默认分块字符数
     */
    public static final int DEFAULT_CHUNK_SIZE = 500;

    /**
     * 兄弟块窗口大小。
     * <p>
     * 键值对模式下每 {@code brotherWindow} 行共享一个 {@code brotherChunkId}。
     * 检索命中其中一行时，会补全同窗口的其他行作为上下文。
     * 窗口不能开得太大，否则一次命中会把整张表拉进上下文。
     */
    public static final int DEFAULT_BROTHER_WINDOW = 10;

    /**
     * 表摘要生成时采样的数据行数上限
     */
    public static final int DEFAULT_SAMPLE_SIZE = 10;

    /**
     * 分块字符数，用于HTML表格模式
     * 表示每个分块包含的最大字符数，同一行不会被拆分到不同的分块中
     */
    private final int chunkSize;

    /**
     * 兄弟块窗口大小
     */
    private final int brotherWindow;

    public ExcelSplitter() {
        this(DEFAULT_CHUNK_SIZE);
    }

    public ExcelSplitter(int chunkSize) {
        this.chunkSize = chunkSize;
        this.htmlMode = false;
        this.brotherWindow = DEFAULT_BROTHER_WINDOW;
    }

    public ExcelSplitter(int chunkSize, boolean htmlMode) {
        this.chunkSize = chunkSize;
        this.htmlMode = htmlMode;
        this.brotherWindow = DEFAULT_BROTHER_WINDOW;
    }

    // ==================== 核心解析方法 ====================

    /**
     * 双模式解析入口
     *
     * @param fileData 文件字节数据
     */
    public List<TextSegment> split(byte[] fileData) throws IOException {
        List<SheetData> sheets = parseSheets(fileData);

        List<TextSegment> result = new ArrayList<>();
        for (SheetData sheet : sheets) {
            if (sheet.rows().isEmpty()) {
                continue;
            }
            List<String> headers = sheet.rows().get(0);
            List<List<String>> dataRows = sheet.rows().subList(1, sheet.rows().size());
            if (dataRows.isEmpty()) {
                continue;
            }
            if (htmlMode) {
                result.addAll(buildHtmlSegments(sheet.sheetName(), headers, dataRows));
            } else {
                result.addAll(buildKeyValueSegments(sheet.sheetName(), headers, dataRows));
            }
        }
        return result;
    }

    /**
     * 解析出表格结构（表头 + 采样行），供表摘要 / 列描述生成使用。
     * <p>
     * 只做结构抽取，不产生 chunk。摘要生成器据此构造 prompt。
     *
     * @param fileData 文件字节数据
     * @return 每个 sheet 的结构信息
     */
    public ExcelTableMeta parseMeta(byte[] fileData) throws IOException {
        List<SheetData> sheets = parseSheets(fileData);

        List<SheetMeta> metas = new ArrayList<>();
        for (SheetData sheet : sheets) {
            if (sheet.rows().isEmpty()) {
                continue;
            }
            List<String> headers = sheet.rows().get(0);
            List<List<String>> dataRows = sheet.rows().size() > 1
                    ? sheet.rows().subList(1, sheet.rows().size())
                    : Collections.emptyList();

            List<List<String>> samples = dataRows.stream()
                    .filter(row -> !isBlankRow(row))
                    .limit(DEFAULT_SAMPLE_SIZE)
                    .collect(Collectors.toList());

            int dataRowCount = (int) dataRows.stream().filter(row -> !isBlankRow(row)).count();

            metas.add(new SheetMeta(sheet.sheetName(), headers, samples, dataRowCount));
        }
        return new ExcelTableMeta(metas);
    }

    // ==================== 解析实现 ====================

    /**
     * 统一解析入口：按文件类型解析成「每个 sheet 一张二维表」。
     */
    private List<SheetData> parseSheets(byte[] fileData) throws IOException {
        FileType fileType = detectFileType(fileData);
        List<SheetData> sheets;
        switch (fileType) {
            case XLSX:
            case XLS:
                sheets = parseExcelSheets(fileData);
                break;
            case CSV:
                sheets = List.of(new SheetData("csv", cleanData(parseCsvRows(fileData))));
                break;
            default:
                throw new IllegalArgumentException("不支持的文件格式");
        }
        // Excel 分支已在读取时清洗过，这里统一再兜一次，保证 CSV / Excel 行为一致
        return sheets.stream()
                .map(s -> new SheetData(s.sheetName(), cleanData(s.rows())))
                .collect(Collectors.toList());
    }

    /**
     * 解析 Excel，按 sheet 分组。
     * <p>
     * 使用 {@code doReadAll()} 读取全部 sheet，通过 {@code readSheetHolder().getSheetName()}
     * 区分来源 sheet。原来只调用 {@code .sheet()} 只能读到第一个 sheet，
     * 多 sheet 的表会被静默丢弃。
     */
    private List<SheetData> parseExcelSheets(byte[] fileData) throws IOException {
        Map<String, List<List<String>>> bySheet = new LinkedHashMap<>();

        try (ByteArrayInputStream bis = new ByteArrayInputStream(fileData)) {
            EasyExcel.read(bis, new ReadListener<Map<Integer, String>>() {
                @Override
                public void invoke(Map<Integer, String> data, AnalysisContext context) {
                    String sheetName = currentSheetName(context);
                    List<String> row = new ArrayList<>();
                    int maxIndex = data.keySet().stream().max(Integer::compareTo).orElse(-1);
                    for (int i = 0; i <= maxIndex; i++) {
                        String value = data.getOrDefault(i, "");
                        row.add(value != null ? value : "");
                    }
                    bySheet.computeIfAbsent(sheetName, k -> new ArrayList<>()).add(row);
                }

                @Override
                public void doAfterAllAnalysed(AnalysisContext context) {
                    // 解析完成
                }
                // EasyExcel 默认将第一行视为表头，不会通过 ReadListener.invoke() 回调返回。
                // 需要设置 headRowNumber(0) 告诉 EasyExcel 从第一行就开始读取数据
            }).headRowNumber(0).doReadAll();
        }

        return bySheet.entrySet().stream()
                .map(entry -> new SheetData(entry.getKey(), entry.getValue()))
                .collect(Collectors.toList());
    }

    private String currentSheetName(AnalysisContext context) {
        try {
            if (context.readSheetHolder() != null && context.readSheetHolder().getSheetName() != null) {
                return context.readSheetHolder().getSheetName();
            }
        } catch (Exception ignored) {
            // 部分 EasyExcel 版本在 doReadAll 首行回调时 sheetHolder 尚未就绪，降级为默认名
        }
        return "Sheet1";
    }

    private List<List<String>> parseCsvRows(byte[] fileData) throws IOException {
        Charset charset = detectCharset(fileData);

        List<List<String>> allRows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new ByteArrayInputStream(fileData), charset))) {

            String line;
            while ((line = reader.readLine()) != null) {
                allRows.add(parseCsvLine(line));
            }
        }
        return allRows;
    }

    /**
     * 简单的CSV行解析（处理引号包裹的字段）
     */
    private List<String> parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (char c : line.toCharArray()) {
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                fields.add(current.toString().trim());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString().trim());

        return fields;
    }

    // ==================== 数据处理核心 ====================

    /**
     * 清理非法控制字符
     */
    private List<List<String>> cleanData(List<List<String>> rows) {
        return rows.stream()
                .map(row -> row.stream()
                        .map(this::cleanCell)
                        .collect(Collectors.toList()))
                .collect(Collectors.toList());
    }

    private String cleanCell(String cell) {
        if (cell == null) return "";
        // 移除控制字符 (0x00-0x1F)，保留换行符(0x0A)和制表符(0x09)
        return cell.replaceAll("[\\x00-\\x09\\x0B-\\x0C\\x0E-\\x1F]", "");
    }

    /**
     * 键值对模式：每行数据一个 chunk，格式 {@code "表头1：值1; 表头2：值2"}。
     * <p>
     * 每 {@link #DEFAULT_BROTHER_WINDOW} 行共享一个 {@code brotherChunkId}，
     * 命中一行时最多补全同窗口的其他行。
     */
    private List<TextSegment> buildKeyValueSegments(String sheetName, List<String> headers, List<List<String>> dataRows) {
        // 1. 先格式化出所有非空行（全空行不产生 chunk，也就不会占窗口名额）
        List<String> texts = new ArrayList<>();
        List<Integer> rowNos = new ArrayList<>();
        for (int i = 0; i < dataRows.size(); i++) {
            List<String> row = dataRows.get(i);
            if (isBlankRow(row)) {
                continue;
            }
            String text = formatKeyValueRow(headers, row);
            if (text.isEmpty()) {
                continue;
            }
            texts.add(text);
            // 表头占第 1 行，数据行 i（0-based）对应 1-based 的第 i+2 行
            rowNos.add(i + 2);
        }
        if (texts.isEmpty()) {
            return Collections.emptyList();
        }

        // 2. 按窗口切分，同一窗口内共享 brotherChunkId，并记录窗口内的序号与总数
        List<TextSegment> result = new ArrayList<>(texts.size());
        int total = texts.size();
        for (int start = 0; start < total; start += brotherWindow) {
            int end = Math.min(start + brotherWindow, total);
            String windowChunkId = SnowflakeIdGenerator.getInstance().nextIdStr();
            int groupSize = end - start;

            for (int i = start; i < end; i++) {
                int rowNo = rowNos.get(i);
                Map<String, Object> metadata = baseMetadata(sheetName, rowNo, rowNo);
                metadata.put(BROTHER_CHUNK_ID, windowChunkId);
                metadata.put(BROTHER_CHUNK_INDEX, i - start + 1);
                metadata.put(BROTHER_CHUNK_TOTAL, groupSize);
                result.add(new TextSegment(texts.get(i), Metadata.from(metadata)));
            }
        }
        return result;
    }

    /**
     * 整行是否全为空。全空行不产生 chunk。
     * <p>
     * 注意不能用"格式化后的文本是否为空"来判断：只要表头非空，
     * 一个全空行也会被格式化成 {@code "车型："} 这种只有键没有值的垃圾 chunk。
     */
    private boolean isBlankRow(List<String> row) {
        if (row == null || row.isEmpty()) {
            return true;
        }
        for (String cell : row) {
            if (cell != null && !cell.trim().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private String formatKeyValueRow(List<String> headers, List<String> row) {
        StringBuilder sb = new StringBuilder();
        for (int j = 0; j < headers.size() && j < row.size(); j++) {
            String header = headers.get(j).trim();
            String value = row.get(j).trim();

            if (!header.isEmpty() || !value.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append("; ");
                }
                sb.append(header).append("：").append(value);
            }
        }
        return sb.toString();
    }

    /**
     * HTML表格模式：按 chunkSize 字符数分块，同一行不会被拆分到不同的分块中。
     * <p>
     * HTML 块本身已自带表头和若干行，是自包含的，不再挂 brotherChunkId。
     * 但仍然记录行号区间，供引用回跳使用。
     */
    private List<TextSegment> buildHtmlSegments(String sheetName, List<String> headers, List<List<String>> dataRows) {
        List<TextSegment> result = new ArrayList<>();

        List<List<String>> currentChunk = new ArrayList<>();
        int currentChunkSize = 0;
        int currentChunkStartRow = 2; // 1-based，表头占第 1 行
        int currentChunkLastRow = 1;

        int headerSize = calculateRowSize(headers);

        for (int i = 0; i < dataRows.size(); i++) {
            List<String> row = dataRows.get(i);
            if (isBlankRow(row)) {
                continue;
            }
            int rowSize = calculateRowSize(row);
            int rowNo = i + 2;

            if (currentChunk.isEmpty()) {
                currentChunk.add(row);
                currentChunkSize = headerSize + rowSize;
                currentChunkStartRow = rowNo;
                currentChunkLastRow = rowNo;
            } else if (currentChunkSize + rowSize <= chunkSize) {
                currentChunk.add(row);
                currentChunkSize += rowSize;
                currentChunkLastRow = rowNo;
            } else {
                result.add(buildHtmlSegment(sheetName, headers, currentChunk, currentChunkStartRow, currentChunkLastRow));

                currentChunk = new ArrayList<>();
                currentChunk.add(row);
                currentChunkSize = headerSize + rowSize;
                currentChunkStartRow = rowNo;
                currentChunkLastRow = rowNo;
            }
        }

        if (!currentChunk.isEmpty()) {
            result.add(buildHtmlSegment(sheetName, headers, currentChunk, currentChunkStartRow, currentChunkLastRow));
        }

        return result;
    }

    private TextSegment buildHtmlSegment(String sheetName, List<String> headers, List<List<String>> dataRows,
                                         int rowStart, int rowEnd) {
        String html = buildHtmlTable(headers, dataRows);
        Map<String, Object> metadata = baseMetadata(sheetName, rowStart, rowEnd);
        return new TextSegment(html, Metadata.from(metadata));
    }

    /**
     * 构造行块/HTML块的公共元数据。
     */
    private Map<String, Object> baseMetadata(String sheetName, int rowStart, int rowEnd) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(CHUNK_ID, SnowflakeIdGenerator.getInstance().nextIdStr());
        metadata.put(CONTENT_TYPE, TYPE_TABLE_ROW);
        metadata.put(SHEET_NAME, sheetName);
        metadata.put(ROW_START, rowStart);
        metadata.put(ROW_END, rowEnd);
        metadata.put(LOCATOR, buildLocator(sheetName, rowStart, rowEnd));
        return metadata;
    }

    /**
     * 引用锚点：Excel 用 sheet + 行号，与 PDF 的 page + bbox 属同一套锚点抽象。
     */
    public static String buildLocator(String sheetName, int rowStart, int rowEnd) {
        Map<String, Object> locator = new LinkedHashMap<>();
        locator.put("type", "sheet");
        locator.put("sheet", sheetName);
        locator.put("rowStart", rowStart);
        locator.put("rowEnd", rowEnd);
        return JSON.toJSONString(locator);
    }

    /**
     * 计算一行的字符数（包括表格标签的字符）
     */
    private int calculateRowSize(List<String> row) {
        int size = 0;
        // 每个单元格会有 <td> 和 </td> 标签，共9个字符
        // 加上换行符等格式化字符
        for (String cell : row) {
            size += (cell != null ? cell.length() : 0) + 9;
        }
        // 加上 <tr> 和 </tr> 标签以及格式化字符
        size += 15;
        return size;
    }

    private String buildHtmlTable(List<String> headers, List<List<String>> dataRows) {
        StringBuilder html = new StringBuilder();
        html.append("<table>\n");

        // 表头
        html.append("  <thead>\n    <tr>\n");
        for (String header : headers) {
            html.append("      <th>").append(escapeHtml(header)).append("</th>\n");
        }
        html.append("    </tr>\n  </thead>\n");

        // 表体
        html.append("  <tbody>\n");
        for (List<String> row : dataRows) {
            html.append("    <tr>\n");
            for (int i = 0; i < headers.size(); i++) {
                String value = i < row.size() ? row.get(i) : "";
                html.append("      <td>").append(escapeHtml(value)).append("</td>\n");
            }
            html.append("    </tr>\n");
        }
        html.append("  </tbody>\n");

        html.append("</table>");
        return html.toString();
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#x27;");
    }

    // ==================== 文件类型检测 ====================

    private enum FileType {
        XLSX, XLS, CSV, UNKNOWN
    }

    /**
     * 通过文件头魔数检测文件类型
     */
    private FileType detectFileType(byte[] data) {
        if (data.length < 4) {
            return FileType.UNKNOWN;
        }

        // ZIP头 -> xlsx (OOXML格式)
        if (data[0] == 0x50 && data[1] == 0x4B && data[2] == 0x03 && data[3] == 0x04) {
            return FileType.XLSX;
        }

        // OLE头 -> xls (BIFF格式)
        if (data[0] == (byte) 0xD0 && data[1] == (byte) 0xCF
                && data[2] == (byte) 0x11 && data[3] == (byte) 0xE0) {
            return FileType.XLS;
        }

        // 简单判断CSV：包含大量逗号或换行符
        String sample = new String(data, 0, Math.min(100, data.length), StandardCharsets.UTF_8);
        if (sample.contains(",") && (sample.contains("\n") || sample.contains("\r"))) {
            return FileType.CSV;
        }

        return FileType.UNKNOWN;
    }

    /**
     * 简单的编码检测
     */
    private Charset detectCharset(byte[] data) {
        // 简单的BOM检测
        if (data.length >= 3 && data[0] == (byte) 0xEF && data[1] == (byte) 0xBB && data[2] == (byte) 0xBF) {
            return StandardCharsets.UTF_8;
        }
        if (data.length >= 2 && data[0] == (byte) 0xFE && data[1] == (byte) 0xFF) {
            return StandardCharsets.UTF_16BE;
        }
        if (data.length >= 2 && data[0] == (byte) 0xFF && data[1] == (byte) 0xFE) {
            return StandardCharsets.UTF_16LE;
        }

        // 默认UTF-8
        return StandardCharsets.UTF_8;
    }

    // ==================== 数据结构 ====================

    /**
     * 单个 sheet 的原始二维数据，第 0 行是表头
     */
    private record SheetData(String sheetName, List<List<String>> rows) {
    }

    /**
     * 表格结构信息（用于生成表摘要 / 列描述）
     *
     * @param sheets 各 sheet 的结构
     */
    public record ExcelTableMeta(List<SheetMeta> sheets) {

        public boolean isEmpty() {
            return sheets == null || sheets.isEmpty();
        }
    }

    /**
     * 单个 sheet 的结构信息
     *
     * @param sheetName     sheet 名
     * @param headers       表头
     * @param sampleRows    采样数据行（不含表头）
     * @param totalDataRows 数据行总数（不含表头）
     */
    public record SheetMeta(String sheetName, List<String> headers, List<List<String>> sampleRows, int totalDataRows) {
    }

    // ==================== Getters ====================

    public int getChunkSize() {
        return chunkSize;
    }

    public int getBrotherWindow() {
        return brotherWindow;
    }
}
