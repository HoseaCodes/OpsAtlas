import { afterEach, describe, expect, it } from "vitest";
import { deploymentEnvironment } from "../environment";

/**
 * The label under the wordmark used to be the hardcoded string "local", so the
 * production console announced itself as a laptop. These tests exist because
 * that was only discoverable by looking at a screenshot of the real thing.
 */
describe("the deployment label", () => {
  const original = process.env.OPSATLAS_ENVIRONMENT;

  afterEach(() => {
    if (original === undefined) delete process.env.OPSATLAS_ENVIRONMENT;
    else process.env.OPSATLAS_ENVIRONMENT = original;
  });

  it("reports what the deployment declares", () => {
    process.env.OPSATLAS_ENVIRONMENT = "production";
    expect(deploymentEnvironment()).toBe("production");
  });

  it("falls back to local when nothing is declared", () => {
    delete process.env.OPSATLAS_ENVIRONMENT;
    expect(deploymentEnvironment()).toBe("local");
  });

  it("treats an empty or blank value as undeclared rather than printing nothing", () => {
    // An env file with `OPSATLAS_ENVIRONMENT=` is a realistic accident, and
    // "control-plane / " with a trailing slash reads as a rendering bug.
    process.env.OPSATLAS_ENVIRONMENT = "";
    expect(deploymentEnvironment()).toBe("local");
    process.env.OPSATLAS_ENVIRONMENT = "   ";
    expect(deploymentEnvironment()).toBe("local");
  });

  it("does not invent a value it was not given", () => {
    // The point of the fix: the label is only ever what somebody configured or
    // the honest default. There is no third source.
    process.env.OPSATLAS_ENVIRONMENT = "staging";
    expect(deploymentEnvironment()).toBe("staging");
  });
});
