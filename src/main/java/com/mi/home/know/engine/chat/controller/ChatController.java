package com.mi.home.know.engine.chat.controller;

import com.mi.home.know.engine.auth.service.AuthService;
import com.mi.home.know.engine.chat.constant.ChatSource;
import com.mi.home.know.engine.chat.entity.ChatConversation;
import com.mi.home.know.engine.chat.entity.ChatMessage;
import com.mi.home.know.engine.chat.service.ChatApplicationService;
import com.mi.home.know.engine.chat.service.ChatConversationService;
import com.mi.home.know.engine.chat.service.ChatMessageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 流式对话接口
 */
@RestController
@RequestMapping("/chat")
@Slf4j
public class ChatController {

    @Autowired
    private ChatConversationService chatConversationService;

    @Autowired
    private ChatMessageService chatMessageService;

    @Autowired
    private ChatApplicationService chatApplicationService;

    @Autowired
    private AuthService authService;

    /**
     * 当前检索调度模式（gate / router），用于前端顶栏展示。
     */
    @Value("${know-engine.retrieval.mode:gate}")
    private String retrievalMode;

    /**
     * RAG 生成所用模型名，用于前端顶栏展示。
     */
    @Value("${know-engine.model.rag:unknown}")
    private String ragModelName;

    /**
     * 引擎运行时配置（只读），供前端展示当前检索模式与对话模型。
     */
    @GetMapping("/config")
    public Map<String, Object> config() {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("mode", retrievalMode);
        cfg.put("model", ragModelName);
        return cfg;
    }

    /**
     * 流式对话接口
     * <p>
     * 入参：content（用户问题）、conversationId（可选）
     * 返回：SSE 流，每个 token 逐字推送；流结束前推送一条 [DONE] 事件携带 conversationId
     * <p>
     * 进度通知格式：{@code [PROGRESS]:xxx...}，用于在前端展示当前处理阶段，减少等待焦虑。
     * 推送环节包括：意图识别、问题改写、问题路由、排序筛选、生成回答等。
     * <p>
     * userId 从 sa-token session 中获取，无需前端传递。
     *
     * @param content        用户问题
     * @param conversationId 会话ID（可选，不传则自动创建新会话）
     */
    @PostMapping(value = "/send", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> send(
            @RequestParam String content,
            @RequestParam(required = false) String conversationId) {
        String userId = authService.getCurrentUserId();
        return chatApplicationService.chat(userId, content, conversationId, ChatSource.USER_WEB);
    }

    /**
     * 查询当前登录用户的对话列表，按更新时间倒序排序
     * <p>
     * userId 从 sa-token session 中获取。
     */
    @GetMapping("/list")
    public List<ChatConversation> listConversations() {
        String userId = authService.getCurrentUserId();
        return chatConversationService.getConversationsByUserId(userId);
    }

    /**
     * 查询指定对话的消息列表，按创建时间正序排序
     *
     * @param conversationId 会话ID
     */
    @GetMapping("/messages")
    public List<ChatMessage> listMessages(@RequestParam String conversationId) {
        return chatMessageService.getMessagesByConversationId(conversationId);
    }

    /**
     * 删除对话（同时删除该对话下所有消息）
     *
     * @param conversationId 会话ID
     */
    @DeleteMapping("/{conversationId}")
    public boolean deleteConversation(@PathVariable String conversationId) {
        chatMessageService.deleteMessagesByConversationId(conversationId);
        return chatConversationService.deleteConversation(conversationId);
    }
}
