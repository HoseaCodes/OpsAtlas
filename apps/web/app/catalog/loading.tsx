import { Loading } from "@/components/states";

/**
 * Streamed while the catalog request is in flight. Skeleton rows match the real
 * row height, so nothing on the page moves when the data arrives.
 */
export default function CatalogLoading() {
  return (
    <div className="pt-6">
      <Loading rows={6} label="Loading the service catalog" />
    </div>
  );
}
