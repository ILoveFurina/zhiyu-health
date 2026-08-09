package com.zhiyu.health.service.consultation;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** HTTP 视图集中定义；状态标签和文案由服务在装配时从 contracts 注入。 */
public final class OnlineConsultationViews {
    private OnlineConsultationViews() {}

    public record ConsultationSummaryView(
            @JsonProperty("chief_complaint") String chiefComplaint,
            @JsonProperty("present_illness") String presentIllness,
            @JsonProperty("allergy_history") String allergyHistory) {}

    public record DoctorView(
            String name,
            String title,
            @JsonProperty("photo_url") String photoUrl,
            @JsonProperty("hospital_name") String hospitalName,
            @JsonProperty("department_name") String departmentName) {}

    public record ConsultationListItem(
            Long id,
            String status,
            @JsonProperty("status_label") String statusLabel,
            @JsonProperty("health_profile_id") Long healthProfileId,
            @JsonProperty("standard_department_name") String standardDepartmentName,
            @JsonProperty("consult_method") String consultMethod,
            @JsonProperty("created_at") String createdAt,
            @JsonProperty("expires_at") String expiresAt) {}

    public record ConsultationDetail(
            Long id,
            String status,
            @JsonProperty("status_label") String statusLabel,
            @JsonProperty("progress_step") String progressStep,
            @JsonProperty("standard_department_id") Long standardDepartmentId,
            @JsonProperty("standard_department_name") String standardDepartmentName,
            ConsultationSummaryView summary,
            @JsonProperty("summary_disclaimer") String summaryDisclaimer,
            @JsonProperty("consult_method") String consultMethod,
            @JsonProperty("consult_method_label") String consultMethodLabel,
            @JsonProperty("method_started_at") String methodStartedAt,
            DoctorView doctor,
            String diagnosis,
            String advice,
            @JsonProperty("expires_at") String expiresAt,
            @JsonProperty("accepted_at") String acceptedAt,
            @JsonProperty("completed_at") String completedAt,
            @JsonProperty("cancelled_at") String cancelledAt,
            @JsonProperty("created_at") String createdAt,
            @JsonProperty("terminal_hint") String terminalHint) {}

    public record PatientRef(String nickname) {}

    public record ConsultationPrescriptionView(
            Long id,
            String status,
            @JsonProperty("status_label") String statusLabel,
            @JsonProperty("review_reason") String reviewReason) {}

    public record ProfileRef(
            @JsonProperty("display_name") String displayName,
            String gender,
            @JsonProperty("birth_date") String birthDate,
            String relationship,
            List<String> allergies) {}

    public record DoctorListItem(
            Long id,
            String status,
            @JsonProperty("status_label") String statusLabel,
            @JsonProperty("standard_department_id") Long standardDepartmentId,
            @JsonProperty("standard_department_name") String standardDepartmentName,
            ConsultationSummaryView summary,
            @JsonProperty("summary_disclaimer") String summaryDisclaimer,
            PatientRef patient,
            @JsonProperty("health_profile") ProfileRef healthProfile,
            @JsonProperty("consult_method") String consultMethod,
            @JsonProperty("consult_method_label") String consultMethodLabel,
            @JsonProperty("accepted_at") String acceptedAt,
            @JsonProperty("completed_at") String completedAt,
            @JsonProperty("created_at") String createdAt,
            @JsonProperty("expires_at") String expiresAt) {}

    public record DoctorConsultationDetail(
            Long id,
            String status,
            @JsonProperty("status_label") String statusLabel,
            @JsonProperty("standard_department_id") Long standardDepartmentId,
            @JsonProperty("standard_department_name") String standardDepartmentName,
            ConsultationSummaryView summary,
            @JsonProperty("summary_disclaimer") String summaryDisclaimer,
            PatientRef patient,
            @JsonProperty("health_profile") ProfileRef healthProfile,
            @JsonProperty("consult_method") String consultMethod,
            @JsonProperty("consult_method_label") String consultMethodLabel,
            @JsonProperty("method_started_at") String methodStartedAt,
            String diagnosis,
            String advice,
            @JsonProperty("accepted_at") String acceptedAt,
            @JsonProperty("completed_at") String completedAt,
            @JsonProperty("cancelled_at") String cancelledAt,
            @JsonProperty("created_at") String createdAt,
            @JsonProperty("expires_at") String expiresAt) {}

    public record MessageView(
            Long id,
            @JsonProperty("sender_type") String senderType,
            String kind,
            String content,
            @JsonProperty("created_at") String createdAt) {}
}
