async function probeWhisper(host, port, timeoutMs = 2000) {
  const target = `http://${host}:${port}/health`;
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);

  try {
    const response = await fetch(target, { signal: controller.signal });
    return response.ok;
  } catch (error) {
    return false;
  } finally {
    clearTimeout(timer);
  }
}

async function autoDetectWhisper() {
  const ports = ['8001', '8082'];
  const hosts = ['127.0.0.1', 'localhost'];
  for (const host of hosts) {
    for (const port of ports) {
      if (await probeWhisper(host, port)) {
        console.log(`Whisper backend detected at ${host}:${port}`);
        return { host, port };
      }
    }
  }
  console.warn('Whisper backend not detected');
  return null;
}

window.autoDetectWhisper = autoDetectWhisper;
