<script setup lang="ts">
import { ref, watch } from 'vue';
import { request } from '@/service/request';

const visible = defineModel<boolean>('visible', { default: false });
const emit = defineEmits<{ resolved: [] }>();
interface Conflict {
  id: number;
  newFileName: string;
  existingFileName: string;
  newSnippet: string;
  existingSnippet: string;
  newValues: string;
  existingValues: string;
  similarity: number;
  maskedSimilarity: number;
  llmReason: string | null;
  status: string;
}
const records = ref<Conflict[]>([]);
const total = ref(0);
const page = ref(1);
const loading = ref(false);
const resolving = ref(false);
const status = ref('PENDING');
const options = [
  { label: '待处理', value: 'PENDING' },
  { label: '已保留新文档', value: 'KEEP_NEW' },
  { label: '已保留旧文档', value: 'KEEP_EXISTING' },
  { label: '已忽略', value: 'IGNORED' },
  { label: '已失效', value: 'OBSOLETE' }
];
async function load() {
  loading.value = true;
  try {
    const { data, error } = await request<{ records: Conflict[]; total: number }>({
      url: '/admin/document-conflicts',
      params: { status: status.value, page: page.value, size: 10 }
    });
    if (!error && data) {
      records.value = data.records;
      total.value = data.total;
    }
  } finally {
    loading.value = false;
  }
}
async function resolve(id: number, action: string) {
  resolving.value = true;
  try {
    const { error } = await request({
      url: `/admin/document-conflicts/${id}/resolve`,
      method: 'post',
      data: { action }
    });
    if (!error) {
      window.$message?.success('处理成功');
      emit('resolved');
      await load();
    }
  } finally {
    resolving.value = false;
  }
}
watch(visible, show => {
  if (show) load();
});
watch(status, () => {
  page.value = 1;
  load();
});
</script>

<template>
  <NModal v-model:show="visible" preset="card" title="文档冲突管理" class="max-w-95vw w-1000px">
    <NAlert type="warning" class="mb-16px">
      以下为规则筛选的疑似冲突。保留一篇会删除另一整篇文档及其索引，请核对内容后处理。
    </NAlert>
    <NSpace class="mb-16px">
      <NSelect v-model:value="status" :options="options" class="w-180px" />
      <NButton :loading="loading" @click="load">刷新</NButton>
    </NSpace>
    <NSpin :show="loading">
      <div class="max-h-60vh overflow-auto">
        <NEmpty v-if="!records.length" description="暂无冲突记录" />
        <NCard v-for="item in records" :key="item.id" class="mb-12px" size="small" :title="`疑似冲突 #${item.id}`">
          <div class="grid grid-cols-2 gap-16px lt-sm:grid-cols-1">
            <div>
              <strong>新文档：{{ item.newFileName }}</strong>
              <p class="whitespace-pre-wrap break-words">{{ item.newSnippet }}</p>
              <p>差异值：{{ item.newValues || '无' }}</p>
            </div>
            <div>
              <strong>已有文档：{{ item.existingFileName }}</strong>
              <p class="whitespace-pre-wrap break-words">{{ item.existingSnippet }}</p>
              <p>差异值：{{ item.existingValues || '无' }}</p>
            </div>
          </div>
          <p>向量相似度 {{ item.similarity.toFixed(3) }} · 骨架相似度 {{ item.maskedSimilarity.toFixed(3) }}</p>
          <p v-if="item.llmReason">模型判断：{{ item.llmReason }}</p>
          <NSpace v-if="item.status === 'PENDING'" class="mt-12px">
            <NPopconfirm @positive-click="resolve(item.id, 'KEEP_NEW')">
              <template #trigger><NButton type="warning" :disabled="resolving">保留新文档</NButton></template>
              确认删除整篇已有文档「{{ item.existingFileName }}」？
            </NPopconfirm>
            <NPopconfirm @positive-click="resolve(item.id, 'KEEP_EXISTING')">
              <template #trigger><NButton type="warning" :disabled="resolving">保留已有文档</NButton></template>
              确认删除整篇新文档「{{ item.newFileName }}」？
            </NPopconfirm>
            <NButton :disabled="resolving" @click="resolve(item.id, 'IGNORED')">不是冲突，两篇都保留</NButton>
          </NSpace>
        </NCard>
      </div>
    </NSpin>
    <NPagination v-model:page="page" class="mt-16px" :item-count="total" :page-size="10" @update:page="load" />
  </NModal>
</template>
