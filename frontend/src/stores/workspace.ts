import { defineStore } from 'pinia'

type Theme = 'light' | 'dark'

const readTheme = (): Theme => {
  const stored = localStorage.getItem('dreamloop-theme')
  return stored === 'dark' ? 'dark' : 'light'
}

export const useWorkspaceStore = defineStore('workspace', {
  state: () => ({
    sidebarCollapsed: false,
    contextOpen: true,
    theme: readTheme() as Theme,
    knowledgeEnabled: true,
    selectedDocumentIds: [] as string[],
    contextKey: '',
  }),
  actions: {
    toggleSidebar() {
      this.sidebarCollapsed = !this.sidebarCollapsed
    },
    toggleContext() {
      this.contextOpen = !this.contextOpen
    },
    toggleKnowledge() {
      this.knowledgeEnabled = !this.knowledgeEnabled
    },
    initializeContext(scope: string) {
      this.contextKey = scope + ':knowledge-context'
      try {
        const saved = JSON.parse(localStorage.getItem(this.contextKey) ?? '[]')
        this.selectedDocumentIds = Array.isArray(saved) ? saved.filter((id): id is string => typeof id === 'string') : []
      } catch { this.selectedDocumentIds = [] }
    },
    toggleDocument(id: string) {
      this.selectedDocumentIds = this.selectedDocumentIds.includes(id)
        ? this.selectedDocumentIds.filter((value) => value !== id)
        : [...this.selectedDocumentIds, id]
      if (this.selectedDocumentIds.length) this.knowledgeEnabled = true
      this.persistContext()
    },
    retainDocuments(ids: string[]) {
      const allowed = new Set(ids)
      this.selectedDocumentIds = this.selectedDocumentIds.filter((id) => allowed.has(id))
      this.persistContext()
    },
    clearDocuments() {
      this.selectedDocumentIds = []
      this.persistContext()
    },
    persistContext() {
      if (this.contextKey) localStorage.setItem(this.contextKey, JSON.stringify(this.selectedDocumentIds))
    },
    toggleTheme() {
      this.theme = this.theme === 'light' ? 'dark' : 'light'
      localStorage.setItem('dreamloop-theme', this.theme)
      this.applyTheme()
    },
    applyTheme() {
      document.documentElement.dataset.theme = this.theme
    },
  },
})
