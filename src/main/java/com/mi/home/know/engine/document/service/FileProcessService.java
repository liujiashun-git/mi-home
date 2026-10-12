package com.mi.home.know.engine.document.service;

import com.mi.home.know.engine.document.constant.FileType;
import com.mi.home.know.engine.document.constant.KnowledgeBaseType;
import com.mi.home.know.engine.document.entity.KnowledgeDocument;

import java.io.File;
import java.io.InputStream;

/**
 * 文件处理服务 - 负责文档转换处理
 */
public interface FileProcessService {
    /**
     * 处理文档转换
     * 1. 从输入流读取文件内容
     * 2. 调用文档解析接口转换格式
     * 3. 转换后的文档保存在MinIO上
     * 4. 更新文档状态
     *
     * @param document 文档对象
     * @param inputStream 文件输入流
     * @return 转换后的文档URL（convertedDocUrl），如果不涉及转换则返回 null
     */
    String processDocument(KnowledgeDocument document, InputStream inputStream);

    /**
     * 判断是否支持该文件
     */
    boolean supports(FileType fileType, KnowledgeBaseType knowledgeBaseType);
}

