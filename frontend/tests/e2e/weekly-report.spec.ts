import { expect, test, type Page } from '@playwright/test'

const mockWeeklyReport = async (page: Page) => {
  await page.route('**/api/auth/me', (route) => route.fulfill({ json: { user_id: 'test-user', workspace_id: 'default' } }))
  await page.route('**/api/sessions', (route) => route.fulfill({ json: [] }))
  await page.route('**/api/documents', (route) => route.fulfill({ json: [{ id: 'source-1', title: '本期项目记录.xlsx', docType: 'upload', status: 'active', latestVersion: 2 }] }))
  await page.route('**/api/weekly-report/templates', async (route) => {
    if (route.request().method() === 'POST') return route.fulfill({ json: { id: 'custom-1', name: '公司模板', description: '', builtIn: false, contentMd: '# {{项目名称}}' } })
    return route.fulfill({ json: [{ id: 'builtin-weekly-report', name: '标准项目周报', description: '', builtIn: true, contentMd: '' }] })
  })
  await page.route('**/api/weekly-report/generate', (route) => route.fulfill({ json: {
    documentId: 'report-1', versionId: 'report-v1', version: 1,
    contentMd: '# 星桥客户门户项目周报\n\n## 本周完成\n- 资料下载功能通过验收 [S1]',
    review: {
      conflicts: [{ type: 'conflict', field: 'defects_closed', message: '资料对同一字段给出了不同值，请人工确认', sourceIds: ['S1', 'S2'], values: { S1: '12', S2: '15' } }],
      missing: [], calculations: [{ metric: '缺陷关闭比例', inputs: [15, 20], formula: '已关闭 / 计划总数', result: 0.75, unit: 'ratio', sourceId: 'S1' }],
    },
    sources: [{ sourceId: 'S1', documentId: 'source-1', versionId: 'source-v2', version: 2, title: '本期项目记录.xlsx', locator: 'v2' }],
    indexStatus: 'not_indexed',
  } }))
  await page.route('**/api/weekly-report/compare', (route) => route.fulfill({ json: { added: 1, removed: 0, unchanged: 3, changes: [{ type: 'added', originalLine: 0, revisedLine: 5, text: '- 人工补充' }] } }))
  await page.route('**/api/documents/report-1', (route) => route.fulfill({ json: {
    document: { id: 'report-1', title: '星桥客户门户项目周报', latestVersion: 2 },
    version: { id: 'report-v2', documentId: 'report-1', version: 2, contentMd: '' }, created: false,
  } }))
}

test('generate, review and save a source-backed weekly report', async ({ page }) => {
  await mockWeeklyReport(page)
  await page.goto('/weekly-report')
  await page.getByLabel('项目名称').fill('星桥客户门户')
  await page.locator('.source-item').filter({ hasText: '本期项目记录.xlsx' }).click()
  await page.getByRole('button', { name: '生成并保存草稿' }).click()
  await expect(page.getByText('资料冲突')).toBeVisible()
  await expect(page.getByText('15 / 20 · 已关闭 / 计划总数 = 75.0%')).toBeVisible()
  const editor = page.getByRole('textbox', { name: '周报正文' })
  await editor.fill(`${await editor.inputValue()}\n- 人工补充`)
  await page.getByRole('button', { name: '查看差异' }).click()
  await expect(page.getByText('新增 1 行')).toBeVisible()
  await page.getByRole('button', { name: '保存正式版本' }).click()
  await expect(page.getByText('修改已保存为新的正式版本。')).toBeVisible()
})
