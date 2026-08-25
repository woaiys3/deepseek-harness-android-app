/** Local node-pty terminal-process implementation for the subprocess seam. */
import { PassThrough } from 'node:stream';
import type { IPty } from 'node-pty';
import type { SubprocessOutcome, SubprocessTerminalForeground, SubprocessTerminalHandle, SubprocessTerminalSignal } from '@deepseek-ai/dsh-subprocess';
import type { ProcessInspector } from './process-inspector.ts';
/**
 * A local terminal whose process-session ownership stays below the PTY backend.
 * The seam's terminate() promise — no write, inspection, or signal in flight
 * after settlement — holds here without operation tracking only because every
 * handle call completes synchronously under the hood (node-pty write, ps-based
 * inspection). A first genuinely asynchronous step in any handle call must add
 * the tracking a remote provider needs.
 */
export declare class LocalTerminalHandle implements SubprocessTerminalHandle {
    private readonly terminal;
    private readonly inspector;
    private readonly graceMs;
    readonly pid: number;
    readonly output: PassThrough;
    readonly done: Promise<SubprocessOutcome>;
    private readonly outcome;
    private readonly dataDisposable;
    private readonly exitDisposable;
    private cleanup;
    private exited;
    private trackedDescendants;
    /** The spawned shell's start identity; scans stop adopting members once the root pid no longer carries it. */
    private readonly rootIdentity;
    /**
     * @param terminal - allocated node-pty process.
     * @param inspector - platform process/session operations.
     * @param graceMs - TERM-to-KILL and exit-wait grace.
     */
    constructor(terminal: IPty, inspector: ProcessInspector, graceMs: number);
    write(data: string): Promise<void>;
    inspectForeground(): Promise<SubprocessTerminalForeground | undefined>;
    signalForeground(signal: SubprocessTerminalSignal): Promise<number>;
    terminate(): Promise<void>;
    /**
     * Force-terminate the observable session synchronously during Node's exit
     * event. This does not claim quiescence and does not replace terminate().
     */
    terminateForHostExit(): void;
    private forceStopShell;
    private survivors;
    private descendants;
    private waitForMembers;
    private signalMembers;
    private forceStopDescendants;
    private unionMembers;
    private stopDescendants;
    private stopShell;
    private closeOnce;
}
//# sourceMappingURL=terminal.d.ts.map