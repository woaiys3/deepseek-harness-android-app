//#region lib/types/invariant.js
/**
* Package-owned invariant companion for `@deepseek-ai/dsh-subprocess-local`.
* @module @deepseek-ai/dsh-subprocess-local/invariant
*/
const PACKAGE_NAME = "@deepseek-ai/dsh-subprocess-local";
/** Cordis companion plugin name. */
const name = "subprocess-local-invariant";
/** Service required before the companion can reserve package ownership. */
const inject = ["invariants"];
/**
* No runtime invariant: this package exposes no independent event sequence or mutable data relation
* beyond contracts enforced at its owning seam.
*/
const install = () => {};
/**
* Register this package's invariant companion.
* @param ctx - Cordis context carrying the invariant service.
* @returns the installed registration's disposer after setup succeeds.
*/
const apply = (ctx) => Promise.resolve(ctx.invariants.register(PACKAGE_NAME, install));
//#endregion
export { apply, inject, name };
