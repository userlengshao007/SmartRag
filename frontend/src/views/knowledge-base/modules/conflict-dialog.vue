<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { request } from '@/service/request';

const visible = defineModel<boolean>('visible', { default: false });
const emit = defineEmits<{ resolved: [] }>();
interface Conflict {
  id: number;
  newFileName: string;
  existingFileName: string;
  newChunkId: number;
  existingChunkId: number;
  newSnippet: string;
  existingSnippet: string;
  newValues: string;
  existingValues: string;
  similarity: number;
  maskedSimilarity: number;
  llmReason: string | null;
  status: string;
  resolvedBy?: string;
  resolvedAt?: string;
  resolutionNote?: string;
}
type Action = 'KEEP_NEW' | 'KEEP_EXISTING' | 'IGNORED';
const records = ref<Conflict[]>([]);
const total = ref(0);
const page = ref(1);
const loading = ref(false);
const resolving = ref(false);
const failed = ref(false);
const selectedId = ref<number>();
const notes = ref<Record<number, string>>({});
const confirmation = ref<{ item: Conflict; action: Action; note: string } | null>(null);
const status = ref('PENDING');
let requestVersion = 0;
const options = [
  { label: '待审核', value: 'PENDING' },
  { label: '已保留新文档', value: 'KEEP_NEW' },
  { label: '已保留旧文档', value: 'KEEP_EXISTING' },
  { label: '两篇均保留', value: 'IGNORED' },
  { label: '已失效', value: 'OBSOLETE' }
];
const selected = computed(() => records.value.find(item => item.id === selectedId.value));
const statusLabel = computed(() => options.find(item => item.value === status.value)?.label || '记录');
const deletedName = computed(() => {
  const value = confirmation.value;
  if (!value) return '';
  return value.action === 'KEEP_NEW' ? value.item.existingFileName : value.item.newFileName;
});
const comparison = computed(() => {
  const a = selected.value?.existingSnippet || '';
  const b = selected.value?.newSnippet || '';
  let start = 0;
  while (start < Math.min(a.length, b.length) && a[start] === b[start]) start += 1;
  let end = 0;
  while (end < Math.min(a.length, b.length) - start && a[a.length - end - 1] === b[b.length - end - 1]) end += 1;
  [start, end] = expandNumericRange(a, b, { start, end });
  const parts = (text: string) => ({
    before: text.slice(0, start),
    changed: text.slice(start, text.length - end),
    after: end ? text.slice(-end) : ''
  });
  return { existing: parts(a), fresh: parts(b) };
});
function expandNumericRange(a: string, b: string, range: { start: number; end: number }) {
  let { start, end } = range;
  while (start > 0 && /\d/.test(a[start - 1]) && (/\d/.test(a[start] || '') || /\d/.test(b[start] || ''))) start -= 1;
  while (
    end > 0 &&
    /\d/.test(a[a.length - end]) &&
    (/\d/.test(a[a.length - end - 1] || '') || /\d/.test(b[b.length - end - 1] || ''))
  )
    end -= 1;
  return [start, end];
}
function values(raw: string) {
  return (raw || '')
    .split(', ')
    .filter(Boolean)
    .map(value =>
      value
        .replace(/^number:/, '数值 ')
        .replace(/^version:/, '版本 ')
        .replace(/^date:/, '日期 ')
    );
}
function score(value: number) {
  return Number.isFinite(value) ? value.toFixed(3) : '—';
}
function dateLabel(value?: string) {
  if (!value) return '—';
  const date = new Date(value);
  return Number.isNaN(date.getTime())
    ? value
    : new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(date);
}
async function load() {
  requestVersion += 1;
  const version = requestVersion;
  loading.value = true;
  failed.value = false;
  try {
    const { data, error } = await request<{ records: Conflict[]; total: number }>({
      url: '/admin/document-conflicts',
      params: { status: status.value, page: page.value, size: 10 }
    });
    if (version !== requestVersion) return;
    if (error || !data) {
      failed.value = true;
      return;
    }
    total.value = data.total;
    const lastPage = Math.max(1, Math.ceil(data.total / 10));
    if (page.value > lastPage) {
      page.value = lastPage;
      await load();
      return;
    }
    records.value = data.records;
    if (!records.value.some(item => item.id === selectedId.value)) selectedId.value = records.value[0]?.id;
  } catch {
    if (version === requestVersion) failed.value = true;
  } finally {
    if (version === requestVersion) loading.value = false;
  }
}
function changeStatus(value: string) {
  status.value = value;
  page.value = 1;
  selectedId.value = undefined;
  load();
}
function prepare(action: Action) {
  if (!selected.value) return;
  confirmation.value = { item: selected.value, action, note: notes.value[selected.value.id] || '' };
}
async function resolve() {
  const value = confirmation.value;
  if (!value || resolving.value) return false;
  resolving.value = true;
  try {
    const { error } = await request({
      url: `/admin/document-conflicts/${value.item.id}/resolve`,
      method: 'post',
      data: { action: value.action, note: value.note }
    });
    if (!error) {
      window.$message?.success(value.action === 'IGNORED' ? '已确认两篇均保留' : '已处理，另一整篇文档已删除');
      notes.value = Object.fromEntries(Object.entries(notes.value).filter(([id]) => id !== String(value.item.id)));
      confirmation.value = null;
      emit('resolved');
      await load();
    }
  } catch {
    window.$message?.error('处理失败，请重试');
  } finally {
    resolving.value = false;
  }
  return false;
}
watch(
  visible,
  show => {
    if (show) {
      page.value = 1;
      load();
    } else {
      requestVersion += 1;
      loading.value = false;
      confirmation.value = null;
    }
  },
  { immediate: true }
);
</script>

<template>
  <NModal
    v-model:show="visible"
    preset="card"
    title="文档冲突审核"
    class="max-w-95vw w-1200px"
    :mask-closable="!resolving"
    :close-on-esc="!resolving"
    :closable="!resolving"
  >
    <NEl class="conflict-review">
      <p class="review-intro">对照原文摘要与差异值，确认资料是否适用于同一对象和场景。</p>
      <div class="review-toolbar">
        <NSelect
          :value="status"
          :options="options"
          aria-label="按审核状态筛选"
          class="w-190px"
          :disabled="resolving"
          @update:value="changeStatus"
        />
        <NText depth="3" class="review-count" aria-live="polite">{{ statusLabel }} · {{ total }} 条</NText>
        <NButton :loading="loading" :disabled="resolving" @click="load">刷新记录</NButton>
      </div>
      <NSpin :show="loading">
        <div class="review-body" :aria-busy="loading">
          <div v-if="failed" class="review-empty" role="alert">
            <NText strong>未能加载冲突记录</NText>
            <NText depth="3">请检查网络连接后重试。</NText>
            <NButton @click="load">重新加载</NButton>
          </div>
          <div v-else-if="!records.length" class="review-empty" role="status">
            <NEmpty :description="loading ? '正在加载记录…' : `暂无${statusLabel}记录`" />
            <NText v-if="!loading" depth="3">冲突检测在文档向量化完成后异步执行，可稍后刷新查看。</NText>
          </div>
          <div v-else class="review-workspace">
            <nav class="review-list" aria-label="冲突记录">
              <button
                v-for="item in records"
                :key="item.id"
                type="button"
                class="record-button"
                :class="{ 'record-button--active': selectedId === item.id }"
                :aria-current="selectedId === item.id ? 'true' : undefined"
                :disabled="resolving || loading"
                @click="selectedId = item.id"
              >
                <span class="record-number">疑似冲突 #{{ item.id }}</span>
                <strong>{{ item.newFileName || '未命名新文档' }}</strong>
                <span class="record-source">对照 {{ item.existingFileName || '未命名已有文档' }}</span>
                <span class="record-values">{{ values(item.newValues).join(' / ') || '查看差异详情' }}</span>
              </button>
            </nav>
            <section v-if="selected" class="review-detail" aria-label="冲突对照详情">
              <header class="detail-heading">
                <h3>
                  内容对照
                  <span>#{{ selected.id }}</span>
                </h3>
                <NTag size="small" :type="selected.status === 'PENDING' ? 'warning' : 'default'" :bordered="false">
                  {{ statusLabel }}
                </NTag>
              </header>
              <div class="comparison-grid">
                <article class="document-pane">
                  <div class="document-label">
                    已有文档
                    <span>分块 {{ selected.existingChunkId }}</span>
                  </div>
                  <h4>{{ selected.existingFileName || '未命名文档' }}</h4>
                  <p class="document-snippet">
                    <span>{{ comparison.existing.before }}</span>
                    <mark>{{ comparison.existing.changed }}</mark>
                    <span>{{ comparison.existing.after }}</span>
                  </p>
                  <div class="value-tags" aria-label="已有文档独有的关键值">
                    <NTag v-for="value in values(selected.existingValues)" :key="value" size="small" :bordered="false">
                      {{ value }}
                    </NTag>
                    <NText v-if="!selected.existingValues" depth="3">无独有关键值</NText>
                  </div>
                </article>
                <article class="document-pane document-pane--new">
                  <div class="document-label">
                    新上传文档
                    <span>分块 {{ selected.newChunkId }}</span>
                  </div>
                  <h4>{{ selected.newFileName || '未命名文档' }}</h4>
                  <p class="document-snippet">
                    <span>{{ comparison.fresh.before }}</span>
                    <mark>{{ comparison.fresh.changed }}</mark>
                    <span>{{ comparison.fresh.after }}</span>
                  </p>
                  <div class="value-tags" aria-label="新文档独有的关键值">
                    <NTag
                      v-for="value in values(selected.newValues)"
                      :key="value"
                      size="small"
                      type="success"
                      :bordered="false"
                    >
                      {{ value }}
                    </NTag>
                    <NText v-if="!selected.newValues" depth="3">无独有关键值</NText>
                  </div>
                </article>
              </div>
              <p class="comparison-caption">
                高亮显示摘要中首尾相同部分之间的差异区域；摘要可能截断，请结合完整文档审核。新上传不代表内容更新。
              </p>
              <details class="detection-details">
                <summary>查看检测依据</summary>
                <div class="score-row">
                  <span>
                    语义相似度
                    <strong>{{ score(selected.similarity) }}</strong>
                  </span>
                  <span>
                    屏蔽关键值后的文本相似度
                    <strong>{{ score(selected.maskedSimilarity) }}</strong>
                  </span>
                </div>
                <p>相似度用于筛选近似内容，不代表冲突概率。</p>
                <p v-if="selected.llmReason">模型复核：{{ selected.llmReason }}</p>
                <p v-else>本条记录没有模型复核意见。</p>
              </details>
              <div v-if="selected.status === 'PENDING'" class="review-actions">
                <label :for="`review-note-${selected.id}`" class="note-label">
                  审核备注
                  <NText depth="3">（选填）</NText>
                </label>
                <NInput
                  v-model:value="notes[selected.id]"
                  :input-props="{ id: `review-note-${selected.id}`, name: 'review-note', 'aria-label': '审核备注' }"
                  type="textarea"
                  :maxlength="480"
                  show-count
                  :autosize="{ minRows: 2, maxRows: 4 }"
                  placeholder="例如：两篇分别适用于测试环境和生产环境…"
                  :disabled="resolving"
                />
                <NAlert type="warning" :show-icon="false" class="mt-16px">
                  选择保留一篇，将删除另一整篇文档及其索引，不仅是当前分块。
                </NAlert>
              </div>
              <div v-else class="resolution-info">
                <p>处理人：{{ selected.resolvedBy || '—' }} · 处理时间：{{ dateLabel(selected.resolvedAt) }}</p>
                <p>审核说明：{{ selected.resolutionNote || '未填写' }}</p>
              </div>
            </section>
            <div v-if="selected?.status === 'PENDING'" class="action-buttons">
              <NButton :disabled="resolving || loading" @click="prepare('IGNORED')">两篇均保留</NButton>
              <NButton type="error" secondary :disabled="resolving || loading" @click="prepare('KEEP_EXISTING')">
                保留已有，删除新文档
              </NButton>
              <NButton type="error" secondary :disabled="resolving || loading" @click="prepare('KEEP_NEW')">
                保留新文档，删除已有
              </NButton>
            </div>
          </div>
        </div>
      </NSpin>
      <div class="review-pagination">
        <NPagination
          v-model:page="page"
          :item-count="total"
          :page-size="10"
          :page-slot="5"
          :disabled="resolving || loading"
          @update:page="load"
        />
      </div>
    </NEl>
  </NModal>
  <NModal
    :show="Boolean(confirmation)"
    preset="dialog"
    :type="confirmation?.action === 'IGNORED' ? 'info' : 'error'"
    :title="confirmation?.action === 'IGNORED' ? '确认两篇均保留' : '确认删除整篇文档'"
    :positive-text="confirmation?.action === 'IGNORED' ? '确认保留两篇' : '确认删除整篇文档'"
    negative-text="返回核对"
    :loading="resolving"
    :mask-closable="!resolving"
    :close-on-esc="!resolving"
    :closable="!resolving"
    :negative-button-props="{ disabled: resolving }"
    @positive-click="resolve"
    @negative-click="confirmation = null"
    @update:show="
      show => {
        if (!show && !resolving) confirmation = null;
      }
    "
  >
    <p v-if="confirmation?.action === 'IGNORED'">此记录将标记为两篇均保留，两篇文档继续参与检索。</p>
    <template v-else>
      <p class="break-words">即将删除「{{ deletedName }}」的整篇文档及其索引。</p>
      <p>其他待处理记录若涉及该文档，也会标记为失效。请确认已经核对完整内容。</p>
    </template>
  </NModal>
</template>

<style scoped>
.conflict-review {
  color: var(--text-color);
}
.review-intro {
  margin: 0 0 20px;
  color: var(--text-color-2);
  line-height: 1.7;
}
.review-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 18px;
  flex-wrap: wrap;
}
.review-count {
  flex: 1;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}
.review-body {
  min-height: 320px;
}
.review-workspace {
  display: grid;
  grid-template-columns: 248px minmax(0, 1fr);
  border: 1px solid var(--border-color);
  border-radius: 12px;
  overflow: hidden;
}
.review-list {
  padding: 8px;
  border-right: 1px solid var(--border-color);
  background: var(--action-color);
  max-height: 52vh;
  overflow-y: auto;
  overscroll-behavior: contain;
}
.record-button {
  display: flex;
  width: 100%;
  flex-direction: column;
  gap: 7px;
  padding: 14px 12px;
  border: 1px solid transparent;
  border-radius: 8px;
  background: transparent;
  color: inherit;
  text-align: left;
  cursor: pointer;
  overflow-wrap: anywhere;
}
.record-button + .record-button {
  margin-top: 4px;
}
.record-button:hover {
  background: var(--hover-color);
}
.record-button:focus-visible {
  outline: 2px solid var(--primary-color);
  outline-offset: -2px;
}
.record-button:disabled {
  cursor: default;
}
.record-button--active {
  background: var(--card-color);
  border-color: var(--primary-color);
}
.record-number,
.record-source {
  color: var(--text-color-3);
  font-size: 12px;
}
.record-values {
  color: var(--primary-color);
  font-size: 12px;
}
.review-detail {
  min-width: 0;
  padding: 24px;
  max-height: 52vh;
  overflow-y: auto;
  overscroll-behavior: contain;
}
.detail-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 18px;
}
.detail-heading h3 {
  margin: 0;
  font-size: 17px;
}
.detail-heading h3 span {
  margin-left: 6px;
  font-size: 13px;
  color: var(--text-color-3);
  font-weight: 400;
}
.comparison-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  border: 1px solid var(--border-color);
  border-radius: 8px;
  overflow: hidden;
}
.document-pane {
  min-width: 0;
  padding: 18px;
}
.document-pane--new {
  border-left: 1px solid var(--border-color);
}
.document-label {
  display: flex;
  gap: 8px;
  justify-content: space-between;
  color: var(--text-color-2);
  font-size: 12px;
}
.document-label span {
  color: var(--text-color-3);
}
.document-pane h4 {
  margin: 12px 0 16px;
  font-size: 14px;
  overflow-wrap: anywhere;
}
.document-snippet {
  margin: 0 0 20px;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  line-height: 1.95;
}
.document-snippet mark {
  background: color-mix(in srgb, var(--warning-color) 20%, transparent);
  color: inherit;
  border-radius: 2px;
}
.document-pane--new mark {
  background: color-mix(in srgb, var(--primary-color) 18%, transparent);
}
.value-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.value-tags :deep(.n-tag) {
  height: auto;
  white-space: normal;
  overflow-wrap: anywhere;
}
.comparison-caption {
  font-size: 12px;
  color: var(--text-color-3);
  line-height: 1.7;
  margin: 12px 0 20px;
}
.detection-details {
  padding: 14px 0;
  border-top: 1px solid var(--border-color);
  border-bottom: 1px solid var(--border-color);
  font-size: 12px;
  color: var(--text-color-2);
}
.detection-details summary {
  cursor: pointer;
  font-size: 13px;
}
.detection-details summary:focus-visible {
  outline: 2px solid var(--primary-color);
  outline-offset: 4px;
}
.detection-details p {
  margin: 10px 0 0;
  line-height: 1.7;
  overflow-wrap: anywhere;
}
.score-row {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  margin-top: 14px;
}
.score-row strong {
  margin-left: 6px;
  font-variant-numeric: tabular-nums;
}
.review-actions {
  margin-top: 20px;
}
.note-label {
  display: block;
  margin-bottom: 8px;
  font-size: 13px;
}
.action-buttons {
  grid-column: 1 / -1;
  padding: 16px;
  border-top: 1px solid var(--border-color);
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  flex-wrap: wrap;
}
.resolution-info {
  padding-top: 14px;
  color: var(--text-color-2);
  font-size: 12px;
  overflow-wrap: anywhere;
}
.review-empty {
  display: flex;
  min-height: 320px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 16px;
  text-align: center;
  padding: 24px;
}
.review-pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 18px;
}
@media (max-width: 760px) {
  .conflict-review {
    max-height: calc(90dvh - 90px);
    overflow-y: auto;
    overscroll-behavior: contain;
  }
  .review-toolbar :deep(.n-select) {
    flex: 1;
    min-width: 0;
    width: auto;
  }
  .review-count {
    order: 3;
    flex-basis: 100%;
  }

  .review-workspace {
    grid-template-columns: minmax(0, 1fr);
  }
  .review-list {
    display: flex;
    gap: 8px;
    max-height: 160px;
    border-right: 0;
    border-bottom: 1px solid var(--border-color);
  }
  .record-button {
    flex: 0 0 210px;
  }
  .record-button + .record-button {
    margin-top: 0;
  }
  .review-detail {
    padding: 16px;
    max-height: none;
  }
  .comparison-grid {
    grid-template-columns: minmax(0, 1fr);
  }
  .document-pane--new {
    border-left: 0;
    border-top: 1px solid var(--border-color);
  }
  .action-buttons > * {
    width: 100%;
  }
  .review-pagination {
    justify-content: center;
  }
}
</style>
