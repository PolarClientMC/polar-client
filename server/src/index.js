// The Polar Client server: a Cloudflare Worker. Everything under /v1/ goes to one Durable Object (the Hub), which keeps
// the data in its built-in SQLite database and holds the live connections of players who are online. Other paths are
// static files from ./public (the cosmetics' textures and models, the admin page), which Cloudflare serves for free.
// /discord/interactions is Polar Bot, the Discord server's bot: Discord sends it commands and button presses here, and
// another Durable Object (the Bot) answers them.
import { Hub } from './hub.js';
import { Bot } from './discord/bot.js';
import { verifyDiscord } from './discord/verify.js';

export { Hub, Bot };

const bot = (env) => env.BOT.get(env.BOT.idFromName('polar-bot'));

async function discord(request, env, url) {
  if (request.method !== 'POST') return new Response('Polar Bot listens here for Discord.', { status: 405 });
  if (!env.DISCORD_PUBLIC_KEY || !env.BOT) return new Response('Polar Bot is not set up yet (deploy-server.bat --discord).', { status: 503 });
  const body = await verifyDiscord(request, env.DISCORD_PUBLIC_KEY);
  if (body === null) return new Response('Bad signature', { status: 401 });
  let i;
  try {
    i = JSON.parse(body);
  } catch {
    return new Response('Bad request', { status: 400 });
  }
  if (i.type === 1) return Response.json({ type: 1 }); // Discord checking the address works
  return bot(env).fetch(new Request('https://bot/interaction', { method: 'POST', body, headers: { 'X-Polar-Site': url.origin } }));
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname.startsWith('/v1/')) {
      const hub = env.HUB.get(env.HUB.idFromName('polar'));
      return hub.fetch(request);
    }
    if (url.pathname === '/discord/interactions') return discord(request, env, url);
    if (url.pathname.startsWith('/discord/admin/') && env.BOT) {
      const headers = new Headers(request.headers);
      headers.set('X-Polar-Site', url.origin);
      return bot(env).fetch(new Request(`https://bot${url.pathname}`, { method: request.method, headers, body: request.method === 'GET' ? undefined : await request.text() }));
    }
    return env.ASSETS.fetch(request);
  },
};
