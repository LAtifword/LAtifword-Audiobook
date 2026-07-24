export class LatifCore {
  constructor() {
    this.name = 'LATIF Core';
    this.skills = [
      { id: 'chat', label: 'Conversational chat', description: 'General conversation and quick guidance.' },
      { id: 'summarize', label: 'Summarization', description: 'Condense long content into clear bullet points.' },
      { id: 'code', label: 'Coding help', description: 'Debug, explain, and refactor code.' },
      { id: 'brainstorm', label: 'Idea generation', description: 'Generate strategies, plans, and product ideas.' },
      { id: 'research', label: 'Research helper', description: 'Compare options and explain tradeoffs.' },
      { id: 'writing', label: 'Writing support', description: 'Polish emails, documents, and posts.' },
    ];
  }

  describe() {
    return 'A minimal LATIF core implementation for the workspace starter.';
  }

  analyzeIntent(message = '') {
    const text = String(message || '').toLowerCase();

    if (/(summarize|summary|bullet|tl;dr|key points|short version)/.test(text)) {
      return 'summarize';
    }

    if (/(code|bug|debug|javascript|typescript|function|class|refactor|test|api|fix)/.test(text)) {
      return 'code';
    }

    if (/(brainstorm|idea|ideas|plan|strategy|roadmap|project|launch)/.test(text)) {
      return 'brainstorm';
    }

    if (/(research|compare|pros|cons|latest|explain|analyze|tradeoff)/.test(text)) {
      return 'research';
    }

    if (/(write|rewrite|email|article|essay|post|poem|story)/.test(text)) {
      return 'writing';
    }

    return 'chat';
  }

  getSuggestedSkills(message = '') {
    const intent = this.analyzeIntent(message);
    const presets = {
      summarize: [this.skills[1], this.skills[5]],
      code: [this.skills[2], this.skills[3]],
      brainstorm: [this.skills[3], this.skills[4]],
      research: [this.skills[4], this.skills[5]],
      writing: [this.skills[5], this.skills[1]],
      chat: [this.skills[0], this.skills[3]],
    };

    return presets[intent] || presets.chat;
  }

  getSuggestedQuickPrompts(message = '') {
    const intent = this.analyzeIntent(message);
    const presets = {
      summarize: ['Summarize this in 5 bullet points', 'Give me a concise executive summary'],
      code: ['Help me debug this function', 'Refactor this snippet for clarity'],
      brainstorm: ['Brainstorm three product ideas', 'Create a simple launch plan'],
      research: ['Compare the tradeoffs between options', 'Give me a short research brief'],
      writing: ['Rewrite this for a professional tone', 'Turn this into a polished email'],
      chat: ['Explain this like I am five', 'Give me a practical next step'],
    };

    return presets[intent] || presets.chat;
  }

  buildSystemPrompt(message = '', options = {}) {
    const intent = this.analyzeIntent(message);
    const provider = options.provider || 'local';
    const skills = this.getSuggestedSkills(message).map((skill) => skill.label).join(', ');

    return [
      'You are LATIF GX, a modern local-first AI assistant.',
      `Intent: ${intent}.`,
      `Preferred capabilities: ${skills}.`,
      'Be concise, practical, and transparent when the connection is offline.',
      provider === 'llama.cpp'
        ? 'Use a compact, helpful response style suited to local Llama-style models.'
        : 'Prefer a lightweight local-model workflow that works well with Ollama-style backends.',
    ].join(' ');
  }

  buildOfflineReply(message = '') {
    const intent = this.analyzeIntent(message);
    const skills = this.getSuggestedSkills(message).slice(0, 3).map((skill) => skill.label).join(', ');

    return [
      `You asked for help with ${intent}.`,
      'I am currently running in offline mode, so I cannot reach a local model server directly.',
      'For Android or desktop setups, start Ollama on port 11434 or llama.cpp on port 8080 and the app will auto-detect it.',
      `Useful skills available right now: ${skills}.`,
    ].join(' ');
  }

  getSuggestedModelName(provider = 'ollama', available = []) {
    const preferred = provider === 'llama.cpp'
      ? ['llama3.2', 'phi3', 'qwen2.5', 'mistral', 'gemma2']
      : ['llama3.2', 'mistral', 'qwen2.5', 'phi3', 'gemma2'];
    const normalized = (available || []).map((name) => String(name).toLowerCase());
    const match = preferred.find((candidate) => normalized.includes(candidate.toLowerCase()));
    return match || available?.[0] || preferred[0] || null;
  }
}
