/** Raster inspection: full decode at admission, header-only probe on verified reads. */
import type { ImageMediaType } from '@deepseek-ai/dsh-attachment';
/** Decoded metadata from a supported image. */
export interface DetectedImage {
    mediaType: ImageMediaType;
    width: number;
    height: number;
}
/**
 * Parse a supported raster's header and return its intrinsic metadata without
 * decoding pixels. Digest-verified reads use this: admission already proved
 * that these exact bytes decode completely, so the read path only re-derives
 * the reference fields instead of paying the full-raster decode again.
 * @param data - complete encoded image bytes.
 * @returns verified format and dimensions.
 */
export declare function probeImage(data: Uint8Array): Promise<DetectedImage>;
/**
 * Fully decode a supported raster and return its intrinsic metadata.
 * @param data - complete encoded image bytes.
 * @param maxPixels - decoded-pixel admission limit.
 * @returns verified format and dimensions.
 */
export declare function detectImage(data: Uint8Array, maxPixels?: number): Promise<DetectedImage>;
//# sourceMappingURL=image.d.ts.map