import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ManifestPrompt } from "../ManifestPrompt";

const PROMPT = "Write a service.yaml manifest for OpsAtlas.\nLine two.";

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("the copyable prompt", () => {
  it("puts the prompt on the clipboard and says what to do with it", async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    vi.stubGlobal("navigator", { ...navigator, clipboard: { writeText } });

    render(<ManifestPrompt prompt={PROMPT} />);
    await userEvent.click(screen.getByRole("button", { name: "Copy prompt" }));

    expect(writeText).toHaveBeenCalledWith(PROMPT);
    expect(screen.getByRole("status")).toHaveTextContent(/Paste it into your assistant/);
  });

  it("says what to do instead when the browser refuses the clipboard", async () => {
    // A button that silently fails is the thing CLAUDE.md §10 rules out, so the
    // refusal has to become an instruction.
    const writeText = vi.fn().mockRejectedValue(new Error("denied"));
    vi.stubGlobal("navigator", { ...navigator, clipboard: { writeText } });

    render(<ManifestPrompt prompt={PROMPT} />);
    await userEvent.click(screen.getByRole("button", { name: "Copy prompt" }));

    expect(screen.getByRole("status")).toHaveTextContent(/would not give access to the clipboard/);
    expect(screen.getByRole("status")).toHaveTextContent(/Cmd\+C or Ctrl\+C/);
  });

  it("shows the prompt itself, so it can be read before it is trusted", () => {
    render(<ManifestPrompt prompt={PROMPT} />);
    expect(screen.getByText(/Write a service.yaml manifest for OpsAtlas/)).toBeInTheDocument();
  });

  it("offers a keyboard route to selecting the whole prompt", async () => {
    render(<ManifestPrompt prompt={PROMPT} />);
    const selectAll = screen.getByRole("button", { name: "Select all" });
    // Reachable and operable by keyboard, not mouse-only.
    selectAll.focus();
    expect(selectAll).toHaveFocus();
    await userEvent.keyboard("{Enter}");
  });

  it("says nothing about copying until something has been copied", () => {
    render(<ManifestPrompt prompt={PROMPT} />);
    expect(screen.getByRole("status")).toBeEmptyDOMElement();
  });
});
