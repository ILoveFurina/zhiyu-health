import flow from '../../../contracts/prescription-flow.json';

export const prescriptionStatuses = flow.statuses;
export const prescriptionStatusLabels = flow.status_labels;
export const prescriptionDecisions = flow.decisions;
export const sourceTypes = flow.source_types;
export const sourceTypeLabels = flow.source_type_labels;
export type PrescriptionSourceType = keyof typeof sourceTypeLabels;
