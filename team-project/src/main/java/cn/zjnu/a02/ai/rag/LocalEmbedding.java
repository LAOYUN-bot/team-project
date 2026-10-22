package cn.zjnu.a02.ai.rag;

import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * 本地确定性向量化（离线可用的 embedding 实现）。
 *
 * <p><b>为什么需要它</b>：v2.0 要求自建 RAG 管线，但开发机完全离线、无法调用云端
 * embedding 接口、也没有 GPU 跑 bge-m3。若直接依赖云向量化，整个 RAG 功能在离线环境下
 * 完全不可用，演示与测试都会断掉。
 *
 * <p><b>做法</b>：中文按「字符二元组（bigram）」、英文/数字按「词」切分，
 * 用稳定哈希把每个 token 映射到固定维度的稀疏向量上（带次线性词频），再做 L2 归一化。
 * 这正是经典的 **hashing trick + TF 余弦相似度**：
 * <ul>
 *   <li>确定性：同一文本永远得到同一向量，单测与评估可复现；</li>
 *   <li>零依赖：不需要模型文件、不需要网络；</li>
 *   <li>可替换：接口与真实 embedding 完全一致（见 {@link EmbeddingService}），
 *       配置成云端 embedding 后即可切换，业务代码不动。</li>
 * </ul>
 *
 * <p><b>能力边界（如实说明）</b>：它捕捉的是字面/词面相似度，不具备语义泛化能力
 * （例如「薪资」与「报酬」不会自动相近）。因此本项目的 RAG 题目设计为
 * 「基于资料的事实问答」，而不是开放式语义联想；接入真实 embedding 后能力会提升。
 */
@Component
public class LocalEmbedding {

    /** 向量维度：256 维在数千片段规模下足够区分，且计算开销极小。 */
    public static final int DIMENSION = 256;

    public float[] embed(String text) {
        float[] vector = new float[DIMENSION];
        if (text == null || text.isBlank()) {
            return vector;
        }
        Map<String, Integer> tf = tokenize(text);
        for (Map.Entry<String, Integer> entry : tf.entrySet()) {
            // 次线性词频：避免长文档被高频词主导
            double weight = 1.0 + Math.log(entry.getValue());
            int hash = entry.getKey().hashCode();
            int idx = Math.floorMod(hash, DIMENSION);
            vector[idx] += (float) weight * (hash % 2 == 0 ? 1.0f : -1.0f);   // 符号哈希，抵消部分碰撞
        }
        normalize(vector);
        return vector;
    }

    /** 余弦相似度（输入向量应已归一化）。 */
    public static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) {
            return 0;
        }
        double dot = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
        }
        return dot;
    }

    /**
     * 分词：
     * <ul>
     *   <li>中文：连续汉字串内取「二元组」，单字也保留（二元组捕捉搭配，单字兜底）；</li>
     *   <li>英文/数字：按词切，转小写。</li>
     * </ul>
     */
    Map<String, Integer> tokenize(String text) {
        Map<String, Integer> tf = new HashMap<>();
        StringBuilder cjk = new StringBuilder();
        StringBuilder latin = new StringBuilder();

        for (char c : text.toCharArray()) {
            if (isCjk(c)) {
                flushLatin(latin, tf);
                cjk.append(c);
            } else if (Character.isLetterOrDigit(c)) {
                flushCjk(cjk, tf);
                latin.append(c);
            } else {
                flushCjk(cjk, tf);
                flushLatin(latin, tf);
            }
        }
        flushCjk(cjk, tf);
        flushLatin(latin, tf);
        return tf;
    }

    private static boolean isCjk(char c) {
        return c >= 0x4E00 && c <= 0x9FFF;
    }

    private static void flushCjk(StringBuilder buffer, Map<String, Integer> tf) {
        int len = buffer.length();
        if (len == 0) {
            return;
        }
        if (len == 1) {
            add(tf, buffer.toString());
        } else {
            for (int i = 0; i + 1 < len; i++) {
                add(tf, buffer.substring(i, i + 2));
            }
        }
        buffer.setLength(0);
    }

    private static void flushLatin(StringBuilder buffer, Map<String, Integer> tf) {
        if (buffer.length() == 0) {
            return;
        }
        add(tf, buffer.toString().toLowerCase());
        buffer.setLength(0);
    }

    private static void add(Map<String, Integer> tf, String token) {
        if (token.isBlank()) {
            return;
        }
        tf.merge(token, 1, Integer::sum);
    }

    /** L2 归一化，使余弦相似度可用点积快速计算。对云端 embedding 也适用。 */
    public static void normalize(float[] vector) {
        double norm = 0;
        for (float v : vector) {
            norm += v * v;
        }
        if (norm <= 0) {
            return;
        }
        float inv = (float) (1.0 / Math.sqrt(norm));
        for (int i = 0; i < vector.length; i++) {
            vector[i] *= inv;
        }
    }
}
