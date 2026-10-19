<script setup lang="ts">
import { uploadAccept } from '@/constants/common';

defineOptions({
  name: 'UploadDialog'
});

const loading = ref(false);
const visible = defineModel<boolean>('visible', { default: false });
const singleOrgOnly = ref(false);

const authStore = useAuthStore();

const { formRef, validate, restoreValidation } = useNaiveForm();
const { defaultRequiredRule } = useFormRules();

const model = ref<Api.KnowledgeBase.Form>(createDefaultModel());

function createDefaultModel(): Api.KnowledgeBase.Form {
  return {
    orgTag: null,
    orgTagName: '',
    uploadMaxSizeBytes: null,
    uploadMaxSizeMb: null,
    isPublic: false,
    fileList: []
  };
}

const rules = ref<FormRules>({
  orgTag: defaultRequiredRule,
  isPublic: defaultRequiredRule,
  fileList: defaultRequiredRule
});

const fileSizeLimitError = computed(() => {
  if (authStore.isAdmin) return '';
  const limit = model.value.uploadMaxSizeBytes;
  if (!limit) return '';
  const oversizedFiles = (model.value.fileList ?? []).filter(item => item.file && item.file.size > limit);
  if (oversizedFiles.length === 0) return '';

  return `当前组织限制非管理员每个文件不超过 ${model.value.uploadMaxSizeMb} MB，${oversizedFiles.length} 个文件超限：${oversizedFiles.map(item => item.name).join('、')}`;
});

const submitDisabled = computed(() => loading.value || Boolean(fileSizeLimitError.value));

function close() {
  visible.value = false;
}

const store = useKnowledgeBaseStore();
async function handleSubmit() {
  if (loading.value) return;
  await validate();
  if (loading.value || fileSizeLimitError.value) return;

  loading.value = true;
  try {
    const { failedIds } = await store.enqueueUpload(model.value);
    if (failedIds.length > 0) {
      model.value.fileList = model.value.fileList?.filter(item => failedIds.includes(item.id));
      return;
    }
    close();
  } finally {
    loading.value = false;
  }
}

async function presetSingleOrgForUser() {
  singleOrgOnly.value = false;
  const { error, data } = await request<Api.OrgTag.Mine>({ url: '/users/org-tags' });
  if (error || !visible.value) return;

  const orgTagDetails = data.orgTagDetails || [];
  if (orgTagDetails.length !== 1) return;

  const singleOrg = orgTagDetails[0];
  model.value.orgTag = singleOrg.tagId;
  onUpdate(singleOrg);
  singleOrgOnly.value = true;
}

watch(visible, () => {
  if (visible.value) {
    model.value = createDefaultModel();
    singleOrgOnly.value = false;
    if (!authStore.isAdmin) {
      presetSingleOrgForUser();
    }
    restoreValidation();
  }
});

function onUpdate(option: unknown) {
  if (option) {
    const selected = option as Api.OrgTag.Item;
    model.value.orgTagName = selected.name;
    model.value.uploadMaxSizeBytes = selected.uploadMaxSizeBytes;
    model.value.uploadMaxSizeMb = selected.uploadMaxSizeMb;
    return;
  }
  model.value.orgTagName = '';
  model.value.uploadMaxSizeBytes = null;
  model.value.uploadMaxSizeMb = null;
}
</script>

<template>
  <NModal
    v-model:show="visible"
    preset="dialog"
    title="文件上传"
    :show-icon="false"
    :mask-closable="false"
    :close-on-esc="!loading"
    :closable="!loading"
    class="w-500px!"
    @positive-click="handleSubmit"
  >
    <NForm
      ref="formRef"
      :model="model"
      :rules="rules"
      :disabled="loading"
      label-placement="left"
      :label-width="100"
      mt-10
    >
      <NFormItem v-if="authStore.isAdmin" label="组织标签" path="orgTag">
        <OrgTagCascader v-model:value="model.orgTag" @change="onUpdate" />
      </NFormItem>
      <NFormItem v-else label="组织标签" path="orgTag">
        <TheSelect
          v-model:value="model.orgTag"
          url="/users/org-tags"
          key-field="orgTagDetails"
          label-field="name"
          value-field="tagId"
          :disabled="singleOrgOnly"
          @change="onUpdate"
        />
      </NFormItem>

      <NFormItem label="是否公开" path="isPublic">
        <NRadioGroup v-model:value="model.isPublic" name="radiogroup">
          <NSpace :size="16">
            <NRadio :value="true">公开</NRadio>
            <NRadio :value="false">私有</NRadio>
          </NSpace>
        </NRadioGroup>
      </NFormItem>
      <NFormItem label="上传文件" path="fileList">
        <div class="w-full">
          <NUpload
            v-model:file-list="model.fileList"
            :accept="uploadAccept"
            multiple
            :disabled="loading"
            :default-upload="false"
          >
            <NButton :disabled="loading">选择文件（可多选）</NButton>
          </NUpload>
          <div v-if="fileSizeLimitError" class="mt-8px text-12px text-#ef4444">
            {{ fileSizeLimitError }}
          </div>
          <div v-else-if="!authStore.isAdmin && model.uploadMaxSizeMb" class="mt-8px text-12px text-#d97706">
            当前组织限制非管理员每个文件不超过 {{ model.uploadMaxSizeMb }} MB
          </div>
          <div class="mt-8px text-12px text-gray-500">
            已选择 {{ model.fileList?.length || 0 }} 个文件，统一使用当前组织标签和公开设置
          </div>
        </div>
      </NFormItem>
    </NForm>
    <template #action>
      <NSpace :size="16">
        <NButton :disabled="loading" @click="close">取消</NButton>
        <NButton type="primary" :loading="loading" :disabled="submitDisabled" @click="handleSubmit">开始上传</NButton>
      </NSpace>
    </template>
  </NModal>
</template>

<style scoped></style>
