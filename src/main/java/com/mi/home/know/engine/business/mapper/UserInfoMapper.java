package com.mi.home.know.engine.business.mapper;

import com.mi.home.know.engine.business.entity.UserInfo;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 客户信息表 Mapper
 */
@Mapper
public interface UserInfoMapper extends BaseMapper<UserInfo> {
}
