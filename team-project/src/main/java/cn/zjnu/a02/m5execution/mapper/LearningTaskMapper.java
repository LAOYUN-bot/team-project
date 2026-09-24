package cn.zjnu.a02.m5execution.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import cn.zjnu.a02.m5execution.entity.LearningTask;

/** 学习任务表 Mapper（课程实验一「任务表 CRUD」）。SQL 见 {@code resources/mapper/LearningTaskMapper.xml}。 */
@Mapper
public interface LearningTaskMapper {

    /** 按用户查任务；{@code status} 为 null 时查全部。关联出所属单元与阶段名。 */
    List<LearningTask> selectByUser(@Param("userId") Long userId, @Param("status") String status);

    LearningTask selectByIdAndUser(@Param("id") Long id, @Param("userId") Long userId);

    int insert(LearningTask task);

    int updateStatus(@Param("id") Long id, @Param("status") String status,
                     @Param("markDone") boolean markDone);

    int countByUser(@Param("userId") Long userId);

    int countByStatus(@Param("userId") Long userId, @Param("status") String status);

    int countOverdue(@Param("userId") Long userId);
}
