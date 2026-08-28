//#region lib/types/invariant.js
/**
* Package-owned invariant companion for `@deepseek-ai/dsh-session-persistence-jsonl`.
* @module @deepseek-ai/dsh-session-persistence-jsonl/invariant
*/
const PACKAGE_NAME = "@deepseek-ai/dsh-session-persistence-jsonl";
/** Cordis companion plugin name. */
const name = "session-persistence-jsonl-invariant";
/** Service required before the companion can reserve package ownership. */
const inject = ["invariants"];
/**
* No runtime invariant: persistence correctness requires backend round-trip and crash-tail tests;
* this package exposes no continuously observable in-process relation.
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
