package cn.zjnu.a02.platform.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import cn.zjnu.a02.common.db.Db;

/**
 * 审计服务（架构文档 §9.2 / NFR-4 责任归属）：
 * 登录、导出、删除、AI 生成等关键动作全部落 {@code audit_log}，保证可追溯。
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final Db db;

    public AuditService(Db db) {
        this.db = db;
    }

    public void record(Long userId, String action, String target, String detail, String ip) {
        try {
            db.update("INSERT INTO audit_log(user_id, action, target, detail, ip) VALUES (?,?,?,?,?)",
                    userId, action, target, detail, ip);
        } catch (Exception e) {
            // 审计失败不能影响主流程
            log.warn("审计日志写入失败 action={} err={}", action, e.getMessage());
        }
    }

    public void record(Long userId, String action, String target, String detail) {
        record(userId, action, target, detail, null);
    }
}
