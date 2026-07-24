import test from 'node:test';
import assert from 'node:assert/strict';
import { LatifCore } from '../src/core/ai-core.js';

test('LatifCore describes the starter project', () => {
  const core = new LatifCore();
  assert.equal(core.describe(), 'A minimal LATIF core implementation for the workspace starter.');
});
