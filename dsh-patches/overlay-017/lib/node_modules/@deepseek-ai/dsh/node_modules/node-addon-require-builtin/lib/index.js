"use strict";
/*
 * Android 适配：本模块上游是「Node-API 原生 addon + 运行时探测」，
 * 通过平台专属可选依赖（node-addon-require-builtin-<platform>-<arch>-<abi>）加载 .node。
 * 这条链在 Android 上必然断掉：没有 Android 产物，本项目的 payload 也按既定红线
 * 不带任何 .node/.so。而 dsh-app-boot 的 internalModules()（host preparation 阶段）
 * 是**无 try/catch 的硬依赖**，加载失败直接致命：
 *   dsh: host preparation failed: No usable native binding found for node-addon-require-builtin-...
 * 结果连 web profile 都起不来。
 *
 * 回退实现：App 启动 node 时本来就带 --expose-internals（MainActivity.spawnNode），
 * 该参数下 require("internal/modules/...") 直接可用，拿到的是同一批内部接口
 * （内部模块加载器 / cjs loader / conditions 工具）。
 * 已实测 dsh-app-boot 校验的 6 个接口在回退路径下全部存在：
 *   esm.resolveSync / esm.getOrCreateModuleJob / cjs.Module._resolveFilename
 *   / helpers.getCjsConditions / utils.getDefaultConditions / resolve.defaultResolve
 *
 * 原生绑定仍然优先：只有拿不到 addon 才走回退，桌面与服务器行为不变。
 */
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.requireBuiltin = requireBuiltin;
exports.isAllowedInternalId = isAllowedInternalId;
exports.getBindingInfo = getBindingInfo;
const node_path_1 = __importDefault(require("node:path"));
const node_module_1 = require("node:module");
const requireInternal = (0, node_module_1.createRequire)(__filename);
let native = null;
let nativeFailure = null;
try {
    const { createEntryApi } = require('node-addon-native-custom-loader');
    native = createEntryApi(node_path_1.default.resolve(__dirname, '..'));
}
catch (error) {
    nativeFailure = error;
}
/** 内部模块访问：优先原生绑定，退化到 --expose-internals 下的普通 require。 */
function requireBuiltin(moduleId) {
    if (native)
        return native.requireBuiltin(moduleId);
    return requireInternal(moduleId);
}
function isAllowedInternalId(moduleId) {
    if (native)
        return native.isAllowedInternalId(moduleId);
    /* 回退路径下由 Node 自身的 --expose-internals 门禁决定可访问范围。 */
    return true;
}
function getBindingInfo() {
    if (native)
        return native.getBindingInfo();
    return {
        binding: 'js-fallback',
        reason: nativeFailure ? String(nativeFailure && nativeFailure.message || nativeFailure) : 'native binding unavailable',
    };
}
exports.default = {
    requireBuiltin,
    isAllowedInternalId,
    getBindingInfo,
};
