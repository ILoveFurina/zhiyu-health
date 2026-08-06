import { useModel } from "@umijs/max";
import { Button, Form, Grid, Input, Space, Typography } from "antd";
import { LockOutlined, UserOutlined } from "@ant-design/icons";
import { login, fetchMe } from "@/services/auth";
import { homeByRole, setCachedUser, setToken } from "@/utils/session";

const BRAND_GREEN = "#0e7a6c";
const BRAND_DEEP = "#123f38";
const TEXT_MUTED = "#5b7470";

export default function LoginPage() {
  const { setInitialState } = useModel("@@initialState");
  const screens = Grid.useBreakpoint();
  const isMobile = !screens.md;

  return (
    <div
      style={{
        minHeight: "100vh",
        background: "#f3f8f6",
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        padding: 24,
      }}
    >
      <div
        style={{
          width: 1080,
          maxWidth: "100%",
          minHeight: 640,
          background: "#fff",
          borderRadius: 20,
          boxShadow: "0 24px 70px rgba(15, 118, 110, 0.12)",
          display: "grid",
          gridTemplateColumns: isMobile ? "1fr" : "1.05fr 0.95fr",
          overflow: "hidden",
        }}
      >
        {!isMobile && (
          <section
            style={{
              background: "#f8fcfb",
              borderRight: "1px solid #e6f2ee",
              padding: "56px 52px",
              display: "flex",
              flexDirection: "column",
              justifyContent: "space-between",
            }}
          >
            <Space size={10}>
              <span
                style={{
                  width: 36,
                  height: 36,
                  borderRadius: 10,
                  background: BRAND_GREEN,
                  color: "#fff",
                  display: "inline-flex",
                  alignItems: "center",
                  justifyContent: "center",
                  fontWeight: 700,
                  fontSize: 18,
                }}
              >
                智
              </span>
              <Typography.Text
                strong
                style={{ fontSize: 18, color: BRAND_GREEN }}
              >
                智愈管理后台
              </Typography.Text>
            </Space>

            <div>
              <Typography.Title
                level={1}
                style={{
                  fontSize: 34,
                  lineHeight: 1.25,
                  color: BRAND_DEEP,
                  margin: "48px 0 0",
                  maxWidth: 560,
                }}
              >
                让每一次接诊更从容
              </Typography.Title>
              <Typography.Paragraph
                style={{
                  marginTop: 16,
                  color: TEXT_MUTED,
                  fontSize: 15,
                  lineHeight: 1.8,
                  maxWidth: 520,
                }}
              >
                面向医疗机构与医生团队的轻量业务管理平台，集中处理组织、处方、药品、收费与智能助手调用。
              </Typography.Paragraph>
              <Space wrap size={[10, 10]} style={{ marginTop: 36 }}>
                {[
                  "组织与医生管理",
                  "电子处方审核",
                  "药品与订单",
                  "Agent 调用日志",
                ].map((item) => (
                  <span
                    key={item}
                    style={{
                      border: "1px solid #dceee8",
                      background: "#fff",
                      color: "#2f6b61",
                      fontSize: 13,
                      padding: "8px 12px",
                      borderRadius: 999,
                    }}
                  >
                    {item}
                  </span>
                ))}
              </Space>
            </div>

            <Typography.Text
              type="secondary"
              style={{ marginTop: 48, fontSize: 12 }}
            >
              智愈 · B 端组织与业务管理
            </Typography.Text>
          </section>
        )}

        <section
          style={{
            padding: isMobile ? "40px 28px" : "56px 52px",
            display: "flex",
            flexDirection: "column",
            justifyContent: "center",
          }}
        >
          <Typography.Text strong style={{ fontSize: 14, color: BRAND_GREEN }}>
            欢迎回来
          </Typography.Text>
          <Typography.Title
            level={2}
            style={{
              fontSize: 26,
              color: BRAND_DEEP,
              margin: "8px 0 0",
              letterSpacing: 0,
            }}
          >
            登录账户
          </Typography.Title>
          <Typography.Paragraph
            type="secondary"
            style={{ marginTop: 8, fontSize: 14 }}
          >
            使用管理员或医生账号进入对应工作台
          </Typography.Paragraph>

          <Form
            layout="vertical"
            style={{ marginTop: 24 }}
            onFinish={async (values) => {
              const { access_token } = await login(
                values as { username: string; password: string },
              );
              setToken(access_token);
              const currentUser = await fetchMe();
              setCachedUser(currentUser);
              await setInitialState({ currentUser });
              // access 插件在同一轮单页跳转中仍可能读取旧权限；完整 replace 后由
              // getInitialState 重新拉取 /me，避免医生首次登录短暂落入 403 页面。
              window.location.replace(homeByRole(currentUser.role));
            }}
          >
            <Form.Item
              name="username"
              label="用户名"
              rules={[{ required: true, message: "请输入用户名" }]}
            >
              <Input
                size="large"
                prefix={<UserOutlined />}
                placeholder="请输入用户名"
                style={{ height: 46, borderRadius: 10 }}
              />
            </Form.Item>
            <Form.Item
              name="password"
              label="密码"
              rules={[{ required: true, message: "请输入密码" }]}
            >
              <Input.Password
                size="large"
                prefix={<LockOutlined />}
                placeholder="请输入密码"
                style={{ height: 46, borderRadius: 10 }}
              />
            </Form.Item>
            <Button
              type="primary"
              htmlType="submit"
              size="large"
              block
              style={{
                height: 48,
                borderRadius: 10,
                background: BRAND_GREEN,
                fontWeight: 600,
                marginTop: 4,
              }}
            >
              登 录
            </Button>
          </Form>

          <Typography.Text
            type="secondary"
            style={{ marginTop: 18, textAlign: "center", fontSize: 12 }}
          >
            演示账号：admin / admin123456
          </Typography.Text>
        </section>
      </div>
    </div>
  );
}
