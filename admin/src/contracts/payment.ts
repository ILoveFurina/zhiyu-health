import flow from '../../../contracts/payment-flow.json';

export const paymentStatuses = flow.statuses;
export const paymentStatusLabels = flow.status_labels;
export type PaymentStatus = keyof typeof paymentStatusLabels;
