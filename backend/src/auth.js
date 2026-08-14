import { createHmac, randomBytes, scryptSync, timingSafeEqual } from 'node:crypto';

const b64 = value => Buffer.from(value).toString('base64url');
export function passwordHash(password, salt = randomBytes(16).toString('hex')) {
  return `${salt}:${scryptSync(password, salt, 32).toString('hex')}`;
}
export function passwordMatches(password, stored) {
  const [salt, hash] = String(stored).split(':');
  if (!salt || !hash) return false;
  const actual = scryptSync(password, salt, 32);
  const expected = Buffer.from(hash, 'hex');
  return actual.length === expected.length && timingSafeEqual(actual, expected);
}
export function signToken(payload, secret, ttlSeconds = 86400) {
  const now = Math.floor(Date.now() / 1000);
  const body = b64(JSON.stringify({ ...payload, iat: now, exp: now + ttlSeconds }));
  return `${body}.${createHmac('sha256', secret).update(body).digest('base64url')}`;
}
export function verifyToken(token, secret) {
  try {
    const [body, signature, extra] = String(token || '').split('.');
    if (!body || !signature || extra) return null;
    const expected = createHmac('sha256', secret).update(body).digest('base64url');
    const providedBuffer = Buffer.from(signature);
    const expectedBuffer = Buffer.from(expected);
    if (
      providedBuffer.length !== expectedBuffer.length ||
      !timingSafeEqual(providedBuffer, expectedBuffer)
    ) return null;
    const payload = JSON.parse(Buffer.from(body, 'base64url').toString());
    return Number.isFinite(payload?.exp) && payload.exp > Date.now() / 1000
      ? payload
      : null;
  } catch {
    return null;
  }
}
