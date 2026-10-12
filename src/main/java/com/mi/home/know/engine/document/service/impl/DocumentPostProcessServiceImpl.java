package com.mi.home.know.engine.document.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.mi.home.know.engine.document.constant.DocumentStatus;
import com.mi.home.know.engine.document.constant.SegmentStatus;
import com.mi.home.know.engine.document.entity.KnowledgeDocument;
import com.mi.home.know.engine.document.entity.KnowledgeDocumentVersion;
import com.mi.home.know.engine.document.entity.KnowledgeSegment;
import com.mi.home.know.engine.document.service.DocumentPostProcessService;
import com.mi.home.know.engine.document.service.KnowledgeDocumentService;
import com.mi.home.know.engine.document.service.KnowledgeDocumentVersionService;
import com.mi.home.know.engine.document.service.KnowledgeSegmentService;
import com.mi.home.know.engine.document.service.VectorStoreService;
import com.mi.home.know.engine.infra.snowflake.SnowflakeIdGenerator;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.mi.home.know.engine.rag.constant.MetadataKeyConstant.ACCESSIBLE_BY;
import static com.mi.home.know.engine.rag.constant.MetadataKeyConstant.CHUNK_ID;
import static com.mi.home.know.engine.rag.constant.MetadataKeyConstant.CONTENT_TYPE;
import static com.mi.home.know.engine.rag.constant.MetadataKeyConstant.DOC_ID;
import static com.mi.home.know.engine.rag.constant.MetadataKeyConstant.FILE_NAME;
import static com.mi.home.know.engine.rag.constant.MetadataKeyConstant.GENERATED_QUESTIONS;
import static com.mi.home.know.engine.rag.constant.MetadataKeyConstant.PARENT_CHUNK_ID;
import static com.mi.home.know.engine.rag.constant.MetadataKeyConstant.QUESTION_ID;
import static com.mi.home.know.engine.rag.constant.MetadataKeyConstant.TYPE_DOC_SUMMARY;
import static com.mi.home.know.engine.rag.constant.MetadataKeyConstant.TYPE_GENERATED_QUESTION;
import static com.mi.home.know.engine.rag.constant.MetadataKeyConstant.TYPE_TABLE_COLUMN;
import static com.mi.home.know.engine.rag.constant.MetadataKeyConstant.TYPE_TABLE_SUMMARY;
import static com.mi.home.know.engine.rag.constant.MetadataKeyConstant.URL;
import static com.mi.home.know.engine.rag.constant.MetadataKeyConstant.VERSION;

/**
 * 文档后处理实现：文档摘要 + 问题生成（doc2query）。
 * <p>
 * 设计约束（对应异步流水线的四条通用模式）：
 * <ol>
 *   <li><b>异步独立执行</b>：独立线程池，不占用主干向量化的事件监听线程；
 *       失败只记日志，文档照常可检索——增强是锦上添花，不是主干的一部分</li>
 *   <li><b>输出契约校验</b>：LLM 输出声称是 JSON 但解析失败/字段缺失时，
 *       拒绝入库并重试，绝不存"半截 JSON"这种静默脏数据进索引</li>
 *   <li><b>确定性兜底</b>：摘要彻底失败时用正文前 500 字顶着，不让概览位空着；
 *       问题生成彻底失败时只跳过该批，正文 chunk 本来就可检索，无兜底需求</li>
 *   <li><b>每批检查取消</b>：问题生成按批 fan-out，每批开头重新确认文档还活着、
 *       版本没被切——用户删掉的文档不再烧 LLM 额度</li>
 * </ol>
 * 另有两条细粒度防护：
 * <ul>
 *   <li><b>版本防护</b>：生成前记下分段 updatedAt，写索引前重读一次，
 *       变了就丢弃该块的问题——防止给已被编辑的正文挂"针对旧正文"的问题</li>
 *   <li><b>幂等</b>：metadata 里已有产物的分段直接跳过，任务可安全重跑</li>
 * </ul>
 */
@Slf4j
@Service
public class DocumentPostProcessServiceImpl implements DocumentPostProcessService {

    /**
     * 拼进摘要 prompt 的正文上限，防止长文档把 prompt 撑爆
     */
    private static final int SUMMARY_INPUT_MAX_CHARS = 4000;

    /**
     * 摘要兜底的正文截断长度（确定性兜底：截原文，不编内容）
     */
    private static final int SUMMARY_FALLBACK_CHARS = 500;

    /**
     * 拼进问题生成 prompt 的单块正文上限
     */
    private static final int CHUNK_TEXT_MAX_CHARS = 800;

    /**
     * 邻块上下文摘录长度（前后各一块）
     */
    private static final int NEIGHBOR_SNIPPET_CHARS = 150;

    /**
     * 单条问题长度上限，超长视为模型输出失控，丢弃
     */
    private static final int QUESTION_MAX_CHARS = 200;

    @Autowired
    private ChatModel chatModel;

    @Autowired
    private KnowledgeSegmentService knowledgeSegmentService;

    @Autowired
    private KnowledgeDocumentService knowledgeDocumentService;

    @Autowired
    private KnowledgeDocumentVersionService knowledgeDocumentVersionService;

    @Autowired
    private VectorStoreService vectorStoreService;

    @Value("classpath:prompts/doc-summary-prompt.txt")
    private Resource docSummaryPromptResource;

    @Value("classpath:prompts/question-generation-prompt.txt")
    private Resource questionGenerationPromptResource;

    @Value("${know-engine.postprocess.enabled:true}")
    private boolean enabled;

    @Value("${know-engine.postprocess.summary-enabled:true}")
    private boolean summaryEnabled;

    @Value("${know-engine.postprocess.question-enabled:true}")
    private boolean questionEnabled;

    @Value("${know-engine.postprocess.questions-per-chunk:3}")
    private int questionsPerChunk;

    @Value("${know-engine.postprocess.questions-max:10}")
    private int questionsMax;

    @Value("${know-engine.postprocess.question-batch-size:5}")
    private int questionBatchSize;

    @Async("postProcessExecutor")
    @Override
    public void postProcessAsync(Long docId, Long versionId) {
        if (!enabled) {
            return;
        }
        try {
            KnowledgeDocument document = knowledgeDocumentService.getById(docId);
            if (document == null) {
                log.info("后处理跳过：文档不存在（可能已删除）, docId={}", docId);
                return;
            }
            // 版本已被切换/有新版本在途：本版本的产物即将被清理，不再投入
            if (!Objects.equals(document.getCurrentVersionId(), versionId)) {
                log.info("后处理跳过：当前版本已切换, docId={}, expect={}, actual={}",
                        docId, versionId, document.getCurrentVersionId());
                return;
            }

            long start = System.currentTimeMillis();
            if (summaryEnabled) {
                generateDocSummary(document, versionId);
            }
            if (questionEnabled) {
                generateQuestions(document, versionId);
            }
            log.info("文档后处理完成, docId={}, versionId={}, 耗时 {} ms",
                    docId, versionId, System.currentTimeMillis() - start);
        } catch (Exception e) {
            // 后处理是增强而非主干：失败不影响文档可检索性
            log.error("文档后处理失败（不影响文档可用性）, docId={}, versionId={}", docId, versionId, e);
        }
    }

    // ==================== 文档摘要 ====================

    /**
     * 生成文档级摘要 chunk（概览形态）。
     * <ul>
     *   <li>gist 放正文最前——向量模型对开头更敏感，先命中"一句话主题"</li>
     *   <li>parentChunkId 挂第一个正文块：不进 chunk 树就成了孤儿；
     *       但检索器的父块替换有类型白名单，摘要命中时【不会】被换回正文块</li>
     *   <li>输出契约校验失败重试一次；仍失败走确定性兜底（正文前 500 字）</li>
     * </ul>
     */
    private void generateDocSummary(KnowledgeDocument document, Long versionId) {
        Long docId = document.getDocId();

        // 幂等：本版本已有摘要 chunk 就跳过
        Long exists = knowledgeSegmentService.count(new QueryWrapper<KnowledgeSegment>()
                .eq("document_id", docId)
                .eq("document_version", versionId)
                .apply("json_extract(metadata, '$." + CONTENT_TYPE + "') = {0}", TYPE_DOC_SUMMARY));
        if (exists != null && exists > 0) {
            log.info("摘要 chunk 已存在，跳过, docId={}", docId);
            return;
        }

        List<KnowledgeSegment> segments = knowledgeSegmentService.list(new QueryWrapper<KnowledgeSegment>()
                .eq("document_id", docId)
                .eq("document_version", versionId)
                .eq("skip_embedding", 0)
                .orderByAsc("chunk_order"));
        if (segments.isEmpty()) {
            // 无可用正文（如纯图片扫描件）：不拿空内容凑数，明示失败
            log.warn("文档无可摘要正文，摘要任务明示失败, docId={}", docId);
            return;
        }

        StringBuilder contentBuilder = new StringBuilder();
        for (KnowledgeSegment segment : segments) {
            if (contentBuilder.length() >= SUMMARY_INPUT_MAX_CHARS) {
                break;
            }
            if (contentBuilder.length() > 0) {
                contentBuilder.append("\n\n");
            }
            contentBuilder.append(segment.getText() == null ? "" : segment.getText());
        }
        String content = truncate(contentBuilder.toString(), SUMMARY_INPUT_MAX_CHARS);
        String docTitle = document.getDocTitle() == null ? "" : document.getDocTitle();

        // 生成 + 契约校验（失败重试一次）
        String prompt = loadPrompt(docSummaryPromptResource)
                .replace("{{docTitle}}", docTitle)
                .replace("{{content}}", content);
        String gist = null;
        String summary = null;
        for (int attempt = 1; attempt <= 2 && gist == null; attempt++) {
            try {
                String response = chatModel.chat(prompt);
                String[] parsed = parseSummaryOutput(response);
                if (parsed != null) {
                    gist = parsed[0];
                    summary = parsed[1];
                } else {
                    log.warn("摘要输出不符合契约（第 {} 次尝试），拒绝入库, docId={}", attempt, docId);
                }
            } catch (Exception e) {
                log.warn("摘要生成调用失败（第 {} 次尝试）, docId={}, error={}", attempt, docId, e.getMessage());
            }
        }

        // 确定性兜底：彻底失败也不让概览位空着——用正文开头节选顶着
        boolean fallback = gist == null;
        if (fallback) {
            gist = "（自动摘要生成失败，以下为正文开头节选）";
            summary = truncate(content, SUMMARY_FALLBACK_CHARS);
            log.warn("摘要生成彻底失败，启用确定性兜底（正文前 {} 字）, docId={}", SUMMARY_FALLBACK_CHARS, docId);
        }

        // gist 放最前：向量模型对开头更敏感
        String text = gist + "\n\n" + summary;

        String firstTextChunkId = segments.get(0).getChunkId();
        int maxOrder = segments.stream()
                .map(KnowledgeSegment::getChunkOrder)
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(segments.size() - 1);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(CHUNK_ID, SnowflakeIdGenerator.getInstance().nextIdStr());
        metadata.put(CONTENT_TYPE, TYPE_DOC_SUMMARY);
        metadata.put(DOC_ID, docId);
        metadata.put(FILE_NAME, docTitle);
        KnowledgeDocumentVersion versionRecord = knowledgeDocumentVersionService.getById(versionId);
        metadata.put(URL, versionRecord == null ? null : versionRecord.getDocUrl());
        metadata.put(VERSION, versionId);
        metadata.put(ACCESSIBLE_BY, document.getAccessibleBy());
        // 挂进 chunk 树（不当孤儿）。检索器对 doc_summary 有类型白名单，
        // 该字段不会触发父块替换——见 KnowEngineElasticsearchContentRetriever
        metadata.put(PARENT_CHUNK_ID, firstTextChunkId);

        KnowledgeSegment summarySegment = new KnowledgeSegment();
        summarySegment.setText(text);
        summarySegment.setChunkId((String) metadata.get(CHUNK_ID));
        summarySegment.setMetadata(JSON.toJSONString(metadata));
        summarySegment.setDocumentId(docId);
        summarySegment.setDocumentVersion(versionId);
        summarySegment.setChunkOrder(maxOrder + 1);
        summarySegment.setSkipEmbedding(0);
        summarySegment.setStatus(SegmentStatus.STORED);
        knowledgeSegmentService.save(summarySegment);

        String embeddingId = vectorStoreService.embedAndStore(summarySegment);
        summarySegment.setEmbeddingId(embeddingId);
        summarySegment.setStatus(SegmentStatus.VECTOR_STORED);
        knowledgeSegmentService.updateById(summarySegment);

        log.info("文档摘要 chunk 生成完成（{}）, docId={}, chunkId={}",
                fallback ? "兜底" : "LLM", docId, summarySegment.getChunkId());
    }

    /**
     * 摘要输出契约校验：剥 BOM、剥代码围栏、提取 JSON 对象、校验必填字段。
     * <p>
     * 任何一步不满足都返回 null（调用方重试或兜底）——
     * 坏输出宁可报错重来，不能把半截 JSON 静默存进检索索引。
     */
    private String[] parseSummaryOutput(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = stripBom(raw).replace("```", "").trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            JSONObject obj = JSON.parseObject(text.substring(start, end + 1));
            String gist = obj.getString("gist");
            String summary = obj.getString("summary");
            if (gist == null || gist.isBlank() || summary == null || summary.isBlank()) {
                return null;
            }
            return new String[]{gist.trim(), summary.trim()};
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 问题生成（doc2query） ====================

    /**
     * 为每个正文 chunk 生成 N 个问题，作为指回原 chunk 的向量索引条目。
     * <p>
     * 关键设计：<b>问题不落 chunk</b>——只往原 chunk 的 metadata 里写一份问题清单
     * （供展示/幂等判断），向量索引里的问题条目带 chunkId 指回原 chunk。
     * 存储里只有一份正文，索引里同一实体允许"正文 + 问句"两种表示；
     * 命中任何一条问题，检索器都会换回原 chunk 的正文。
     * <p>
     * 只对"正文形态"的 chunk 生成：普通文本块 + 表格行块（表格行的问题
     * 自带型号与参数词，正好补向量对数字失明的洞）。表摘要/列描述本身
     * 就是问句友好的替身，跳过。
     */
    private void generateQuestions(KnowledgeDocument document, Long versionId) {
        Long docId = document.getDocId();
        List<KnowledgeSegment> segments = knowledgeSegmentService.list(new QueryWrapper<KnowledgeSegment>()
                .eq("document_id", docId)
                .eq("document_version", versionId)
                .eq("skip_embedding", 0)
                .eq("status", SegmentStatus.VECTOR_STORED)
                .orderByAsc("chunk_order"));

        List<KnowledgeSegment> targets = new ArrayList<>();
        for (KnowledgeSegment segment : segments) {
            Map<String, Object> metadata = parseMetadata(segment.getMetadata());
            Object contentType = metadata.get(CONTENT_TYPE);
            // 表摘要/列描述已经是问句友好的替身；摘要块同理。只对正文形态生成
            if (TYPE_TABLE_SUMMARY.equals(contentType)
                    || TYPE_TABLE_COLUMN.equals(contentType)
                    || TYPE_DOC_SUMMARY.equals(contentType)) {
                continue;
            }
            // 幂等：已生成过问题的块跳过
            if (metadata.containsKey(GENERATED_QUESTIONS)) {
                continue;
            }
            targets.add(segment);
        }
        if (targets.isEmpty()) {
            log.info("无需生成问题的正文块, docId={}", docId);
            return;
        }

        String docTitle = document.getDocTitle() == null ? "" : document.getDocTitle();
        int totalQuestions = 0;

        // 按批 fan-out：每批一次 LLM 调用；每批开头检查文档是否已取消/删除/切版本，
        // 用户放弃的文档不再烧剩余批次的 LLM 额度
        for (int from = 0; from < targets.size(); from += Math.max(questionBatchSize, 1)) {
            KnowledgeDocument freshDoc = knowledgeDocumentService.getById(docId);
            if (freshDoc == null || !Objects.equals(freshDoc.getCurrentVersionId(), versionId)) {
                log.warn("文档已删除或版本已切换，中止剩余问题生成批次, docId={}, 已处理 {}/{} 块",
                        docId, from, targets.size());
                break;
            }

            int to = Math.min(from + Math.max(questionBatchSize, 1), targets.size());
            List<KnowledgeSegment> batch = targets.subList(from, to);
            totalQuestions += processQuestionBatch(docTitle, docId, versionId, targets, batch, from);
        }

        log.info("问题生成完成, docId={}, 正文块 {} 个, 共写入索引条目 {} 条", docId, targets.size(), totalQuestions);
    }

    /**
     * 处理一个问题批次：构造上下文 → LLM → 契约校验 → 版本防护 → 回写 metadata + 批量索引。
     *
     * @return 本批成功写入索引的问题条数
     */
    private int processQuestionBatch(String docTitle, Long docId, Long versionId,
                                     List<KnowledgeSegment> allTargets,
                                     List<KnowledgeSegment> batch, int batchOffset) {
        // 版本防护快照：生成期间内容可能被编辑
        Map<Long, LocalDateTime> revisionSnapshot = new LinkedHashMap<>();
        for (KnowledgeSegment segment : batch) {
            revisionSnapshot.put(segment.getId(), segment.getUpdatedAt());
        }

        String prompt = loadPrompt(questionGenerationPromptResource)
                .replace("{{questionCount}}", String.valueOf(Math.max(1, Math.min(questionsPerChunk, questionsMax))))
                .replace("{{docTitle}}", docTitle)
                .replace("{{chunks}}", buildChunkBlock(allTargets, batch, batchOffset));

        List<QuestionGroup> groups = null;
        for (int attempt = 1; attempt <= 2 && groups == null; attempt++) {
            try {
                String response = chatModel.chat(prompt);
                groups = parseQuestionOutput(response, batchOffset, batch.size());
                if (groups == null) {
                    log.warn("问题输出不符合契约（第 {} 次尝试），整批拒绝, docId={}, batch=[{}..{})",
                            attempt, docId, batchOffset, batchOffset + batch.size());
                }
            } catch (Exception e) {
                log.warn("问题生成调用失败（第 {} 次尝试）, docId={}, error={}", attempt, docId, e.getMessage());
            }
        }
        if (groups == null) {
            // 整批放弃：正文块本来就可检索，问题只是增强
            return 0;
        }

        String accessibleBy = extractAccessibleBy(batch.get(0));
        List<TextSegment> indexEntries = new ArrayList<>();
        int questionCount = Math.max(1, Math.min(questionsPerChunk, questionsMax));

        for (QuestionGroup group : groups) {
            // prompt 里给的是全局编号（batchOffset + 批内下标），这里换算回批内下标
            KnowledgeSegment target = batch.get(group.chunkIndex() - batchOffset);

            // 版本防护：写索引前重读，updatedAt 变了说明正文已被编辑，产物作废
            KnowledgeSegment fresh = knowledgeSegmentService.getById(target.getId());
            if (fresh == null
                    || !Objects.equals(fresh.getUpdatedAt(), revisionSnapshot.get(target.getId()))) {
                log.warn("分段在问题生成期间被修改，丢弃该块的问题, chunkId={}", target.getChunkId());
                continue;
            }

            Map<String, Object> metadata = parseMetadata(fresh.getMetadata());
            if (metadata.containsKey(GENERATED_QUESTIONS)) {
                continue; // 并发重跑的幂等保护
            }

            List<String> questions = sanitizeQuestions(group.questions(), questionCount);
            if (questions.isEmpty()) {
                continue;
            }

            // 问题清单写进原 chunk 的 metadata（存储侧）
            metadata.put(GENERATED_QUESTIONS, questions);
            fresh.setMetadata(JSON.toJSONString(metadata));
            knowledgeSegmentService.updateById(fresh);

            // 问题条目写进向量索引（索引侧）：文本=问句，chunkId 指回原 chunk
            for (String question : questions) {
                Map<String, Object> entryMetadata = new LinkedHashMap<>();
                entryMetadata.put(DOC_ID, docId);
                entryMetadata.put(FILE_NAME, docTitle);
                entryMetadata.put(VERSION, versionId);
                entryMetadata.put(ACCESSIBLE_BY, accessibleBy);
                entryMetadata.put(CHUNK_ID, fresh.getChunkId());
                entryMetadata.put(CONTENT_TYPE, TYPE_GENERATED_QUESTION);
                entryMetadata.put(QUESTION_ID, SnowflakeIdGenerator.getInstance().nextIdStr());
                // 前置文档标题再 embed：拉近问句与文档级关键词（型号/车系）的距离
                indexEntries.add(new TextSegment(
                        docTitle + "\n" + question,
                        Metadata.from(entryMetadata)));
            }
        }

        if (indexEntries.isEmpty()) {
            return 0;
        }
        // 批内所有问题合并成一次向量写入，减少与向量库的交互次数
        vectorStoreService.embedAndStoreSegments(indexEntries);
        return indexEntries.size();
    }

    /**
     * 构造拼进 prompt 的片段清单：每块带正文 + 前后各一块的上下文摘录。
     * 孤立 chunk 的语义是断的，补上邻块后生成的问题更贴合语义断点。
     */
    private String buildChunkBlock(List<KnowledgeSegment> allTargets, List<KnowledgeSegment> batch, int batchOffset) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < batch.size(); i++) {
            int globalIndex = batchOffset + i;
            KnowledgeSegment current = batch.get(i);
            if (i > 0) {
                sb.append("\n\n");
            }
            sb.append("[chunkIndex=").append(globalIndex).append("]\n");
            sb.append(truncate(current.getText(), CHUNK_TEXT_MAX_CHARS)).append('\n');
            if (globalIndex > 0) {
                String prev = allTargets.get(globalIndex - 1).getText();
                sb.append("（前一块结尾：").append(truncate(prev, NEIGHBOR_SNIPPET_CHARS)).append("）\n");
            }
            if (globalIndex < allTargets.size() - 1) {
                String next = allTargets.get(globalIndex + 1).getText();
                sb.append("（后一块开头：").append(truncate(next, NEIGHBOR_SNIPPET_CHARS)).append("）\n");
            }
        }
        return sb.toString();
    }

    /**
     * 问题输出契约校验：剥 BOM/围栏 → 解析 JSON 数组 → 校验形状与下标范围。
     * chunkIndex 是全局编号（与 prompt 中 [chunkIndex=N] 一致），
     * 合法范围 [batchOffset, batchOffset + batchSize)。
     * 解析失败或形状不对返回 null，调用方整批重试后放弃——不存半截产物。
     */
    private List<QuestionGroup> parseQuestionOutput(String raw, int batchOffset, int batchSize) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = stripBom(raw).replace("```", "").trim();
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            String candidate = text.substring(start, end + 1);
            JSONArray array;
            try {
                array = JSON.parseArray(candidate);
            } catch (Exception first) {
                // 常见畸形修复：模型会把对象收尾写成双大括号（正确为 ]}，模型输出 ]}}）。
                // 本 JSON 中 questions 数组内不含嵌套对象，"]}}" 序列必为该畸形，可安全替换
                array = JSON.parseArray(candidate.replace("]}}", "]}"));
            }
            List<QuestionGroup> groups = new ArrayList<>();
            for (int i = 0; i < array.size(); i++) {
                JSONObject obj = array.getJSONObject(i);
                if (obj == null) {
                    return null;
                }
                Integer chunkIndex = obj.getInteger("chunkIndex");
                JSONArray questions = obj.getJSONArray("questions");
                if (chunkIndex == null || chunkIndex < batchOffset || chunkIndex >= batchOffset + batchSize
                        || questions == null || questions.isEmpty()) {
                    return null;
                }
                List<String> list = new ArrayList<>();
                for (int j = 0; j < questions.size(); j++) {
                    String q = questions.getString(j);
                    if (q != null && !q.isBlank()) {
                        list.add(q.trim());
                    }
                }
                groups.add(new QuestionGroup(chunkIndex, list));
            }
            return groups.isEmpty() ? null : groups;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 清洗问题：去空、截断、按内容去重、封顶。防止一个 chunk 刷出几十条问题稀释索引。
     */
    private List<String> sanitizeQuestions(List<String> raw, int limit) {
        if (raw == null || raw.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>();
        for (String question : raw) {
            if (question == null || question.isBlank()) {
                continue;
            }
            String trimmed = truncate(question.trim(), QUESTION_MAX_CHARS);
            boolean duplicated = false;
            for (String existing : result) {
                if (existing.equalsIgnoreCase(trimmed)) {
                    duplicated = true;
                    break;
                }
            }
            if (!duplicated) {
                result.add(trimmed);
            }
            if (result.size() >= limit) {
                break;
            }
        }
        return result;
    }

    // ==================== 工具方法 ====================

    private String loadPrompt(Resource resource) {
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取 prompt 模板失败: " + resource, e);
        }
    }

    private String stripBom(String text) {
        if (text != null && text.startsWith("\uFEFF")) {
            return text.substring(1);
        }
        return text;
    }

    private String truncate(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        return trimmed.length() <= maxChars ? trimmed : trimmed.substring(0, maxChars);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseMetadata(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return JSON.parseObject(metadataJson, LinkedHashMap.class);
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private String extractAccessibleBy(KnowledgeSegment segment) {
        Object value = parseMetadata(segment.getMetadata()).get(ACCESSIBLE_BY);
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 一批问题解析结果：chunkIndex 是批内下标（0-based）
     */
    private record QuestionGroup(int chunkIndex, List<String> questions) {
    }
}
