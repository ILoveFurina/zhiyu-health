import doc from '../../../contracts/knowledge-documents.json';

export const documentStatuses = doc.document_status;
export const documentSources = doc.document_source;
export const orphanTimeoutSeconds = doc.orphan_timeout_seconds;
export const embeddingEndpoint = doc.embedding.endpoint;
export const embeddingMaxTexts = doc.embedding.max_texts;
export const embeddingBatchSize = doc.embedding.batch_size;
export const embeddingTimeoutMs = doc.embedding.timeout_ms;
export const embeddingErrorCodes = doc.embedding.error_codes;
export const embeddingInputFormat = doc.embedding_input_format;
export const chunkSize = doc.chunking.chunk_size;
export const chunkOverlap = doc.chunking.chunk_overlap;
export const uploadAllowedTypes = doc.upload.allowed_types as readonly string[];
export const uploadAllowedExtensions = doc.upload.allowed_extensions as readonly string[];
export const uploadMaxFileBytes = doc.upload.max_file_bytes;
export const uploadMaxFiles = doc.upload.max_files;
export const titleFormat = doc.title_format;
export const errorCodes = doc.error_codes;

export type DocumentStatus = (typeof documentStatuses)[number];
export type DocumentSource = (typeof documentSources)[number];
