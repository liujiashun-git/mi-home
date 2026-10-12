package com.mi.home.know.engine.ai.model;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * 意图识别结果
 *
 */
public record IntentRecognitionResult(
        @JsonPropertyDescription("意图识别的推断理由")
        String reasoning,

        @JsonPropertyDescription("用户问题是否与小米汽车领域相关：true-小米汽车相关问题，false-无关问题（如育儿、天气、政治等）")
        boolean related,

        @JsonPropertyDescription("意图识别结果,售前咨询与购买、售后维修与保养、车辆使用与技术指导、投诉与维权、汽车营销政策、闲聊与通用问答、其他")
        String intent,

        @JsonPropertyDescription("从用户输入中提取的关键实体信息")
        Entities entities) {

    /**
     * 实体信息
     */
    public record Entities(
            @JsonPropertyDescription("用户提到的具体车型，如：小米 SU7、小米 SU7 Pro、小米 SU7 Ultra、小米 YU7。注意 SU7 与 SU7 Ultra 是不同车型")
            String car_model,

            @JsonPropertyDescription("用户提到的具体汽车ID，如：100001、200002、300003")
            String car_id,

            @JsonPropertyDescription("订单号、合同号或服务单编号")
            String order_id,

            @JsonPropertyDescription("用户提到的具体4S店或服务中心名称")
            String dealer,

            @JsonPropertyDescription("用户描述的车辆问题，如：发动机异响、屏幕黑屏")
            String fault_description,

            @JsonPropertyDescription("用户期望的预约日期或时间")
            String appointment_time,

            @JsonPropertyDescription("用户询问的零部件名称，如：轮胎、刹车片")
            String part_name,

            @JsonPropertyDescription("用户询问的具体车辆功能，如：自适应巡航、自动泊车、中控屏投屏")
            String function_name) {
    }
}
