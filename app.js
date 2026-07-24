import { LatifCore } from './src/core/ai-core.js';
import { OfflineCache } from './js/offline-cache.js';
import './js/voice-backend.js';

const State = {
  host: '127.0.0.1',
  port: '11434',
  provider: 'auto',
  providerLabel: 'Auto',
  serverAvailable: false,
  lastKnownWorkingServer: null,
  serverCheckInProgress: false,
  serverCheckTimestamp: 0,
  ollamaDiscoveryStatus: 'unchecked',
  discoveredOllamaHost: null,
  models: [],
  model: null,
  isGenerating: false,
  stream: false,
  abortCtrl: null,
  conversation: [],
  lastIntent: 'chat',
};

const cache = new OfflineCache();
const core = new LatifCore();

const $ = (id) => document.getElementById(id);

function baseUrl() {
  return `http://${State.host}:${State.port}`;
}

function toast(message) {
  const el = $('toast');
  if (!el) return;
  el.textContent = message;
  el.classList.add('visible');
  clearTimeout(el._timeout);
  el._timeout = setTimeout(() => el.classList.remove('visible'), 3400);
}

function escapeHtml(value) {
  return String(value || '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

function showBubble(text, role = 'assistant', modelName = '') {
  const stream = $('chatStream');
  if (!stream) return;
  const bubble = document.createElement('div');
  bubble.className = `bubble ${role}`;
  bubble.innerHTML = `<div>${escapeHtml(text).replace(/\n/g, '<br/>')}</div>`;
  if (modelName) {
    const meta = document.createElement('small');
    meta.textContent = modelName;
    bubble.appendChild(meta);
  }
  stream.appendChild(bubble);
  stream.scrollTop = stream.scrollHeight;
}

function setServerStatus(online) {
  const dot = $('serverDot');
  if (dot) {
    dot.classList.toggle('online', online);
  }
  const pill = $('serverPillText');
  if (pill) {
    pill.textContent = online ? `${State.providerLabel} @ ${State.host}:${State.port}` : 'Offline';
  }
  const hint = $('cacheHint');
  if (hint) {
    hint.style.display = online ? 'none' : (State.lastKnownWorkingServer ? 'block' : 'none');
  }
  const statusLine = $('statusLine');
  if (statusLine) {
    statusLine.textContent = online
      ? (State.model ? `${State.providerLabel} • ${State.model}` : `${State.providerLabel} • ready`)
      : 'Offline • using cached fallback responses';
  }
}

function renderSkillSuggestions(message = '') {
  const chips = $('skillChips');
  if (!chips) return;
  const inputValue = message || ($('chatInput')?.value || '').trim();
  const skills = core.getSuggestedSkills(inputValue);
  chips.innerHTML = skills.map((skill) => `<span class="skill-chip">${escapeHtml(skill.label)}</span>`).join('');
}

function renderQuickPrompts(message = '') {
  const container = $('quickPrompts');
  if (!container) return;
  const prompts = core.getSuggestedQuickPrompts(message || ($('chatInput')?.value || '').trim());
  container.innerHTML = prompts.map((prompt) => `<button class="quick-prompt" type="button">${escapeHtml(prompt)}</button>`).join('');
  container.querySelectorAll('.quick-prompt').forEach((button) => {
    button.addEventListener('click', () => {
      const input = $('chatInput');
      if (input) {
        input.value = button.textContent;
        renderSkillSuggestions(input.value);
        input.focus();
      }
    });
  });
}

function buildCandidateList() {
  const customHost = ($('hostInput')?.value || '').trim() || State.host;
  const customPort = ($('portInput')?.value || '').trim() || State.port;
  const hosts = [customHost, '127.0.0.1', 'localhost', '10.0.2.2', '192.168.0.10', '192.168.0.100', '192.168.1.10', '192.168.1.100', '192.168.43.1'];
  const ports = [customPort, '11434', '8080'];
  const candidates = [];

  hosts.forEach((host) => {
    ports.forEach((port) => {
      if (!candidates.some((candidate) => candidate.host === host && candidate.port === port)) {
        candidates.push({ host, port });
      }
    });
  });

  return candidates;
}

async function probeServer(host, port, timeoutMs = 1800) {
  const checks = [
    { provider: 'ollama', url: `http://${host}:${port}/api/tags` },
    { provider: 'ollama', url: `http://${host}:${port}/api/models` },
    { provider: 'llama.cpp', url: `http://${host}:${port}/v1/models` },
  ];

  for (const check of checks) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    try {
      const response = await fetch(check.url, { signal: controller.signal });
      if (!response.ok) continue;
      const payload = await response.json().catch(() => null);
      if (check.provider === 'ollama' && (payload?.models || payload?.data)) {
        return { ok: true, provider: 'ollama', payload };
      }
      if (check.provider === 'llama.cpp' && (payload?.data || payload?.models || payload?.id)) {
        return { ok: true, provider: 'llama.cpp', payload };
      }
    } catch (error) {
      console.debug('Probe failed', check.url, error);
    } finally {
      clearTimeout(timer);
    }
  }

  return null;
}

async function autoDetectServer() {
  if (State.serverCheckInProgress) return;
  State.serverCheckInProgress = true;
  let working = null;
  const attempts = buildCandidateList();

  const saved = localStorage.getItem('latif_lastKnownWorkingServer');
  if (saved) {
    try {
      const parsed = JSON.parse(saved);
      if (parsed.host && parsed.port) {
        attempts.unshift(parsed);
      }
    } catch (error) {
      console.warn('Invalid saved lastKnownWorkingServer', error);
    }
  }

  for (const candidate of attempts) {
    const available = await probeServer(candidate.host, candidate.port);
    if (available) {
      working = { ...candidate, provider: available.provider };
      break;
    }
  }

  if (working) {
    State.serverAvailable = true;
    State.host = working.host;
    State.port = working.port;
    State.provider = working.provider;
    State.providerLabel = working.provider === 'llama.cpp' ? 'Llama.cpp' : 'Ollama';
    State.lastKnownWorkingServer = { host: working.host, port: working.port };
    localStorage.setItem('latif_lastKnownWorkingServer', JSON.stringify(State.lastKnownWorkingServer));
    State.ollamaDiscoveryStatus = 'discovered';
    State.discoveredOllamaHost = `${working.host}:${working.port}`;
    setServerStatus(true);
  } else {
    State.serverAvailable = false;
    State.provider = 'auto';
    State.providerLabel = 'Auto';
    State.ollamaDiscoveryStatus = 'not-found';
    setServerStatus(false);
  }

  State.serverCheckInProgress = false;
  State.serverCheckTimestamp = Date.now();
}

async function fetchModels() {
  try {
    const endpoint = State.provider === 'llama.cpp'
      ? `${baseUrl()}/v1/models`
      : `${baseUrl()}/api/tags`;
    const response = await fetch(endpoint, { method: 'GET' });
    if (!response.ok) throw new Error('Model fetch failed');
    const payload = await response.json();

    if (State.provider === 'llama.cpp') {
      const items = Array.isArray(payload.data) ? payload.data : [];
      State.models = items.map((item) => ({ name: item.id || item.name || item.model }));
    } else {
      State.models = Array.isArray(payload.models)
        ? payload.models.map((item) => ({ name: item.name || item.model }))
        : [];
    }

    const recommended = core.getSuggestedModelName(State.provider === 'llama.cpp' ? 'llama.cpp' : 'ollama', State.models.map((item) => item.name));
    State.model = State.model || recommended || State.models[0]?.name || null;
    if (State.model) {
      localStorage.setItem('latif_lastKnownModel', State.model);
    }
  } catch (error) {
    console.warn('Unable to fetch models', error);
    State.models = [];
    State.model = State.model || localStorage.getItem('latif_lastKnownModel') || null;
  }
}

function getChatEndpoint() {
  return State.provider === 'llama.cpp'
    ? `${baseUrl()}/v1/chat/completions`
    : `${baseUrl()}/api/chat`;
}

function buildRequestBody(modelName, messages, extra = {}) {
  if (State.provider === 'llama.cpp') {
    return {
      model: modelName,
      messages,
      stream: false,
      ...extra,
    };
  }

  return {
    model: modelName,
    messages,
    stream: false,
    options: { temperature: 0.7 },
    ...extra,
  };
}

function parseAssistantText(payload) {
  if (State.provider === 'llama.cpp') {
    return payload.choices?.[0]?.message?.content || payload.message?.content || payload.text || '';
  }

  return payload.message?.content || payload.response || '';
}

async function fetchWithRetry(url, options, maxRetries = 2) {
  let attempt = 0;
  while (attempt < maxRetries) {
    try {
      const response = await fetch(url, { ...options });
      if (response.ok) return response;
      if (response.status >= 500) throw new Error(`Server error ${response.status}`);
      return response;
    } catch (error) {
      attempt += 1;
      if (attempt >= maxRetries) throw error;
      const delayMs = Math.min(1000 * 2 ** (attempt - 1), 8000);
      await new Promise((resolve) => setTimeout(resolve, delayMs));
    }
  }
}

async function streamResponse(messages) {
  if (!messages.length) return;
  const latestUserMessage = messages[messages.length - 1]?.content || 'Hello';
  const modelName = (State.model || ($('modelInput')?.value?.trim()) || State.models[0]?.name || 'unknown');
  State.model = modelName;
  if ($('modelInput')) {
    $('modelInput').value = modelName;
  }
  const endpoint = getChatEndpoint();
  const requestMessages = [
    { role: 'system', content: core.buildSystemPrompt(latestUserMessage, { provider: State.provider }) },
    ...State.conversation,
    ...messages,
  ];

  try {
    State.isGenerating = true;
    const response = await fetchWithRetry(endpoint, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(buildRequestBody(modelName, requestMessages)),
      signal: State.abortCtrl?.signal,
    });

    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const payload = await response.json();
    const responseText = parseAssistantText(payload) || core.buildOfflineReply(latestUserMessage);

    if (!State.stream) {
      cache.saveChatResponse(messages, modelName, responseText).catch((error) => {
        console.warn('Cache save failed:', error);
      });
    }

    showBubble(responseText, 'assistant', modelName);
    State.conversation = [...State.conversation, ...messages, { role: 'assistant', content: responseText }].slice(-12);
  } catch (error) {
    if (!State.serverAvailable) {
      const cached = await cache.searchCache(latestUserMessage, modelName);
      if (cached.length > 0) {
        const best = cached[0];
        const timestamp = new Date(best.timestamp).toLocaleTimeString();
        const disclaimer = `**[OFFLINE MODE]** Showing cached response from ${timestamp} — this may not reflect current context or recent changes.\n\n`;
        showBubble(disclaimer + best.responseText, 'assistant', modelName);
        return;
      }
      showBubble(core.buildOfflineReply(latestUserMessage), 'assistant', modelName);
      return;
    }
    const cacheStatus = await cache.getCacheSize();
    const cacheMessage = cacheStatus.entryCount > 0
      ? `The local model server is unreachable. ${cacheStatus.entryCount} cached responses are still available.`
      : 'The local model server is unreachable and no cached responses are available.';
    showBubble(`⚠ Connection error: ${cacheMessage}`, 'assistant');
  } finally {
    State.isGenerating = false;
  }
}

async function updateCacheDisplay() {
  try {
    const stats = await cache.getCacheSize();
    const sizeDisplay = $('cacheSizeDisplay');
    if (sizeDisplay) {
      sizeDisplay.textContent = stats.sizeKB > 0 ? `${stats.sizeKB} KB` : '—';
    }
    const countDisplay = $('cacheCountDisplay');
    if (countDisplay) {
      countDisplay.textContent = String(stats.entryCount);
    }
  } catch (error) {
    console.warn('Cache display update failed:', error);
  }
}

async function updateCacheStatsModal() {
  const stats = await cache.getCacheStats();
  const totalSize = $('cacheTotalSize');
  if (totalSize) totalSize.textContent = `${stats.totalSizeKB} KB`;
  const totalEntries = $('cacheTotalEntries');
  if (totalEntries) totalEntries.textContent = String(stats.entryCount);
  const oldestEntry = $('cacheOldestEntry');
  if (oldestEntry) oldestEntry.textContent = stats.oldestEntry ? new Date(stats.oldestEntry).toLocaleDateString() : '—';
  const newestEntry = $('cacheNewestEntry');
  if (newestEntry) newestEntry.textContent = stats.newestEntry ? new Date(stats.newestEntry).toLocaleDateString() : '—';
  const entries = await cache.getRecentEntries(10);
  const entryList = $('cacheEntryList');
  if (!entryList) return;
  if (entries.length === 0) {
    entryList.innerHTML = '<div class="cache-entry">No cached responses yet.</div>';
  } else {
    entryList.innerHTML = entries.map((entry) => `
      <div class="cache-entry">
        <div class="entry-model">${escapeHtml(entry.modelName)}</div>
        <div class="entry-query">${escapeHtml(entry.queryText.substring(0, 60))}</div>
        <div class="entry-date">${new Date(entry.timestamp).toLocaleDateString()}</div>
      </div>
    `).join('');
  }
}

function wireCacheHandlers() {
  const viewButton = $('btnViewCacheStats');
  if (viewButton) {
    viewButton.addEventListener('click', async () => {
      try {
        await updateCacheStatsModal();
        const modal = $('cacheStatsModal');
        const backdrop = $('cacheStatsBackdrop');
        if (modal) modal.classList.add('open');
        if (backdrop) backdrop.classList.add('open');
      } catch (error) {
        toast('Failed to load cache stats: ' + (error.message || error));
      }
    });
  }

  const closeButton = $('btnCloseCacheStats');
  if (closeButton) {
    closeButton.addEventListener('click', () => {
      const modal = $('cacheStatsModal');
      const backdrop = $('cacheStatsBackdrop');
      if (modal) modal.classList.remove('open');
      if (backdrop) backdrop.classList.remove('open');
    });
  }

  const clearButton = $('btnClearCache');
  if (clearButton) {
    clearButton.addEventListener('click', async () => {
      if (window.confirm('Clear all cached responses? This cannot be undone.')) {
        try {
          await cache.clearCache();
          await updateCacheDisplay();
          toast('✓ Cache cleared');
        } catch (error) {
          toast('✗ Failed to clear cache: ' + (error.message || error));
        }
      }
    });
  }
}

function bindControls() {
  const hostInput = $('hostInput');
  const portInput = $('portInput');
  const providerSelect = $('providerSelect');
  const modelInput = $('modelInput');
  const chatInput = $('chatInput');

  if (hostInput) hostInput.value = State.host;
  if (portInput) portInput.value = State.port;
  if (providerSelect) providerSelect.value = State.provider;
  if (modelInput) modelInput.value = State.model || '';

  if (providerSelect) {
    providerSelect.addEventListener('change', () => {
      State.provider = providerSelect.value;
      State.providerLabel = State.provider === 'llama.cpp' ? 'Llama.cpp' : 'Ollama';
      if (!portInput?.value) {
        portInput.value = State.provider === 'llama.cpp' ? '8080' : '11434';
        State.port = portInput.value;
      }
      if (modelInput && !modelInput.value) {
        modelInput.value = State.provider === 'llama.cpp' ? 'llama3.2' : 'llama3.2';
      }
      State.model = modelInput?.value?.trim() || State.model;
      renderSkillSuggestions(chatInput?.value || '');
      renderQuickPrompts(chatInput?.value || '');
    });
  }

  if (modelInput) {
    modelInput.addEventListener('input', () => {
      State.model = modelInput.value.trim() || null;
    });
  }

  if (chatInput) {
    chatInput.addEventListener('input', () => {
      renderSkillSuggestions(chatInput.value);
      renderQuickPrompts(chatInput.value);
    });
  }

  const settingsToggle = $('btnOpenSettings');
  const settingsPanel = $('settingsPanel');
  if (settingsToggle && settingsPanel) {
    settingsToggle.addEventListener('click', () => {
      settingsPanel.classList.toggle('hidden');
    });
  }

  const voiceButton = $('btnVoice');
  if (voiceButton) {
    voiceButton.addEventListener('click', () => {
      toast('Voice capture is ready for a future whisper integration.');
    });
  }

  const attachmentsButton = $('btnAttachments');
  if (attachmentsButton) {
    attachmentsButton.addEventListener('click', () => {
      toast('Attachment support is ready for document uploads.');
    });
  }

  const testButton = $('btnTestServer');
  if (testButton) {
    testButton.addEventListener('click', async () => {
      State.host = hostInput?.value.trim() || '127.0.0.1';
      State.port = portInput?.value.trim() || (State.provider === 'llama.cpp' ? '8080' : '11434');
      await autoDetectServer();
      if (State.serverAvailable) {
        await fetchModels();
        if (modelInput) {
          modelInput.value = State.model || '';
        }
        toast(`✓ ${State.providerLabel} detected and connected`);
      } else {
        toast(`⚠ ${State.providerLabel} not found at the configured address`);
      }
    });
  }

  const sendButton = $('btnSend');
  if (sendButton) {
    sendButton.addEventListener('click', async () => {
      const text = chatInput?.value.trim();
      if (!text) return;
      if (chatInput) chatInput.value = '';
      showBubble(text, 'user');
      const messages = [{ role: 'user', content: text }];
      await streamResponse(messages);
      renderSkillSuggestions('');
      renderQuickPrompts('');
    });
  }

  renderSkillSuggestions('');
  renderQuickPrompts('');
}

async function init() {
  bindControls();
  wireCacheHandlers();
  const whisperBackend = await window.autoDetectWhisper?.();
  if (whisperBackend) {
    console.log('Whisper backend detected:', whisperBackend);
    toast(`Voice backend ready at ${whisperBackend.host}:${whisperBackend.port}`);
  }

  await Promise.all([autoDetectServer(), cache.open()]);
  if (State.serverAvailable) {
    await fetchModels();
  }
  await updateCacheDisplay();
  setInterval(async () => {
    if (!State.isGenerating && Date.now() - State.serverCheckTimestamp > 30000) {
      await autoDetectServer();
      if (State.serverAvailable) {
        await fetchModels();
      }
    }
    await updateCacheDisplay();
  }, 30000);

  window.addEventListener('online', async () => {
    toast('Network online — re-checking local server');
    await autoDetectServer();
    if (State.serverAvailable) {
      await fetchModels();
      toast(`✓ Reconnected to ${State.providerLabel}`);
    }
  });

  window.addEventListener('offline', () => {
    State.serverAvailable = false;
    setServerStatus(false);
    toast('⚠ Offline — using cached responses');
  });
}

init().catch((error) => {
  console.error(error);
  toast('Initialization failed.');
});
