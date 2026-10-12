package com.mi.home.know.engine.rag.modules.splitter;


import com.mi.home.know.engine.infra.snowflake.SnowflakeIdGenerator;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;

import java.util.*;
import java.util.stream.Collectors;

import static com.mi.home.know.engine.rag.constant.MetadataKeyConstant.*;

/**
 * Markdown文档分割器，基于标题层级进行文档分段
 * 支持保留元数据、父子分段关系等高级特性
 *
 * @author andyflury （https://github.com/langchain4j/langchain4j/issues/574 ）
 * 增加对父子分段的支持
 */
public class MarkdownHeaderParentTextSplitter implements DocumentSplitter {

    private static final Map<String, String> DEFAULT_HEADERS_TO_SPLIT = new HashMap<>();

    static {
        DEFAULT_HEADERS_TO_SPLIT.put("#", "title");
        DEFAULT_HEADERS_TO_SPLIT.put("##", "subtitle");
        DEFAULT_HEADERS_TO_SPLIT.put("###", "subsubtitle");
        DEFAULT_HEADERS_TO_SPLIT.put("####", "subsubsubtitle");
        DEFAULT_HEADERS_TO_SPLIT.put("#####", "subsubsubsubtitle");
        DEFAULT_HEADERS_TO_SPLIT.put("######", "subsubsubsubsubtitle");
    }

    /**
     * 需要分割的标题列表，按标题标记长度倒序排列
     */
    private List<Map.Entry<String, String>> headersToSplitOn;

    /**
     * 是否按行返回结果
     */
    private boolean returnEachLine;

    /**
     * 是否剥离标题行本身
     */
    private boolean stripHeaders;

    /**
     * 每个分片的最大字符数，0表示不限制
     */
    private int chunkSize;

    /**
     * 相邻分片之间的重叠字符数
     */
    private int overlap;

    /**
     * 构造函数
     *
     * @param headersToSplitOn 标题分割映射表，key为标题标记（如"#"、"##"），value为元数据中的键名
     * @param returnEachLine   是否按行返回结果，false时会聚合相同元数据的行
     * @param stripHeaders     是否在结果中移除标题行
     */
    public MarkdownHeaderParentTextSplitter(Map<String, String> headersToSplitOn, boolean returnEachLine, boolean stripHeaders) {
        this(headersToSplitOn, returnEachLine, stripHeaders, 0, 0);

    }

    public MarkdownHeaderParentTextSplitter(int chunkSize, int overlap) {
        this(DEFAULT_HEADERS_TO_SPLIT, true, false, chunkSize, overlap);
    }

    /**
     * 构造函数（通过标题级别指定分割层级）
     *
     * @param titleLevel     标题级别（1-6），表示按1到titleLevel级标题进行分割
     * @param returnEachLine 是否按行返回结果，false时会聚合相同元数据的行
     * @param stripHeaders   是否在结果中移除标题行
     * @param chunkSize      每个分片的最大字符数，超出则按chunkSize再次切割，0表示不限制
     * @param overlap        相邻分片之间的重叠字符数
     */
    public MarkdownHeaderParentTextSplitter(int titleLevel, boolean returnEachLine, boolean stripHeaders, int chunkSize, int overlap) {
        this(buildHeadersMap(titleLevel), returnEachLine, stripHeaders, chunkSize, overlap);
    }

    /**
     * 根据标题级别生成标题分割映射表
     *
     * @param titleLevel 标题级别（1-6）
     * @return 标题分割映射表
     */
    private static Map<String, String> buildHeadersMap(int titleLevel) {
        if (titleLevel < 1 || titleLevel > 6) {
            throw new IllegalArgumentException("titleLevel must be between 1 and 6, but got: " + titleLevel);
        }
        String[] names = {"title", "subtitle", "subsubtitle", "subsubsubtitle", "subsubsubsubtitle", "subsubsubsubsubtitle"};
        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 1; i <= titleLevel; i++) {
            String key = "#".repeat(i);
            headers.put(key, names[i - 1]);
        }
        return headers;
    }

    /**
     * 构造函数（支持 chunkSize 和 overlap）
     *
     * @param headersToSplitOn 标题分割映射表，key为标题标记（如"#"、"##"），value为元数据中的键名
     * @param returnEachLine   是否按行返回结果，false时会聚合相同元数据的行
     * @param stripHeaders     是否在结果中移除标题行
     * @param chunkSize        每个分片的最大字符数，超出则按chunkSize再次切割，0表示不限制
     * @param overlap          相邻分片之间的重叠字符数
     */
    public MarkdownHeaderParentTextSplitter(Map<String, String> headersToSplitOn, boolean returnEachLine, boolean stripHeaders, int chunkSize, int overlap) {
        // 按标题标记长度倒序排列，确保优先匹配更长的标记（如"###"优先于"##"）
        this.headersToSplitOn = headersToSplitOn.entrySet().stream()
                .sorted(Comparator.comparingInt(e -> -e.getKey().length()))
                .collect(Collectors.toList());
        this.returnEachLine = returnEachLine;
        this.stripHeaders = stripHeaders;
        this.chunkSize = chunkSize;
        this.overlap = overlap;
    }



    /**
     * 对传入的文档执行切分（DocumentSplitter 接口实现）
     * <p>
     * 流程：
     * 1. 移除所有空行，简化后续逐行处理
     * 2. 调用核心方法 splitWithMetadata() 进行标题层级分割
     * 3. 将结果封装为 LangChain4j 的 TextSegment 列表返回
     *
     * @param document 待切分的 LangChain4j Document 对象
     * @return 切分后的 TextSegment 列表，每个片段包含文本内容和元数据
     */
    @Override
    public List<TextSegment> split(Document document) {
        System.out.println("开始解析Markdown文档...");

        // 将文档文本按换行符分割成行，过滤掉所有空行（trim 后为空），再重新拼接
        // 目的是简化后续的标题检测逻辑，避免空行干扰
        String text = Arrays.stream(document.text().split("\n"))
                .filter(line -> !line.trim().isEmpty())
                .collect(Collectors.joining("\n"));

        // 创建一个列表，用于存放最终返回的 TextSegment
        List<TextSegment> result = new ArrayList<>();

        // 调用核心分割方法，传入过滤后的文本和文档的原始元数据
        // splitWithMetadata 返回的是项目内部定义的 DocumentWithMetadata 列表
        List<DocumentWithMetadata> segments = splitWithMetadata(text, document.metadata().toMap());

        // 遍历切分结果，将 DocumentWithMetadata 转换为 LangChain4j 的 TextSegment
        for (DocumentWithMetadata segment : segments) {
            result.add(new TextSegment(segment.getContent(), Metadata.from(segment.getMetadata())));
        }

        // 返回切分后的文本片段列表
        return result;
    }

    /**
     * 简化版分割方法，不保留元数据
     *
     * @param text 待分割的文本
     * @return 分割后的文本片段列表
     */

    public List<TextSegment> splitText(String text) {
        // 移除文本中所有空行
        String filteredText = Arrays.stream(text.split("\n"))
                .filter(line -> !line.trim().isEmpty())
                .collect(Collectors.joining("\n"));

        List<TextSegment> result = new ArrayList<>();
        List<DocumentWithMetadata> segments = splitWithMetadata(filteredText, new HashMap<>());
        for (DocumentWithMetadata segment : segments) {
            result.add(new TextSegment(segment.getContent(), Metadata.from(segment.getMetadata())));
        }

        return result;
    }

    /**
     * 核心分割逻辑，保留元数据
     * <p>
     * 这是整个切分器的核心方法。它逐行遍历 Markdown 文本，完成以下工作：
     * 1. 识别标题行（# / ## / ### 等），遇到新标题时保存前一个标题块的内容
     * 2. 维护标题栈（headerStack），追踪当前的标题层级结构
     * 3. 将每一段非标题内容与其所属的标题元数据绑定
     * 4. 最后根据 returnEachLine 配置决定是否聚合，并根据 chunkSize 二次切割
     *
     * @param text         待分割的文本（已过滤空行）
     * @param baseMetadata 基础元数据（如文档ID），会被传递到每个片段中
     * @return 带有元数据的文档片段列表
     */
    private List<DocumentWithMetadata> splitWithMetadata(String text, Map<String, Object> baseMetadata) {
        // 将文本按换行符拆分为行列表，后续逐行处理
        List<String> lines = Arrays.asList(text.split("\n"));
        // 存储每一行（或聚合后的块）及其对应的元数据
        List<Line> linesWithMetadata = new ArrayList<>();
        // 当前累积的内容（遇到标题或空行时清空，保存到 linesWithMetadata）
        List<String> currentContent = new ArrayList<>();
        // 当前行的元数据副本，用于传递给下一行
        Map<String, Object> currentMetadata = new HashMap<>(baseMetadata);
        // 标题栈，用于追踪当前的标题层级结构（如：第一章 > 1.1 > 1.1.1）
        List<Header> headerStack = new ArrayList<>();
        // 初始元数据，每次遇到新标题时更新，作为"当前标题上下文"的基础
        Map<String, Object> initialMetadata = new HashMap<>(baseMetadata);

        // 代码块状态跟踪：代码块内的 # 不作为标题处理
        boolean inCodeBlock = false;
        String openingFence = "";

        // 逐行遍历文档
        for (String line : lines) {
            String strippedLine = line.trim();

            // ========== 代码块检测 ==========
            // 代码块内的所有内容（包括 # 开头的行）都不应作为标题处理
            if (!inCodeBlock) {
                // 检测代码块开始标记（``` 或 ~~~）
                if (strippedLine.startsWith("```")) {
                    inCodeBlock = true;
                    openingFence = "```";
                } else if (strippedLine.startsWith("~~~")) {
                    inCodeBlock = true;
                    openingFence = "~~~";
                }
            } else {
                // 检测代码块结束标记
                if (strippedLine.startsWith(openingFence)) {
                    inCodeBlock = false;
                    openingFence = "";
                }
            }

            // 代码块内的行直接加入当前内容，不做标题检测，继续下一次循环
            if (inCodeBlock) {
                currentContent.add(strippedLine);
                continue;
            }

            // ========== 标题行检测 ==========
            // 使用 interrupted 标签，一旦匹配到标题就跳出 for 循环
            interrupted:
            {
                // 遍历所有需要分割的标题级别（如 #、##、###）
                // headersToSplitOn 已按标题标记长度倒序排列，确保 ### 优先于 ## 匹配
                for (Map.Entry<String, String> header : headersToSplitOn) {
                    String sep = header.getKey();    // 标题标记，如 "#"、"##"、"###"
                    String name = header.getValue(); // 元数据中的键名，如 "title"、"subtitle"

                    // 判断当前行是否为有效的标题行
                    // 格式必须为：sep + 空格 + 标题内容，或 sep 单独一行
                    if (strippedLine.startsWith(sep) && 
                        (strippedLine.length() == sep.length() || strippedLine.charAt(sep.length()) == ' ')) {
                        
                        if (name != null) {
                            // 计算当前标题的级别（# 的个数）
                            int currentHeaderLevel = (int) sep.chars().filter(ch -> ch == '#').count();

                            // ========== 标题栈维护 ==========
                            // 移除所有级别 >= 当前级别的标题
                            // 例如：当前是 ###，则从栈中弹出 ### 和更深的标题，保留 ## 和 #
                            // 这样从 "### 1.1.1" 回退到 "## 1.2" 时，## 之前的 ### 会被清除
                            while (!headerStack.isEmpty() && 
                                   headerStack.get(headerStack.size() - 1).getLevel() >= currentHeaderLevel) {
                                Header poppedHeader = headerStack.remove(headerStack.size() - 1);
                                initialMetadata.remove(poppedHeader.getName());
                            }

                            // 将当前标题加入栈顶，并更新元数据
                            // headerType.getData() 是标题的内容（如 "1.1 概述"）
                            Header headerType = new Header(currentHeaderLevel, name, 
                                                           strippedLine.substring(sep.length()).trim());
                            headerStack.add(headerType);
                            initialMetadata.put(name, headerType.getData());
                            initialMetadata.put(HEADER_LEVEL, currentHeaderLevel);

                            // 为每个分段生成唯一的 chunk_id（雪花算法）
                            // 后续父子分段、检索溯源都依赖此 ID
                            String currentChunkId = SnowflakeIdGenerator.getInstance().nextIdStr();
                            initialMetadata.put(CHUNK_ID, currentChunkId);
                        }

                        // 遇到新标题时，将之前累积的内容保存为一个片段
                        // 这个片段携带的是上一个标题的元数据
                        if (!currentContent.isEmpty()) {
                            linesWithMetadata.add(new Line(String.join("\n", currentContent), currentMetadata));
                            currentContent.clear();
                        }

                        // 根据 stripHeaders 配置决定标题行是否保留在文本内容中
                        // 默认 false：保留标题行在文本中，保证可读性
                        if (!stripHeaders) {
                            currentContent.add(strippedLine);
                        }

                        // 匹配到标题，跳出 for 循环（不执行下面的非标题行处理）
                        break interrupted;
                    }
                }

                // ========== 非标题行处理 ==========
                // 非空行 → 加入当前累积内容
                if (!strippedLine.isEmpty()) {
                    currentContent.add(strippedLine);
                } 
                // 空行且有累积内容 → 将当前累积内容保存为一个片段
                // 空行作为自然段落分隔符
                else if (!currentContent.isEmpty()) {
                    linesWithMetadata.add(new Line(String.join("\n", currentContent), currentMetadata));
                    currentContent.clear();
                }
            }

            // 更新当前元数据为最新的标题信息
            // 这样后续的非标题行都能带上正确的标题上下文
            currentMetadata = new HashMap<>(initialMetadata);
        }

        // 循环结束后，处理最后一块累积的内容
        if (!currentContent.isEmpty()) {
            linesWithMetadata.add(new Line(String.join("\n", currentContent), currentMetadata));
        }

        // 根据配置决定返回方式
        List<DocumentWithMetadata> segments;
        if (!returnEachLine) {
            // 聚合模式：将相同元数据的行合并
            segments = aggregateLinesToChunks(linesWithMetadata);
        } else {
            // 逐行模式：保持每行独立
            segments = linesWithMetadata.stream()
                    .map(line -> new DocumentWithMetadata(line.getContent(), line.getMetadata()))
                    .collect(Collectors.toList());
        }

        // 如果设置了 chunkSize，对超出大小的分片进行二次切割
        if (chunkSize > 0) {
            segments = splitByChunkSize(segments);
        }

        return segments;
    }

    /**
     * 聚合行为分块
     * 将具有相同元数据的行合并为一个分块，并处理父子关系
     *
     * @param lines 待聚合的行列表
     * @return 聚合后的文档片段列表
     */
    private List<DocumentWithMetadata> aggregateLinesToChunks(List<Line> lines) {
        List<Line> aggregatedChunks = new ArrayList<>();
        for (Line line : lines) {
            // 情况1：元数据相同，直接合并到上一个分块
            if (!aggregatedChunks.isEmpty() && aggregatedChunks.get(aggregatedChunks.size() - 1).getMetadata().equals(line.getMetadata())) {
                Line last = aggregatedChunks.get(aggregatedChunks.size() - 1);
                last.setContent(last.getContent() + "  \n" + line.getContent());
            }
            // 情况2：元数据不同但上一行以标题结尾且未剥离标题，则也合并
            // 这样可以将标题和其下的第一段内容合并在一起
            else if (!aggregatedChunks.isEmpty() && !aggregatedChunks.get(aggregatedChunks.size() - 1).getMetadata().equals(line.getMetadata())
                    && aggregatedChunks.get(aggregatedChunks.size() - 1).getMetadata().size() < line.getMetadata().size()
                    && aggregatedChunks.get(aggregatedChunks.size() - 1).getContent().split("\n")[aggregatedChunks.get(aggregatedChunks.size() - 1).getContent().split("\n").length - 1].startsWith("#") && !stripHeaders) {

                Line last = aggregatedChunks.get(aggregatedChunks.size() - 1);
                last.setContent(last.getContent() + "  \n" + line.getContent());
            }
            // 情况3：创建新分块
            else {
                aggregatedChunks.add(line);
            }
        }

        return aggregatedChunks.stream()
                .map(chunk -> new DocumentWithMetadata(chunk.getContent(), chunk.getMetadata()))
                .collect(Collectors.toList());
    }

    /**
     * 对超出 chunkSize 的分片进行二次切割（父子分段 + 滑动窗口重叠的核心实现）
     * <p>
     * 这是简历中"父子分段方案"和"滑动窗口重叠策略"的代码实现。
     * <p>
     * 切割规则：
     * - 未超出 chunkSize 的分片保持不变
     * - 超出 chunkSize 的分片：保留完整分片作为父段（标记为跳过向量化），同时生成多个子段
     *
     * @param segments 按标题切分后的原始分片列表
     * @return 切割后的分片列表（包含父段和子段）
     */
    private List<DocumentWithMetadata> splitByChunkSize(List<DocumentWithMetadata> segments) {
        // 存放最终结果：未超 chunkSize 的原样保留，超了的包含父段+子段
        List<DocumentWithMetadata> result = new ArrayList<>();

        // 遍历每一个标题块
        for (DocumentWithMetadata segment : segments) {
            String content = segment.getContent();

            if (content.length() <= chunkSize) {
                // 情况1：内容长度未超过 chunkSize，保持原样，不做任何处理
                result.add(segment);
            } else {
                // 情况2：内容长度超过 chunkSize，触发父子分段

                // ========== 第一步：创建父段 ==========
                // 父段保留完整内容，标记为跳过向量化（SKIP_EMBEDDING=1）
                // 这样向量检索时匹配不到父段，但后续通过 PARENT_CHUNK_ID 能找到它
                Map<String, Object> fullMetadata = new HashMap<>(segment.getMetadata());

                // 为父段生成唯一 ID，子段通过这个 ID 指向父段
                String parentChunkId = SnowflakeIdGenerator.getInstance().nextIdStr();

                fullMetadata.put(CHUNK_ID, parentChunkId);
                fullMetadata.put(SKIP_EMBEDDING, 1);  // 父段不向量化
                result.add(new DocumentWithMetadata(content, fullMetadata));

                // ========== 第二步：创建多个子段（含滑动窗口重叠） ==========
                int start = 0;
                while (start < content.length()) {
                    // 计算当前子段的结束位置：从 start 开始取 chunkSize 个字符
                    int end = Math.min(start + chunkSize, content.length());
                    // 从完整内容中截取子段文本
                    String subContent = content.substring(start, end);

                    // 复制父段的元数据作为子段元数据的基础
                    Map<String, Object> subMetadata = new HashMap<>(segment.getMetadata());
                    // 为子段生成独立的 chunk_id
                    subMetadata.put(CHUNK_ID, SnowflakeIdGenerator.getInstance().nextIdStr());
                    // 记录父段 ID，后续检索时通过此 ID 找到完整上下文的父段
                    subMetadata.put(PARENT_CHUNK_ID, parentChunkId);

                    result.add(new DocumentWithMetadata(subContent, subMetadata));

                    // 如果是最后一个子段，结束循环
                    if (end == content.length()) {
                        break;
                    }

                    // ========== 滑动窗口重叠的核心逻辑 ==========
                    // 下一个子段的起始位置 = 当前子段的结束位置 - overlap
                    // 例如：chunkSize=500, overlap=50
                    // 子段1: 0-500, 子段2: 450-950, 子段3: 900-1400...
                    // 相邻子段之间有 50 个字符的重叠，保证切分处的上下文不丢失
                    start = end - Math.min(overlap, end);
                }
            }
        }
        return result;
    }


    /**
     * 内部类：表示带有元数据的文本行
     */
    public static class Line {
        /**
         * 文本内容
         */
        private String content;
        /**
         * 元数据信息
         */
        private Map<String, Object> metadata;

        public Line(String content, Map<String, Object> metadata) {
            this.content = content;
            this.metadata = metadata;
        }

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }

        public Map<String, Object> getMetadata() {
            return metadata;
        }

        public void setMetadata(Map<String, Object> metadata) {
            this.metadata = metadata;
        }
    }

    /**
     * 内部类：表示Markdown标题
     */
    public static class Header {
        /**
         * 标题级别（1-6）
         */
        private int level;
        /**
         * 元数据中的键名
         */
        private String name;
        /**
         * 标题文本内容（不含#标记）
         */
        private String data;

        public Header(int level, String name, String data) {
            this.level = level;
            this.name = name;
            this.data = data;
        }

        public int getLevel() {
            return level;
        }

        public void setLevel(int level) {
            this.level = level;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getData() {
            return data;
        }

        public void setData(String data) {
            this.data = data;
        }
    }

    /**
     * 内部类：携带元数据的文档片段
     */
    private static class DocumentWithMetadata {
        private final String content;
        private final Map<String, Object> metadata;

        public DocumentWithMetadata(String content, Map<String, Object> metadata) {
            this.content = content;
            this.metadata = new HashMap<>(metadata);
        }

        public String getContent() {
            return content;
        }

        public Map<String, Object> getMetadata() {
            return metadata;
        }
    }
}
