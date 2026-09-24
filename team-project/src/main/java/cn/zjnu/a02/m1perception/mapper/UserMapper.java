package cn.zjnu.a02.m1perception.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import cn.zjnu.a02.m1perception.entity.User;

/**
 * 用户表 Mapper（课程实验一：Entity / Mapper / Service / Controller 分层）。
 *
 * <p>SQL 写在 {@code resources/mapper/UserMapper.xml}，与本接口方法名一一对应；
 * 使用 MyBatis 的「接口 + XML」方式而不是注解拼接 SQL，
 * 便于后续把复杂查询（如岗位匹配度聚合）留在 XML 里统一评审。
 */
@Mapper
public interface UserMapper {

    User selectById(@Param("id") Long id);

    User selectByUsername(@Param("username") String username);

    int countByUsername(@Param("username") String username);

    /** 新增用户；主键回填到 {@code user.id}（useGeneratedKeys）。 */
    int insert(User user);

    int updateRole(@Param("id") Long id, @Param("role") String role);

    int updatePassword(@Param("id") Long id, @Param("passwordHash") String passwordHash);

    /** 逻辑删除（{@code deleted=1}），保证数据可追溯。 */
    int softDelete(@Param("id") Long id);

    List<User> selectAll();
}
