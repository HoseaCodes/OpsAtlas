import Link from "next/link";
import { Empty } from "@/components/states";

export default function ServiceNotFound() {
  return (
    <Empty title="No such service.">
      Nothing is registered under that name in this organization.{" "}
      <Link href="/catalog" className="underline" style={{ color: "var(--accent)" }}>
        Back to the catalog
      </Link>
      .
    </Empty>
  );
}
