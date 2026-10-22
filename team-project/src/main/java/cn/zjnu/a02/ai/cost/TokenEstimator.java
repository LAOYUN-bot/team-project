package cn.zjnu.a02.ai.cost;

import org.springframework.stereotype.Component;

/**
 * Token 消耗估算（NFR-8 成本核算）。
 *
 * <p>真实 token 数需由供应商返回；但流式响应、缓存命中、Mock 通道都拿不到 usage，
 * 因此需要一个**稳定的估算**来支撑成本统计。
 *
 * <p>估算规则（按主流中文分词器的经验比例）：
 * <ul>
 *   <li>中文字符：约 1 字符 ≈ 0.7 token（即 1 token ≈ 1.43 个汉字）；</li>
 *   <li>英文/数字：约 1 词 ≈ 1.3 token；</li>
 *   <li>标点与符号：按 0.3 token 计。</li>
 * </ul>
 * 该估算只用于**能力对比与预算控制**，不作为计费依据；接口会明确标注是估算值。
 */
@Component
public class TokenEstimator {

    private static final double CJK_PER_TOKEN = 0.7;
    private static final double LATIN_WORD_TOKENS = 1.3;
    private static final double SYMBOL_TOKENS = 0.3;

    public int estimate(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int cjk = 0;
        int latinWords = 0;
        int symbols = 0;
        boolean inWord = false;

        for (char c : text.toCharArray()) {
            if (isCjk(c)) {
                cjk++;
                inWord = false;
            } else if (Character.isLetterOrDigit(c)) {
                if (!inWord) {
                    latinWords++;
                    inWord = true;
                }
            } else {
                symbols++;
                inWord = false;
            }
        }
        double tokens = cjk * CJK_PER_TOKEN + latinWords * LATIN_WORD_TOKENS + symbols * SYMBOL_TOKENS;
        return Math.max(1, (int) Math.ceil(tokens));
    }

    private static boolean isCjk(char c) {
        return c >= 0x4E00 && c <= 0x9FFF;
    }

    /** 按「元/千 token」单价折算成本（人民币）。 */
    public double cost(int promptTokens, int completionTokens, double inPricePer1k, double outPricePer1k) {
        double cost = promptTokens / 1000.0 * inPricePer1k + completionTokens / 1000.0 * outPricePer1k;
        return Math.round(cost * 1_000_000) / 1_000_000.0;
    }
}
