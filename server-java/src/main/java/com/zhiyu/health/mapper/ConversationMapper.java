package com.zhiyu.health.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhiyu.health.entity.Conversation;
import org.apache.ibatis.annotations.Mapper;

/** 会话聚合 mapper。 */
@Mapper
public interface ConversationMapper extends BaseMapper<Conversation> {
}
