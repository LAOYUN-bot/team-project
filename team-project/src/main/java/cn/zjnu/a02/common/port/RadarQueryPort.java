package cn.zjnu.a02.common.port;

import java.util.Map;

/**
 * 雷达图查询端口（架构文档 §2.3：业务模块之间允许通过接口依赖，不允许通过 Mapper 依赖）。
 *
 * <p>由 M3 决策层实现，M2 认知层（差距分析）与 M6 反馈层（成长面板）通过本端口读取画像，
 * 从而保证「雷达版本化」的写入逻辑只有一个归属模块。
 */
public interface RadarQueryPort {

    /** 用户最新版本的 8 维分值；无画像时返回空 Map。 */
    Map<String, Double> latestScores(long userId);

    /** 指定版本的 8 维分值；不存在返回空 Map。 */
    Map<String, Double> scoresOfVersion(long userId, int version);

    /** 最新版本号；无画像返回 0。 */
    int latestVersion(long userId);
}
