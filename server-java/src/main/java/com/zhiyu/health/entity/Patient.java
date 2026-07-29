package com.zhiyu.health.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/** C 端 mock 登录患者身份；健康数据不放在此实体。 */
@Getter
@Setter
@TableName("patients")
public class Patient {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String nickname;
    private OffsetDateTime createdAt;

    public Patient() {}

    public Patient(Long id, String nickname) {
        this.id = id;
        this.nickname = nickname;
    }
}
