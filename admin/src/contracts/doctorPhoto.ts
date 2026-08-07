import limits from '../../../contracts/doctor-photo-limits.json';

// 票 54：医生头像上传限制与响应结构，从 contracts/doctor-photo-limits.json 推导
export const doctorPhotoMaxBytes = limits.max_bytes;
export const doctorPhotoAllowedTypes = limits.allowed_types as readonly string[];
export const doctorPhotoMaxFiles = limits.max_files;

export interface DoctorPhotoUpload {
  object_key: string;
  url: string;
}
