/** Local durable attachment backend rooted below `DSH_HOME`. @module @deepseek-ai/dsh-attachment-local */
import { Context } from '@deepseek-ai/cordis';
import z from '@deepseek-ai/schemastery';
import { AttachmentStore } from '@deepseek-ai/dsh-attachment';
import type { ImageAttachmentLimits, ImageAttachmentRef, SaveImageAttachment, StoredImageAttachment } from '@deepseek-ai/dsh-attachment';
export { detectImage } from './image.ts';
export { readImageFile, saveImageFile, validateImageFile } from './store.ts';
/** Default maximum encoded bytes for one image. */
export declare const DEFAULT_MAX_IMAGE_BYTES: number;
/** Default maximum images in one prompt. */
export declare const DEFAULT_MAX_IMAGES_PER_MESSAGE = 20;
/** Default maximum aggregate image bytes in one prompt. */
export declare const DEFAULT_MAX_MESSAGE_IMAGE_BYTES: number;
/** Default maximum intrinsic pixels for one image. */
export declare const DEFAULT_MAX_IMAGE_PIXELS = 40000000;
/** Local attachment backend configuration. */
export interface Config {
    /** Explicit harness home; omitted follows `DSH_HOME`, then `~/.dsh`. */
    dshHome?: string;
    /** Maximum encoded bytes accepted for one image. */
    maxImageBytes?: number;
    /** Maximum image count accepted in one submitted message. */
    maxImagesPerMessage?: number;
    /** Maximum aggregate encoded image bytes accepted in one submitted message. */
    maxMessageImageBytes?: number;
    /** Maximum intrinsic width multiplied by height accepted for one image. */
    maxImagePixels?: number;
}
/** Persistent content-addressed local attachment store. */
export declare class LocalAttachmentStore extends AttachmentStore {
    static Config: z<Config>;
    /** Absolute versioned storage root. */
    readonly root: string;
    readonly imageLimits: ImageAttachmentLimits;
    constructor(ctx: Context, config: Config);
    validateImage(input: SaveImageAttachment): Promise<void>;
    saveImage(input: SaveImageAttachment): Promise<ImageAttachmentRef>;
    readImage(ref: ImageAttachmentRef, signal?: AbortSignal): Promise<StoredImageAttachment>;
}
export default LocalAttachmentStore;
//# sourceMappingURL=index.d.ts.map