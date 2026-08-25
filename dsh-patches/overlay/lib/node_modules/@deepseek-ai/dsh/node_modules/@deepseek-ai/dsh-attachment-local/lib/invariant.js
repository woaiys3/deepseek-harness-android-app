//#region lib/types/invariant.js
/** Package-owned invariant companion for `@deepseek-ai/dsh-attachment-local`. @module @deepseek-ai/dsh-attachment-local/invariant */
const PACKAGE_NAME = "@deepseek-ai/dsh-attachment-local";
/** Cordis companion plugin name. */
const name = "attachment-local-invariant";
/** Services required before package ownership can be reserved. */
const inject = ["invariants", "attachments"];
/** No runtime invariant: immutable writes and verified reads are enforced directly at the backend boundary. */
const install = () => {};
/**
* Register the package invariant companion.
* @param ctx - Cordis context carrying the invariant service.
* @returns the registration disposer.
*/
const apply = (ctx) => Promise.resolve(ctx.invariants.register(PACKAGE_NAME, install));
//#endregion
export { apply, inject, name };
