package com.mi.home.know.engine.chat.entity;

import com.mi.home.know.engine.ai.model.IntentRecognitionResult;
import com.mi.home.know.engine.chat.constant.ChatSource;

public record ChatParam(String userId, String conversationId, String messageId, String content, String assistantMessageId,
                        IntentRecognitionResult intentRecognitionResult, ChatSource chatSource) {
}
