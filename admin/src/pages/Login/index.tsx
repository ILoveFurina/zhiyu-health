import { history, useModel } from '@umijs/max';
import { LoginForm, ProFormText } from '@ant-design/pro-components';
import { LockOutlined, UserOutlined } from '@ant-design/icons';
import { login, fetchMe } from '@/services/auth';
import { homeByRole, setCachedUser, setToken } from '@/utils/session';

export default function LoginPage() {
  const { setInitialState } = useModel('@@initialState');

  return (
    <div style={{ height: '100vh', background: '#f0f2f5' }}>
      <LoginForm
        title="智愈管理后台"
        subTitle="B 端组织与业务管理"
        onFinish={async (values) => {
          const { access_token } = await login(values as { username: string; password: string });
          setToken(access_token);
          const currentUser = await fetchMe();
          setCachedUser(currentUser);
          await setInitialState({ currentUser });
          history.replace(homeByRole(currentUser.role));
        }}
      >
        <ProFormText
          name="username"
          fieldProps={{ size: 'large', prefix: <UserOutlined /> }}
          placeholder="用户名"
          rules={[{ required: true, message: '请输入用户名' }]}
        />
        <ProFormText.Password
          name="password"
          fieldProps={{ size: 'large', prefix: <LockOutlined /> }}
          placeholder="密码"
          rules={[{ required: true, message: '请输入密码' }]}
        />
      </LoginForm>
    </div>
  );
}
