/** Content-addressed, owner-private local attachment storage. */
import type { ImageAttachmentLimits, ImageAttachmentRef, SaveImageAttachment, StoredImageAttachment } from '@deepseek-ai/dsh-attachment';
/**
 * Run the full admission policy for one image without touching storage.
 * @param input - encoded bytes and declared metadata.
 * @param limits - resolved storage policy.
 * @returns completion after the encoded raster has been fully decoded.
 */
export declare function validateImageFile(input: SaveImageAttachment, limits: ImageAttachmentLimits): Promise<void>;
/**
 * Save and verify immutable image bytes below a versioned attachment root.
 * @param root - absolute `DSH_HOME/attachments/v1` root.
 * @param input - encoded bytes and declared metadata.
 * @param limits - resolved storage policy.
 * @returns durable content-addressed reference.
 */
export declare function saveImageFile(root: string, input: SaveImageAttachment, limits: ImageAttachmentLimits): Promise<ImageAttachmentRef>;
/**
 * Read and verify one content-addressed image.
 * @param root - absolute `DSH_HOME/attachments/v1` root.
 * @param ref - reference recorded in the session log.
 * @param signal - optional cancellation for filesystem and verification work.
 * @returns verified bytes and reference.
 * @throws the signal reason when aborted, or an AttachmentError when verification fails.
 */
export declare function readImageFile(root: string, ref: ImageAttachmentRef, signal?: AbortSignal): Promise<StoredImageAttachment>;
//# sourceMappingURL=store.d.ts.map