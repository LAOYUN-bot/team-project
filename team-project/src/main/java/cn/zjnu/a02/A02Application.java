package cn.zjnu.a02;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * A02 面向未来工作的 AI 职业导航与终身学习伙伴系统 —— 应用入口。
 *
 * <p>架构依据 {@code docs/04-架构设计.md}：
 * <ul>
 *   <li>六步闭环 M1 感知 → M2 认知 → M3 决策 → M4 生成 → M5 执行 → M6 反馈，包结构即闭环结构；</li>
 *   <li>业务真相源在关系库（默认 H2 内存库，可切 MySQL），AI 只是能力提供方；</li>
 *   <li>AI 能力经 {@code AiChatPort} → 统一模型网关访问，默认 {@code mock} 实现，无 API Key 亦可完整演示。</li>
 * </ul>
 *
 * <p>持久化分两层（课程实验一要求 Entity / Mapper / Service / Controller 分层）：
 * <b>实体 CRUD 走 MyBatis Mapper</b>（{@code **.mapper} 包 + {@code resources/mapper/*.xml}），
 * <b>跨表聚合与分析型查询走 JdbcTemplate</b>（{@code common.db.Db}）。
 */
@SpringBootApplication
@MapperScan("cn.zjnu.a02.**.mapper")
public class A02Application {

    public static void main(String[] args) {
        SpringApplication.run(A02Application.class, args);
    }
}
