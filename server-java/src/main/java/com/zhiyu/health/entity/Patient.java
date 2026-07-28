package com.zhiyu.health.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/** C 端 mock 登录患者身份；健康数据不放在此实体。 */
@TableName("patients")
public class Patient {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String nickname;
    private OffsetDateTime createdAt;

    public Patient() {
    }

    public Patient(Long id, String nickname) {
        this.id = id;
        this.nickname = nickname;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
