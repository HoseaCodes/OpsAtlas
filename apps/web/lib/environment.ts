/**
 * Which deployment this console is attached to.
 *
 * Read at request time rather than baked in, so one image runs against any
 * deployment — the same reason `OPSATLAS_API_URL` is read at runtime. A
 * `NEXT_PUBLIC_` variable would be inlined at build time and would make the
 * image environment-specific, which is the property the Dockerfile went out of
 * its way to preserve.
 *
 * This replaces the string "local", hardcoded in the shell, which meant the
 * production console spent its first day telling everyone it was a laptop. A
 * label that can only be right by coincidence is worse than no label at all.
 *
 * The default stays "local" because that is where an unconfigured console is
 * almost certainly running, and because a deployment sets it explicitly.
 */
export function deploymentEnvironment(): string {
  const declared = process.env.OPSATLAS_ENVIRONMENT?.trim();
  return declared && declared.length > 0 ? declared : "local";
}
