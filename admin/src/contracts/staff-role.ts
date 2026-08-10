import staffRoles from '../../../contracts/staff-roles.json';

// B 端 staff_users.role 单一事实源（票 88）：角色枚举与中文标签均由契约推导
export const roles = staffRoles.roles;
export const roleLabels = staffRoles.role_labels;
export type Role = keyof typeof roleLabels;
