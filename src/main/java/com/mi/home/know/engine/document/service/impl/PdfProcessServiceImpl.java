package com.mi.home.know.engine.document.service.impl;

import com.mi.home.know.engine.document.constant.FileType;
import com.mi.home.know.engine.document.constant.KnowledgeBaseType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 文件处理服务 - 负责文档转换处理
 */
@Slf4j
@Service
public class PdfProcessServiceImpl extends MinerUProcessBaseServiceImpl {

    @Override
    public boolean supports(FileType fileType, KnowledgeBaseType knowledgeBaseType) {
        return fileType == FileType.PDF;
    }
}

