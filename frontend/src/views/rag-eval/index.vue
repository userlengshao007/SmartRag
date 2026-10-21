<script setup lang="tsx">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue';
import type { DataTableColumns } from 'naive-ui';
import { NButton, NTag } from 'naive-ui';
import type { EvalCase, EvalCaseResult, EvalReport, EvalRun, HumanReview } from '@/service/api/rag-eval';
import { fetchEvalReport, fetchEvalRuns, previewEvalDataset, saveEvalReview, startEval } from '@/service/api/rag-eval';

const runs = ref<EvalRun[]>([]);
const selectedId = ref('');
const report = ref<EvalReport | null>(null);
const loading = ref(false);
const reportLoading = ref(false);
const loadFailed = ref(false);
const showCreate = ref(false);
const reusedRunLabel = ref('');
const submitting = ref(false);
const validating = ref(false);
const preview = ref<EvalCase[]>([]);
const dataset = ref('');
const fileName = ref('');
const fileInput = ref<HTMLInputElement | null>(null);
const form = reactive({ label: '', topK: 5, retrievalOnly: true });
const filter = ref('all');
const search = ref('');
const activeCase = ref<EvalCaseResult | null>(null);
const showCase = ref(false);
const saving = ref(false);
const review = reactive<{ correct: boolean | null; score: number | null; note: string }>({
  correct: null,
  score: null,
  note: ''
});
let timer: ReturnType<typeof setTimeout> | undefined;
let reportSequence = 0;
let stopped = false;

const selected = computed(() => runs.value.find(item => item.runId === selectedId.value));
const summary = computed(() => report.value?.summary ?? selected.value?.summary);
const running = computed(() => runs.value.some(item => item.state === 'RUNNING'));
const sources = computed(() => [...new Set(preview.value.flatMap(item => item.expectedFileNames))]);
const labeledCount = computed(
  () => preview.value.filter(item => item.expectedFileNames.length || item.expectedFileMd5s.length).length
);
const progress = computed(() =>
  Math.round((100 * (selected.value?.completedCases ?? 0)) / Math.max(selected.value?.totalCases ?? 1, 1))
);
const stageLabels: Record<string, string> = {
  REGRESSED: '精排后丢失命中',
  IMPROVED: '精排后新增命中',
  CANDIDATE_MISS: '候选阶段未命中',
  UNCHANGED: '命中状态不变',
  FAILED: '模型失败，回退 RRF',
  DISABLED: '未启用精排',
  EMPTY: '无候选',
  UNLABELED: '未标注证据'
};
const filterOptions = [
  { label: '全部题目', value: 'all' },
  { label: '检索未命中', value: 'miss' },
  { label: '精排后丢失命中', value: 'regressed' },
  { label: '候选未命中', value: 'candidate-miss' },
  { label: '回答错误', value: 'wrong' },
  { label: '待人工复核', value: 'review' },
  { label: '调用异常', value: 'error' }
];
const diagnosisLabels: Record<string, string> = {
  OK: '命中且答对',
  PROMPT_OR_MODEL: '命中但答错',
  RETRIEVAL_MISS: '检索未命中',
  MISS_BUT_CORRECT: '未命中但判答对',
  NOT_LABELED: '未标注检索证据',
  RETRIEVAL_FAILED: '检索调用失败',
  GENERATION_FAILED: '生成失败',
  JUDGE_FAILED: '评委失败'
};
const reviewMap = computed(() => new Map(report.value?.humanReview?.reviews.map(item => [item.caseId, item]) ?? []));
const filteredCases = computed(() =>
  (report.value?.cases ?? []).filter(item => {
    const matches =
      item.question.toLowerCase().includes(search.value.trim().toLowerCase()) ||
      item.caseId.includes(search.value.trim());
    const state =
      filter.value === 'all' ||
      (filter.value === 'miss' && item.retrievalHit === false) ||
      (filter.value === 'regressed' && item.stages?.comparison?.change === 'REGRESSED') ||
      (filter.value === 'candidate-miss' && item.stages?.comparison?.change === 'CANDIDATE_MISS') ||
      (filter.value === 'wrong' && item.judgeCorrect === false) ||
      (filter.value === 'review' && item.needsHumanReview && !reviewMap.value.has(item.caseId)) ||
      (filter.value === 'error' && Boolean(item.error));
    return matches && state;
  })
);

function percent(value?: number | null) {
  return value === null || value === undefined ? '—' : `${(value * 100).toFixed(1)}%`;
}
function dateTime(value?: string | null) {
  return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—';
}
function stateLabel(run: EvalRun) {
  return { RUNNING: '评测中', SUCCEEDED: '已完成', FAILED: '失败' }[run.state];
}
function openCase(item: EvalCaseResult) {
  activeCase.value = item;
  const saved = reviewMap.value.get(item.caseId);
  Object.assign(review, { correct: saved?.correct ?? null, score: saved?.score ?? null, note: saved?.note ?? '' });
  showCase.value = true;
}
const columns: DataTableColumns<EvalCaseResult> = [
  { title: '题目', key: 'question', minWidth: 240, ellipsis: { tooltip: true } },
  {
    title: '检索命中',
    key: 'retrievalHit',
    width: 100,
    render: row =>
      row.retrievalHit === null ? (
        '未标注'
      ) : (
        <NTag size="small" bordered={false} type={row.retrievalHit ? 'success' : 'warning'}>
          {row.retrievalHit ? '命中' : '未命中'}
        </NTag>
      )
  },
  {
    title: '回答判定',
    key: 'judgeCorrect',
    width: 100,
    render: row =>
      row.judgeCorrect === null ? (
        '未评分'
      ) : (
        <NTag size="small" bordered={false} type={row.judgeCorrect ? 'success' : 'error'}>
          {row.judgeCorrect ? '正确' : '错误'}
        </NTag>
      )
  },
  {
    title: '评分',
    key: 'judgeScore',
    width: 70,
    render: row => (row.judgeScore === null ? '—' : `${row.judgeScore}/5`)
  },
  {
    title: '排查线索',
    key: 'diagnosis',
    width: 165,
    render: row => (row.error ? '调用异常' : (diagnosisLabels[row.diagnosis ?? ''] ?? '仅检索'))
  },
  {
    title: '人工复核',
    key: 'review',
    width: 100,
    render: row => {
      if (reviewMap.value.has(row.caseId)) return '已复核';
      return row.needsHumanReview ? '待复核' : '—';
    }
  },
  {
    title: '操作',
    key: 'action',
    width: 85,
    fixed: 'right',
    render: row => (
      <NButton text type="primary" onClick={() => openCase(row)}>
        查看详情
      </NButton>
    )
  }
];

async function selectRun(runId: string) {
  selectedId.value = runId;
  report.value = null;
  showCase.value = false;
  reportLoading.value = false;
  reportSequence += 1;
  const sequence = reportSequence;
  const run = runs.value.find(item => item.runId === runId);
  if (run?.state !== 'SUCCEEDED') return;
  reportLoading.value = true;
  try {
    const { data, error } = await fetchEvalReport(runId);
    if (sequence === reportSequence && !error) report.value = data;
  } finally {
    if (sequence === reportSequence) reportLoading.value = false;
  }
}
async function refresh() {
  if (loading.value) return;
  loading.value = true;
  try {
    const { data, error } = await fetchEvalRuns();
    loadFailed.value = Boolean(error);
    if (error || !data) return;
    const previous = selected.value?.state;
    runs.value = data;
    if (!selectedId.value && data.length) await selectRun(data[0].runId);
    else if (selected.value?.state === 'SUCCEEDED' && (previous !== 'SUCCEEDED' || !report.value))
      await selectRun(selectedId.value);
  } finally {
    loading.value = false;
  }
}
async function poll() {
  await refresh();
  if (!stopped && running.value && !loadFailed.value) timer = setTimeout(poll, 3000);
}
function startPolling() {
  clearTimeout(timer);
  poll().catch(() => {
    loadFailed.value = true;
  });
}
function openCreate() {
  reusedRunLabel.value = '';
  preview.value = [];
  dataset.value = '';
  fileName.value = '';
  Object.assign(form, { label: '', topK: 5, retrievalOnly: true });
  showCreate.value = true;
}
function rerun() {
  if (!report.value || !selected.value || running.value || submitting.value) return;
  const cases: EvalCase[] = report.value.cases.map(item => ({
    id: item.caseId,
    question: item.question,
    referenceAnswer: item.referenceAnswer,
    expectedFileNames: item.expectedFileNames ?? [],
    expectedFileMd5s: item.expectedFileMd5s ?? [],
    expectedEvidence: item.expectedEvidence ?? []
  }));
  if (!cases.length) return;
  preview.value = cases;
  dataset.value = cases.map(item => JSON.stringify(item)).join('\n');
  fileName.value = `复用 ${cases.length} 道历史题目（可替换文件）`;
  reusedRunLabel.value = selected.value.label;
  const retrievalOnly = report.value.config.retrievalOnly;
  Object.assign(form, {
    label: `${selected.value.label.replace(/ · 再次运行$/, '').slice(0, 32)} · 再次运行`,
    topK: selected.value.topK,
    retrievalOnly: typeof retrievalOnly === 'boolean' ? retrievalOnly : selected.value.retrievalOnly
  });
  showCreate.value = true;
}
async function readFile(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0];
  if (!file) return;
  preview.value = [];
  dataset.value = '';
  fileName.value = '';
  if (file.size > 5 * 1024 * 1024 || !file.name.toLowerCase().endsWith('.jsonl')) {
    window.$message?.error('请选择不超过 5MB 的 .jsonl 文件');
    (event.target as HTMLInputElement).value = '';
    return;
  }
  validating.value = true;
  try {
    const jsonl = await file.text();
    const { data, error } = await previewEvalDataset(jsonl);
    if (!error && data) {
      preview.value = data;
      dataset.value = jsonl;
      fileName.value = file.name;
      if (!form.label) form.label = file.name.replace(/\.jsonl$/i, '');
    }
  } catch {
    window.$message?.error('读取文件失败，请重新选择');
  } finally {
    validating.value = false;
    (event.target as HTMLInputElement).value = '';
  }
}
async function submit() {
  if (!dataset.value || !form.label.trim() || !form.topK || submitting.value) return;
  submitting.value = true;
  try {
    const { data, error } = await startEval({ ...form, label: form.label.trim(), jsonl: dataset.value });
    if (error || !data) return;
    showCreate.value = false;
    runs.value = [data, ...runs.value.filter(item => item.runId !== data.runId)];
    await selectRun(data.runId);
    window.$message?.success('评测已提交');
    startPolling();
  } finally {
    submitting.value = false;
  }
}
async function saveReview() {
  if (!activeCase.value || review.correct === null || saving.value) return;
  saving.value = true;
  const runId = selectedId.value;
  const caseId = activeCase.value.caseId;
  try {
    const verdict: HumanReview = {
      caseId,
      correct: review.correct,
      score: review.score,
      note: review.note.trim() || null
    };
    const { data, error } = await saveEvalReview(runId, verdict);
    if (!error && data && report.value?.runId === runId) {
      report.value = { ...report.value, humanReview: data };
      window.$message?.success('人工复核已保存');
    }
  } finally {
    saving.value = false;
  }
}
function download(content: string, name: string) {
  const url = URL.createObjectURL(new Blob([content], { type: 'application/json;charset=utf-8' }));
  const link = document.createElement('a');
  link.href = url;
  link.download = name;
  link.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
function downloadTemplate() {
  const item = {
    id: 'fact-01',
    question: '请替换为源文档能回答的问题',
    expectedFileNames: ['smartrag.pdf'],
    expectedEvidence: ['请替换为 PDF 分块中的证据原文'],
    referenceAnswer: '请根据源文档填写参考答案'
  };
  download(`# 格式模板，请替换为真实题目和答案后运行\n${JSON.stringify(item)}\n`, 'eval-template.jsonl');
}
onMounted(startPolling);
onBeforeUnmount(() => {
  stopped = true;
  clearTimeout(timer);
  reportSequence += 1;
});
</script>

<template>
  <div class="eval-page">
    <header class="page-heading">
      <div>
        <div class="eyebrow">KNOWLEDGE QUALITY</div>
        <h1>RAG 评测</h1>
        <p>先确认资料找对了，再确认回答答对了。</p>
      </div>
      <NSpace>
        <NButton :loading="loading" @click="startPolling">刷新</NButton>
        <NButton type="primary" @click="openCreate">新建评测</NButton>
      </NSpace>
    </header>
    <div class="workflow">
      <span>
        <b>01</b>
        准备题目与参考答案
      </span>
      <span>→</span>
      <span>
        <b>02</b>
        检查检索证据
      </span>
      <span>→</span>
      <span>
        <b>03</b>
        生成与评分
      </span>
      <span>→</span>
      <span>
        <b>04</b>
        人工复核
      </span>
    </div>
    <NAlert v-if="loadFailed" type="error" title="暂时无法加载评测" class="mb-4">
      请确认后端已启动、已加载评测功能，并使用管理员账号登录。
      <NButton text type="primary" @click="startPolling">重试</NButton>
    </NAlert>
    <div class="eval-layout">
      <NCard title="评测记录" :bordered="false" class="history-card">
        <template #header-extra>
          <NTag size="small" :bordered="false">最近 {{ runs.length }} 次</NTag>
        </template>
        <NEmpty v-if="!runs.length && !loading" description="还没有评测记录">
          <template #extra><NButton size="small" @click="openCreate">创建第一次评测</NButton></template>
        </NEmpty>
        <button
          v-for="run in runs"
          :key="run.runId"
          class="run-item"
          :class="{ active: selectedId === run.runId }"
          @click="selectRun(run.runId)"
        >
          <div class="run-title">
            <strong>{{ run.label }}</strong>
            <NTag
              size="small"
              :bordered="false"
              :type="run.state === 'RUNNING' ? 'info' : run.state === 'FAILED' ? 'error' : 'success'"
            >
              {{ stateLabel(run) }}
            </NTag>
          </div>
          <p>{{ run.retrievalOnly ? '仅检索' : '完整评测' }} · {{ run.totalCases }} 道题 · Top {{ run.topK }}</p>
          <div class="run-metrics">
            <span>命中 {{ percent(run.summary?.recallAtK) }}</span>
            <span>准确 {{ percent(run.summary?.accuracy) }}</span>
          </div>
          <small>{{ dateTime(run.startedAt) }}</small>
        </button>
      </NCard>
      <main class="result-area">
        <NCard v-if="!selected" :bordered="false" class="welcome-card">
          <div class="welcome-icon">◎</div>
          <h2>给知识库做一次体检</h2>
          <p>上传带参考答案的题目，检查系统能否找到证据、正确作答。</p>
          <div class="source-note">
            <strong>你的源文档与评测题目</strong>
            <p>
              项目默认源文档为
              <code>docs/smartrag.pdf</code>
              ，文字说明为
              <code>docs/smartrag.md</code>
              。请先在「知识库」确认文档已完成入库。
            </p>
            <p>
              这里上传的是
              <strong>.jsonl 题目文件</strong>
              ，每道题包含问题、参考答案、期望文件和证据短语。
            </p>
          </div>
          <NSpace justify="center">
            <NButton @click="downloadTemplate">下载题目模板</NButton>
            <NButton type="primary" @click="openCreate">新建评测</NButton>
          </NSpace>
        </NCard>
        <template v-else>
          <NCard :bordered="false">
            <div class="result-heading">
              <div>
                <h2>{{ selected.label }}</h2>
                <p>
                  {{ selected.retrievalOnly ? '仅检索评测' : '完整评测' }} · {{ selected.totalCases }} 道题 · Top
                  {{ selected.topK }} · {{ dateTime(selected.startedAt) }}
                </p>
              </div>
              <NSpace>
                <NButton
                  :disabled="!report || running || submitting || !report.cases.length"
                  type="primary"
                  @click="rerun"
                >
                  再次运行
                </NButton>
                <NButton
                  :disabled="!report"
                  @click="report && download(JSON.stringify(report, null, 2), `eval-${selectedId}.json`)"
                >
                  下载报告
                </NButton>
              </NSpace>
            </div>
            <template v-if="selected.state === 'RUNNING'">
              <NProgress type="line" :percentage="progress" processing />
              <p class="muted mt-3">
                已完成 {{ selected.completedCases }} / {{ selected.totalCases }} 道题。{{
                  selected.retrievalOnly ? '正在检索并对照证据标注。' : '正在逐题执行检索、生成和评委评分。'
                }}
                可离开页面，任务会继续运行。
              </p>
            </template>
            <NAlert v-if="selected.state === 'FAILED'" type="error" title="评测未完成">
              {{ selected.message || '请查看后端日志后重试。' }}
            </NAlert>
            <div class="metric-grid">
              <div class="metric primary">
                <span>检索命中率</span>
                <strong>{{ percent(summary?.recallAtK) }}</strong>
                <small>
                  {{ summary ? `${summary.retrievalHits} / ${summary.labeledCases} 道标注题命中` : '等待评测完成' }}
                </small>
                <p>前 {{ selected.topK }} 条结果中，至少找到一条正确证据。</p>
              </div>
              <div class="metric secondary">
                <span>
                  回答准确率
                  <NTag size="tiny" :bordered="false">LLM 评委</NTag>
                </span>
                <strong>{{ selected.retrievalOnly ? '未评估' : percent(summary?.accuracy) }}</strong>
                <small>
                  {{
                    selected.retrievalOnly
                      ? '仅检索模式不生成回答、不评分'
                      : summary
                        ? `${summary.correctCases} / ${summary.judgedCases} 道成功评分题正确`
                        : '等待评测完成'
                  }}
                </small>
                <p>对照参考答案，判断核心结论是否正确。</p>
              </div>
            </div>
            <NAlert v-if="summary?.stages?.comparedCases" type="info" :show-icon="false">
              同一轮候选对比（{{ summary.stages.comparedCases }} 道标注且精排成功的题）： RRF 命中率
              {{ percent(summary.stages.rrfHitRate) }} → 精排 {{ percent(summary.stages.rerankHitRate) }}； MRR
              {{ summary.stages.rrfMrr?.toFixed(3) }} → {{ summary.stages.rerankMrr?.toFixed(3) }}。 新增命中
              {{ summary.stages.improvedCases }} 题，丢失命中 {{ summary.stages.regressedCases }} 题。
              精排名次按阈值过滤前计算，上方检索命中率按过滤后的最终结果计算。
            </NAlert>
            <div v-if="summary" class="secondary-metrics">
              <span>
                MRR
                <strong>{{ summary.mrr?.toFixed(3) ?? '—' }}</strong>
              </span>
              <span>
                平均评分
                <strong>
                  {{ summary.meanJudgeScore === null ? '—' : `${summary.meanJudgeScore.toFixed(2)} / 5` }}
                </strong>
              </span>
              <span>
                调用失败
                <strong>{{ summary.failedCases }} 题</strong>
              </span>
              <span>
                标记复核
                <strong>{{ summary.needsHumanReviewCount }} 题</strong>
              </span>
            </div>
          </NCard>
          <NSpin :show="reportLoading">
            <NCard v-if="report" title="逐题结果" :bordered="false">
              <template #header-extra>
                <span class="muted">{{ filteredCases.length }} / {{ report.cases.length }} 题</span>
              </template>
              <NAlert v-if="report.humanReview?.agreement.warning" type="warning" class="mb-4">
                {{ report.humanReview.agreement.warning }}
              </NAlert>
              <div v-if="report.humanReview" class="review-summary">
                已复核 {{ report.humanReview.reviews.length }} 题 · 与评委一致率
                {{ percent(report.humanReview.agreement.agreementRate) }} · Kappa
                {{ report.humanReview.agreement.cohensKappa?.toFixed(2) ?? '—' }}
              </div>
              <div class="case-toolbar">
                <NInput v-model:value="search" clearable placeholder="搜索问题或题号" aria-label="搜索评测题目" />
                <NSelect v-model:value="filter" :options="filterOptions" aria-label="筛选评测结果" />
              </div>
              <NDataTable
                :columns="columns"
                :data="filteredCases"
                :row-key="(row: EvalCaseResult) => row.caseId"
                :pagination="{ pageSize: 10 }"
                :scroll-x="980"
              />
              <NCollapse class="mt-4">
                <NCollapseItem title="本次评测配置与指标口径" name="config">
                  <p class="muted">
                    检索命中率只统计有期望文件标注的题；回答准确率只统计评委成功评分的题。LLM 评分需结合人工复核解读。
                  </p>
                  <pre class="text-block">{{ JSON.stringify(report.config, null, 2) }}</pre>
                </NCollapseItem>
              </NCollapse>
            </NCard>
          </NSpin>
        </template>
      </main>
    </div>
    <NModal
      v-model:show="showCreate"
      preset="card"
      :title="reusedRunLabel ? '再次运行评测' : '新建评测'"
      class="create-modal"
      :mask-closable="!submitting"
      :closable="!submitting"
    >
      <NAlert v-if="reusedRunLabel" type="info" :show-icon="false" class="mb-4">
        已复用「{{ reusedRunLabel }}」的题目、TopK
        和评测模式，可修改后运行。将创建新记录，使用当前知识库与模型配置，保留原评测结果。
      </NAlert>
      <NForm label-placement="top">
        <NFormItem label="评测名称" required>
          <NInput v-model:value="form.label" placeholder="例如：默认分块 · 第一轮基线" :maxlength="40" show-count />
        </NFormItem>
        <NFormItem label="上传题目文件（JSONL）" required>
          <div class="upload-box">
            <input ref="fileInput" type="file" accept=".jsonl" aria-label="选择 JSONL 题目文件" @change="readFile" />
            <NButton :loading="validating" @click="fileInput?.click()">{{ fileName || '选择 .jsonl 文件' }}</NButton>
            <p>每行一道题，最多 500 题、5MB。上传题目文件，不是 PDF 源文档。</p>
            <NButton text type="primary" @click="downloadTemplate">下载格式模板</NButton>
          </div>
        </NFormItem>
        <div v-if="preview.length" class="dataset-preview">
          <strong>校验通过 · {{ preview.length }} 道题 · {{ labeledCount }} 道有检索标注</strong>
          <p>期望源文件：{{ sources.join('、') || '仅标注 MD5 或未标注文件' }}</p>
          <p v-for="item in preview.slice(0, 3)" :key="item.id">{{ item.id }} · {{ item.question }}</p>
        </div>
        <NFormItem label="评测模式">
          <NRadioGroup v-model:value="form.retrievalOnly">
            <NSpace>
              <NRadio :value="true">仅检索</NRadio>
              <NRadio :value="false">完整评测</NRadio>
            </NSpace>
          </NRadioGroup>
        </NFormItem>
        <NAlert :type="form.retrievalOnly ? 'info' : 'warning'" :show-icon="false" class="mb-4">
          {{
            form.retrievalOnly
              ? '只检查能否找到正确证据。会调用 Embedding，不调用回答模型和评委。'
              : '每题通常调用两次 LLM：生成回答、评委评分；空输出可能重试。使用当前配置的模型。'
          }}
        </NAlert>
        <NFormItem label="每题检索条数 TopK">
          <NInputNumber v-model:value="form.topK" :min="1" :max="20" :precision="0" />
        </NFormItem>
        <p class="muted">请先确认期望文档已在知识库入库，且当前账号有权访问。参考答案只给评委，不交给回答模型。</p>
      </NForm>
      <template #footer>
        <NSpace justify="end">
          <NButton :disabled="submitting" @click="showCreate = false">取消</NButton>
          <NButton
            type="primary"
            :loading="submitting"
            :disabled="!preview.length || !form.label.trim() || !form.topK || running || validating"
            @click="submit"
          >
            {{ running ? '已有评测运行中' : '开始评测' }}
          </NButton>
        </NSpace>
      </template>
    </NModal>
    <NDrawer v-model:show="showCase" :width="760" placement="right" :auto-focus="false" class="case-drawer">
      <NDrawerContent v-if="activeCase" :title="`题目详情 · ${activeCase.caseId}`" closable>
        <section class="detail-section">
          <h3>问题</h3>
          <p class="text-block">{{ activeCase.question }}</p>
          <h3>参考答案</h3>
          <p class="text-block">{{ activeCase.referenceAnswer }}</p>
        </section>
        <section class="detail-section">
          <h3>预期证据</h3>
          <p>
            文件：{{
              [...activeCase.expectedFileNames, ...activeCase.expectedFileMd5s].join('、') || '未标注，不计入检索命中率'
            }}
          </p>
          <NSpace>
            <NTag v-for="text in activeCase.expectedEvidence" :key="text" size="small">{{ text }}</NTag>
          </NSpace>
        </section>
        <section v-if="activeCase.stages?.comparison" class="detail-section">
          <h3>同一轮排序对比</h3>
          <p>{{ stageLabels[activeCase.stages.comparison.change] || activeCase.stages.comparison.change }}</p>
          <p>
            候选 {{ activeCase.stages.candidateCount }} 条； RRF 首个正确证据排名
            {{ activeCase.stages.comparison.rrfFirstRank ?? '前 K 未命中' }}； 精排首个正确证据排名
            {{ activeCase.stages.comparison.rerankFirstRank ?? '前 K 未命中或未精排' }}； 阈值过滤前 K 中
            {{ activeCase.stages.thresholdDroppedInTopK }} 条。
          </p>
          <details>
            <summary>RRF 全部候选（按原排名）</summary>
            <div
              v-for="chunk in activeCase.stages.candidates"
              :key="`${chunk.fileMd5}-${chunk.chunkId}`"
              class="evidence-block"
            >
              <strong>#{{ chunk.rank }} {{ chunk.fileName || chunk.fileMd5 }} · 分块 {{ chunk.chunkId }}</strong>
              <p>RRF 分 {{ chunk.score?.toFixed(4) }} · {{ chunk.relevant ? '匹配标注' : '未匹配标注' }}</p>
              <p class="text-block">{{ chunk.textContent }}</p>
            </div>
          </details>
          <details>
            <summary>精排全部候选（过滤前）</summary>
            <div
              v-for="chunk in activeCase.stages.comparison.reranked"
              :key="`${chunk.fileMd5}-${chunk.chunkId}`"
              class="evidence-block"
            >
              <strong>#{{ chunk.rank }} {{ chunk.fileName || chunk.fileMd5 }} · 分块 {{ chunk.chunkId }}</strong>
              <p>相关性 {{ chunk.score?.toFixed(4) }} · {{ chunk.relevant ? '匹配标注' : '未匹配标注' }}</p>
              <p class="text-block">{{ chunk.textContent }}</p>
            </div>
          </details>
        </section>
        <section class="detail-section">
          <h3>实际检索结果 · {{ activeCase.retrieved.length }} 条</h3>
          <NEmpty v-if="!activeCase.retrieved.length" description="本题未返回检索结果" />
          <div v-for="chunk in activeCase.retrieved" :key="`${chunk.fileMd5}-${chunk.chunkId}`" class="evidence-block">
            <div class="evidence-title">
              <strong>#{{ chunk.rank }} {{ chunk.fileName || chunk.fileMd5 }}</strong>
              <NTag v-if="activeCase.retrievalHit !== null" size="small" :type="chunk.relevant ? 'success' : 'default'">
                {{ chunk.relevant ? '匹配标注' : '未匹配标注' }}
              </NTag>
            </div>
            <p class="muted">
              分块 {{ chunk.chunkId }} · {{ chunk.pageNumber ? `第 ${chunk.pageNumber} 页 · ` : '' }}分数
              {{ chunk.score?.toFixed(4) ?? '—' }}
            </p>
            <p class="text-block">{{ chunk.textContent ?? '旧版报告未保存证据原文，请重新运行评测以记录。' }}</p>
          </div>
        </section>
        <section class="detail-section">
          <h3>系统回答</h3>
          <p class="text-block">{{ activeCase.answer || '本题没有生成回答（仅检索模式或生成失败）。' }}</p>
          <h3>LLM 评委</h3>
          <p>
            {{ activeCase.judgeCorrect === null ? '未评分' : activeCase.judgeCorrect ? '判定正确' : '判定错误' }} ·
            {{ activeCase.judgeScore === null ? '—' : `${activeCase.judgeScore}/5` }}
          </p>
          <p class="text-block">{{ activeCase.judgeReason || '—' }}</p>
          <NAlert v-if="activeCase.error" type="error">{{ activeCase.error }}</NAlert>
        </section>
        <section v-if="!selected?.retrievalOnly" class="detail-section">
          <h3>人工复核</h3>
          <p class="muted">根据参考答案核对回答，人工结论单独保存，不覆盖评委评分。</p>
          <NForm label-placement="top">
            <NFormItem label="回答是否正确" required>
              <NRadioGroup v-model:value="review.correct">
                <NRadio :value="true">正确</NRadio>
                <NRadio :value="false">错误</NRadio>
              </NRadioGroup>
            </NFormItem>
            <NFormItem label="评分（可选）">
              <NInputNumber v-model:value="review.score" :min="1" :max="5" :precision="0" clearable />
            </NFormItem>
            <NFormItem label="备注">
              <NInput
                v-model:value="review.note"
                type="textarea"
                :maxlength="500"
                show-count
                placeholder="例如：缺少适用条件，核心结论不完整"
              />
            </NFormItem>
          </NForm>
          <NButton type="primary" :loading="saving" :disabled="review.correct === null" @click="saveReview">
            保存人工复核
          </NButton>
        </section>
      </NDrawerContent>
    </NDrawer>
  </div>
</template>

<style scoped>
.eval-page {
  padding: 22px;
  color: var(--n-text-color);
}
.page-heading,
.result-heading,
.run-title,
.case-toolbar,
.evidence-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}
h1 {
  font-size: 27px;
  font-weight: 700;
  margin: 5px 0;
}
h2 {
  font-size: 19px;
  font-weight: 600;
  margin: 0;
}
h3 {
  font-size: 15px;
  font-weight: 600;
  margin: 16px 0 10px;
}
p {
  line-height: 1.7;
}
.page-heading p,
.result-heading p,
.muted {
  color: #788597;
  font-size: 13px;
}
.eyebrow {
  font-size: 10px;
  letter-spacing: 2px;
  color: #66958c;
  font-weight: 700;
}
.workflow {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 20px;
  margin: 22px 0;
  color: #788597;
  font-size: 12px;
}
.workflow b {
  color: #438a7b;
  margin-right: 7px;
}
.eval-layout {
  display: grid;
  grid-template-columns: 280px minmax(0, 1fr);
  gap: 18px;
  align-items: start;
}
.history-card,
.result-area > *,
.result-area .n-card {
  border-radius: 12px;
}
.result-area {
  display: grid;
  gap: 18px;
  min-width: 0;
}
.run-item {
  display: block;
  width: 100%;
  text-align: left;
  padding: 14px;
  border: 1px solid transparent;
  border-radius: 8px;
  margin-bottom: 10px;
  background: rgba(128, 144, 160, 0.05);
  cursor: pointer;
  color: inherit;
}
.run-item.active {
  border-color: #74b5a6;
  background: rgba(65, 160, 135, 0.09);
}
.run-item:hover {
  background: rgba(65, 160, 135, 0.07);
}
.run-item strong {
  overflow-wrap: anywhere;
}
.run-item p,
.run-item small {
  font-size: 11px;
  color: #788597;
}
.run-item p {
  margin: 8px 0;
}
.run-metrics {
  display: flex;
  gap: 16px;
  font-size: 12px;
  margin-bottom: 8px;
}
.metric-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 18px;
  margin: 22px 0;
}
.metric {
  border-radius: 10px;
  padding: 22px;
  background: rgba(74, 145, 126, 0.06);
  border: 1px solid rgba(74, 145, 126, 0.15);
}
.metric.secondary {
  background: rgba(86, 127, 181, 0.05);
  border-color: rgba(86, 127, 181, 0.14);
}
.metric > span,
.metric > small {
  display: block;
  font-size: 12px;
}
.metric > strong {
  display: block;
  font-size: 38px;
  font-weight: 650;
  margin: 10px 0;
  font-variant-numeric: tabular-nums;
}
.metric p {
  font-size: 12px;
  color: #788597;
  margin: 10px 0 0;
}
.secondary-metrics {
  display: flex;
  flex-wrap: wrap;
  gap: 24px;
  color: #788597;
  font-size: 12px;
}
.secondary-metrics strong {
  margin-left: 6px;
  color: #4c6576;
}
.case-toolbar {
  margin-bottom: 18px;
}
.case-toolbar .n-input {
  max-width: 380px;
}
.case-toolbar .n-select {
  width: 180px;
  flex-shrink: 0;
}
.welcome-card {
  text-align: center;
  padding: 35px 15px;
}
.welcome-icon {
  font-size: 64px;
  color: #63a994;
  line-height: 1.5;
}
.welcome-card > p {
  color: #788597;
}
.source-note {
  text-align: left;
  padding: 20px;
  border-radius: 8px;
  background: rgba(128, 144, 160, 0.06);
  max-width: 600px;
  margin: 25px auto;
  font-size: 13px;
}
.source-note code {
  overflow-wrap: anywhere;
}
.upload-box {
  width: 100%;
  border: 1px dashed #a4b9b3;
  border-radius: 8px;
  padding: 22px;
  text-align: center;
}
.upload-box input {
  display: none;
}
.upload-box p {
  color: #788597;
  font-size: 12px;
}
.dataset-preview {
  padding: 15px;
  border-radius: 8px;
  background: rgba(65, 160, 135, 0.07);
  margin-bottom: 20px;
  font-size: 12px;
  overflow-wrap: anywhere;
}
.text-block {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font-size: 13px;
  line-height: 1.8;
  font-family: inherit;
}
.detail-section {
  border-bottom: 1px solid rgba(128, 144, 160, 0.16);
  padding-bottom: 22px;
}
.evidence-block {
  padding: 16px;
  background: rgba(128, 144, 160, 0.06);
  border-radius: 8px;
  margin: 12px 0;
}
.evidence-title {
  align-items: flex-start;
  font-size: 13px;
}
.review-summary {
  font-size: 12px;
  color: #438a7b;
  margin-bottom: 16px;
}
:global(.create-modal) {
  width: min(660px, calc(100vw - 32px));
}
:global(.case-drawer) {
  max-width: 100vw;
}
@media (max-width: 1000px) {
  .eval-layout {
    grid-template-columns: 1fr;
  }
  .history-card {
    max-height: 320px;
    overflow: auto;
  }
}
@media (max-width: 600px) {
  .eval-page {
    padding: 12px;
  }
  .page-heading,
  .result-heading {
    align-items: flex-start;
    flex-direction: column;
  }
  .metric-grid {
    grid-template-columns: 1fr;
  }
  .workflow {
    gap: 10px;
  }
  .case-toolbar {
    flex-direction: column;
    align-items: stretch;
  }
  .case-toolbar .n-select {
    width: 100%;
  }
}
</style>
