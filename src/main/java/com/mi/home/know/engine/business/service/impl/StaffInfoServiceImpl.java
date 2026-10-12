package com.mi.home.know.engine.business.service.impl;

import com.mi.home.know.engine.business.entity.StaffInfo;
import com.mi.home.know.engine.business.mapper.StaffInfoMapper;
import com.mi.home.know.engine.business.service.StaffInfoService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * 员工信息表 Service 实现类
 */
@Service
public class StaffInfoServiceImpl extends ServiceImpl<StaffInfoMapper, StaffInfo> implements StaffInfoService {

    @Override
    public StaffInfo getByEmpId(String empId) {
        return this.getOne(new LambdaQueryWrapper<StaffInfo>().eq(StaffInfo::getEmpId, empId));
    }
}
