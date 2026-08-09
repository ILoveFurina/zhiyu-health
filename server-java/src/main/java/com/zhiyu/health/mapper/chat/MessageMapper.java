package com.zhiyu.health.mapper.chat;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhiyu.health.entity.chat.Message;
import org.apache.ibatis.annotations.Mapper;

/** 会话消息 mapper。 */
@Mapper
public interface MessageMapper extends BaseMapper<Message> {}
