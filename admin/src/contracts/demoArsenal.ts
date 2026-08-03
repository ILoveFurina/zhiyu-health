import demoArsenal from '../../../contracts/demo-arsenal.json';

export const knowledgeSourceValues = demoArsenal.knowledge_source_values as readonly string[];
export const knowledgeSourceDefault = demoArsenal.knowledge_source_default;
export const knowledgeSourceRedisKey = demoArsenal.knowledge_source_redis_key;
export const resetConfirmPhrase = demoArsenal.reset_confirm_phrase;
export const resetFreezeStatus = demoArsenal.reset_freeze_status;

export type KnowledgeSource = (typeof knowledgeSourceValues)[number];
