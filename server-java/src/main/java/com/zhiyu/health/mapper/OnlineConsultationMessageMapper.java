package com.zhiyu.health.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhiyu.health.entity.OnlineConsultationMessage;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface OnlineConsultationMessageMapper extends BaseMapper<OnlineConsultationMessage> {

    /** 增量轮询：按单调 id 游标取新消息，端侧以上次最大 id 续拉。 */
    @Select(
            """
            SELECT * FROM online_consultation_messages
            WHERE consultation_id = #{consultationId} AND id > #{afterId}
            ORDER BY id ASC
            """)
    List<OnlineConsultationMessage> selectAfterId(
            @Param("consultationId") long consultationId, @Param("afterId") long afterId);
}
