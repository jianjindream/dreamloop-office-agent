import { expect, test, type Page } from '@playwright/test'

type Version = { id: string; documentId: string; version: number; contentMd: string; metadata: Record<string, unknown>; createdAt: string }

const mockDocuments = async (page: Page) => {
  let title = ''
  const versions: Version[] = []
  const document = () => ({ id: 'doc-1', title, docType: 'note', latestVersion: versions.length, updatedAt: new Date().toISOString() })
  await page.route('**/api/status', (route) => route.fulfill({ json: { rag_loaded: false, rag_mode: 'mock', tools_count: 0, llm_model: 'mock', embedding_model: 'mock', is_mock: true, infrastructure: {} } }))
  await page.route('**/api/documents', async (route) => {
    if (route.request().method() === 'POST') {
      const payload = route.request().postDataJSON() as { title: string; content_md: string; metadata?: Record<string, unknown> }
      title = payload.title
      versions.push({ id: 'ver-1', documentId: 'doc-1', version: 1, contentMd: payload.content_md, metadata: payload.metadata ?? {}, createdAt: new Date().toISOString() })
      return route.fulfill({ json: { document: document(), version: versions.at(-1), created: true } })
    }
    return route.fulfill({ json: versions.length ? [document()] : [] })
  })
  await page.route('**/api/documents/doc-1', async (route) => {
    if (route.request().method() === 'PUT') {
      const payload = route.request().postDataJSON() as { title: string; content_md: string }
      title = payload.title
      versions.push({ id: `ver-${versions.length + 1}`, documentId: 'doc-1', version: versions.length + 1, contentMd: payload.content_md, metadata: {}, createdAt: new Date().toISOString() })
      return route.fulfill({ json: { document: document(), version: versions.at(-1), created: false } })
    }
    return route.fulfill({ json: { document: document(), version: versions.at(-1) } })
  })
  await page.route('**/api/documents/doc-1/versions', (route) => route.fulfill({ json: [...versions].reverse() }))
}

test('create, edit, save and inspect document versions', async ({ page }) => {
  await mockDocuments(page)
  await page.goto('/documents')
  await page.getByRole('button', { name: '新建文档' }).click()
  await page.getByRole('textbox', { name: '文档标题' }).fill('交付说明')
  await page.getByRole('textbox', { name: 'Markdown 文档正文' }).fill('# 第一版\n\n正文')
  await page.getByRole('button', { name: '保存文档' }).click()
  await expect(page.getByText('已保存为版本 1')).toBeVisible()
  await page.getByRole('textbox', { name: 'Markdown 文档正文' }).fill('# 第二版\n\n更新后的正文')
  await page.getByRole('button', { name: '保存文档' }).click()
  await expect(page.getByText('已保存为版本 2')).toBeVisible()
  await page.getByRole('button', { name: '历史版本' }).click()
  await expect(page.getByRole('button', { name: /版本 1/ })).toBeVisible()
  await page.getByRole('button', { name: /版本 1/ }).click()
  await expect(page.getByRole('heading', { name: '版本 1' })).toBeVisible()
})
