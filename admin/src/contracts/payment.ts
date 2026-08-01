import flow from '../../../contracts/payment-flow.json';

export const paymentStatuses = flow.statuses;
export const paymentStatusLabels = flow.status_labels;
export const paymentMessages = flow.messages;
export type PaymentStatus = keyof typeof paymentStatusLabels;
