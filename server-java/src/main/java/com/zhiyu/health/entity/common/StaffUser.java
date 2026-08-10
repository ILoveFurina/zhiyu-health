package com.zhiyu.health.entity.common;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** B 端员工账号实体，镜像 schema.sql staff_users 表；passwordHash 永不出接口 */
@Getter
@Setter
@TableName("staff_users")
public class StaffUser {

    /** 角色取值与票 02 Python 原件一致（小写），与 contracts/staff-roles.json 同源 */
    public static final String ROLE_ADMIN = "admin";

    public static final String ROLE_DOCTOR = "doctor";

    /** 全局药师（票 88，ADR-0035）：处方审核、院区药房库存与药品订单模拟履约，不绑定医院/院区 */
    public static final String ROLE_PHARMACIST = "pharmacist";

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;
    private String passwordHash;
    private String role;
    private Long doctorId;
}
