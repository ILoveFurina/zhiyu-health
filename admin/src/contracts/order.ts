import flow from '../../../contracts/order-flow.json';

export const orderStatuses = flow.statuses;
export const orderStatusLabels = flow.status_labels;
export const orderDecisions = flow.decisions;
export const orderSources = flow.sources;
export const orderSourceLabels = flow.source_labels;
export const pickupMethods = flow.pickup_methods;
export const pickupMethodLabels = flow.pickup_method_labels;
export const paymentTimeoutSeconds = flow.payment_timeout_seconds;
export const simulatedCarrierName = flow.simulated_carrier_name;
export type DrugOrderStatus = keyof typeof orderStatusLabels;
export type PickupMethod = keyof typeof pickupMethodLabels;
