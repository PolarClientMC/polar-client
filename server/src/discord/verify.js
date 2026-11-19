// Discord signs every request it sends to the bot (Ed25519, with the app's public key). Anything without a valid
// signature is refused; Discord itself checks that we refuse bad ones before it accepts the bot's address.
const keys = new Map();

function hex(s) {
  const out = new Uint8Array(s.length / 2);
  for (let i = 0; i < out.length; i++) out[i] = parseInt(s.slice(i * 2, i * 2 + 2), 16);
  return out;
}

async function key(publicKey) {
  if (!keys.has(publicKey)) {
    let k;
    try {
      k = await crypto.subtle.importKey('raw', hex(publicKey), { name: 'Ed25519' }, false, ['verify']);
    } catch {
      // older Workers runtimes only know the non-standard name
      k = await crypto.subtle.importKey('raw', hex(publicKey), { name: 'NODE-ED25519', namedCurve: 'NODE-ED25519' }, false, ['verify']);
    }
    keys.set(publicKey, k);
  }
  return keys.get(publicKey);
}

/** The request's body if Discord signed it, otherwise null. */
export async function verifyDiscord(request, publicKey) {
  const sig = request.headers.get('X-Signature-Ed25519') || '';
  const ts = request.headers.get('X-Signature-Timestamp') || '';
  const body = await request.text();
  if (!/^[0-9a-f]{128}$/i.test(sig) || !/^\d+$/.test(ts) || !/^[0-9a-f]{64}$/i.test(publicKey || '')) return null;
  const k = await key(publicKey.toLowerCase());
  const algo = k.algorithm && k.algorithm.name === 'NODE-ED25519' ? { name: 'NODE-ED25519' } : { name: 'Ed25519' };
  const ok = await crypto.subtle.verify(algo, k, hex(sig), new TextEncoder().encode(ts + body));
  return ok ? body : null;
}
