/** 运行时量具证据脱敏：结构化票据和浏览器错误里的握手 URL 都可能携带票据。 */
export function redactEvidence(value) {
  if (typeof value === 'string') return value
    .replace(/([?&](?:auth_?token|access_?token|token)=)[^&\s"'<>]+/gi, '$1[REDACTED]')
    .replace(/(\bBearer\s+)[A-Za-z0-9._~+\/-]+=*/gi, '$1[REDACTED]');
  if (Array.isArray(value)) return value.map(redactEvidence);
  if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value)
    .map(([key, item]) => [key, /token|authorization/i.test(key) ? '[REDACTED]' : redactEvidence(item)]));
  return value;
}
