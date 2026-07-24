import test from 'node:test';
import assert from 'node:assert/strict';
import { LatifCore } from '../src/core/ai-core.js';

test('LatifCore describes the starter project', () => {
  const core = new LatifCore();
  assert.equal(core.describe(), 'A minimal LATIF core implementation for the workspace starter.');
});

test('LatifCore detects skill intent from chat prompts', () => {
  const core = new LatifCore();
  assert.equal(core.analyzeIntent('Summarize this article in three bullets'), 'summarize');
  assert.equal(core.analyzeIntent('Help me fix this JavaScript bug'), 'code');
  assert.equal(core.analyzeIntent('Brainstorm ideas for a local AI app'), 'brainstorm');
});

test('LatifCore can build a helpful offline reply', () => {
  const core = new LatifCore();
  const reply = core.buildOfflineReply('Explain how to set up Ollama on Android');
  assert.match(reply, /Ollama/i);
  assert.match(reply, /Android/i);
  assert.match(reply, /local/i);
});
