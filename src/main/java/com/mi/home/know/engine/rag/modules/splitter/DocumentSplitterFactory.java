package com.mi.home.know.engine.rag.modules.splitter;

import com.mi.home.know.engine.document.constant.SplitType;
import com.mi.home.know.engine.document.entity.DocumentSplitParam;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentByRegexSplitter;
import dev.langchain4j.data.document.splitter.DocumentByWordSplitter;

/**
 * 文档切分器工厂类
 * <p>
 * 根据用户指定的切分策略（splitType）返回对应的 DocumentSplitter 实例。
 * 支持以下五种策略：
 * <ul>
 *   <li>TITLE — 基于 Markdown 标题层级做父子分段（推荐）</li>
 *   <li>SMART — 智能切分，默认按 TITLE 策略，自动设置 overlap 为 chunkSize 的 10%</li>
 *   <li>LENGTH — 按固定字符/单词数简单切分</li>
 *   <li>SEPARATOR — 按自定义分隔符切分</li>
 *   <li>REGEX — 按自定义正则表达式切分</li>
 * </ul>
 *
 */
public class DocumentSplitterFactory {

    /**
     * 根据切分参数获取对应的切分器
     *
     * @param documentSplitParam 切分参数（包含 splitType, chunkSize, overlap, titleLevel, separator, regex）
     * @return DocumentSplitter 实例，若 splitType 不匹配则返回 null
     */
    public static DocumentSplitter getInstance(DocumentSplitParam documentSplitParam) {

        // TITLE 策略：基于 Markdown 标题层级切分
        // 先按 # / ## / ### 等标题分割语义块，再对每个块按 chunkSize 做二次切割
        // titleLevel 控制切到几级标题（如 titleLevel=3 表示切到 ###）
        if (SplitType.TITLE.name().equals(documentSplitParam.splitType())) {
            return new MarkdownHeaderParentTextSplitter(
                    documentSplitParam.titleLevel(), false, false,
                    documentSplitParam.chunkSize(), documentSplitParam.overlap()
            );
        }

        // LENGTH 策略：按固定字符/单词数简单切分
        // 不关心文档结构，直接按 chunkSize 截断，超过就切下一个
        // 适用于没有标题结构的纯文本
        if (SplitType.LENGTH.name().equals(documentSplitParam.splitType())) {
            return new DocumentByWordSplitter(
                    documentSplitParam.chunkSize(), documentSplitParam.overlap()
            );
        }

        // SEPARATOR 策略：按自定义分隔符切分
        // 以 separator 作为一级分隔符，以 \n\n（空行）作为二级分隔符
        // 适用于有明确分隔标记但无标题的文档
        if (SplitType.SEPARATOR.name().equals(documentSplitParam.splitType())) {
            return new DocumentByRegexSplitter(
                    documentSplitParam.separator(), "\\n\\n",
                    documentSplitParam.chunkSize(), documentSplitParam.overlap()
            );
        }

        // REGEX 策略：按自定义正则表达式切分
        // 以 regex 作为一级分隔符，以 \n\n（空行）作为二级分隔符
        // 适用于需要复杂匹配规则的场景
        if (SplitType.REGEX.name().equals(documentSplitParam.splitType())) {
            return new DocumentByRegexSplitter(
                    documentSplitParam.regex(), "\\n\\n",
                    documentSplitParam.chunkSize(), documentSplitParam.overlap()
            );
        }

        // SMART 策略：智能切分
        // 默认使用 TITLE 策略（按标题切分），overlap 自动设为 chunkSize 的 10%
        // 这是最推荐的默认策略，兼顾语义完整性和检索效果
        if (SplitType.SMART.name().equals(documentSplitParam.splitType())) {
            int defaultOverlap = (int) (documentSplitParam.chunkSize() * 0.1);
            return new MarkdownHeaderParentTextSplitter(
                    documentSplitParam.chunkSize(), defaultOverlap
            );
        }

        // 不支持的策略类型
        return null;
    }
}
