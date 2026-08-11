import test from 'node:test'
import assert from 'node:assert/strict'

import { SkillLibrary } from '../../src/agent/library/skill_library.js'

test('word-overlap retrieval selects from source skill docs without embeddings', async () => {
  const library: any = new SkillLibrary({}, null)
  library.skill_docs = [
    'skills.breakBlockAt\nBreak a block at exact coordinates.',
    'skills.collectBlock\nCollect several nearby coal ore blocks.',
    'skills.wait\nWait without moving.'
  ]
  library.always_show_skills_docs = {}

  const docs = await library.getRelevantSkillDocs('collect nearby coal ore blocks', 1)

  assert.match(docs, /skills\.collectBlock/)
})

test('word-overlap retrieval returns all source docs when selection is unlimited', async () => {
  const library: any = new SkillLibrary({}, null)
  library.skill_docs = [
    'skills.breakBlockAt\nBreak a block.',
    'skills.collectBlock\nCollect blocks.'
  ]
  library.always_show_skills_docs = {}

  const docs = await library.getRelevantSkillDocs('anything', -1)

  assert.match(docs, /skills\.breakBlockAt/)
  assert.match(docs, /skills\.collectBlock/)
})
