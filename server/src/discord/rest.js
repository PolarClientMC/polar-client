// Calls to Discord's REST API as the bot, with rate-limit waits. Files go as multipart (for ticket transcripts).
export const API = 'https://discord.com/api/v10';

export class DiscordError extends Error {
  constructor(status, data, method, path) {
    super(`${method} ${path} -> ${status}: ${(data && data.message) || 'error'}`);
    this.status = status;
    this.code = data && data.code;
    this.data = data;
  }
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

export class Rest {
  constructor(token, base = API) {
    this.token = token;
    this.base = base;
  }

  async req(method, path, { body, reason, files, auth = true } = {}) {
    for (let attempt = 0; attempt < 4; attempt++) {
      const headers = { 'User-Agent': 'DiscordBot (https://polar-client.polarclientminecraft.workers.dev, 1.0) PolarBot' };
      if (auth && this.token) headers.Authorization = `Bot ${this.token}`;
      if (reason) headers['X-Audit-Log-Reason'] = encodeURIComponent(String(reason).slice(0, 400));
      let payload;
      if (files && files.length) {
        payload = new FormData();
        payload.append('payload_json', JSON.stringify({ ...(body || {}), attachments: files.map((f, i) => ({ id: i, filename: f.name })) }));
        files.forEach((f, i) => payload.append(`files[${i}]`, new Blob([f.data], { type: f.type || 'text/plain' }), f.name));
      } else if (body !== undefined) {
        headers['Content-Type'] = 'application/json';
        payload = JSON.stringify(body);
      }
      const res = await fetch(this.base + path, { method, headers, body: payload });
      if (res.status === 429) {
        const j = await res.json().catch(() => ({}));
        await sleep(Math.min(10, Number(j.retry_after) || 1) * 1000 + 100);
        continue;
      }
      if (res.status >= 500 && attempt < 2) {
        await sleep(800);
        continue;
      }
      const text = await res.text();
      let data = null;
      try {
        data = text ? JSON.parse(text) : null;
      } catch {
        data = { message: text.slice(0, 200) };
      }
      if (!res.ok) throw new DiscordError(res.status, data, method, path);
      return data;
    }
    throw new DiscordError(429, { message: 'Discord is rate-limiting the bot; try again in a minute' }, method, path);
  }

  get(path) { return this.req('GET', path); }
  post(path, body, opts = {}) { return this.req('POST', path, { ...opts, body }); }
  patch(path, body, opts = {}) { return this.req('PATCH', path, { ...opts, body }); }
  put(path, body, opts = {}) { return this.req('PUT', path, { ...opts, body }); }
  del(path, opts = {}) { return this.req('DELETE', path, opts); }
}
