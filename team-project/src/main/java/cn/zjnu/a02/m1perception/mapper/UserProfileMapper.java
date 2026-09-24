package cn.zjnu.a02.m1perception.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import cn.zjnu.a02.m1perception.entity.UserProfile;

/** 用户基础信息 Mapper。SQL 见 {@code resources/mapper/UserProfileMapper.xml}。 */
@Mapper
public interface UserProfileMapper {

    UserProfile selectByUserId(@Param("userId") Long userId);

    int countByUserId(@Param("userId") Long userId);

    int insert(UserProfile profile);

    int update(UserProfile profile);

    int deleteByUserId(@Param("userId") Long userId);
}
