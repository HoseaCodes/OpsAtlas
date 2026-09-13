"use client";

import { useRef, useState } from "react";

type CopyState =
  | { kind: "idle" }
  | { kind: "copied" }
  | { kind: "unavailable" };

/**
 * The prompt, and a button that puts it on the clipboard.
 *
 * The prompt is built on the server from the schema and the policy rules, so
 * this component only presents it. It decides nothing about what a manifest
 * should contain - CLAUDE.md section 5 keeps that in the control plane.
 *
 * The clipboard API is unavailable outside a secure context and can be refused
 * by permission policy, so a failure is a state with an instruction rather than
 * a button that silently does nothing.
 */
export function ManifestPrompt({ prompt }: { prompt: string }) {
  const [copyState, setCopyState] = useState<CopyState>({ kind: "idle" });
  const promptRef = useRef<HTMLPreElement>(null);

  async function copy() {
    try {
      await navigator.clipboard.writeText(prompt);
      setCopyState({ kind: "copied" });
    } catch {
      // Select the text instead, so the keyboard shortcut still works and the
      // person is one keystroke from the same outcome.
      selectPrompt();
      setCopyState({ kind: "unavailable" });
    }
  }

  function selectPrompt() {
    const element = promptRef.current;
    if (!element) return;
    const range = document.createRange();
    range.selectNodeContents(element);
    const selection = window.getSelection();
    selection?.removeAllRanges();
    selection?.addRange(range);
  }

  const lineCount = prompt.split("\n").length;

  return (
    <details className="card max-w-[80ch] overflow-hidden">
      <summary className="cursor-pointer list-none px-3.5 py-3 text-[13px] font-medium text-ink marker:content-none">
        <span className="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-1">
          <span>Do not have a service.yaml yet? Copy a prompt for your assistant</span>
          <span className="text-[12px] font-normal text-ink-3">{lineCount} lines</span>
        </span>
        <span className="mt-1 block text-[12.5px] font-normal text-ink-2">
          It carries the schema, the ten scorecard rules and an example, so what comes back should paste
          straight into the box below.
        </span>
      </summary>

      <div className="border-t border-rule px-3.5 py-3">
        <div className="flex flex-wrap items-center gap-3">
          <button
            type="button"
            onClick={copy}
            className="cursor-pointer rounded-[5px] border border-rule-2 bg-transparent px-2.5 py-1.5 text-[12.5px] text-ink-2 hover:border-ink-3 hover:text-ink"
          >
            Copy prompt
          </button>
          <button
            type="button"
            onClick={selectPrompt}
            className="cursor-pointer rounded-[5px] border border-rule-2 bg-transparent px-2.5 py-1.5 text-[12.5px] text-ink-2 hover:border-ink-3 hover:text-ink"
          >
            Select all
          </button>

          {/* Confirmation is a sentence, not a colour change: CLAUDE.md
              section 10 rations colour to four jobs and this is not one. */}
          <span role="status" aria-live="polite" className="text-[12.5px] text-ink-2">
            {copyState.kind === "copied"
              ? "Copied. Paste it into your assistant, then paste the YAML it returns into the box below."
              : null}
            {copyState.kind === "unavailable"
              ? "This browser would not give access to the clipboard. The prompt is selected — copy it with Cmd+C or Ctrl+C."
              : null}
          </span>
        </div>

        <pre
          ref={promptRef}
          tabIndex={0}
          className="mono mt-3 max-h-[320px] overflow-auto whitespace-pre-wrap break-words rounded-md border border-rule bg-surface p-3 text-[12px] leading-[1.6] text-ink-2"
        >
          {prompt}
        </pre>
      </div>
    </details>
  );
}
