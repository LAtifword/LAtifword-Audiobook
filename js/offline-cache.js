export class OfflineCache {
  constructor() {
    this.dbName = 'latif-offline-cache';
    this.storeName = 'responses';
    this.dbVersion = 1;
    this.maxEntries = 50;
    this.db = null;
  }

  async open() {
    if (this.db) return this.db;
    return new Promise((resolve, reject) => {
      const request = window.indexedDB.open(this.dbName, this.dbVersion);

      request.onupgradeneeded = (event) => {
        const db = event.target.result;
        const store = db.createObjectStore(this.storeName, { keyPath: 'id' });
        store.createIndex('timestamp', 'timestamp');
        store.createIndex('modelName', 'modelName');
        store.createIndex('queryText', 'queryText');
      };

      request.onsuccess = () => {
        this.db = request.result;
        resolve(this.db);
      };

      request.onerror = () => reject(request.error);
    });
  }

  async _withStore(mode, callback) {
    const db = await this.open();
    return new Promise((resolve, reject) => {
      const tx = db.transaction(this.storeName, mode);
      const store = tx.objectStore(this.storeName);
      const result = callback(store);
      tx.oncomplete = () => resolve(result);
      tx.onerror = () => reject(tx.error);
    });
  }

  _hashString(value) {
    const encoder = new TextEncoder();
    const data = encoder.encode(value);
    return crypto.subtle.digest('SHA-256', data).then((hash) => {
      return Array.from(new Uint8Array(hash)).map((byte) => byte.toString(16).padStart(2, '0')).join('');
    });
  }

  async saveChatResponse(messages, modelName, responseText) {
    const queryText = messages[messages.length - 1]?.content || '';
    const hashSource = `${modelName}::${queryText}`;
    const queryHash = await this._hashString(hashSource);
    const record = {
      id: queryHash,
      timestamp: Date.now(),
      modelName,
      queryHash,
      queryText,
      responseText,
      messageCount: messages.length,
      tags: this._extractTags(queryText),
    };

    await this._withStore('readwrite', (store) => {
      store.put(record);
    });

    const stats = await this.getCacheSize();
    if (stats.entryCount > this.maxEntries) {
      await this._pruneOldest(stats.entryCount - this.maxEntries);
    }
    return record;
  }

  async _pruneOldest(count) {
    return this._withStore('readwrite', (store) => {
      const request = store.index('timestamp').openCursor();
      let removed = 0;
      request.onsuccess = (event) => {
        const cursor = event.target.result;
        if (!cursor || removed >= count) return;
        store.delete(cursor.primaryKey);
        removed += 1;
        cursor.continue();
      };
    });
  }

  _extractTags(text) {
    const words = text.toLowerCase().match(/\b[a-z]{3,}\b/g) || [];
    const stopWords = new Set(['the', 'and', 'for', 'with', 'that', 'this', 'from', 'have', 'into', 'about', 'their', 'there', 'which', 'would', 'could', 'should']);
    return Array.from(new Set(words.filter((word) => !stopWords.has(word))).slice(0, 12));
  }

  async getCacheSize() {
    const db = await this.open();
    return new Promise((resolve, reject) => {
      const tx = db.transaction(this.storeName, 'readonly');
      const store = tx.objectStore(this.storeName);
      const entries = [];
      const request = store.openCursor();

      request.onsuccess = (event) => {
        const cursor = event.target.result;
        if (!cursor) {
          const totalChars = entries.reduce((sum, entry) => sum + entry.responseText.length + entry.queryText.length, 0);
          resolve({
            sizeKB: Math.round((totalChars * 2) / 1024),
            entryCount: entries.length,
            oldestEntry: entries[0]?.timestamp || null,
            newestEntry: entries[entries.length - 1]?.timestamp || null,
          });
          return;
        }
        entries.push(cursor.value);
        cursor.continue();
      };

      request.onerror = () => reject(request.error);
    });
  }

  async getCacheStats() {
    const db = await this.open();
    return new Promise((resolve, reject) => {
      const tx = db.transaction(this.storeName, 'readonly');
      const store = tx.objectStore(this.storeName);
      const entries = [];
      const request = store.openCursor();

      request.onsuccess = (event) => {
        const cursor = event.target.result;
        if (!cursor) {
          const totalSize = entries.reduce((sum, entry) => sum + entry.responseText.length + entry.queryText.length, 0);
          const averageSize = entries.length ? Math.round(totalSize / entries.length) : 0;
          resolve({
            totalSizeKB: Math.round((totalSize * 2) / 1024),
            entryCount: entries.length,
            oldestEntry: entries[0]?.timestamp || null,
            newestEntry: entries[entries.length - 1]?.timestamp || null,
            averageSize,
          });
          return;
        }
        entries.push(cursor.value);
        cursor.continue();
      };

      request.onerror = () => reject(request.error);
    });
  }

  async getRecentEntries(limit = 10) {
    const db = await this.open();
    return new Promise((resolve, reject) => {
      const tx = db.transaction(this.storeName, 'readonly');
      const store = tx.objectStore(this.storeName);
      const request = store.index('timestamp').openCursor(null, 'prev');
      const items = [];

      request.onsuccess = (event) => {
        const cursor = event.target.result;
        if (!cursor || items.length >= limit) {
          resolve(items);
          return;
        }
        items.push(cursor.value);
        cursor.continue();
      };

      request.onerror = () => reject(request.error);
    });
  }

  async clearCache() {
    await this._withStore('readwrite', (store) => {
      store.clear();
    });
  }

  async searchCache(userQuery, modelName, threshold = 0.6) {
    if (!userQuery) return [];
    const db = await this.open();
    const queryTokens = this._extractTags(userQuery);
    const targetModel = modelName;

    return new Promise((resolve, reject) => {
      const tx = db.transaction(this.storeName, 'readonly');
      const store = tx.objectStore(this.storeName);
      const request = store.openCursor();
      const matches = [];

      request.onsuccess = (event) => {
        const cursor = event.target.result;
        if (!cursor) {
          matches.sort((a, b) => b.score - a.score || b.timestamp - a.timestamp);
          resolve(matches.filter((item) => item.score >= threshold));
          return;
        }

        const entry = cursor.value;
        if (entry.modelName !== targetModel) {
          cursor.continue();
          return;
        }

        const overlap = this._scoreOverlap(queryTokens, entry.tags);
        const exactMatch = entry.queryText.toLowerCase() === userQuery.toLowerCase() ? 1.0 : 0;
        const score = Math.max(exactMatch, overlap * 0.6 + (exactMatch ? 0.4 : 0));

        if (score > 0) {
          matches.push({ ...entry, score });
        }
        cursor.continue();
      };

      request.onerror = () => reject(request.error);
    });
  }

  _scoreOverlap(tokens, tags) {
    if (!tokens.length || !tags?.length) return 0;
    const querySet = new Set(tokens);
    const shared = tags.filter((tag) => querySet.has(tag)).length;
    return shared / Math.max(tokens.length, tags.length);
  }
}
