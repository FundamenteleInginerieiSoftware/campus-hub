import { apiFetch } from "./client.ts";

// Shape of ReliabilityReportResponse.java (my incident module, api-contract "Equipment reliability").
// Every number has one decimal. null means "not enough data", never 0:
// mtbfHours when the unit was never used, avgRepairHours when nothing was resolved,
// vsModel when its twins (same model) have under 10 h of use together or never failed.
export type ReliabilityLevel = "INSPECT" | "WATCH";

export type AssetReliabilityItem = {
  assetId: number;
  assetName: string;
  model: string;
  status: "AVAILABLE" | "UNDER_MAINTENANCE" | "RETIRED";
  failures: number;
  severeFailures: number;
  usageHours: number;
  failuresPer100h: number;
  mtbfHours: number | null;
  avgRepairHours: number | null;
  vsModel: number | null;
  level: ReliabilityLevel;
  reason: string;
};

export type ReliabilityReport = {
  windowDays: number;
  from: string;
  to: string;
  items: AssetReliabilityItem[];
};

/** Units with at least one failure in the last `days` days, worst first (GET /api/v1/admin/reliability). Not paged. */
export function fetchReliability(days: number) {
  return apiFetch<ReliabilityReport>(`/api/v1/admin/reliability?days=${days}`);
}
