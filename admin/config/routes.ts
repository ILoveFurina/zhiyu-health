export default [
  { path: '/login', component: './Login', layout: false },
  { path: '/', redirect: '/hospitals' },
  {
    name: '医院管理',
    path: '/hospitals',
    component: './Hospital',
    access: 'canAdmin',
  },
  {
    name: '科室管理',
    path: '/departments',
    component: './Department',
    access: 'canAdmin',
  },
  {
    name: '医生管理',
    path: '/doctors',
    component: './Doctor',
    access: 'canAdmin',
  },
  { name: '电子处方审核', path: '/prescriptions', component: './Prescription', access: 'canAdmin' },
  { name: '接诊台', path: '/workbench', component: './Workbench', access: 'canDoctor' },
];
