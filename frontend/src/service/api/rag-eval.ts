import { request } from '../request';

export interface EvalCase {
  id: string;
  question: string;
  referenceAnswer: string;
  expectedFileNames: string[];
  expectedFileMd5s: string[];
  expectedEvidence: string[];
}

export interface EvalSummary {
  totalCases: number;
  labeledCases: number;
  retrievalHits: number;
  recallAtK: number | null;
  mrr: number | null;
  judgedCases: number;
  correctCases: number;
  accuracy: number | null;
  meanJudgeScore: number | null;
  failedCases: number;
  needsHumanReviewCount: number;
  diagnosisCounts: Record<string, number>;
  stages?: {
    comparedCases: number;
    rrfHitRate: number | null;
    rrfMrr: number | null;
    rerankHitRate: number | null;
    rerankMrr: number | null;
    regressedCases: number;
    improvedCases: number;
  } | null;
}

export interface EvalRun {
  runId: string;
  label: string;
  datasetSource: string;
  topK: number;
  state: 'RUNNING' | 'SUCCEEDED' | 'FAILED';
  totalCases: number;
  completedCases: number;
  retrievalOnly: boolean;
  summary: EvalSummary | null;
  message: string | null;
  startedAt: string;
  finishedAt: string | null;
}

export interface RetrievedChunk {
  rank: number;
  fileMd5: string;
  fileName: string | null;
  chunkId: number;
  score: number | null;
  relevant: boolean;
  textContent: string | null;
  pageNumber: number | null;
}

export interface EvalCaseResult {
  caseId: string;
  question: string;
  referenceAnswer: string;
  expectedFileNames: string[];
  expectedFileMd5s: string[];
  expectedEvidence: string[];
  retrieved: RetrievedChunk[];
  retrievalHit: boolean | null;
  answer: string | null;
  judgeScore: number | null;
  judgeCorrect: boolean | null;
  judgeReason: string | null;
  diagnosis: string | null;
  needsHumanReview: boolean;
  error: string | null;
  latencyMs: number;
  stages?: {
    candidateHit: boolean | null;
    candidateCount: number;
    thresholdDroppedInTopK: number;
    candidates: RetrievedChunk[];
    comparison?: {
      rerankStatus: string;
      rrfHit: boolean | null;
      rrfFirstRank: number | null;
      rerankHit: boolean | null;
      rerankFirstRank: number | null;
      change: string;
      reranked: RetrievedChunk[];
    } | null;
  } | null;
}

export interface HumanReview {
  caseId: string;
  correct: boolean;
  score: number | null;
  note: string | null;
}

export interface HumanReviewSummary {
  reviews: HumanReview[];
  agreement: {
    comparableCases: number;
    agreementRate: number | null;
    cohensKappa: number | null;
    warning: string | null;
  };
}

export interface EvalReport {
  runId: string;
  config: Record<string, unknown>;
  summary: EvalSummary;
  cases: EvalCaseResult[];
  humanReview?: HumanReviewSummary;
}

export function fetchEvalRuns() {
  return request<EvalRun[]>({ url: '/admin/eval/runs' });
}

export function fetchEvalReport(runId: string) {
  return request<EvalReport>({ url: `/admin/eval/runs/${runId}/report` });
}

export function previewEvalDataset(jsonl: string) {
  return request<EvalCase[]>({ url: '/admin/eval/preview', method: 'post', data: { jsonl } });
}

export function startEval(data: { jsonl: string; topK: number; label: string; retrievalOnly: boolean }) {
  return request<EvalRun>({ url: '/admin/eval/run', method: 'post', data });
}

export function saveEvalReview(runId: string, review: HumanReview) {
  return request<HumanReviewSummary>({
    url: `/admin/eval/runs/${runId}/human-reviews`,
    method: 'post',
    data: { reviews: [review] }
  });
}
