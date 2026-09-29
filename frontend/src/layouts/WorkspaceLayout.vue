<script setup lang="ts">
import { computed } from 'vue'
import { RouterView, useRoute } from 'vue-router'
import AppSidebar from '@/components/app/AppSidebar.vue'
import AppTopbar from '@/components/app/AppTopbar.vue'
import ContextPanel from '@/components/app/ContextPanel.vue'
import DocumentCanvas from '@/components/document/DocumentCanvas.vue'
import { useWorkspaceStore } from '@/stores/workspace'
import { useDocumentCanvasStore } from '@/stores/documentCanvas'

const route = useRoute()
const workspace = useWorkspaceStore()
const canvas = useDocumentCanvasStore()
const showCanvas = computed(() => canvas.open && (route.name === 'chat' || route.name === 'documents'))
const showContext = computed(() => !showCanvas.value && Boolean(route.meta.showContext) && workspace.contextOpen)
</script>

<template>
  <div
    class="workspace-layout"
    :class="{
      'sidebar-collapsed': workspace.sidebarCollapsed,
      'context-visible': showContext,
      'canvas-visible': showCanvas,
    }"
  >
    <AppSidebar />
    <section class="workspace-main">
      <AppTopbar />
      <main class="page-stage">
        <RouterView />
      </main>
    </section>
    <ContextPanel v-if="showContext" />
    <DocumentCanvas v-if="showCanvas" />
  </div>
</template>

<style scoped>
.workspace-layout {
  display: grid;
  grid-template-columns: var(--sidebar-width) minmax(0, 1fr);
  width: 100%;
  height: 100%;
  background:
    radial-gradient(circle at 72% 0%, rgba(111, 101, 235, 0.055), transparent 28%),
    var(--bg-app);
  transition: grid-template-columns 180ms ease;
}

.workspace-layout.sidebar-collapsed {
  grid-template-columns: var(--sidebar-collapsed) minmax(0, 1fr);
}

.workspace-layout.context-visible {
  grid-template-columns: var(--sidebar-width) minmax(0, 1fr) 320px;
}

.workspace-layout.sidebar-collapsed.context-visible {
  grid-template-columns: var(--sidebar-collapsed) minmax(0, 1fr) 320px;
}

.workspace-layout.canvas-visible {
  grid-template-columns: var(--sidebar-width) minmax(0, 1fr) minmax(440px, 38vw);
}

.workspace-layout.sidebar-collapsed.canvas-visible {
  grid-template-columns: var(--sidebar-collapsed) minmax(0, 1fr) minmax(440px, 38vw);
}

.workspace-main {
  display: grid;
  grid-template-rows: var(--topbar-height) minmax(0, 1fr);
  min-width: 0;
  min-height: 0;
}

.page-stage {
  min-width: 0;
  min-height: 0;
  overflow: auto;
}

@media (max-width: 1180px) {
  .workspace-layout.canvas-visible,
  .workspace-layout.sidebar-collapsed.canvas-visible {
    grid-template-columns: var(--sidebar-width) minmax(0, 1fr);
  }

  .workspace-layout.sidebar-collapsed.canvas-visible {
    grid-template-columns: var(--sidebar-collapsed) minmax(0, 1fr);
  }

  .document-canvas {
    position: fixed;
    z-index: 30;
    top: 0;
    right: 0;
    bottom: 0;
    width: min(520px, calc(100vw - var(--sidebar-collapsed)));
  }

  .workspace-layout.context-visible,
  .workspace-layout.sidebar-collapsed.context-visible {
    grid-template-columns: var(--sidebar-width) minmax(0, 1fr);
  }

  .workspace-layout.sidebar-collapsed.context-visible {
    grid-template-columns: var(--sidebar-collapsed) minmax(0, 1fr);
  }
}

@media (max-width: 860px) {
  .workspace-layout,
  .workspace-layout.context-visible,
  .workspace-layout.canvas-visible,
  .workspace-layout.sidebar-collapsed.canvas-visible {
    grid-template-columns: var(--sidebar-collapsed) minmax(0, 1fr);
  }
}

@media (max-width: 600px) {
  .workspace-layout,
  .workspace-layout.context-visible,
  .workspace-layout.sidebar-collapsed,
  .workspace-layout.sidebar-collapsed.context-visible,
  .workspace-layout.canvas-visible,
  .workspace-layout.sidebar-collapsed.canvas-visible {
    grid-template-columns: 1fr;
  }

  .document-canvas { width: 100vw; }
}
</style>
