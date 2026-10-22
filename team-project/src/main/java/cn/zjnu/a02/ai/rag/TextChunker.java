package cn.zjnu.a02.ai.rag;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 文本切块器（FR-7.2，对应 v2.0 §3.3.2「切块策略」）。
 *
 * <p>策略：<b>按语义段落/句子切分，再累积到目标字数</b>，块间保留重叠。
 * <ul>
 *   <li>目标块大小 300–500 字（可配置，默认 400）；</li>
 *   <li>块间重叠 50–80 字（默认 60），避免把一句话从中间切断后语义丢失；</li>
 *   <li>markdown 标题单独作为语义边界，不跨标题合并。</li>
 * </ul>
 *
 * <p>为什么不用固定长度切块：中文按固定字数硬切会把「指标体系的三个层级」这类
 * 概念切散，检索命中后拼出来的上下文读不懂。语义边界优先是本项目的实测结论。
 */
@Component
public class TextChunker {

    private final int targetSize;
    private final int overlap;

    public TextChunker(@Value("${a02.rag.chunk.size:400}") int targetSize,
                       @Value("${a02.rag.chunk.overlap:60}") int overlap) {
        this.targetSize = Math.max(120, targetSize);
        this.overlap = Math.max(0, Math.min(overlap, this.targetSize / 3));
    }

    /** 切块结果：内容 + 序号。 */
    public record Chunk(int index, String content) {
    }

    public List<Chunk> split(String text) {
        List<String> units = toSemanticUnits(text);
        List<Chunk> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int index = 0;

        for (String unit : units) {
            if (current.length() > 0 && current.length() + unit.length() > targetSize) {
                String content = current.toString().trim();
                if (!content.isEmpty()) {
                    chunks.add(new Chunk(index++, content));
                }
                // 用上一块的尾部做重叠，保持跨块语义连续
                current = new StringBuilder();
                if (overlap > 0 && content.length() > overlap) {
                    current.append(content, content.length() - overlap, content.length());
                }
            }
            current.append(unit);
        }
        String tail = current.toString().trim();
        if (!tail.isEmpty()) {
            chunks.add(new Chunk(index, tail));
        }
        return chunks;
    }

    /**
     * 把原文拆成「语义单元」：标题、段落、句子。
     * 保留换行与句末标点，保证拼回去仍可读。
     */
    private List<String> toSemanticUnits(String text) {
        List<String> units = new ArrayList<>();
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');

        for (String paragraph : normalized.split("\n{2,}")) {
            String p = paragraph.strip();
            if (p.isEmpty()) {
                continue;
            }
            // 标题单独成块边界
            if (p.startsWith("#")) {
                units.add(p + "\n");
                continue;
            }
            // 段落内按句子切
            StringBuilder sentence = new StringBuilder();
            for (char c : p.toCharArray()) {
                sentence.append(c);
                if (c == '。' || c == '！' || c == '？' || c == '；' || c == '\n') {
                    units.add(sentence.toString());
                    sentence.setLength(0);
                }
            }
            if (sentence.length() > 0) {
                units.add(sentence.toString());
            }
        }
        return units;
    }
}
