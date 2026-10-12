package com.mi.home.know.engine.business.converter;

import com.mi.home.know.engine.business.constant.CarOrderStatus;
import com.mi.home.know.engine.business.entity.CarOrder;
import com.mi.home.know.engine.business.vo.CarOrderVO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.Named;
import org.mapstruct.factory.Mappers;

import java.util.List;

/**
 * CarOrder对象转换器
 * 使用MapStruct实现CarOrder与CarOrderVO之间的转换
 */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface CarOrderConverter {

    CarOrderConverter INSTANCE = Mappers.getMapper(CarOrderConverter.class);

    /**
     * CarOrder转CarOrderVO
     */
    @Mapping(source = "orderStatus", target = "orderStatus", qualifiedByName = "orderStatusToString")
    @Mapping(target = "carNickname", ignore = true)
    CarOrderVO toVO(CarOrder carOrder);

    /**
     * 批量转换
     */
    List<CarOrderVO> toVOList(List<CarOrder> carOrders);

    /**
     * 订单状态枚举转字符串
     */
    @Named("orderStatusToString")
    default String orderStatusToString(CarOrderStatus status) {
        return status != null ? status.getStatus() : null;
    }
}
