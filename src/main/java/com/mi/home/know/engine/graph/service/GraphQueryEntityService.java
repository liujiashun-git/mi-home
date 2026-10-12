package com.mi.home.know.engine.graph.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.mi.home.know.engine.graph.config.GraphProperties;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 运行时的查询侧实体抽取：把用户问题变成一组"拿去图库里按名字匹配"的实体名。
 *
 * <h3>为什么用改写后的 query，而不是原始问句</h3>
 * 实体抽取的输入质量取决于<b>指代消解</b>，而指代消解恰好是 query 改写已经做过的事——
 * 多轮对话里用户说"它的续航呢"，原始问句里的"它"抽不出任何实体；
 * 改写后的"小米 SU7 Max 的续航"才能抽出"小米 SU7 Max"。复用它，别在原始 query 上重做一遍。
 * <p>
 * 在本项目的位置：查询改写器在检索路由<b>之前</b>执行（LangChain4j 的 RetrievalAugmentor
 * 先 transform、再 route），所以检索器拿到的 query 已经是改写结果——这个"复用"是架构天然给的，
 * 不需要额外接线。
 *
 * <h3>成本</h3>
 * 一次 LLM 调用。所以上游必须有闸门：不需要检索的闲聊轮次根本不进 RAG，
 * 不会为这次抽取付费。
 */
@Slf4j
@Service
public class GraphQueryEntityService {

    @Autowired
    private ChatModel chatModel;

    @Value("classpath:prompts/graph-query-entity-prompt.txt")
    private Resource promptResource;

    @Autowired
    private GraphProperties properties;

    /**
     * 从查询里抽实体名。
     * <p>
     * <b>任何失败都返回空列表</b>，绝不抛异常：图是叠加层，抽不出实体就跳过图路，
     * 主干（向量 ∥ 关键词）照常回答。抽不出实体是常态，不是错误。
     *
     * @param query 改写后的查询文本
     * @return 实体名列表（保持模型输出顺序、按大小写不敏感去重、封顶）；抽不出返回空列表
     */
    public List<String> extract(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        try {
            String response = chatModel.chat(buildPrompt(query));
            return parse(response);
        } catch (Exception e) {
            log.warn("查询实体抽取失败，本次跳过图路, query={}", query, e);
            return List.of();
        }
    }

    /**
     * 暴露 prompt 构造，供验收接口展示"实际送进模型的是什么"
     */
    public String buildPrompt(String query) {
        try {
            return promptResource.getContentAsString(StandardCharsets.UTF_8)
                    .replace("{{query}}", query == null ? "" : query);
        } catch (IOException e) {
            throw new IllegalStateException("读取 prompt 模板失败: " + promptResource, e);
        }
    }

    /**
     * 输出契约校验：剥 BOM/围栏 → 取 JSON 数组 → 只认字符串元素（也兼容 {"name": "..."} 形态）。
     * 不合契约时返回空列表而不是抛错——抽不出实体不值得把整条检索链拖垮。
     */
    private List<String> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String text = raw.replace("```", "").replace("\uFEFF", "").trim();
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start < 0 || end <= start) {
            log.info("查询实体抽取输出不是 JSON 数组（模型可能认为问题里没有实体）");
            return List.of();
        }
        try {
            JSONArray array = JSON.parseArray(text.substring(start, end + 1));
            Map<String, String> deduped = new LinkedHashMap<>();
            for (int i = 0; i < array.size(); i++) {
                String name = readName(array.get(i));
                if (name == null || name.isBlank()) {
                    continue;
                }
                String trimmed = name.trim();
                if (trimmed.length() > 60) {
                    continue;
                }
                deduped.putIfAbsent(trimmed.toLowerCase(Locale.ROOT), trimmed);
                if (deduped.size() >= Math.max(properties.getQueryMaxEntities(), 1)) {
                    break;
                }
            }
            List<String> names = new ArrayList<>(deduped.values());
            log.debug("查询实体抽取结果: {}", names);
            return names;
        } catch (Exception e) {
            log.warn("查询实体抽取输出解析失败，本次跳过图路", e);
            return List.of();
        }
    }

    private String readName(Object element) {
        if (element instanceof String name) {
            return name;
        }
        if (element instanceof JSONObject obj) {
            return obj.getString("name");
        }
        return null;
    }
}
