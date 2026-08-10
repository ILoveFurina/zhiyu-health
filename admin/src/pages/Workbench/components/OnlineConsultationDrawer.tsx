import { useCallback, useEffect, useRef, useState } from 'react';
import {
  Alert, App, Avatar, Button, Descriptions, Divider, Drawer, Form, Input,
  Popconfirm, Space, Spin, Tag, Typography,
} from 'antd';
import { SendOutlined, VideoCameraOutlined } from '@ant-design/icons';
import {
  consultationStatuses,
  consultationTexts,
  consultMethods,
  messageKinds,
  senderTypes,
  summaryFieldLabels,
} from '@/contracts/consultation';
import {
  accept,
  complete,
  fetchDetail,
  fetchMessages,
  sendMessage,
  startMethod,
  type ConsultationDetail,
  type ConsultationMessage,
  type ConsultMethod,
} from '@/services/consultation';
import { prescriptionStatuses } from '@/contracts/prescription';
import {
  checkOnlinePrescriptionSafety,
  createOnlinePrescription,
  fetchMedications,
  fetchOnlineConsultationPrescription,
  type ConsultationPrescription,
  type Medication,
  type PrescriptionInput,
} from '@/services/prescription';
import PrescriptionForm from './PrescriptionForm';
import AuthPhoto from '@/components/AuthPhoto';
import { formatChatTime, formatDateTime } from '@/utils/time';

interface Props {
  consultationId?: number;
  open: boolean;
  onClose: () => void;
  onChanged: () => void;
}

/** 票 58：image 消息 content 为 {"object_key","media_type"} JSON，解析失败返回空串（模板走兜底）。 */
const parseImageObjectKey = (content: string) => {
  try {
    const parsed = JSON.parse(content);
    return typeof parsed.object_key === 'string' ? parsed.object_key : '';
  } catch {
    return '';
  }
};

const statusTagColor = (status?: string) => {
  switch (status) {
    case consultationStatuses.waiting_doctor:
      return 'gold';
    case consultationStatuses.in_progress:
      return 'blue';
    case consultationStatuses.completed:
      return 'green';
    default:
      return 'default';
  }
};

// 处方审核状态 Tag 配色，跟随 Ant Design 惯例：待审核金 / 已通过绿 / 已驳回红
const prescriptionTagColor = (status?: string) => {
  switch (status) {
    case prescriptionStatuses.pending:
      return 'gold';
    case prescriptionStatuses.approved:
      return 'green';
    case prescriptionStatuses.rejected:
      return 'red';
    default:
      return 'default';
  }
};

export default function OnlineConsultationDrawer({ consultationId, open, onClose, onChanged }: Props) {
  const { message } = App.useApp();
  const [form] = Form.useForm<{ diagnosis: string; advice: string }>();
  const [detail, setDetail] = useState<ConsultationDetail>();
  const [loading, setLoading] = useState(false);
  const [accepting, setAccepting] = useState(false);
  const [methodSubmitting, setMethodSubmitting] = useState(false);
  const [messages, setMessages] = useState<ConsultationMessage[]>([]);
  const [draft, setDraft] = useState('');
  const [sending, setSending] = useState(false);
  const [completing, setCompleting] = useState(false);
  const [medications, setMedications] = useState<Medication[]>([]);
  const [prescriptionSubmitting, setPrescriptionSubmitting] = useState(false);
  const [prescriptionCreated, setPrescriptionCreated] = useState(false);
  const [prescription, setPrescription] = useState<ConsultationPrescription | null>(null);
  const [remainingSeconds, setRemainingSeconds] = useState<number | null>(null);
  const lastIdRef = useRef(0);
  const medicationsLoadedRef = useRef(false);
  const threadRef = useRef<HTMLDivElement>(null);

  const inProgress = detail?.status === consultationStatuses.in_progress;
  // 票 86：固定时长窗倒计时，仅进行中且后端给了结束时刻时启用
  const endsAt = inProgress ? (detail?.consultation_ends_at ?? null) : null;
  // 已开方：接口返回有处方，或本地刚提交成功（一问诊一处方）
  const hasPrescription = prescription != null || prescriptionCreated;
  const prescriptionRejected = prescription?.status === prescriptionStatuses.rejected;
  // 消息仅对已绑定医生可见（服务端 requireBoundToDoctor）：待接诊单未接受时打开抽屉
  // 若拉消息会 404"问诊单不存在"并被全局 errorHandler 弹窗；接受后 inProgress 翻转触发重拉
  const canViewMessages = detail != null
    && (inProgress || detail.status === consultationStatuses.completed);

  // 打开时按 id 加载完整详情，并重置消息、开方状态与完成表单
  useEffect(() => {
    if (!open || !consultationId) return;
    setDetail(undefined);
    setMessages([]);
    setDraft('');
    setPrescriptionCreated(false);
    setPrescription(null);
    lastIdRef.current = 0;
    setLoading(true);
    fetchDetail(consultationId)
      .then((res) => {
        setDetail(res.consultation);
        // 处方审核状态随抽屉打开拉取；处方只可能在接诊后产生，待接诊单不拉取，
        // 避免与消息接口同因（未绑定医生 404 被全局 errorHandler 弹窗）；无处方为正常态
        const s = res.consultation.status;
        if (s === consultationStatuses.in_progress || s === consultationStatuses.completed) {
          fetchOnlineConsultationPrescription(consultationId)
            .then((r) => setPrescription(r.prescription))
            .catch(() => {});
        }
      })
      .catch(() => {})
      .finally(() => setLoading(false));
    // 药品可选列表与问诊无关，只拉一次；用 ref 避免把 medications 写进依赖造成详情重拉
    if (!medicationsLoadedRef.current) {
      medicationsLoadedRef.current = true;
      fetchMedications()
        .then(setMedications)
        .catch(() => {
          medicationsLoadedRef.current = false;
        });
    }
  }, [open, consultationId]);

  // 增量合并消息，按 id 去重，维持轮询游标
  const appendMessages = useCallback((incoming: ConsultationMessage[]) => {
    if (!incoming.length) return;
    lastIdRef.current = Math.max(lastIdRef.current, incoming[incoming.length - 1].id);
    setMessages((prev) => {
      const seen = new Set(prev.map((item) => item.id));
      const fresh = incoming.filter((item) => !seen.has(item.id));
      return fresh.length ? [...prev, ...fresh] : prev;
    });
  }, []);

  // 打开时先全量加载一次；进行中每 3s 增量轮询，关闭或结束后清除
  useEffect(() => {
    if (!open || !consultationId || !canViewMessages) return;
    let cancelled = false;
    const pull = async () => {
      try {
        const res = await fetchMessages(consultationId, lastIdRef.current);
        if (!cancelled) appendMessages(res.messages);
      } catch {
        // 轮询失败静默，交由下一 tick 重试
      }
    };
    pull();
    if (!inProgress) return () => { cancelled = true; };
    const timer = setInterval(pull, 3000);
    return () => { cancelled = true; clearInterval(timer); };
  }, [open, consultationId, inProgress, canViewMessages, appendMessages]);

  // 新消息滚动到底部
  useEffect(() => {
    const el = threadRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [messages]);

  // 票 86：剩余时间每秒倒数；到 0 停在"问诊已结束"（终态由后端惰性收敛，下次详情刷新带入）。
  // 卸载或离开 IN_PROGRESS 时清理计时器与状态
  useEffect(() => {
    if (!endsAt) {
      setRemainingSeconds(null);
      return;
    }
    const tick = () => setRemainingSeconds(
      Math.max(0, Math.floor((new Date(endsAt).getTime() - Date.now()) / 1000)),
    );
    tick();
    const timer = setInterval(tick, 1000);
    return () => clearInterval(timer);
  }, [endsAt]);

  // detail 就绪后完成表单才会挂载，此时再重置，避免对尚未连接 Form 的实例执行操作。
  useEffect(() => {
    if (open && consultationId && inProgress) form.resetFields();
  }, [open, consultationId, inProgress, form]);

  const handleAccept = async () => {
    if (!consultationId) return;
    setAccepting(true);
    try {
      const res = await accept(consultationId);
      setDetail(res.consultation);
      message.success(consultationTexts.doctor_accepted);
      onChanged();
    } catch (err: any) {
      // 409 提示由全局 errorHandler 弹出（accept_conflict）；问诊单已被抢走，刷新池并关闭
      if (err?.response?.status === 409) {
        onChanged();
        onClose();
      }
    } finally {
      setAccepting(false);
    }
  };

  const handleStartMethod = async (method: ConsultMethod) => {
    if (!consultationId) return;
    setMethodSubmitting(true);
    try {
      const res = await startMethod(consultationId, method);
      setDetail(res.consultation);
      message.success(method === consultMethods.video
        ? consultationTexts.video_started
        : consultationTexts.text_started);
    } catch {
      // method_already_set 等冲突由全局提示；回读详情保持界面一致
      fetchDetail(consultationId)
        .then((res) => setDetail(res.consultation))
        .catch(() => {});
    } finally {
      setMethodSubmitting(false);
    }
  };

  const handleSend = async () => {
    const content = draft.trim();
    if (!content || !consultationId || !inProgress) return;
    setSending(true);
    try {
      const res = await sendMessage(consultationId, content);
      appendMessages([res.message]);
      setDraft('');
    } catch {
      // 非进行中发送等错误由全局 errorHandler 弹出
    } finally {
      setSending(false);
    }
  };

  const handleComplete = async (values: { diagnosis: string; advice: string }) => {
    if (!consultationId) return;
    setCompleting(true);
    try {
      const res = await complete(consultationId, values);
      setDetail(res.consultation);
      message.success(consultationTexts.consult_completed);
      onChanged();
    } catch {
      // 幂等完成接口，错误由全局 errorHandler 弹出
    } finally {
      setCompleting(false);
    }
  };

  const handlePrescribe = async (values: PrescriptionInput) => {
    if (!consultationId) return;
    setPrescriptionSubmitting(true);
    try {
      await createOnlinePrescription(consultationId, values);
      setPrescriptionCreated(true);
      message.success('电子处方已提交审核');
    } catch (err: any) {
      // 一问诊一处方：409 冲突话术由全局 errorHandler 弹出（服务端 detail），同步隐藏开方区
      if (err?.response?.status === 409) setPrescriptionCreated(true);
    } finally {
      setPrescriptionSubmitting(false);
    }
  };

  // 倒计时会每秒重绘抽屉；保持回调引用稳定，避免 PrescriptionForm 将重绘误判为选药变化并重复预检。
  const handleCheckPrescriptionSafety = useCallback((ids: number[]) => {
    if (!consultationId) return Promise.reject(new Error('问诊单不存在'));
    return checkOnlinePrescriptionSafety(consultationId, ids);
  }, [consultationId]);

  return (
    <Drawer title="在线问诊详情" width={640} open={open} onClose={onClose} destroyOnHidden>
      <Spin spinning={loading}>
        {detail && (
          <Space direction="vertical" size="large" style={{ width: '100%' }}>
            <Descriptions column={2} size="small">
              <Descriptions.Item label="患者">{detail.patient.nickname}</Descriptions.Item>
              <Descriptions.Item label="服务对象">{detail.health_profile.display_name}</Descriptions.Item>
              <Descriptions.Item label="性别">{detail.health_profile.gender}</Descriptions.Item>
              <Descriptions.Item label="出生日期">{detail.health_profile.birth_date}</Descriptions.Item>
              <Descriptions.Item label="与本人关系">{detail.health_profile.relationship}</Descriptions.Item>
              <Descriptions.Item label="标准科室">{detail.standard_department_name}</Descriptions.Item>
              <Descriptions.Item label="过敏史">
                {detail.health_profile.allergies.length ? detail.health_profile.allergies.join('、') : '无'}
              </Descriptions.Item>
              <Descriptions.Item label="状态">
                <Tag color={statusTagColor(detail.status)}>{detail.status_label}</Tag>
              </Descriptions.Item>
              {/* 票 86：剩余 ≤ 5 分钟橙色警示；倒数到 0 后固定显示"问诊已结束"并停止 */}
              {endsAt && remainingSeconds != null && (
                <Descriptions.Item label="剩余时间">
                  {remainingSeconds > 0 ? (
                    <Typography.Text type={remainingSeconds <= 300 ? 'warning' : undefined} strong>
                      {`${String(Math.floor(remainingSeconds / 60)).padStart(2, '0')}:${String(remainingSeconds % 60).padStart(2, '0')}`}
                      {remainingSeconds <= 300 && '（即将自动结束）'}
                    </Typography.Text>
                  ) : (
                    <Typography.Text type="secondary">问诊已结束</Typography.Text>
                  )}
                </Descriptions.Item>
              )}
              <Descriptions.Item label="接诊截止" span={endsAt && remainingSeconds != null ? 1 : 2}>
                {formatDateTime(detail.expires_at)}
              </Descriptions.Item>
            </Descriptions>

            <Alert
              type="info"
              showIcon
              message="AI 病情摘要"
              description={
                <Space direction="vertical" size={4}>
                  {detail.summary ? (
                    <>
                      <Typography.Text>{summaryFieldLabels.chief_complaint}：{detail.summary.chief_complaint}</Typography.Text>
                      <Typography.Text>{summaryFieldLabels.present_illness}：{detail.summary.present_illness}</Typography.Text>
                      <Typography.Text>{summaryFieldLabels.allergy_history}：{detail.summary.allergy_history}</Typography.Text>
                    </>
                  ) : (
                    <Typography.Text>暂无病情摘要。</Typography.Text>
                  )}
                  <Typography.Text strong type="warning">{detail.summary_disclaimer}</Typography.Text>
                </Space>
              }
            />

            {detail.status === consultationStatuses.waiting_doctor && (
              <Button type="primary" block loading={accepting} onClick={handleAccept}>接受问诊</Button>
            )}

            {inProgress && (
              <>
                <Divider orientation="left" style={{ margin: 0 }}>接诊方式</Divider>
                {detail.consult_method === null && (
                  <Space>
                    <Button type="primary" loading={methodSubmitting}
                      onClick={() => handleStartMethod(consultMethods.text)}>
                      发起图文问诊
                    </Button>
                    <Popconfirm title="确认发起模拟视频问诊？" onConfirm={() => handleStartMethod(consultMethods.video)}>
                      <Button loading={methodSubmitting}>发起模拟视频</Button>
                    </Popconfirm>
                  </Space>
                )}
                {detail.consult_method === consultMethods.text && (
                  <Tag color="blue">{detail.consult_method_label}</Tag>
                )}
                {detail.consult_method === consultMethods.video && (
                  <div style={{
                    background: '#123f38', borderRadius: 8, padding: '14px 16px',
                    color: '#fff', display: 'flex', alignItems: 'center', gap: 12,
                  }}>
                    <Avatar size={40} icon={<VideoCameraOutlined />} style={{ background: '#0e7a6c' }} />
                    <div>
                      <div style={{ fontWeight: 600 }}>模拟视频问诊 · 已连接</div>
                      <div style={{ fontSize: 12, opacity: 0.75 }}>
                        {detail.consult_method_label}
                        {detail.method_started_at ? ` · ${formatDateTime(detail.method_started_at)}` : ''}
                      </div>
                    </div>
                  </div>
                )}
              </>
            )}

            {(inProgress || detail.status === consultationStatuses.completed) && (
              <>
                <Divider orientation="left" style={{ margin: 0 }}>问诊沟通</Divider>
                <div
                  ref={threadRef}
                  style={{
                    maxHeight: 280, overflowY: 'auto', border: '1px solid #e6f2ee',
                    borderRadius: 8, padding: 12, background: '#fafdfc',
                  }}
                >
                  {messages.length === 0 && (
                    <Typography.Text type="secondary" style={{ display: 'block', textAlign: 'center' }}>
                      暂无沟通消息
                    </Typography.Text>
                  )}
                  {messages.map((item) => {
                    if (item.sender_type === senderTypes.system) {
                      return (
                        <div key={item.id} style={{ textAlign: 'center', margin: '8px 0' }}>
                          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                            {item.content} · {formatChatTime(item.created_at)}
                          </Typography.Text>
                        </div>
                      );
                    }
                    const fromDoctor = item.sender_type === senderTypes.doctor;
                    return (
                      <div key={item.id} style={{
                        display: 'flex', margin: '8px 0',
                        justifyContent: fromDoctor ? 'flex-end' : 'flex-start',
                      }}>
                        <div style={{
                          maxWidth: '75%', padding: '8px 12px', borderRadius: 8,
                          background: fromDoctor ? '#0e7a6c' : '#fff',
                          color: fromDoctor ? '#fff' : 'inherit',
                          border: fromDoctor ? 'none' : '1px solid #e6f2ee',
                        }}>
                          {/* 票 58：患者图片消息（kind=image），content 为 {"object_key","media_type"} JSON。
                              回看走 reception 域鉴权代理（/api/b/reception/photos，doctor 可访问）——
                              /api/b/photos 仅限 admin 角色且 <img> 带不了 Bearer，故 AuthPhoto 先 fetch blob；
                              kind 与契约 message_kinds 索引一一对应 */}
                          {item.kind === messageKinds[1] ? (
                            parseImageObjectKey(item.content) ? (
                              <AuthPhoto objectKey={parseImageObjectKey(item.content)} />
                            ) : (
                              <Typography.Text type="secondary">图片暂不可查看</Typography.Text>
                            )
                          ) : (
                            <div style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>{item.content}</div>
                          )}
                          <div style={{ fontSize: 11, opacity: 0.65, marginTop: 4, textAlign: 'right' }}>
                            {formatChatTime(item.created_at)}
                          </div>
                        </div>
                      </div>
                    );
                  })}
                </div>
                {inProgress ? (
                  detail.consult_method === null ? (
                    <Typography.Text type="secondary">{consultationTexts.method_required}。</Typography.Text>
                  ) : (
                    <Space.Compact style={{ width: '100%' }}>
                      <Input
                        value={draft}
                        onChange={(e) => setDraft(e.target.value)}
                        onPressEnter={handleSend}
                        placeholder="输入回复内容，回车发送"
                        maxLength={2000}
                      />
                      <Button type="primary" icon={<SendOutlined />} loading={sending}
                        disabled={!draft.trim()} onClick={handleSend}>
                        发送
                      </Button>
                    </Space.Compact>
                  )
                ) : (
                  <Typography.Text type="secondary">
                    {consultationTexts.consult_completed}，沟通记录仅供查看。
                  </Typography.Text>
                )}
              </>
            )}

            {inProgress && (
              <>
                <Divider orientation="left" style={{ margin: 0 }}>开具处方</Divider>
                {hasPrescription ? (
                  <Space direction="vertical" size="small" style={{ width: '100%' }}>
                    <Space>
                      <Typography.Text>处方审核状态：</Typography.Text>
                      <Tag color={prescriptionTagColor(prescription?.status ?? prescriptionStatuses.pending)}>
                        {prescription?.status_label ?? '审核中'}
                      </Tag>
                    </Space>
                    {/* 驳回即终态，不提供编辑/重提入口；仅展示驳回原因供医生知悉 */}
                    {prescriptionRejected && (
                      <Alert
                        type="error"
                        showIcon
                        message="处方已被驳回"
                        description={prescription?.review_reason || '未填写驳回原因'}
                      />
                    )}
                  </Space>
                ) : (
                  <PrescriptionForm
                    checkSafety={handleCheckPrescriptionSafety}
                    medications={medications}
                    submitting={prescriptionSubmitting}
                    onSubmit={handlePrescribe}
                  />
                )}
              </>
            )}

            {inProgress && (
              <>
                <Divider orientation="left" style={{ margin: 0 }}>完成问诊</Divider>
                <Form form={form} layout="vertical" onFinish={handleComplete}>
                  <Form.Item name="diagnosis" label="诊断结论"
                    rules={[{ required: true, whitespace: true, message: '请填写诊断结论' }, { max: 2000 }]}>
                    <Input.TextArea rows={3} placeholder="填写医生诊断结论" />
                  </Form.Item>
                  <Form.Item name="advice" label="医嘱"
                    rules={[{ required: true, whitespace: true, message: '请填写医嘱' }, { max: 2000 }]}>
                    <Input.TextArea rows={3} placeholder="填写后续治疗、复诊或生活建议" />
                  </Form.Item>
                  <Popconfirm title="确认完成问诊？完成后不可再发送消息" onConfirm={() => form.submit()}>
                    <Button type="primary" loading={completing}>完成问诊</Button>
                  </Popconfirm>
                </Form>
              </>
            )}

            {detail.status === consultationStatuses.completed && (
              <Descriptions title="问诊记录" column={1} bordered size="small">
                <Descriptions.Item label="诊断结论">{detail.diagnosis}</Descriptions.Item>
                <Descriptions.Item label="医嘱">{detail.advice}</Descriptions.Item>
                <Descriptions.Item label="完成时间">{formatDateTime(detail.completed_at)}</Descriptions.Item>
                {/* 处方审核结果（票 60 A4）：无处方不渲染；驳回附原因 */}
                {prescription && (
                  <Descriptions.Item label="处方审核">
                    <Space direction="vertical" size={4}>
                      <Tag color={prescriptionTagColor(prescription.status)}>{prescription.status_label}</Tag>
                      {prescription.status === prescriptionStatuses.rejected && (
                        <Typography.Text type="danger">
                          驳回原因：{prescription.review_reason || '未填写驳回原因'}
                        </Typography.Text>
                      )}
                    </Space>
                  </Descriptions.Item>
                )}
              </Descriptions>
            )}
          </Space>
        )}
      </Spin>
    </Drawer>
  );
}
