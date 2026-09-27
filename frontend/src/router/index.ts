import { createRouter, createWebHistory } from 'vue-router'
import WorkspaceLayout from '@/layouts/WorkspaceLayout.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/',
      component: WorkspaceLayout,
      children: [
        { path: '', redirect: '/chat' },
        {
          path: 'chat',
          name: 'chat',
          component: () => import('@/pages/ChatPage.vue'),
          meta: { title: 'AI 对话', eyebrow: 'DreamLoop Workspace', showContext: true },
        },
        {
          path: 'knowledge',
          name: 'knowledge',
          component: () => import('@/pages/KnowledgePage.vue'),
          meta: { title: '知识库', eyebrow: 'Workspace Knowledge' },
        },
        {
          path: 'documents',
          name: 'documents',
          component: () => import('@/pages/DocumentsPage.vue'),
          meta: { title: '文档', eyebrow: 'AI Documents' },
        },
        {
          path: 'tools',
          name: 'tools',
          component: () => import('@/pages/ToolsPage.vue'),
          meta: { title: '工具中心', eyebrow: 'Tools & MCP' },
        },
        {
          path: 'status',
          name: 'status',
          component: () => import('@/pages/StatusPage.vue'),
          meta: { title: '系统状态', eyebrow: 'System Health' },
        },
        {
          path: 'settings',
          name: 'settings',
          component: () => import('@/pages/SettingsPage.vue'),
          meta: { title: '设置', eyebrow: 'Workspace Settings' },
        },
      ],
    },
  ],
})

export default router
