import test from 'node:test'
import assert from 'node:assert/strict'

import {
  FORGE_AGENT_GRADLE_VERSION,
  buildGradleDistributionUrl,
  buildForgeAgentGradleArgs
} from '../../src/forgeAgent/buildSupport.js'

test('buildGradleDistributionUrl points to the pinned Gradle distribution', () => {
  assert.equal(FORGE_AGENT_GRADLE_VERSION, '8.1.1')
  assert.equal(
    buildGradleDistributionUrl(),
    'https://services.gradle.org/distributions/gradle-8.1.1-bin.zip'
  )
})

test('buildForgeAgentGradleArgs targets the forge-agent project directory', () => {
  assert.deepEqual(
    buildForgeAgentGradleArgs('F:/computer/java_ximu/game-ai'),
    ['-p', 'F:/computer/java_ximu/game-ai/forge-agent', 'build']
  )
})
