package com.mi.home.know.engine.rag.util;

import com.mi.home.know.engine.chat.constant.RetrievalSource;
import com.mi.home.know.engine.chat.entity.ChatMessage;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.mi.home.know.engine.rag.constant.MetadataKeyConstant.*;


public class ReferenceUtil {

    public static List<ChatMessage.RagReference> getRagReferences(List<Content> contents, RetrievalSource retrievalSource) {
        return contents.stream().map(content ->
                ChatMessage.RagReference.builder()
                        .documentId(content.textSegment().metadata().getInteger(DOC_ID) + "")
                        .documentTitle(content.textSegment().metadata().getString(FILE_NAME))
                        .url(content.textSegment().metadata().getString(URL))
                        .chunkId(content.textSegment().metadata().getString(CHUNK_ID))
                        .chunkContent(content.textSegment().text())
                        .similarityScore((Double) content.metadata().get(ContentMetadata.SCORE))
                        .retrievalSource(retrievalSource)
                        .rerankScore((Double) content.metadata().get(ContentMetadata.RERANKED_SCORE))
                        .metadata(buildMeta(content))
                        .build()).collect(Collectors.toList());
    }

    public static ChatMessage.RagReference getRagReference(Content content, RetrievalSource retrievalSource) {
        return ChatMessage.RagReference.builder()
                .documentId(content.textSegment().metadata().getInteger(DOC_ID) + "")
                .documentTitle(content.textSegment().metadata().getString(FILE_NAME))
                .url(content.textSegment().metadata().getString(URL))
                .chunkId(content.textSegment().metadata().getString(CHUNK_ID))
                .chunkContent(content.textSegment().text())
                .similarityScore((Double) content.metadata().get(ContentMetadata.SCORE))
                .retrievalSource(retrievalSource)
                .rerankScore((Double) content.metadata().get(ContentMetadata.RERANKED_SCORE))
                .metadata(buildMeta(content))
                .build();
    }

    /**
     * 抽出前端展示需要的定位信息（表格 chunk 的 sheet + 行号、分块类型等）。
     * <p>
     * 只做透传、不做转换：字段存在才放进 Map，避免给前端塞一堆 null。
     * 前端据此渲染「Sheet「xxx」· 第 2–11 行」这类引用锚点。
     * <p>
     * 注意：这里<b>刻意不走 {@code Metadata.getString/getInteger}</b>。
     * langchain4j 的 Metadata 是严格类型的 —— 对一个 Integer 值调 getString 会直接抛
     * RuntimeException，而 ES 反序列化回来的元数据类型并不总是和写入时一致
     * （例如 version 在 Excel 链路写入的是 int）。一旦抛出，整个引用回写都会被
     * 上层 catch 吞掉，表现为「回答正常但没有任何引用来源」。所以这里统一走
     * {@code toMap()} 原样取值。
     */
    private static Map<String, Object> buildMeta(Content content) {
        Map<String, Object> raw = content.textSegment().metadata() == null
                ? Map.of()
                : content.textSegment().metadata().toMap();
        Map<String, Object> meta = new LinkedHashMap<>();
        copyIfPresent(meta, raw, CONTENT_TYPE);
        copyIfPresent(meta, raw, LOCATOR);
        copyIfPresent(meta, raw, SHEET_NAME);
        copyIfPresent(meta, raw, ROW_START);
        copyIfPresent(meta, raw, ROW_END);
        copyIfPresent(meta, raw, RETRIEVAL_SOURCE);
        copyIfPresent(meta, raw, VERSION);
        return meta.isEmpty() ? null : meta;
    }

    private static void copyIfPresent(Map<String, Object> target, Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (value != null) {
            target.put(key, value);
        }
    }
}
