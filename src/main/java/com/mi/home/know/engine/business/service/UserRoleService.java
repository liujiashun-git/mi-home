package com.mi.home.know.engine.business.service;

import com.mi.home.know.engine.chat.entity.ChatParam;
import com.mi.home.know.engine.rag.constant.RoleEnum;

public interface UserRoleService {

    public RoleEnum getUserRole(ChatParam chatParam);
}
