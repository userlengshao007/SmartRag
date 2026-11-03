<script setup lang="ts">
import { computed } from 'vue';
import type { Component } from 'vue';
import { loginModuleRecord } from '@/constants/app';
import { useAppStore } from '@/store/modules/app';
import { useThemeStore } from '@/store/modules/theme';
import { $t } from '@/locales';
import PwdLogin from './modules/pwd-login.vue';
import CodeLogin from './modules/code-login.vue';
import Register from './modules/register.vue';
import ResetPwd from './modules/reset-pwd.vue';
import BindWechat from './modules/bind-wechat.vue';

interface Props {
  /** The login module */
  module?: UnionKey.LoginModule;
}

const props = defineProps<Props>();

const appStore = useAppStore();
const themeStore = useThemeStore();

interface LoginModule {
  label: string;
  component: Component;
}

const moduleMap: Record<UnionKey.LoginModule, LoginModule> = {
  'pwd-login': { label: loginModuleRecord['pwd-login'], component: PwdLogin },
  'code-login': { label: loginModuleRecord['code-login'], component: CodeLogin },
  register: { label: loginModuleRecord.register, component: Register },
  'reset-pwd': { label: loginModuleRecord['reset-pwd'], component: ResetPwd },
  'bind-wechat': { label: loginModuleRecord['bind-wechat'], component: BindWechat }
};

const activeModule = computed(() => moduleMap[props.module || 'pwd-login']);
const isRegisterModule = computed(() => (props.module || 'pwd-login') === 'register');
</script>

<template>
  <div class="login-page" :class="{ 'login-page--dark': themeStore.darkMode }">
    <div class="login-shell" :class="{ 'login-shell--register': isRegisterModule }">
      <aside class="brand-panel">
        <div class="brand-lockup">
          <SystemLogo class="brand-symbol text-44px" />
          <span>SmartRAG</span>
        </div>
        <div class="brand-story">
          <span class="brand-eyebrow">YOUR KNOWLEDGE, CONNECTED</span>
          <h1>
            让知识相连，
            <br />
            让答案发生。
          </h1>
          <p>
            从散落的文档到清晰的洞察，
            <br />
            构建属于你的智能知识空间。
          </p>
          <div class="knowledge-map" aria-hidden="true">
            <svg viewBox="0 0 340 180" fill="none">
              <path
                d="M45 95L133 40L214 100L295 45M45 95L140 150L214 100L290 155M133 40L140 150"
                stroke="currentColor"
                stroke-width="1.5"
              />
              <circle cx="45" cy="95" r="13" />
              <circle cx="133" cy="40" r="9" />
              <circle cx="140" cy="150" r="9" />
              <circle cx="295" cy="45" r="9" />
              <circle cx="290" cy="155" r="9" />
              <rect x="190" y="76" width="48" height="48" rx="15" />
              <path d="M204 100H224M214 90V110" stroke="#123D35" stroke-width="2" />
            </svg>
          </div>
          <div class="brand-tags">
            <span>知识检索</span>
            <span>智能问答</span>
            <span>来源可溯</span>
          </div>
        </div>
        <span class="brand-footnote">A SPACE FOR YOUR NEXT IDEA.</span>
      </aside>
      <section class="login-content">
        <header class="login-toolbar">
          <div class="mobile-brand">
            <SystemLogo class="text-32px text-primary" />
            <strong>SmartRAG</strong>
          </div>
          <ThemeSchemaSwitch
            :theme-schema="themeStore.themeScheme"
            :show-tooltip="false"
            @switch="themeStore.toggleThemeScheme"
          />
          <LangSwitch
            v-if="themeStore.header.multilingual.visible"
            :lang="appStore.locale"
            :lang-options="appStore.localeOptions"
            :show-tooltip="false"
            @change-lang="appStore.changeLocale"
          />
        </header>
        <main class="login-panel" :class="{ 'login-panel--register': isRegisterModule }">
          <span class="login-eyebrow">WELCOME TO SMARTRAG</span>
          <h2>{{ $t(activeModule.label) }}</h2>
          <p class="login-description">
            {{ isRegisterModule ? '创建账号，开启你的知识探索之旅。' : '欢迎回来，继续探索你的知识空间。' }}
          </p>
          <Transition :name="themeStore.page.animateMode" mode="out-in" appear>
            <component :is="activeModule.component" />
          </Transition>
        </main>
        <footer class="login-footer">SmartRAG · 让每一份知识都有回响</footer>
      </section>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  min-height: 100%;
  overflow: auto;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px 24px;
  background: #eef2ed;
  color: #20332f;
}
.login-shell {
  display: grid;
  grid-template-columns: 0.95fr 1.05fr;
  width: min(1040px, 100%);
  min-height: 680px;
  border: 1px solid #dce5dd;
  border-radius: 24px;
  overflow: hidden;
  background: #fff;
  box-shadow: 0 24px 80px #203c3010;
}
.login-shell--register {
  width: min(1320px, 100%);
  grid-template-columns: 0.7fr 1.3fr;
}
.brand-panel {
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  padding: 40px;
  background: #123d35;
  color: #e9f2e6;
}
.brand-lockup {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 25px;
  font-weight: 650;
  letter-spacing: -0.8px;
}
.brand-symbol {
  color: #3b7d6e;
}
.brand-story {
  padding: 48px 0 32px;
}
.brand-eyebrow,
.login-eyebrow,
.brand-footnote {
  font-size: 10px;
  letter-spacing: 2px;
  font-weight: 600;
}
.brand-eyebrow {
  color: #b5ce9b;
}
.brand-story h1 {
  font-size: 40px;
  line-height: 1.5;
  letter-spacing: -1px;
  margin: 20px 0;
  font-weight: 550;
}
.brand-story p {
  color: #b4c9c0;
  line-height: 1.9;
  font-size: 14px;
}
.knowledge-map {
  margin: 24px 0;
  color: #759b84;
}
.knowledge-map svg {
  width: 100%;
  max-height: 175px;
}
.knowledge-map circle,
.knowledge-map rect {
  fill: #d4e6a5;
}
.brand-tags {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}
.brand-tags span {
  padding: 6px 12px;
  border: 1px solid #ffffff26;
  border-radius: 6px;
  color: #d2dfd6;
  font-size: 11px;
}
.brand-footnote {
  color: #8cad9f;
  font-size: 9px;
}
.login-content {
  min-width: 0;
  display: flex;
  flex-direction: column;
  padding: 30px 48px 24px;
}
.login-toolbar {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
  min-height: 32px;
}
.mobile-brand {
  display: none;
}
.login-panel {
  width: 100%;
  max-width: 380px;
  margin: auto;
  padding: 40px 0;
}
.login-panel--register {
  max-width: 860px;
}
.login-eyebrow {
  color: #117568;
}
.login-panel h2 {
  font-size: 28px;
  font-weight: 650;
  margin: 12px 0 8px;
}
.login-description {
  color: #7b8982;
  font-size: 13px;
  margin-bottom: 32px;
}
.login-footer {
  text-align: center;
  color: #8b978f;
  font-size: 11px;
}
.login-panel :deep(.n-input) {
  --n-height: 46px !important;
  --n-border-radius: 10px !important;
}
.login-panel :deep(.n-button) {
  --n-border-radius: 10px !important;
}
.login-page--dark {
  background: #0f1917;
  color: #e0eae4;
}
.login-page--dark .login-shell {
  background: #172321;
  border-color: #30413a;
}
@media (max-width: 900px) {
  .brand-panel {
    padding: 30px;
  }
  .brand-story h1 {
    font-size: 32px;
  }
  .login-content {
    padding: 24px;
  }
  .login-shell--register {
    grid-template-columns: 1fr;
  }
  .login-shell--register .brand-panel {
    display: none;
  }
  .login-shell--register .mobile-brand {
    display: flex;
  }
}
@media (max-width: 640px) {
  .login-page {
    padding: 20px 12px;
  }
  .login-shell {
    grid-template-columns: 1fr;
    min-height: 600px;
    border-radius: 18px;
  }
  .brand-panel {
    display: none;
  }
  .mobile-brand {
    display: flex;
    align-items: center;
    gap: 10px;
    margin-right: auto;
    font-size: 20px;
  }
  .login-content {
    padding: 24px;
  }
  .login-panel {
    padding: 40px 0;
  }
}
</style>
