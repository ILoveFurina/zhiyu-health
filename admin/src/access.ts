import type { InitialState } from './app';

// 票 88：角色矩阵 —— admin 全部；pharmacist 处方审核/院区药房库存/药品订单；doctor 接诊/排班/开方
export default (initialState: InitialState | undefined) => {
  const role = initialState?.currentUser?.role;
  return {
    canAdmin: role === 'admin',
    canAdminOrPharmacist: role === 'admin' || role === 'pharmacist',
    canDoctor: role === 'doctor',
  };
};
