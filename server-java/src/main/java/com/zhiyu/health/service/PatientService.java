package com.zhiyu.health.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhiyu.health.entity.Patient;
import com.zhiyu.health.mapper.PatientMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** C 端免注册 mock 登录：按昵称取或建患者身份。 */
@Service
public class PatientService {

    public static final String DEFAULT_NICKNAME = "演示患者";

    private final PatientMapper patientMapper;

    public PatientService(PatientMapper patientMapper) {
        this.patientMapper = patientMapper;
    }

    @Transactional
    public Patient mockLogin(String nickname) {
        String resolved = nickname == null || nickname.isBlank() ? DEFAULT_NICKNAME : nickname.trim();
        Patient patient = patientMapper.selectOne(new LambdaQueryWrapper<Patient>()
                .eq(Patient::getNickname, resolved));
        if (patient != null) {
            return patient;
        }
        Patient created = new Patient(null, resolved);
        patientMapper.insert(created);
        return created;
    }
}
